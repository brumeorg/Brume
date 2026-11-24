package com.fungle.brume.state;

import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.config.model.JsonPathConfig;
import com.fungle.brume.config.model.SemanticType;
import com.fungle.brume.config.model.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionStateWriterTest {

    @Test
    @DisplayName("ADR-0065 — a JSONB column is recorded as JSONB, declared strategy or not : "
            + "the audit must not treat a column anonymized path by path as neutralized")
    void jsonbColumnIsRecordedAsJsonb() {
        List<JsonPathConfig> paths = List.of(new JsonPathConfig("$.zip", SemanticType.ADDRESS, Strategy.KEEP));

        assertThat(ExecutionStateWriter.strategyOf(new ColumnConfig("address_json", null, SemanticType.JSONB, paths)))
                .isEqualTo("JSONB");
        assertThat(ExecutionStateWriter.strategyOf(new ColumnConfig("address_json", Strategy.FAKE, SemanticType.JSONB, paths)))
                .isEqualTo("JSONB");
        assertThat(ExecutionStateWriter.strategyOf(new ColumnConfig("email", Strategy.FAKE, SemanticType.EMAIL, null)))
                .isEqualTo("FAKE");
        assertThat(ExecutionStateReader.NEUTRALIZED_STRATEGIES).doesNotContain("JSONB");
    }
}
