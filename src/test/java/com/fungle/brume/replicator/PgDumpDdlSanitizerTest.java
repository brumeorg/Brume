package com.fungle.brume.replicator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgDumpDdlSanitizerTest {

    @Test
    @DisplayName("#3b — strips \\restrict/\\unrestrict (pg_dump ≥ 17.6) so the .sql replays with an older psql")
    void stripsRestrictMetaCommands() {
        String ddl = """
                \\restrict AbC123
                SET statement_timeout = 0;
                CREATE TABLE s.t (id integer);
                \\unrestrict AbC123
                """;

        assertThat(PgDumpDdlSanitizer.sanitize(ddl)).isEqualTo("""
                SET statement_timeout = 0;
                CREATE TABLE s.t (id integer);
                """);
    }

    @Test
    @DisplayName("#3b — strips SET transaction_timeout (pg_dump ≥ 17) rejected by servers ≤ 16")
    void stripsTransactionTimeout() {
        String ddl = "SET lock_timeout = 0;\nSET transaction_timeout = 0;\nSET idle_in_transaction_session_timeout = 0;\n";

        assertThat(PgDumpDdlSanitizer.sanitize(ddl))
                .isEqualTo("SET lock_timeout = 0;\nSET idle_in_transaction_session_timeout = 0;\n");
    }

    @Test
    @DisplayName("never alters a dollar-quoted function body, even lines starting with a backslash")
    void keepsDollarQuotedBodies() {
        String ddl = """
                CREATE FUNCTION s.f() RETURNS text
                    LANGUAGE plpgsql AS $_$
                BEGIN
                \\restrict-looking line kept
                SET transaction_timeout = 0;
                  RETURN $1 || 'x';
                END;
                $_$;
                \\unrestrict k
                """;

        assertThat(PgDumpDdlSanitizer.sanitize(ddl)).isEqualTo("""
                CREATE FUNCTION s.f() RETURNS text
                    LANGUAGE plpgsql AS $_$
                BEGIN
                \\restrict-looking line kept
                SET transaction_timeout = 0;
                  RETURN $1 || 'x';
                END;
                $_$;
                """);
    }

    @Test
    @DisplayName("never alters a multi-line string literal")
    void keepsMultiLineStringLiterals() {
        String ddl = "COMMENT ON TABLE s.t IS 'line one\n\\not a meta-command\nend';\n";

        assertThat(PgDumpDdlSanitizer.sanitize(ddl)).isEqualTo(ddl);
    }

    @Test
    @DisplayName("ignores quotes and dollars inside -- comments")
    void ignoresQuotesInComments() {
        String ddl = "-- Name: it's $weird; Type: TABLE\n\\unrestrict k\nCREATE TABLE s.t ();\n";

        assertThat(PgDumpDdlSanitizer.sanitize(ddl)).isEqualTo("-- Name: it's $weird; Type: TABLE\nCREATE TABLE s.t ();\n");
    }

    @Test
    @DisplayName("keeps CRLF line endings and a missing trailing newline")
    void keepsLineEndings() {
        assertThat(PgDumpDdlSanitizer.sanitize("A;\r\n\\restrict k\r\nB;")).isEqualTo("A;\r\nB;");
        assertThat(PgDumpDdlSanitizer.sanitize("")).isEmpty();
    }
}
