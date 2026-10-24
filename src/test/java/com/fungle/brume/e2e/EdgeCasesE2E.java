package com.fungle.brume.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Edge cases of the fixture {@code e2e_edge} (ADR-0051) on the binary under test : UUID keys
 * (FPE_UUID), composite FK, a mixed-case table, reserved-word columns, text that is hard to
 * escape in a COPY stream, UNIQUE columns and many small chunks.
 *
 * <p>{@code "Account".nickname} is kept as-is and unique : it pairs source and target rows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EdgeCasesE2E {

    private static final String SCHEMA = "e2e_edge";
    private static final Pattern UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern HMAC_SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    /** Expected rows — see the scope comment of e2e/source/e2e_edge.sql. */
    private static final Map<String, Integer> EXPECTED_ROWS = Map.of(
            "Account", 400, "session", 10, "shipment", 4, "order_line", 4, "fiche client", 5,
            "login_event", 10, "badge", 6);

    /** Table → ORDER BY of its golden snapshot. */
    private static final Map<String, String> GOLDEN_ORDER = Map.of(
            "Account", "id", "session", "id", "shipment", "id", "order_line", "order_no, line_no",
            "fiche client", "id", "login_event", "id, happened", "badge", "id");

    private final E2eProject project = E2eProject.create("edge-cases");
    private final JdbcTemplate source = E2eSettings.source().jdbc();
    private final JdbcTemplate target = E2eSettings.target().jdbc();
    private boolean executed;
    /** Rows of holiday lost through JDBC in test 9, compared with the DUMP sink in test 11. */
    private int holidaysLostThroughJdbc = -1;

    @BeforeAll
    void createProject() throws IOException {
        E2eSettings.seedSource();
        assertThat(project.run("init", "jdbc").exitCode()).isZero();
        project.configureEnv(SCHEMA);
        project.useConfig(Files.readString(E2eSettings.RESOURCES.resolve("edge-brume.yml")));
    }

    @Test
    @Order(1)
    @DisplayName("plan and execute agree on the rows, with ~60 chunks of 7 rows ; "
            + "the partitioned login_event is one table, its partitions are never read (#71c)")
    void planAndExecuteAgree() throws IOException {
        Map<String, Integer> planned = E2eProject.plannedRows(project.run("plan", "--json"));
        BrumeCli.Result execute = project.run("execute");

        assertThat(execute.exitCode()).as(execute.describe()).isZero();
        assertThat(planned).containsExactlyInAnyOrderEntriesOf(EXPECTED_ROWS);
        Map<String, Integer> written = new TreeMap<>();
        EXPECTED_ROWS.keySet().forEach(t -> written.put(t, count(target, t)));
        assertThat(written).containsExactlyInAnyOrderEntriesOf(EXPECTED_ROWS);
        JsonNode stats = new ObjectMapper().readTree(project.file("reports/brume-execution-report.json").toFile())
                .path("tableStats");
        assertThat(stats).allSatisfy(t -> {
            assertThat(EXPECTED_ROWS).as("table of the report").containsKey(t.path("table").asText());
            assertThat(t.path("conflicts").asLong()).as("rows written twice [%s]", t.path("table").asText()).isZero();
        });
        executed = true;
    }

    @Test
    @Order(2)
    @DisplayName("FPE_UUID : account ids are new UUIDs, and both UUID FKs follow their account")
    void uuidKeysArePseudonymizedAndPropagated() {
        assumeExecuted();
        List<String> sourceIds = source.queryForList("SELECT id::text FROM e2e_edge.\"Account\"", String.class);
        List<String> targetIds = target.queryForList("SELECT id::text FROM e2e_edge.\"Account\"", String.class);
        assertThat(targetIds).allMatch(id -> UUID.matcher(id).matches()).doesNotContainAnyElementsOf(sourceIds);

        String sessions = """
                SELECT s.id AS natural_key, a.nickname FROM e2e_edge.session s
                LEFT JOIN e2e_edge."Account" a ON a.id = s.account_id""";
        assertThat(byKey(target, sessions)).as("session.account_id → same account (by nickname)")
                .isEqualTo(byKey(source, sessions));
    }

    @Test
    @Order(3)
    @DisplayName("composite FK (order_no, line_no) : each shipment keeps its order line, order_no is FPE_ID")
    void compositeForeignKeyFollowsItsParent() {
        assumeExecuted();
        String shipments = """
                SELECT sh.id AS natural_key, ol.label, a.nickname FROM e2e_edge.shipment sh
                LEFT JOIN e2e_edge.order_line ol ON (ol.order_no, ol.line_no) = (sh.order_no, sh.line_no)
                LEFT JOIN e2e_edge."Account" a ON a.id = sh.account_id""";
        assertThat(byKey(target, shipments)).as("(label, account) reached through the FKs").isEqualTo(byKey(source, shipments));
        assertThat(target.queryForList("SELECT order_no FROM e2e_edge.order_line", Long.class))
                .doesNotContainAnyElementsOf(List.of(100L, 102L));
        assertThat(target.queryForList("SELECT label FROM e2e_edge.order_line", String.class))
                .as("lines no shipment references stay out").containsExactlyInAnyOrder("keyboard", "mouse", "screen", "cable");
    }

    @Test
    @Order(4)
    @DisplayName("KEEP text survives the write path byte for byte : tab, CR/LF, backslash, \\N, quotes, emoji, '' vs NULL")
    void textSurvivesTheWritePath() {
        assumeExecuted();
        String bios = "SELECT nickname AS natural_key, bio FROM e2e_edge.\"Account\"";
        assertThat(byKey(target, bios)).isEqualTo(byKey(source, bios));
        assertThat(target.queryForObject("SELECT count(*) FROM e2e_edge.\"Account\" WHERE bio = ''", Integer.class))
                .as("empty string is not turned into NULL").isEqualTo(1);
        assertThat(target.queryForObject("SELECT count(*) FROM e2e_edge.\"Account\" WHERE bio = E'\\\\N'", Integer.class))
                .as("the literal \\N is not taken for the COPY NULL marker").isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("reserved-word columns \"user\" (HASH) and \"order\" (KEEP), UNIQUE email (FAKE EMAIL)")
    void reservedWordsAndUniqueEmail() {
        assumeExecuted();
        String accounts = "SELECT nickname AS natural_key, \"user\", \"order\", email FROM e2e_edge.\"Account\"";
        Map<Object, Map<String, Object>> src = byKey(source, accounts);
        Map<Object, Map<String, Object>> tgt = byKey(target, accounts);
        SoftAssertions soft = new SoftAssertions();
        src.forEach((key, s) -> {
            Map<String, Object> t = tgt.get(key);
            soft.assertThat((String) t.get("user")).as("\"user\" HASH [%s]", key).matches(HMAC_SHA256_HEX);
            soft.assertThat(t.get("order")).as("\"order\" KEEP [%s]", key).isEqualTo(s.get("order"));
            soft.assertThat(t.get("email")).as("email FAKE [%s]", key).isNotEqualTo(s.get("email"));
        });
        soft.assertAll();
        assertThat(target.queryForObject("SELECT count(DISTINCT email) FROM e2e_edge.\"Account\"", Integer.class))
                .as("no collision on the UNIQUE email").isEqualTo(400);
    }

    @Test
    @Order(6)
    @DisplayName("#26c — names PostgreSQL only takes quoted : table \"fiche client\", accented columns, "
            + "a column holding a double quote, an accented FK column")
    void quotedOnlyIdentifiersGoThroughThePipeline() {
        assumeExecuted();
        String fiches = "SELECT f.id AS natural_key, a.nickname, f.\"prénom\", f.\"nom de famille\", f.\"téléphone\","
                + " f.\"note \"\"interne\"\"\" AS note"
                + " FROM e2e_edge.\"fiche client\" f LEFT JOIN e2e_edge.\"Account\" a ON a.id = f.\"compte référent\"";
        Map<Object, Map<String, Object>> src = byKey(source, fiches);
        Map<Object, Map<String, Object>> tgt = byKey(target, fiches);
        assertThat(tgt).hasSize(5);
        SoftAssertions soft = new SoftAssertions();
        src.forEach((key, s) -> {
            Map<String, Object> t = tgt.get(key);
            soft.assertThat(t.get("nickname")).as("\"compte référent\" → same account [%s]", key).isEqualTo(s.get("nickname"));
            soft.assertThat(t.get("prénom")).as("prénom FAKE [%s]", key).isNotNull().isNotEqualTo(s.get("prénom"));
            soft.assertThat(t.get("nom de famille")).as("nom de famille FAKE [%s]", key).isNotNull().isNotEqualTo(s.get("nom de famille"));
            soft.assertThat(t.get("note")).as("note \"interne\" KEEP [%s]", key).isEqualTo(s.get("note"));
            if (s.get("téléphone") != null) {
                soft.assertThat(t.get("téléphone")).as("téléphone MASK [%s]", key).isNotEqualTo(s.get("téléphone"));
            }
        });
        soft.assertAll();
    }

    @Test
    @Order(7)
    @DisplayName("target equals the e2e_edge golden dataset value by value")
    void targetMatchesGoldenDataset() {
        assumeExecuted();
        assertThat(goldenDiffs(true)).as("regenerate with -De2e.updateGolden=true if intended").isEmpty();
    }

    /**
     * #29a regression — FAKE FIRST_NAME has ≈ 5 000 possible values : 24 of the 400 nicknames
     * used to collide on the UNIQUE constraint and be dropped in silence (376 rows, exit 0).
     * On a UNIQUE column the fake now carries a 64-bit suffix derived from the real value.
     * #29c regression — each column of a linked pair cut the suffixed fake to its own length :
     * a varchar(20) FK no longer matched its varchar(100) parent.
     * #70d — no linked_columns entry : FAKE propagates along the FK like HASH and FPE_ID.
     */
    @Test
    @Order(8)
    @DisplayName("#29a — FAKE FIRST_NAME on the UNIQUE nickname : 400 rows, 400 distinct suffixed names ; "
            + "#29c / #70d — the shorter FK column badge.holder_nickname gets the same value, cut the same way, "
            + "without linked_columns")
    void fakeFirstNameOnUniqueColumnKeepsEveryRow() throws IOException {
        String yaml = Files.readString(E2eSettings.RESOURCES.resolve("edge-brume.yml"));
        String email = """
                        - name: email
                          strategy: FAKE
                          type: EMAIL
                """;
        assertThat(yaml).contains(email);
        // No linked_columns : FAKE follows the FK badge.holder_nickname on its own (#70d, ADR-0057)
        project.useConfig(yaml.replace(email, email + """
                        - name: nickname
                          strategy: FAKE
                          type: FIRST_NAME
                """));

        BrumeCli.Result execute = project.run("execute");

        assertThat(execute.exitCode()).as(execute.describe()).isZero();
        assertThat(count(target, "Account")).as("rows written out of 400").isEqualTo(400);
        assertThat(target.queryForList("SELECT nickname FROM e2e_edge.\"Account\"", String.class))
                .doesNotHaveDuplicates().allMatch(n -> n.matches("^.+\\.[0-9a-f]{16}$"));
        // #29c — badge.holder_nickname references the UNIQUE nickname : same suffixed value
        String badges = """
                SELECT b.id AS natural_key, a."user" FROM e2e_edge.badge b
                LEFT JOIN e2e_edge."Account" a ON a.nickname = b.holder_nickname""";
        assertThat(count(target, "badge")).isEqualTo(6);
        assertThat(target.queryForList("SELECT holder_nickname FROM e2e_edge.badge", String.class))
                .allMatch(n -> n.length() <= 20 && n.matches("^.+\\.[0-9a-f]{16}$"));
        assertThat(byKey(target, badges)).as("each badge still finds its account through the FK")
                .allSatisfy((id, row) -> assertThat(row.get("user")).isNotNull());
        assertThat(byKey(target, badges).keySet()).isEqualTo(byKey(source, badges).keySet());
    }

    /**
     * #29a — a strategy that can map two values onto one (here DATE_SHIFT on 60 consecutive
     * UNIQUE days) loses the colliding rows : {@code ON CONFLICT DO NOTHING} skips them. They
     * used to be counted as « already in the target ». The run still goes on (exit 0), but
     * the lost rows are now counted apart and named in the console and JSON reports.
     */
    @Test
    @Order(9)
    @DisplayName("#29a — rows lost to a collision on a UNIQUE column do not stop the run and are reported")
    void collisionOnUniqueColumnIsReportedWithoutStoppingTheRun() throws IOException {
        String yaml = Files.readString(E2eSettings.RESOURCES.resolve("edge-brume.yml"))
                .replace("    - table: Account\n      filter:\n", "    - table: Account\n      filter:\n    - table: holiday\n      filter:\n");
        project.useConfig(yaml + """
                    - table: holiday
                      columns:
                        - name: day
                          strategy: DATE_SHIFT
                """);

        BrumeCli.Result execute = project.run("execute");

        assertThat(execute.exitCode()).as(execute.describe()).isZero();
        int written = count(target, "holiday");
        int lost = 60 - written;
        assertThat(lost).as("DATE_SHIFT on 60 consecutive days must make some of them collide").isPositive();
        holidaysLostThroughJdbc = lost;
        assertThat(target.queryForObject("SELECT count(DISTINCT day) FROM e2e_edge.holiday", Integer.class))
                .isEqualTo(written);
        assertThat(execute.stdout()).contains(
                "[UNIQUE_COLLISION] e2e_edge.holiday : ",                      // WARN log, per chunk
                "[WARN] " + lost + " ligne(s) perdue(s)", "holiday : " + lost,   // execution report
                "holiday : " + lost + " ligne(s) perdue(s)");                     // plan vs real insight

        Map<String, JsonNode> stats = new LinkedHashMap<>();
        new ObjectMapper().readTree(project.file("reports/brume-execution-report.json").toFile())
                .path("tableStats").forEach(t -> stats.put(t.path("table").asText(), t));
        assertThat(stats.get("holiday").path("uniqueCollisions").asLong()).isEqualTo(lost);
        assertThat(stats.get("holiday").path("conflicts").asLong()).as("not « already in the target »").isZero();
        assertThat(stats.get("holiday").path("batchErrors").asLong()).isZero();
        assertThat(stats.get("Account").path("uniqueCollisions").asLong()).isZero();
        assertThat(count(target, "Account")).isEqualTo(400);
    }

    /**
     * #71c regression — a partitioned table used to be read as itself and as each of its
     * partitions : the dump held two COPY blocks of the same rows and psql stopped on
     * duplicate keys. The e2e_edge dump (partitioned table, quoted-only names of #26c) now
     * restores without error to the golden dataset.
     *
     * <p>#5c regression — restored in a session at UTC+14, far from the time zone of brume
     * (UTC in CI) : timestamptz values were written as wall-clock times without offset and
     * moved by the difference.
     */
    @Test
    @Order(10)
    @DisplayName("#71c — the DUMP of e2e_edge restores with psql (session at UTC+14, #5c) to the golden "
            + "dataset, with one COPY for login_event and none for its partitions")
    void dumpRestoresToGolden() throws IOException {
        project.useConfig(Files.readString(E2eSettings.RESOURCES.resolve("edge-brume.yml")));
        project.setEnv("BRUME_PIPELINE_MODE", "STREAMING");
        project.setEnv("BRUME_SINK_TYPE", "DUMP");
        project.setEnv("BRUME_SINK_OUTPUT_PATH", "./dumps/e2e_edge.sql");
        project.setEnv("BRUME_SINK_COMPRESSION", "NONE");

        BrumeCli.Result execute = project.run("execute");

        assertThat(execute.exitCode()).as(execute.describe()).isZero();
        assertThat(Files.readAllLines(project.file("dumps/e2e_edge.sql"))).filteredOn(line -> line.startsWith("COPY "))
                .anyMatch(line -> line.startsWith("COPY \"e2e_edge\".\"login_event\" ("))
                .noneMatch(line -> line.contains("login_event_20"));
        target.execute("DROP SCHEMA IF EXISTS e2e_edge CASCADE");
        String dump = "SET TIME ZONE 'Pacific/Kiritimati';\n" + Files.readString(project.file("dumps/e2e_edge.sql"));
        assertThat(Psql.run(E2eSettings.target(), dump, "e2e_edge.sql")).as("psql errors while restoring").isEmpty();
        assertThat(goldenDiffs(false)).isEmpty();
    }

    /**
     * #29b regression — the DUMP sink writes to no database : the rows whose DATE_SHIFT collided
     * went into the dump and psql rejected the whole COPY block of holiday at restore. The sink
     * now drops them before writing, counts them like the JDBC sink does, and the dump restores.
     */
    @Test
    @Order(11)
    @DisplayName("#29b — DUMP sink : rows that would break a UNIQUE key are dropped and reported, "
            + "the dump restores with psql without error")
    void dumpDropsCollidingRowsAndRestores() throws IOException {
        String yaml = Files.readString(E2eSettings.RESOURCES.resolve("edge-brume.yml"))
                .replace("    - table: Account\n      filter:\n", "    - table: Account\n      filter:\n    - table: holiday\n      filter:\n");
        project.useConfig(yaml + """
                    - table: holiday
                      columns:
                        - name: day
                          strategy: DATE_SHIFT
                """);
        project.setEnv("BRUME_PIPELINE_MODE", "STREAMING");
        project.setEnv("BRUME_SINK_TYPE", "DUMP");
        project.setEnv("BRUME_SINK_OUTPUT_PATH", "./dumps/e2e_edge-holiday.sql");
        project.setEnv("BRUME_SINK_COMPRESSION", "NONE");

        BrumeCli.Result execute = project.run("execute");

        assertThat(execute.exitCode()).as(execute.describe()).isZero();
        target.execute("DROP SCHEMA IF EXISTS e2e_edge CASCADE");
        assertThat(Psql.run(E2eSettings.target(), project.file("dumps/e2e_edge-holiday.sql"))).as("psql errors while restoring").isEmpty();
        int lost = 60 - count(target, "holiday");
        assertThat(lost).isPositive();
        if (holidaysLostThroughJdbc >= 0) {
            assertThat(lost).as("same rows lost as through JDBC").isEqualTo(holidaysLostThroughJdbc);
        }
        assertThat(execute.stdout()).contains("[UNIQUE_COLLISION] e2e_edge.holiday : ", "[WARN] " + lost + " ligne(s) perdue(s)");
        Map<String, JsonNode> stats = new LinkedHashMap<>();
        new ObjectMapper().readTree(project.file("reports/brume-execution-report.json").toFile())
                .path("tableStats").forEach(t -> stats.put(t.path("table").asText(), t));
        assertThat(stats.get("holiday").path("uniqueCollisions").asLong()).isEqualTo(lost);
        assertThat(count(target, "Account")).isEqualTo(400);
    }

    // ------------------------------------------------------------------------------------

    /** Differences with the golden dataset ; {@code mayUpdate} lets -De2e.updateGolden rewrite it. */
    private Map<String, List<String>> goldenDiffs(boolean mayUpdate) {
        Map<String, List<String>> diffs = new TreeMap<>();
        GOLDEN_ORDER.forEach((table, orderBy) -> {
            Path golden = E2eSettings.GOLDEN_DIR.resolve(SCHEMA).resolve(table + ".tsv");
            List<String> actual = GoldenDataset.snapshot(target, SCHEMA, quoted(table), orderBy);
            List<String> tableDiffs = mayUpdate ? GoldenDataset.diff(golden, actual) : GoldenDataset.compareWith(golden, actual);
            if (!tableDiffs.isEmpty()) diffs.put(table, tableDiffs);
        });
        return diffs;
    }

    private void assumeExecuted() {
        assumeTrue(executed, "brume execute failed — see planAndExecuteAgree");
    }

    private static String quoted(String table) {
        return "\"" + table.replace("\"", "\"\"") + "\"";
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + "." + quoted(table), Integer.class));
    }

    /** Rows keyed by their {@code natural_key} column (a value kept as-is by the config). */
    private static Map<Object, Map<String, Object>> byKey(JdbcTemplate jdbc, String sql) {
        Map<Object, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql)) {
            Map<String, Object> values = new LinkedHashMap<>(row);
            rows.put(values.remove("natural_key"), values);
        }
        return rows;
    }
}
