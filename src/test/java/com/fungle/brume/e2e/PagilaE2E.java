package com.fungle.brume.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Brume on Pagila, a realistic sample database (ADR-0051) : enum and domain types, arrays,
 * {@code tsvector}, {@code bytea}, a partitioned {@code payment} table, composite keys,
 * triggers. Nothing is compared to hand-written values : the checks are invariants that hold
 * on any database — plan = execute, no orphan FK, no source value of a pseudonymized column
 * left in the target, and two runs give the same target.
 *
 * <p>Source data : {@code src/test/resources/e2e/pagila/} (upstream commit and checksums in
 * its README), loaded with psql into a dedicated {@code pagila} database on both servers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PagilaE2E {

    private static final Path PAGILA = E2eSettings.RESOURCES.resolve("pagila");
    private static final Set<String> PSEUDONYMIZING = Set.of("FAKE", "HASH", "MASK");
    /** Outputs that cannot coincide with a real value by chance : safe to compare as sets. */
    private static final Set<String> HIGH_ENTROPY = Set.of("HASH", "MASK", "EMAIL", "IP_ADDRESS", "IBAN");

    private final E2eSettings.Db sourceDb = E2eSettings.source().withDatabase("pagila");
    private final E2eSettings.Db targetDb = E2eSettings.target().withDatabase("pagila");
    private JdbcTemplate source;
    private JdbcTemplate target;

    @BeforeAll
    void loadPagila() throws IOException {
        recreatePagilaDatabase(E2eSettings.source());
        recreatePagilaDatabase(E2eSettings.target());
        String schema;
        try (InputStream in = Psql.open(PAGILA.resolve("pagila-schema.sql.gz"))) {
            // uuidv7() only exists from PostgreSQL 18 ; the data sets every uuid explicitly.
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("uuidv7()", "gen_random_uuid()");
        }
        assertThat(Psql.run(sourceDb, schema, "pagila-schema.sql")).as("schema load errors").isEmpty();
        assertThat(Psql.run(sourceDb, PAGILA.resolve("pagila-data.sql.gz"))).as("data load errors").isEmpty();
        source = sourceDb.jdbc();
        target = targetDb.jdbc();
    }

    /**
     * #126c regression — the schema analysis used to list views as tables : scan wrote rules
     * for {@code actor_info}, {@code customer_list}, {@code staff_list}…, and execute failed
     * writing into them. The generated {@code brume.yml} now runs as-is and keeps every invariant.
     */
    @Test
    @Order(1)
    @DisplayName("scan → plan → execute without editing brume.yml : views are not proposed (#126c)")
    void scanThenExecuteWithoutEditing() throws IOException {
        E2eProject project = project("pagila-scan");
        BrumeCli.Result scan = project.run("scan", "--force");
        assertThat(scan.exitCode()).as(scan.describe()).isZero();
        Set<String> views = new HashSet<>(source.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind IN ('v', 'm')""", String.class));
        assertThat(views).as("Pagila views and materialized views")
                .contains("actor_info", "customer_list", "staff_list", "rental_by_category");
        JsonNode generated = new YAMLMapper().readTree(project.file("brume.yml").toFile());
        assertThat(tablesOf(generated.path("extraction"))).doesNotContainAnyElementsOf(views);
        assertThat(tablesOf(generated.path("anonymization"))).doesNotContainAnyElementsOf(views);

        Map<String, Integer> planned = E2eProject.plannedRows(project.run("plan", "--json"));
        BrumeCli.Result execute = project.run("execute");

        assertThat(execute.exitCode()).as(execute.describe()).isZero();
        assertInvariants(planned, rules(project.file("brume.yml")));
    }

    @Test
    @Order(2)
    @DisplayName("curated config : invariants hold, and a second run gives the same target")
    void curatedConfigKeepsInvariants() throws IOException {
        E2eProject project = project("pagila-curated");
        project.useConfig(Files.readString(PAGILA.resolve("brume.yml")));

        Map<String, Integer> planned = E2eProject.plannedRows(project.run("plan", "--json"));
        BrumeCli.Result first = project.run("execute");
        assertThat(first.exitCode()).as(first.describe()).isZero();
        assertInvariants(planned, rules(project.file("brume.yml")));

        Map<String, String> firstRun = tableHashes(planned.keySet());
        BrumeCli.Result second = project.run("execute");
        assertThat(second.exitCode()).as(second.describe()).isZero();
        assertThat(tableHashes(planned.keySet())).as("target content of the second run").isEqualTo(firstRun);
    }

    // ------------------------------------------------------------------------------------

    private E2eProject project(String name) {
        E2eProject project = E2eProject.create(name);
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv("public");
        project.setEnv("BRUME_SOURCE_DB", "pagila");
        project.setEnv("BRUME_TARGET_DB", "pagila");
        return project;
    }

    /** One rule of a brume.yml : strategy and semantic type (may be empty). */
    private record Rule(String strategy, String type) {
    }

    private void assertInvariants(Map<String, Integer> planned, Map<String, Rule> rules) {
        SoftAssertions soft = new SoftAssertions();
        planned.forEach((table, rows) -> soft.assertThat(count(target, table))
                .as("rows of %s : execute = plan", table).isEqualTo(rows.longValue()));
        orphanCounts().forEach((fk, orphans) -> soft.assertThat(orphans).as("orphan rows for FK %s", fk).isZero());
        rules.forEach((column, rule) -> {
            if (!PSEUDONYMIZING.contains(rule.strategy())) return;
            String table = column.substring(0, column.indexOf('.'));
            String col = column.substring(column.indexOf('.') + 1);
            String key = pairingKey(table, rules);
            if (key != null) {
                Map<String, String> before = valuesByKey(source, table, key, col);
                Map<String, String> after = valuesByKey(target, table, key, col);
                List<String> kept = after.keySet().stream()
                        .filter(k -> after.get(k) != null && !after.get(k).isEmpty() && after.get(k).equals(before.get(k)))
                        .toList();
                soft.assertThat(kept).as("rows of %s that kept their source value (paired on %s)", column, key).isEmpty();
            } else if (HIGH_ENTROPY.contains(rule.strategy()) || HIGH_ENTROPY.contains(rule.type())) {
                Set<String> leaked = new HashSet<>(distinctValues(source, table, col));
                leaked.retainAll(distinctValues(target, table, col));
                soft.assertThat(leaked).as("source values of %s (%s) found in the target", column, rule).isEmpty();
            }
            // Otherwise (FAKE name / address / date on a table without an intact unique key) :
            // a fake value may legitimately equal another person's real value — not checked.
        });
        soft.assertAll();
    }

    /**
     * First unique key of {@code table} (primary key first) none of whose columns is rewritten :
     * not declared in the config and not part of a FK (FK columns inherit strategies). Pairs
     * source and target rows ; {@code null} when the table has no such key.
     */
    private String pairingKey(String table, Map<String, Rule> rules) {
        Set<String> fkColumns = new HashSet<>(source.queryForList("""
                SELECT a.attname FROM pg_constraint con
                JOIN pg_class t ON t.oid = con.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (con.conkey)
                WHERE con.contype = 'f' AND n.nspname = 'public' AND t.relname = ?""", String.class, table));
        List<String> keys = source.queryForList("""
                SELECT array_to_string(array_agg(a.attname ORDER BY k.ord), ',')
                FROM pg_index ix JOIN pg_class t ON t.oid = ix.indrelid JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
                WHERE n.nspname = 'public' AND t.relname = ? AND ix.indisunique
                GROUP BY ix.indexrelid, ix.indisprimary ORDER BY ix.indisprimary DESC, 1""", String.class, table);
        for (String key : keys) {
            boolean intact = true;
            for (String col : key.split(",")) {
                Rule rule = rules.get(table + "." + col);
                if (fkColumns.contains(col) || (rule != null && !rule.strategy().equals("KEEP"))) intact = false;
            }
            if (intact) return key;
        }
        return null;
    }

    private static Map<String, String> valuesByKey(JdbcTemplate jdbc, String table, String key, String column) {
        String keyExpr = "concat_ws('|', " + String.join(", ", java.util.Arrays.stream(key.split(","))
                .map(c -> "\"" + c + "\"::text").toList()) + ")";
        Map<String, String> values = new java.util.HashMap<>();
        jdbc.query("SELECT " + keyExpr + ", \"" + column + "\"::text FROM public.\"" + table + "\"",
                rs -> { values.put(rs.getString(1), rs.getString(2)); });
        return values;
    }

    /** Orphan row count of every FK constraint of the target, composite FKs included. */
    private Map<String, Long> orphanCounts() {
        List<Map<String, Object>> fks = target.queryForList("""
                SELECT con.conname, child.relname AS child, parent.relname AS parent,
                       array_to_string(array_agg(ca.attname ORDER BY k.ord), ',') AS child_cols,
                       array_to_string(array_agg(pa.attname ORDER BY k.ord), ',') AS parent_cols
                FROM pg_constraint con
                JOIN pg_class child ON child.oid = con.conrelid
                JOIN pg_class parent ON parent.oid = con.confrelid
                JOIN pg_namespace n ON n.oid = con.connamespace
                JOIN LATERAL unnest(con.conkey, con.confkey) WITH ORDINALITY AS k(c, p, ord) ON true
                JOIN pg_attribute ca ON ca.attrelid = con.conrelid AND ca.attnum = k.c
                JOIN pg_attribute pa ON pa.attrelid = con.confrelid AND pa.attnum = k.p
                WHERE con.contype = 'f' AND n.nspname = 'public'
                GROUP BY con.conname, child.relname, parent.relname""");
        Map<String, Long> orphans = new LinkedHashMap<>();
        for (Map<String, Object> fk : fks) {
            String[] childCols = ((String) fk.get("child_cols")).split(",");
            String[] parentCols = ((String) fk.get("parent_cols")).split(",");
            StringBuilder notNull = new StringBuilder();
            StringBuilder join = new StringBuilder();
            for (int i = 0; i < childCols.length; i++) {
                if (i > 0) {
                    notNull.append(" AND ");
                    join.append(" AND ");
                }
                notNull.append("c.\"").append(childCols[i]).append("\" IS NOT NULL");
                join.append("p.\"").append(parentCols[i]).append("\" = c.\"").append(childCols[i]).append('"');
            }
            Long count = target.queryForObject("SELECT count(*) FROM public.\"" + fk.get("child") + "\" c WHERE "
                    + notNull + " AND NOT EXISTS (SELECT 1 FROM public.\"" + fk.get("parent") + "\" p WHERE " + join + ")",
                    Long.class);
            orphans.put(fk.get("child") + "." + fk.get("conname"), count);
        }
        return orphans;
    }

    /** "table.column" → rule, for every rule of a brume.yml. */
    private static Map<String, Rule> rules(Path brumeYml) throws IOException {
        JsonNode yaml = new YAMLMapper().readTree(brumeYml.toFile());
        Map<String, Rule> rules = new LinkedHashMap<>();
        yaml.path("anonymization").path("tables").forEach(table -> table.path("columns").forEach(col ->
                rules.put(table.path("table").asText() + "." + col.path("name").asText(),
                        new Rule(col.path("strategy").asText(), col.path("type").asText()))));
        return rules;
    }

    private static List<String> tablesOf(JsonNode section) {
        List<String> tables = new java.util.ArrayList<>();
        section.path("tables").forEach(t -> tables.add(t.path("table").asText()));
        return tables;
    }

    /** Server-side md5 of each table's sorted rows. */
    private Map<String, String> tableHashes(Set<String> tables) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String table : tables) {
            hashes.put(table, target.queryForObject(
                    "SELECT md5(coalesce(string_agg(t::text, E'\\n' ORDER BY t::text), '')) FROM public.\"" + table + "\" t",
                    String.class));
        }
        return hashes;
    }

    private static Set<String> distinctValues(JdbcTemplate jdbc, String table, String column) {
        // An empty value carries no personal data : MASK legitimately keeps '' as ''.
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT \"" + column + "\"::text FROM public.\"" + table
                + "\" WHERE \"" + column + "\" IS NOT NULL AND \"" + column + "\"::text <> ''", String.class));
    }

    private static long count(JdbcTemplate jdbc, String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM public.\"" + table + "\"", Long.class);
        return n == null ? 0 : n;
    }

    private static void recreatePagilaDatabase(E2eSettings.Db server) {
        JdbcTemplate admin = server.jdbc();
        admin.execute("DROP DATABASE IF EXISTS pagila WITH (FORCE)");
        admin.execute("CREATE DATABASE pagila");
    }
}
