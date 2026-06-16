package com.fungle.brume.config.model;


/**
 * Root model of the Brume configuration file ({@code config.yaml}).
 *
 * <p>Parsed by {@link com.fungle.brume.config.ConfigLoader} and validated by
 * {@link com.fungle.brume.config.ConfigValidator} before the replication pipeline starts.
 *
 * <p>The configuration file contains two top-level sections:
 * <ul>
 *   <li>{@code extraction} — which tables to extract and with which filters</li>
 *   <li>{@code anonymization} — per-column anonymization rules and cross-table linkages</li>
 * </ul>
 *
 * <p>Database connection properties are intentionally absent from this file —
 * they are managed separately via {@code application.yaml}.
 *
 * @param extraction    extraction rules: which tables to read, optional filters, FK depth
 * @param anonymization anonymization rules: per-column strategies and cross-table linkages
 */
public record AnonymizerConfig(ExtractionConfig extraction, AnonymizationConfig anonymization) {
}

