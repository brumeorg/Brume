package com.fungle.brume.writer;

import com.fungle.brume.config.model.AnonymizerConfig;
import com.fungle.brume.report.ExecutionReport;
import com.fungle.brume.schema.model.DatabaseSchema;

/**
 * Context passed to {@link Sink#open(WriteContext)} and held by the sink for the
 * duration of a write session.
 *
 * @param schemaName target schema name (e.g. {@code "test_brume"})
 * @param config     the loaded anonymizer configuration
 * @param schema     the analyzed source database schema
 * @param report     the execution report collector for stats and errors
 */
public record WriteContext(
        String schemaName,
        AnonymizerConfig config,
        DatabaseSchema schema,
        ExecutionReport report
) {}
