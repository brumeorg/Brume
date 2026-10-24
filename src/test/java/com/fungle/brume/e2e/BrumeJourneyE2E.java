package com.fungle.brume.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Functional journey of a new Brume user, run against the binary under test (native image
 * in CI) and two real Postgres instances — ADR-0049.
 *
 * <ol>
 *   <li>{@code brume init jdbc} bootstraps {@code .env.brume} + {@code brume.yml}</li>
 *   <li>the test fills {@code .env.brume} with the CI databases and secrets</li>
 *   <li>{@code brume scan --force} on the English then the French schema</li>
 *   <li>the versioned {@code e2e/brume.yml} replaces the scan output</li>
 *   <li>{@code brume plan} (read-only) then {@code brume execute} (JDBC sink)</li>
 *   <li>the target database is compared to the source and to the golden dataset</li>
 * </ol>
 *
 * <p>Steps share one working directory, kept under {@code target/e2e-work/} with the
 * output of every command, so a failed CI run can be inspected from the job artifacts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BrumeJourneyE2E {

    private static final String EN = "e2e_en";
    private static final String FR = "e2e_fr";

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$");
    private static final Pattern IBAN = Pattern.compile("^[A-Z]{2}[0-9]{2}[A-Za-z0-9]{10,30}$");
    private static final Pattern HMAC_SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    /** Expected rows per table after execute — see the scope comment of e2e/source/e2e_en.sql. */
    private static final Map<String, Integer> EXPECTED_ROWS = orderedMap(
            "users", 5, "products", 5, "orders", 8, "order_items", 13, "audit_logs", 5);

    // ---------------------------------------------------------------------------------
    // Scan expectations — "table.column" → "STRATEGY[ TYPE]"
    // ---------------------------------------------------------------------------------

    private static final Map<String, String> SCAN_EN = orderedMap(
            "users.id", "FPE_ID",
            "users.email", "FAKE EMAIL",
            "users.first_name", "FAKE FIRST_NAME",
            "users.last_name", "FAKE LAST_NAME",
            "users.phone", "MASK PHONE",
            "users.iban", "HASH IBAN",
            "users.ip_address", "MASK IP_ADDRESS",
            "users.birth_date", "FAKE DATE",
            "users.password_hash", "HASH",
            "users.ssn", "HASH",
            "users.gender", "NULLIFY",
            "products.id", "FPE_ID",
            "orders.id", "FPE_ID",
            "orders.shipping_address", "FAKE ADDRESS",
            "order_items.id", "FPE_ID",
            "audit_logs.id", "FPE_ID",
            "audit_logs.user_email", "FAKE EMAIL",
            "audit_logs.ip_address", "MASK IP_ADDRESS");

    private static final Map<String, String> SCAN_FR = orderedMap(
            "clients.id", "FPE_ID",
            "clients.prenom", "FAKE FIRST_NAME",
            "clients.nom", "FAKE LAST_NAME",
            "clients.courriel", "FAKE EMAIL",
            "clients.telephone", "MASK PHONE",
            "clients.portable", "MASK PHONE",
            "clients.adresse", "FAKE ADDRESS",
            "clients.code_postal", "MASK ADDRESS",
            "clients.ville", "MASK ADDRESS",
            "clients.date_naissance", "FAKE DATE",
            "clients.sexe", "NULLIFY",
            "clients.mot_de_passe", "HASH",
            "clients.numero_securite_sociale", "HASH",
            "clients.iban", "HASH IBAN",
            "clients.adresse_ip", "MASK IP_ADDRESS",
            "produits.id", "FPE_ID",
            "commandes.id", "FPE_ID",
            "commandes.adresse_livraison", "FAKE ADDRESS",
            "lignes_commande.id", "FPE_ID");

    /**
     * Known wrong suggestions, tracked in #126b (substring matching + type clash). Asserted
     * as-is so a fix of the mapper turns this test red : move the entry out of this map
     * (or into the expected one) when #126b lands.
     */
    private static final Map<String, String> SCAN_EN_KNOWN_GAPS = orderedMap(
            "users.address_json", "FAKE ADDRESS",   // JSONB column : needs FAKE JSONB + json_paths
            "users.company_name", "HASH");          // 'pan' (card number) matched inside "company"

    private static final Map<String, String> SCAN_FR_KNOWN_GAPS = orderedMap(
            "clients.nombre_commandes", "FAKE LAST_NAME"); // 'nom' matched inside "nombre", INTEGER column

    private static final List<String> FK_COLUMNS_EN = List.of(
            "users.manager_id", "orders.user_id", "order_items.order_id", "order_items.product_id");
    private static final List<String> FK_COLUMNS_FR = List.of(
            "commandes.client_id", "lignes_commande.commande_id", "lignes_commande.produit_id");

    private final E2eProject project = E2eProject.create("journey");
    private final JdbcTemplate source = E2eSettings.source().jdbc();
    private final JdbcTemplate target = E2eSettings.target().jdbc();
    private boolean executed;

    @BeforeAll
    void seedSourceAndResetTarget() {
        E2eSettings.seedSource();
        target.execute("DROP SCHEMA IF EXISTS " + EN + " CASCADE");
        target.execute("DROP SCHEMA IF EXISTS " + FR + " CASCADE");
    }

    // =================================================================================
    // 1. init
    // =================================================================================

    @Test
    @Order(1)
    @DisplayName("init jdbc — creates .env.brume (JDBC template) and brume.yml (example config)")
    void initJdbcCreatesProjectFiles() throws IOException {
        BrumeCli.Result result = project.run("init", "jdbc");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(project.file(".env.brume")).as(".env.brume created")
                .hasSameTextualContentAs(Path.of(".env.jdbc.template"));
        assertThat(project.file("brume.yml")).as("brume.yml created")
                .hasSameTextualContentAs(Path.of("brume.example.yml"));
    }

    // =================================================================================
    // 2. scan — English then French schema
    // =================================================================================

    @Test
    @Order(2)
    @DisplayName("scan (EN) — overwrites brume.yml with the PII / PK rules of an English schema")
    void scanDetectsEnglishPii() throws IOException {
        project.configureEnv(EN);

        BrumeCli.Result result = project.run("scan", "--force");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        Path scanned = keepCopy("brume.yml", "brume.scan-" + EN + ".yml");
        assertScanRules(scanned, SCAN_EN, SCAN_EN_KNOWN_GAPS, FK_COLUMNS_EN);
    }

    @Test
    @Order(3)
    @DisplayName("scan (FR) — detects French column names (courriel, portable, date_naissance, …)")
    void scanDetectsFrenchPii() throws IOException {
        project.configureEnv(FR);

        BrumeCli.Result result = project.run("scan", "--force");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        Path scanned = keepCopy("brume.yml", "brume.scan-" + FR + ".yml");
        assertScanRules(scanned, SCAN_FR, SCAN_FR_KNOWN_GAPS, FK_COLUMNS_FR);
    }

    // =================================================================================
    // 3. plan — minimal, read-only
    // =================================================================================

    @Test
    @Order(4)
    @DisplayName("plan — lists the extracted tables and leaves the target untouched")
    void planIsReadOnly() throws IOException {
        project.configureEnv(EN);
        project.useConfig(Files.readString(E2eSettings.RESOURCES.resolve("brume.yml")));

        BrumeCli.Result result = project.run("plan", "--json");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        JsonNode plan = new ObjectMapper().readTree(result.stdout()).path("plan");
        assertThat(plan.path("sourceSchema").asText()).isEqualTo(EN);
        assertThat(E2eProject.plannedRows(result)).as("planned rows per table (direct + FK parents + FK children)")
                .containsExactlyInAnyOrderEntriesOf(EXPECTED_ROWS);
        assertThat(project.file("reports/brume-plan-report.html")).exists();
        assertThat(schemaExists(target, EN)).as("plan must not write to the target").isFalse();
    }

    // =================================================================================
    // 4. execute — JDBC sink, then the target is inspected
    // =================================================================================

    @Test
    @Order(5)
    @DisplayName("execute — replicates and pseudonymizes e2e_en into the target database")
    void executeSucceeds() {
        BrumeCli.Result result = project.run("execute");

        assertThat(result.exitCode()).as(result.describe()).isZero();
        assertThat(schemaExists(target, EN)).as("target schema created").isTrue();
        assertThat(project.file("reports/brume-execution-report.json")).exists();
        executed = true;
    }

    @Test
    @Order(6)
    @DisplayName("execute — right number of rows (filter + FK parents) and same columns as the source")
    void targetHasExpectedRowsAndColumns() {
        assumeExecuted();
        SoftAssertions soft = new SoftAssertions();
        EXPECTED_ROWS.forEach((table, rows) -> {
            soft.assertThat(count(target, table)).as("rows in %s", table).isEqualTo(rows.longValue());
            soft.assertThat(columns(target, table)).as("columns of %s", table)
                    .containsExactlyElementsOf(columns(source, table));
        });
        soft.assertAll();
    }

    @Test
    @Order(7)
    @DisplayName("execute — users : every declared column pseudonymized, KEEP columns untouched")
    void usersArePseudonymized() {
        assumeExecuted();
        String sql = """
                SELECT id, email, first_name, last_name, phone, iban, ip_address,
                       address_json->>'street' AS street, address_json->>'city' AS city,
                       address_json->>'zip' AS zip, birth_date, password_hash, ssn, gender,
                       company_name, manager_id, last_login_at, created_at::text AS natural_key
                FROM e2e_en.users""";
        Map<String, Map<String, Object>> src = byKey(source, sql + " WHERE id BETWEEN 1001 AND 1005");
        Map<String, Map<String, Object>> tgt = byKey(target, sql);
        assertThat(tgt.keySet()).as("extracted users (1006 has no 2025 order)").isEqualTo(src.keySet());

        SoftAssertions soft = new SoftAssertions();
        src.forEach((key, s) -> {
            Map<String, Object> t = tgt.get(key);
            soft.assertThat(t.get("id")).as("users.id FPE_ID [%s]", key).isNotEqualTo(s.get("id"));
            assertFakeEmail(soft, "users.email", key, s.get("email"), t.get("email"));
            soft.assertThat(t.get("first_name")).as("users.first_name FAKE [%s]", key).isNotNull().isNotEqualTo(s.get("first_name"));
            soft.assertThat(t.get("last_name")).as("users.last_name FAKE [%s]", key).isNotNull().isNotEqualTo(s.get("last_name"));
            soft.assertThat(t.get("phone")).as("users.phone MASK PHONE [%s]", key).isEqualTo(maskPhone((String) s.get("phone")));
            soft.assertThat((String) t.get("iban")).as("users.iban FAKE IBAN [%s]", key)
                    .isNotNull().isNotEqualTo(s.get("iban")).matches(IBAN);
            soft.assertThat(t.get("ip_address")).as("users.ip_address MASK [%s]", key).isEqualTo(maskIp((String) s.get("ip_address")));
            soft.assertThat(t.get("street")).as("$.street FAKE [%s]", key).isNotNull().isNotEqualTo(s.get("street"));
            soft.assertThat(t.get("city")).as("$.city MASK [%s]", key).isEqualTo(((String) s.get("city")).charAt(0) + "***");
            assertHmac(soft, "$.zip", key, t.get("zip"));
            soft.assertThat(((Date) t.get("birth_date")).toLocalDate().getYear()).as("users.birth_date FAKE DATE [%s]", key)
                    .isBetween(1935, 2007);
            assertHmac(soft, "users.password_hash", key, t.get("password_hash"));
            soft.assertThat(t.get("ssn")).as("users.ssn NULLIFY [%s]", key).isNull();
            soft.assertThat(t.get("gender")).as("users.gender NULLIFY [%s]", key).isNull();
            soft.assertThat(t.get("company_name")).as("users.company_name KEEP [%s]", key).isEqualTo(s.get("company_name"));
            assertDateShift(soft, key, (Timestamp) s.get("last_login_at"), (Timestamp) t.get("last_login_at"));
        });
        soft.assertAll();
    }

    @Test
    @Order(8)
    @DisplayName("execute — FK propagation : every pseudonymized FK points to the pseudonymized parent of the source row")
    void foreignKeysFollowTheirParents() {
        assumeExecuted();
        Map<Object, String> srcUser = idToKey(source, "SELECT id, created_at::text AS natural_key FROM e2e_en.users");
        Map<Object, String> tgtUser = idToKey(target, "SELECT id, created_at::text AS natural_key FROM e2e_en.users");
        SoftAssertions soft = new SoftAssertions();

        // users.manager_id → users.id (self reference, cycle 1001 ↔ 1005)
        String managers = "SELECT manager_id, created_at::text AS natural_key FROM e2e_en.users";
        Map<String, Map<String, Object>> srcManagers = byKey(source, managers);
        byKey(target, managers).forEach((key, t) -> soft.assertThat(tgtUser.get(t.get("manager_id")))
                .as("users.manager_id of user created %s", key)
                .isEqualTo(srcUser.get(srcManagers.get(key).get("manager_id"))));

        // orders.user_id → users.id
        String orders = """
                SELECT id, user_id, shipping_address, total_amount, notes, created_at::text AS natural_key
                FROM e2e_en.orders""";
        Map<String, Map<String, Object>> srcOrders = byKey(source, orders + " WHERE created_at >= '2025-01-01'");
        Map<String, Map<String, Object>> tgtOrders = byKey(target, orders);
        assertThat(tgtOrders.keySet()).as("orders kept by the 2025 filter").isEqualTo(srcOrders.keySet());
        srcOrders.forEach((key, s) -> {
            Map<String, Object> t = tgtOrders.get(key);
            soft.assertThat(t.get("id")).as("orders.id FPE_ID [%s]", key).isNotEqualTo(s.get("id"));
            soft.assertThat(tgtUser.get(t.get("user_id"))).as("orders.user_id [%s]", key)
                    .isNotNull().isEqualTo(srcUser.get(s.get("user_id")));
            soft.assertThat(t.get("shipping_address")).as("orders.shipping_address FAKE [%s]", key)
                    .isNotNull().isNotEqualTo(s.get("shipping_address"));
            soft.assertThat(t.get("total_amount")).as("orders.total_amount KEEP [%s]", key).isEqualTo(s.get("total_amount"));
            soft.assertThat(t.get("notes")).as("orders.notes NULLIFY [%s]", key).isNull();
        });

        // order_items.order_id → orders.id and order_items.product_id → products.id :
        // each item is identified by (its order, its product) through their KEEP columns.
        String items = """
                SELECT i.id, i.quantity, o.created_at::text || ' / ' || p.label AS natural_key
                FROM e2e_en.order_items i
                LEFT JOIN e2e_en.orders o ON o.id = i.order_id
                LEFT JOIN e2e_en.products p ON p.id = i.product_id""";
        Map<String, Map<String, Object>> srcItems = byKey(source, items + " WHERE i.id BETWEEN 4003 AND 4015");
        Map<String, Map<String, Object>> tgtItems = byKey(target, items);
        assertThat(tgtItems.keySet()).as("order_items with both FKs resolved to the right (order, product) — "
                + "a null key means an orphan FK").isEqualTo(srcItems.keySet());
        srcItems.forEach((key, s) -> {
            soft.assertThat(tgtItems.get(key).get("id")).as("order_items.id FPE_ID [%s]", key).isNotEqualTo(s.get("id"));
            soft.assertThat(tgtItems.get(key).get("quantity")).as("order_items.quantity KEEP [%s]", key).isEqualTo(s.get("quantity"));
        });

        // products : FK parents of the extracted items only (2006 belongs to a 2024 order)
        String products = "SELECT id, label AS natural_key, description, price, created_at FROM e2e_en.products";
        Map<String, Map<String, Object>> srcProducts = byKey(source, products + " WHERE id BETWEEN 2001 AND 2005");
        Map<String, Map<String, Object>> tgtProducts = byKey(target, products);
        assertThat(tgtProducts.keySet()).as("products resolved as FK parents").isEqualTo(srcProducts.keySet());
        srcProducts.forEach((key, s) -> {
            Map<String, Object> t = tgtProducts.get(key);
            soft.assertThat(t.get("id")).as("products.id FPE_ID [%s]", key).isNotEqualTo(s.get("id"));
            soft.assertThat(List.of(Objects.toString(t.get("description")), t.get("price"), t.get("created_at")))
                    .as("products KEEP columns [%s]", key)
                    .isEqualTo(List.of(Objects.toString(s.get("description")), s.get("price"), s.get("created_at")));
        });
        soft.assertAll();
    }

    @Test
    @Order(9)
    @DisplayName("execute — audit_logs : linked_columns give users.email and audit_logs.user_email the same fake value")
    void auditLogsArePseudonymizedConsistently() {
        assumeExecuted();
        String sql = """
                SELECT id, user_email, action, ip_address,
                       payload->'user'->>'email' AS payload_email,
                       payload->'user'->'session'->>'token' AS token,
                       payload->'metadata'->>'source' AS payload_source,
                       created_at::text AS natural_key
                FROM e2e_en.audit_logs""";
        Map<String, Map<String, Object>> src = byKey(source, sql);
        Map<String, Map<String, Object>> tgt = byKey(target, sql);
        assertThat(tgt.keySet()).as("audit_logs has no filter").isEqualTo(src.keySet());

        String users = "SELECT email, created_at::text AS natural_key FROM e2e_en.users";
        Map<String, String> srcUserKeyByEmail = new HashMap<>();
        byKey(source, users + " WHERE id BETWEEN 1001 AND 1005")
                .forEach((key, row) -> srcUserKeyByEmail.put((String) row.get("email"), key));
        Map<String, Map<String, Object>> tgtUsers = byKey(target, users);

        SoftAssertions soft = new SoftAssertions();
        src.forEach((key, s) -> {
            Map<String, Object> t = tgt.get(key);
            soft.assertThat(t.get("id")).as("audit_logs.id FPE_ID [%s]", key).isNotEqualTo(s.get("id"));
            assertFakeEmail(soft, "audit_logs.user_email", key, s.get("user_email"), t.get("user_email"));
            String linkedUser = srcUserKeyByEmail.get((String) s.get("user_email"));
            if (linkedUser != null) {
                soft.assertThat(t.get("user_email")).as("linked_columns user_email [%s]", key)
                        .isEqualTo(tgtUsers.get(linkedUser).get("email"));
            }
            soft.assertThat(t.get("action")).as("audit_logs.action KEEP [%s]", key).isEqualTo(s.get("action"));
            soft.assertThat(t.get("ip_address")).as("audit_logs.ip_address MASK [%s]", key)
                    .isEqualTo(maskIp((String) s.get("ip_address")));
            assertFakeEmail(soft, "$.user.email", key, s.get("payload_email"), t.get("payload_email"));
            if (s.get("token") == null) {
                soft.assertThat(t.get("token")).as("$.user.session.token null stays null [%s]", key).isNull();
            } else {
                assertHmac(soft, "$.user.session.token", key, t.get("token"));
            }
            soft.assertThat(t.get("payload_source")).as("undeclared JSON path untouched [%s]", key)
                    .isEqualTo(s.get("payload_source"));
        });
        soft.assertAll();
    }

    @Test
    @Order(10)
    @DisplayName("execute — target equals the golden dataset value by value (determinism contract)")
    void targetMatchesGoldenDataset() {
        assumeExecuted();
        Map<String, List<String>> diffs = new TreeMap<>();
        for (String table : EXPECTED_ROWS.keySet()) {
            List<String> snapshot = GoldenDataset.snapshot(target, EN, table, "id");
            List<String> tableDiffs = GoldenDataset.diff(E2eSettings.GOLDEN_DIR.resolve(EN).resolve(table + ".tsv"), snapshot);
            if (!tableDiffs.isEmpty()) diffs.put(table, tableDiffs);
        }
        assertThat(diffs).as("differences with src/test/resources/e2e/expected/%s/ — if intended, "
                + "regenerate with -De2e.updateGolden=true and review the diff", EN).isEmpty();
    }

    // =================================================================================
    // helpers
    // =================================================================================

    private Path keepCopy(String file, String copyName) throws IOException {
        return Files.copy(project.file(file), project.file(copyName), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void assertScanRules(Path brumeYml, Map<String, String> expected,
                                        Map<String, String> knownGaps, List<String> fkColumns) throws IOException {
        JsonNode yaml = new YAMLMapper().readTree(brumeYml.toFile());
        Map<String, String> rules = new LinkedHashMap<>();
        yaml.path("anonymization").path("tables").forEach(table -> table.path("columns").forEach(col -> {
            String rule = col.path("strategy").asText()
                    + (col.hasNonNull("type") ? " " + col.path("type").asText() : "");
            rules.put(table.path("table").asText() + "." + col.path("name").asText(), rule);
        }));
        Map<String, String> all = new LinkedHashMap<>(expected);
        all.putAll(knownGaps);

        assertThat(rules).as("rules written by scan in %s (known gaps : #126b)", brumeYml.getFileName())
                .containsExactlyInAnyOrderEntriesOf(all);
        assertThat(rules).as("FK columns are left to FkStrategyPropagator").doesNotContainKeys(fkColumns.toArray(String[]::new));

        List<String> extractionTables = new ArrayList<>();
        yaml.path("extraction").path("tables").forEach(t -> extractionTables.add(t.path("table").asText()));
        assertThat(extractionTables).as("extraction stub lists every table having a rule")
                .containsExactlyInAnyOrderElementsOf(all.keySet().stream()
                        .map(k -> k.substring(0, k.indexOf('.'))).distinct().toList());
    }

    private void assumeExecuted() {
        assumeTrue(executed, "brume execute failed — see executeSucceeds");
    }

    private static boolean schemaExists(JdbcTemplate jdbc, String schema) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?", Integer.class, schema);
        return n != null && n > 0;
    }

    private static long count(JdbcTemplate jdbc, String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + EN + "." + table, Long.class);
        return n == null ? 0 : n;
    }

    private static List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("""
                SELECT column_name || ' ' || data_type FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position""", String.class, EN, table);
    }

    /** Rows keyed by their {@code natural_key} column : a KEEP value shared by source and target. */
    private static Map<String, Map<String, Object>> byKey(JdbcTemplate jdbc, String sql) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql)) {
            rows.put(String.valueOf(row.get("natural_key")), row);
        }
        return rows;
    }

    private static Map<Object, String> idToKey(JdbcTemplate jdbc, String sql) {
        Map<Object, String> ids = new HashMap<>();
        byKey(jdbc, sql).forEach((key, row) -> ids.put(row.get("id"), key));
        return ids;
    }

    private static void assertFakeEmail(SoftAssertions soft, String column, String key, Object src, Object tgt) {
        soft.assertThat((String) tgt).as("%s FAKE EMAIL [%s]", column, key)
                .isNotNull().isNotEqualTo(src).matches(EMAIL);
    }

    private static void assertHmac(SoftAssertions soft, String column, String key, Object tgt) {
        soft.assertThat((String) tgt).as("%s HASH (HmacSHA256 hex) [%s]", column, key).isNotNull().matches(HMAC_SHA256_HEX);
    }

    private static void assertDateShift(SoftAssertions soft, String key, Timestamp src, Timestamp tgt) {
        if (src == null) {
            soft.assertThat(tgt).as("users.last_login_at DATE_SHIFT keeps NULL [%s]", key).isNull();
            return;
        }
        Duration shift = Duration.between(src.toLocalDateTime(), tgt.toLocalDateTime());
        soft.assertThat(shift.toDays()).as("users.last_login_at DATE_SHIFT within ±30 days [%s]", key).isBetween(-30L, 30L);
        soft.assertThat(shift.toSeconds() % 86_400).as("DATE_SHIFT moves whole days [%s]", key).isZero();
    }

    /** MASK PHONE keeps the first 3 and the last 2 characters. */
    private static String maskPhone(String phone) {
        if (phone == null) return null;
        return phone.substring(0, 3) + "*".repeat(phone.length() - 5) + phone.substring(phone.length() - 2);
    }

    /** MASK IP_ADDRESS keeps the first two octets. */
    private static String maskIp(String ip) {
        String[] octets = ip.split("\\.");
        return octets[0] + "." + octets[1] + ".*.*";
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> orderedMap(Object... keyValues) {
        Map<String, V> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], (V) keyValues[i + 1]);
        }
        return map;
    }
}
