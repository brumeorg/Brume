package com.fungle.brume.schema;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fungle.brume.schema.model.DatabaseSchema;
import com.fungle.brume.schema.model.TableMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Régression #81a (ADR-0042) — vérifie que {@code SchemaAnalyzer.warnAboutPkStructure} émet un
 * WARN distinct pour les tables à PK composite et pour les tables sans PK, et n'avertit pas les
 * tables à PK simple. Remplace l'ancien {@code log.debug} muet de {@code loadPrimaryKeys} qui
 * rendait une PK composite indistinguable d'une table sans PK.
 *
 * <p>Test pur sur un {@link DatabaseSchema} construit en mémoire (pas de Docker) — la logique de
 * signalement est découplée de l'introspection JDBC.
 */
class SchemaAnalyzerCompositePkWarningTest {

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        Logger logger = (Logger) LoggerFactory.getLogger(SchemaAnalyzer.class);
        appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
    }

    @AfterEach
    void detach() {
        Logger logger = (Logger) LoggerFactory.getLogger(SchemaAnalyzer.class);
        logger.detachAppender(appender);
    }

    private static DatabaseSchema mixedSchema() {
        Map<String, TableMetadata> tables = new LinkedHashMap<>();
        // single-column PK — must NOT be warned
        tables.put("users", new TableMetadata("users", List.of(), List.of(), "id"));
        // composite PK — must be warned as composite
        tables.put("tenant_users",
                new TableMetadata("tenant_users", List.of(), List.of(), List.of("tenant_id", "user_id")));
        // no PK — must be warned as PK-less
        tables.put("audit_events", new TableMetadata("audit_events", List.of(), List.of(), List.of()));
        return new DatabaseSchema(tables);
    }

    @Test
    @DisplayName("warnAboutPkStructure logs composite-PK and no-PK tables in separate WARNs")
    void warnsCompositeAndNoPkSeparately() {
        SchemaAnalyzer.warnAboutPkStructure(mixedSchema());

        List<String> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());

        // Exactly two WARNs: one for composite, one for no-PK. The single-PK table is silent.
        assertThat(warns).hasSize(2);

        assertThat(warns).anyMatch(m -> m.contains("composite primary key")
                && m.contains("tenant_users") && !m.contains("audit_events"));
        assertThat(warns).anyMatch(m -> m.contains("without a primary key")
                && m.contains("audit_events") && !m.contains("tenant_users"));
        // The single-column-PK table is never the subject of a WARN: hasSize(2) above already
        // guarantees only the composite + no-PK lines are emitted (no third "users" warning).
    }

    @Test
    @DisplayName("DatabaseSchema derives composite-PK and no-PK table lists")
    void schemaDerivesPkStructure() {
        DatabaseSchema schema = mixedSchema();
        assertThat(schema.compositePkTables()).containsExactly("tenant_users");
        assertThat(schema.tablesWithoutPrimaryKey()).containsExactly("audit_events");
    }

    @Test
    @DisplayName("TableMetadata distinguishes single, composite and absent primary keys")
    void tableMetadataPkHelpers() {
        TableMetadata single = new TableMetadata("users", List.of(), List.of(), "id");
        TableMetadata composite =
                new TableMetadata("tenant_users", List.of(), List.of(), List.of("tenant_id", "user_id"));
        TableMetadata none = new TableMetadata("audit_events", List.of(), List.of(), List.of());

        assertThat(single.singlePrimaryKeyColumn()).isEqualTo("id");
        assertThat(single.hasCompositePrimaryKey()).isFalse();
        assertThat(single.hasNoPrimaryKey()).isFalse();

        assertThat(composite.singlePrimaryKeyColumn()).isNull();
        assertThat(composite.hasCompositePrimaryKey()).isTrue();
        assertThat(composite.primaryKeyColumns()).containsExactly("tenant_id", "user_id");

        assertThat(none.singlePrimaryKeyColumn()).isNull();
        assertThat(none.hasNoPrimaryKey()).isTrue();
        assertThat(none.hasCompositePrimaryKey()).isFalse();
    }
}
