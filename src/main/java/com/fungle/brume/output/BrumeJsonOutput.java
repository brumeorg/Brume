package com.fungle.brume.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fungle.brume.report.ComparisonSummary;
import com.fungle.brume.report.ExecutionSummary;
import com.fungle.brume.report.PlanSummary;

/**
 * Top-level JSON wrapper emitted on {@code stdout} when {@link OutputMode#JSON} is active.
 * The structure is intentionally fixed and self-describing so external scripts can rely on
 * the field names (ADR-0030).
 *
 * <p>Each subcommand populates the wrapper differently:
 * <ul>
 *   <li>{@code plan}     — only {@code plan} is set; {@code execution} and {@code comparison} are null.</li>
 *   <li>{@code dry-run}  — {@code plan} + {@code execution} are set; {@code comparison} is null
 *       (no real target to compare against).</li>
 *   <li>{@code execute}  — all three fields are set.</li>
 *   <li>any failure path — populated as far as the pipeline progressed; the
 *       {@link com.fungle.brume.command.BrumeExecutionExceptionHandler} emits a separate
 *       error object on {@code stderr}.</li>
 * </ul>
 *
 * <p>Null fields are omitted from the serialized JSON via
 * {@link JsonInclude.Include#NON_NULL}.
 *
 * @param plan       pre-execution plan snapshot (always present for {@code plan}/{@code dry-run}/{@code execute})
 * @param execution  post-execution snapshot (present for {@code dry-run}/{@code execute})
 * @param comparison plan vs actual comparison (present only on successful {@code execute})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BrumeJsonOutput(
        PlanSummary plan,
        ExecutionSummary execution,
        ComparisonSummary comparison
) {
    public static BrumeJsonOutput planOnly(PlanSummary plan) {
        return new BrumeJsonOutput(plan, null, null);
    }

    public static BrumeJsonOutput planAndExecution(PlanSummary plan, ExecutionSummary execution) {
        return new BrumeJsonOutput(plan, execution, null);
    }

    public static BrumeJsonOutput full(PlanSummary plan, ExecutionSummary execution, ComparisonSummary comparison) {
        return new BrumeJsonOutput(plan, execution, comparison);
    }
}
