package com.fungle.brume.replicator;

import com.fungle.brume.config.ReplicationProperties;
import com.fungle.brume.report.DdlExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage of {@link SchemaReplicator#executeDdl} for #28 / A17 — the audit of STRICT vs
 * LENIENT DDL error modes. Pre-#28, LENIENT silently dropped failed statements after a WARN
 * log line, so the rapport never reflected them. This test guards the two contracts:
 *
 * <ol>
 *   <li>STRICT mode still fails fast on the first error (no regression).</li>
 *   <li>LENIENT mode returns a {@link DdlExecutionResult} that names every dropped
 *       statement so the rapport can surface them ({@code DdlFailure[]}).</li>
 * </ol>
 */
class SchemaReplicatorDdlExecutionTest {

    @Test
    @DisplayName("STRICT mode — first failing DDL throws and propagates the statement preview + cause")
    void strictMode_throwsOnFirstError() throws Exception {
        SchemaReplicator replicator = newReplicator(ReplicationProperties.DdlErrorMode.STRICT);
        DataSource ds = stubDataSourceWithFailingStatement(2, "relation already exists");

        // 3 statements ; index 2 fails. STRICT must throw before reaching statement 3.
        String ddl = "CREATE TABLE t1 (id int);\nCREATE TABLE t2 (id int);\nCREATE TABLE t3 (id int);\n";

        assertThatThrownBy(() -> replicator.executeDdl(ds, ddl))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("DDL statement #2 failed")
                .hasMessageContaining("CREATE TABLE t2")
                .hasMessageContaining("relation already exists");

        // Statement 3 must NOT have been executed
        verify(ds.getConnection().createStatement(), times(2)).execute(ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("LENIENT mode — failed statement is recorded in DdlExecutionResult, pipeline continues")
    void lenientMode_recordsFailureAndContinues() throws Exception {
        SchemaReplicator replicator = newReplicator(ReplicationProperties.DdlErrorMode.LENIENT);
        DataSource ds = stubDataSourceWithFailingStatement(2, "extension \"pgcrypto\" is not available");

        String ddl = "CREATE TABLE t1 (id int);\nCREATE EXTENSION pgcrypto;\nCREATE TABLE t3 (id int);\n";

        DdlExecutionResult result = replicator.executeDdl(ds, ddl);

        // 2 statements applied (t1 + t3), 1 ignored (extension)
        assertThat(result.ok()).isEqualTo(2);
        assertThat(result.ignored()).isEqualTo(1);
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).statementIndex())
                .as("matches the 1-based index used in the WARN log line for easy correlation")
                .isEqualTo(2);
        assertThat(result.failures().get(0).sqlPreview())
                .as("preview is a single trimmed line")
                .contains("CREATE EXTENSION pgcrypto");
        assertThat(result.failures().get(0).errorMessage())
                .contains("pgcrypto");

        // All 3 statements must have been ATTEMPTED — LENIENT means "keep going"
        verify(ds.getConnection().createStatement(), times(3)).execute(ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("LENIENT mode — multiple failed statements all surfaced with their indices")
    void lenientMode_multipleFailuresAllRecorded() throws Exception {
        SchemaReplicator replicator = newReplicator(ReplicationProperties.DdlErrorMode.LENIENT);

        // Fail statements #2 and #4 ; pass #1, #3, #5
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        doNothing().when(conn).setAutoCommit(true);

        when(stmt.execute(ArgumentMatchers.contains("t1"))).thenReturn(false);
        when(stmt.execute(ArgumentMatchers.contains("t2"))).thenThrow(new SQLException("error 2"));
        when(stmt.execute(ArgumentMatchers.contains("t3"))).thenReturn(false);
        when(stmt.execute(ArgumentMatchers.contains("t4"))).thenThrow(new SQLException("error 4"));
        when(stmt.execute(ArgumentMatchers.contains("t5"))).thenReturn(false);

        // splitStatements ends a statement on `;` at end-of-(trimmed)-line — separate the
        // 5 statements with newlines so we get 5 distinct stmt.execute() calls.
        String ddl = "CREATE TABLE t1 (id int);\n"
                + "CREATE TABLE t2 (id int);\n"
                + "CREATE TABLE t3 (id int);\n"
                + "CREATE TABLE t4 (id int);\n"
                + "CREATE TABLE t5 (id int);\n";
        DdlExecutionResult result = replicator.executeDdl(ds, ddl);

        assertThat(result.ok()).isEqualTo(3);
        assertThat(result.ignored()).isEqualTo(2);
        assertThat(result.failures())
                .extracting("statementIndex", "errorMessage")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, "error 2"),
                        org.assertj.core.groups.Tuple.tuple(4, "error 4")
                );
    }

    @Test
    @DisplayName("LENIENT mode — empty DDL returns empty result (no failures, ok=0)")
    void lenientMode_emptyDdl() throws Exception {
        SchemaReplicator replicator = newReplicator(ReplicationProperties.DdlErrorMode.LENIENT);
        DataSource ds = stubDataSourceWithFailingStatement(-1, null);

        DdlExecutionResult result = replicator.executeDdl(ds, "");

        assertThat(result.ok()).isZero();
        assertThat(result.ignored()).isZero();
        assertThat(result.failures()).isEmpty();
    }

    @Test
    @DisplayName("LENIENT mode — multi-line statement is collapsed to a single-line preview, truncated at 80 chars")
    void lenientMode_previewIsSingleLineTruncated() throws Exception {
        SchemaReplicator replicator = newReplicator(ReplicationProperties.DdlErrorMode.LENIENT);

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        doNothing().when(conn).setAutoCommit(true);

        // A long, multi-line statement that fails
        String longStmt = "CREATE TABLE very_long_table_name_aaaaaaaaaaaaaaaaaaaa (\n"
                + "  id bigint PRIMARY KEY,\n"
                + "  name varchar(255)\n"
                + ");\n";
        when(stmt.execute(ArgumentMatchers.anyString())).thenThrow(new SQLException("oops"));

        DdlExecutionResult result = replicator.executeDdl(ds, longStmt);

        assertThat(result.failures()).hasSize(1);
        String preview = result.failures().get(0).sqlPreview();
        assertThat(preview)
                .as("single line (whitespace collapsed)")
                .doesNotContain("\n")
                .as("truncated at 80 chars max")
                .hasSizeLessThanOrEqualTo(80)
                .as("starts with the CREATE TABLE keyword so an operator can identify it")
                .startsWith("CREATE TABLE very_long_table_name");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static SchemaReplicator newReplicator(ReplicationProperties.DdlErrorMode mode) {
        return new SchemaReplicator(new ReplicationProperties(
                "schema",
                "pg_dump",
                300,
                mode,
                20,
                3,
                new ReplicationProperties.Source(
                        "jdbc:postgresql://localhost:5432/postgres", "postgres", "postgres"),
                new ReplicationProperties.Target(
                        "jdbc:postgresql://localhost:5460/postgres", "postgres", "postgres"),
                false
        ), new com.fungle.brume.shutdown.CancellationRegistry(
                new com.fungle.brume.shutdown.CancellationToken()));
    }

    /**
     * Builds a DataSource whose Statement.execute(...) returns false for every statement
     * EXCEPT the one whose 1-based index equals {@code failingIndex}, which throws a
     * SQLException with {@code message}.
     *
     * <p>Pass {@code failingIndex=-1} for an all-success stub. The mock relies on
     * positional matching on the call sequence — sufficient because the SUT calls
     * {@code stmt.execute} once per statement in order.
     */
    private static DataSource stubDataSourceWithFailingStatement(int failingIndex, String message)
            throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        doNothing().when(conn).setAutoCommit(true);

        if (failingIndex == 1) {
            // Throw on first call, then return false on subsequent
            when(stmt.execute(ArgumentMatchers.anyString()))
                    .thenThrow(new SQLException(message))
                    .thenReturn(false);
        } else if (failingIndex == 2) {
            when(stmt.execute(ArgumentMatchers.anyString()))
                    .thenReturn(false)
                    .thenThrow(new SQLException(message))
                    .thenReturn(false);
        } else if (failingIndex == 3) {
            when(stmt.execute(ArgumentMatchers.anyString()))
                    .thenReturn(false)
                    .thenReturn(false)
                    .thenThrow(new SQLException(message));
        } else {
            // failingIndex < 1 or > 3: all-success
            when(stmt.execute(ArgumentMatchers.anyString())).thenReturn(false);
        }
        return ds;
    }
}
