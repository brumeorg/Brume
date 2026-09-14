package com.fungle.brume.init;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PgDumpCheckerTest {

    @Test
    @DisplayName("parses major version from PG 17 --version line")
    void parsesPg17() {
        assertThat(PgDumpChecker.parseMajorVersion("pg_dump (PostgreSQL) 17.2"))
                .contains(17);
    }

    @Test
    @DisplayName("parses major version from PG 18 --version line")
    void parsesPg18() {
        assertThat(PgDumpChecker.parseMajorVersion("pg_dump (PostgreSQL) 18.0"))
                .contains(18);
    }

    @Test
    @DisplayName("parses major version from distro-tagged --version line")
    void parsesDistroTagged() {
        assertThat(PgDumpChecker.parseMajorVersion(
                "pg_dump (PostgreSQL) 17.2 (Ubuntu 17.2-1.pgdg22.04+1)"))
                .contains(17);
    }

    @Test
    @DisplayName("parses major version from single-digit release")
    void parsesSingleDigit() {
        assertThat(PgDumpChecker.parseMajorVersion("pg_dump (PostgreSQL) 9.6.24"))
                .contains(9);
    }

    @Test
    @DisplayName("returns empty on unrecognized output")
    void returnsEmptyOnUnknown() {
        assertThat(PgDumpChecker.parseMajorVersion("no version here")).isEmpty();
        assertThat(PgDumpChecker.parseMajorVersion(null)).isEmpty();
        assertThat(PgDumpChecker.parseMajorVersion("")).isEmpty();
    }

    @Test
    @DisplayName("Result.meetsRequirement true only for >= 17")
    void meetsRequirement() {
        assertThat(new PgDumpChecker.Result(true, Optional.of(17), Optional.empty()).meetsRequirement())
                .isTrue();
        assertThat(new PgDumpChecker.Result(true, Optional.of(18), Optional.empty()).meetsRequirement())
                .isTrue();
        assertThat(new PgDumpChecker.Result(true, Optional.of(16), Optional.empty()).meetsRequirement())
                .isFalse();
        assertThat(new PgDumpChecker.Result(false, Optional.empty(), Optional.empty()).meetsRequirement())
                .isFalse();
    }
}
