package com.fungle.brume.replicator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaReplicatorCacheKeyTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/postgres";

    @Test
    @DisplayName("#3b — the DDL cache key changes with the pg_dump binary, even on a minor bump (\\restrict since 17.6)")
    void cacheKeyDependsOnPgDumpVersion() {
        var v175 = SchemaReplicator.cacheFileFor(URL, "s", "fp", "pg_dump (PostgreSQL) 17.5");
        var v176 = SchemaReplicator.cacheFileFor(URL, "s", "fp", "pg_dump (PostgreSQL) 17.6");
        var v176Again = SchemaReplicator.cacheFileFor(URL, "s", "fp", "pg_dump (PostgreSQL) 17.6");

        assertThat(v175).isNotEqualTo(v176);
        assertThat(v176).isEqualTo(v176Again);
    }
}
