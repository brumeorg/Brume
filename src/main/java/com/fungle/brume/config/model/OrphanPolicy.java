package com.fungle.brume.config.model;

/**
 * What the FK walk does with a reference that leaves the {@code fk_depth} radius around the
 * seed rows ({@code extraction.orphan_policy}, ADR-0050).
 */
public enum OrphanPolicy {

    /**
     * Default. The parents of every copied row are climbed without depth limit (upward only,
     * never descending from them again) — the target never holds an orphan FK.
     */
    KEEP_PARENTS,

    /**
     * Strict radius: rows beyond {@code fk_depth} are not copied, and the references pointing
     * to them are kept as-is. The run warns and reports the orphan count per FK.
     */
    ALLOW_ORPHANS
}
