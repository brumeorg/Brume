package com.fungle.brume.scenario;

import com.fungle.brume.audit.anonymity.AnonymityAuditSpec;
import com.fungle.brume.audit.anonymity.AnonymityReport;
import com.fungle.brume.audit.anonymity.AnonymityAuditor;
import com.fungle.brume.audit.anonymity.AuditRunner;
import com.fungle.brume.audit.anonymity.EquivalenceClassDistribution;
import com.fungle.brume.audit.anonymity.TableAuditResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT bout-en-bout pour {@code brume audit --anonymity} sur une fixture
 * k-calibrée — couvre le gap {@code #73e} laissé au commit de #73.
 *
 * <p>Construit un schéma cible dédié {@code audit_anonymity_it.users} avec 5
 * rows dont la k-anonymity est connue à l'avance :
 * <ul>
 *   <li>2 rows uniquement identifiables sur {@code (birth_date, postal_code)}
 *       (k=1, classes singleton) ;</li>
 *   <li>3 rows partageant {@code (1990-01-01, 69001)} (k=3, une seule classe).</li>
 * </ul>
 *
 * <p>Asserte que {@link AuditRunner} et {@link AnonymityAuditor} produisent
 * exactement ce qui est attendu (distribution, k_min, singletons, exit code 8
 * en mode {@code --strict --k-min 5}).
 *
 * <p>Boot en mode audit ({@code brume.preflight.mode=AUDIT}) — le preflight ne
 * vérifie que la cible, la source peut être down sans conséquence.
 *
 * <p><strong>Requires Docker</strong> : {@code brume-target} sur le port 5460
 * (cf. {@code docker-compose.yml}).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "brume.config-path=src/test/resources/test-config-integration.yaml",
        "brume.sink.type=JDBC",
        "brume.preflight.mode=AUDIT",
        "replication.source.url=jdbc:postgresql://localhost:5432/postgres",
        "replication.target.url=jdbc:postgresql://localhost:5460/postgres",
        "replication.schema=audit_anonymity_it",
        "replication.pgdump-path=docker exec -e PGPASSWORD=postgres brume-source pg_dump",
        "replication.pool-size=3"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnonymityAuditIT {

    @Autowired
    private AnonymityAuditor auditor;

    @Autowired
    private AuditRunner auditRunner;

    private JdbcTemplate targetJdbc;

    @Autowired
    public void setTargetJdbc(@Qualifier("targetDataSource") DataSource ds) {
        this.targetJdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    void setupCalibratedFixture() {
        targetJdbc.execute("DROP SCHEMA IF EXISTS audit_anonymity_it CASCADE");
        targetJdbc.execute("CREATE SCHEMA audit_anonymity_it");
        targetJdbc.execute("""
                CREATE TABLE audit_anonymity_it.users (
                    id           BIGSERIAL PRIMARY KEY,
                    birth_date   DATE NOT NULL,
                    postal_code  INTEGER NOT NULL,
                    gender       CHAR(1) NOT NULL
                )""");
        // 2 singletons + 1 classe k=3 sur (birth_date, postal_code) :
        // singletons    : (1985-03-15, 75001, M) ; (1992-07-22, 13002, F)
        // classe k=3    : (1990-01-01, 69001) × 3 (gender variable, hors quasi-id)
        targetJdbc.execute("""
                INSERT INTO audit_anonymity_it.users (birth_date, postal_code, gender) VALUES
                    ('1985-03-15', 75001, 'M'),
                    ('1992-07-22', 13002, 'F'),
                    ('1990-01-01', 69001, 'M'),
                    ('1990-01-01', 69001, 'F'),
                    ('1990-01-01', 69001, 'M')
                """);
    }

    @AfterEach
    void cleanup() {
        targetJdbc.execute("DROP SCHEMA IF EXISTS audit_anonymity_it CASCADE");
    }

    @Test
    @DisplayName("audit on calibrated fixture produces expected k=1 singletons + k=3 class")
    void auditProducesExpectedDistribution() {
        AnonymityAuditSpec spec = new AnonymityAuditSpec(
                Map.of("users", List.of("birth_date", "postal_code")),
                false, false, 5L, 1.0, null, null);

        AnonymityReport report = auditor.audit(spec);

        assertThat(report.tables()).hasSize(1);
        TableAuditResult t = report.tables().get(0);
        EquivalenceClassDistribution d = t.distribution();

        assertThat(t.table()).isEqualTo("users");
        assertThat(t.quasiIdColumns()).containsExactly("birth_date", "postal_code");
        assertThat(d.totalRows()).as("5 rows total").isEqualTo(5L);
        assertThat(d.totalClasses()).as("3 distinct equivalence classes").isEqualTo(3L);
        assertThat(d.singletons()).as("2 singleton classes (k=1)").isEqualTo(2L);
        assertThat(d.k2to4()).as("1 class with k=3").isEqualTo(1L);
        assertThat(d.k5to9()).isZero();
        assertThat(d.k10to99()).isZero();
        assertThat(d.k100plus()).isZero();
        assertThat(d.kMin()).as("smallest class is k=1").isEqualTo(1L);
        assertThat(report.overallKMin()).isEqualTo(1L);
    }

    @Test
    @DisplayName("audit lists the 2 singleton rows in the singletons sample")
    void singletonsSampleContainsCalibratedRows() {
        AnonymityAuditSpec spec = new AnonymityAuditSpec(
                Map.of("users", List.of("birth_date", "postal_code")),
                false, false, 5L, 1.0, null, null);

        AnonymityReport report = auditor.audit(spec);
        TableAuditResult t = report.tables().get(0);

        // 2 singletons must appear in the sample (SINGLETON_LIMIT=20 > 2)
        assertThat(t.singletons()).hasSize(2);
        List<String> flat = t.singletons().stream()
                .map(r -> String.join(",", r.values()))
                .toList();
        assertThat(flat).anyMatch(s -> s.contains("1985-03-15") && s.contains("75001"));
        assertThat(flat).anyMatch(s -> s.contains("1992-07-22") && s.contains("13002"));
    }

    @Test
    @DisplayName("--strict --k-min 5 on calibrated fixture exits with code 8 (policy violated)")
    void strictModeReturnsExitCode8WhenPolicyViolated() {
        AnonymityAuditSpec spec = new AnonymityAuditSpec(
                Map.of("users", List.of("birth_date", "postal_code")),
                false, true, 5L, 1.0, null, null);

        int exitCode = auditRunner.run(spec);

        assertThat(exitCode).isEqualTo(AuditRunner.EXIT_POLICY_VIOLATED);
    }

    @Test
    @DisplayName("--strict --k-min 1 on calibrated fixture exits 0 (k_min=1 meets threshold)")
    void strictModeReturnsZeroWhenThresholdMet() {
        AnonymityAuditSpec spec = new AnonymityAuditSpec(
                Map.of("users", List.of("birth_date", "postal_code")),
                false, true, 1L, 1.0, null, null);

        int exitCode = auditRunner.run(spec);

        assertThat(exitCode).isZero();
    }

    @Test
    @DisplayName("audit fails fast on unknown column with Levenshtein suggestion")
    void unknownColumnFailsWithSuggestion() {
        AnonymityAuditSpec spec = new AnonymityAuditSpec(
                Map.of("users", List.of("birth_dat")), // typo
                false, false, 5L, 1.0, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> auditor.audit(spec))
                .isInstanceOf(com.fungle.brume.config.ConfigurationException.class)
                .hasMessageContaining("birth_dat")
                .hasMessageContaining("users.birth_dat")
                .satisfies(e -> {
                    com.fungle.brume.config.ConfigurationException ce =
                            (com.fungle.brume.config.ConfigurationException) e;
                    assertThat(ce.suggestion()).contains("birth_date");
                });
    }
}
