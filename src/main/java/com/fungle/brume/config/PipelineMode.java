package com.fungle.brume.config;

/**
 * Execution mode for the extract → anonymize → write pipeline.
 */
public enum PipelineMode {
    /**
     * Default mode: rows are processed table by table in a streaming fashion.
     */
    STREAMING,

    /**
     * Legacy mode: the full dataset is extracted, anonymized, then written from memory.
     */
    BATCH
}
