package com.fungle.brume.writer;

import com.fungle.brume.config.model.ColumnConfig;
import com.fungle.brume.extraction.model.ExtractedRow;
import com.fungle.brume.schema.model.TableMetadata;
import com.fungle.brume.schema.model.UniqueKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Keeps a dump restorable (#29b) : the DUMP sink writes no row to a database, so nothing tells
 * it that two pseudonymized rows share a value of a {@code UNIQUE} key — {@code psql} would
 * then reject the whole {@code COPY} block of the table. The guard remembers the keys already
 * written and refuses a row that would repeat one ; the sink counts it as lost, like the JDBC
 * sink does for a row skipped by {@code ON CONFLICT DO NOTHING}.
 *
 * <p>Only keys that can collide are tracked : a key is at risk when one of its columns goes
 * through a strategy that can map two values onto one (FAKE, MASK, DATE_SHIFT…, or NULLIFY
 * under {@code NULLS NOT DISTINCT}). Columns kept as-is, hashed or encrypted (KEEP, HASH,
 * FPE_ID, FPE_UUID) stay as distinct as in the source. A key is remembered as a 64-bit
 * fingerprint of the text written in the dump : 8 bytes per row and key, a false collision
 * once in about 2⁶⁴ / n² — never for realistic volumes.
 */
final class UniqueKeyGuard {

    /** Marks a key that holds a NULL under the default NULLS DISTINCT rule : it never collides. */
    private static final long NO_KEY = 0L;

    private final List<TrackedKey> keys;

    private UniqueKeyGuard(List<TrackedKey> keys) {
        this.keys = keys;
    }

    /**
     * @param table the table metadata (its unique keys)
     * @param rules the anonymization rules of the table, by column ; a missing column is KEEP
     * @return a guard over the keys at risk, or {@code null} when no key can collide
     */
    static UniqueKeyGuard forTable(TableMetadata table, Map<String, ColumnConfig> rules) {
        List<TrackedKey> keys = new ArrayList<>();
        for (UniqueKey key : table.uniqueKeys()) {
            if (key.columns().stream().anyMatch(c -> mayCollide(rules.get(c), key.nullsNotDistinct()))) {
                keys.add(new TrackedKey(key, new LongHashSet()));
            }
        }
        return keys.isEmpty() ? null : new UniqueKeyGuard(keys);
    }

    private static boolean mayCollide(ColumnConfig rule, boolean nullsNotDistinct) {
        if (rule == null || rule.strategy() == null) {
            return false;
        }
        return switch (rule.strategy()) {
            case KEEP, HASH, FPE_ID, FPE_UUID -> false;
            case NULLIFY -> nullsNotDistinct;
            default -> true;
        };
    }

    /**
     * Admits the row if it repeats no tracked key, and then remembers its keys.
     *
     * @return {@code false} when writing the row would break a unique key
     */
    boolean admit(ExtractedRow row) {
        long[] fingerprints = new long[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            TrackedKey tracked = keys.get(i);
            fingerprints[i] = fingerprint(tracked.key(), row);
            if (fingerprints[i] != NO_KEY && tracked.seen().contains(fingerprints[i])) {
                return false;
            }
        }
        for (int i = 0; i < keys.size(); i++) {
            if (fingerprints[i] != NO_KEY) keys.get(i).seen().add(fingerprints[i]);
        }
        return true;
    }

    private static long fingerprint(UniqueKey key, ExtractedRow row) {
        StringBuilder text = new StringBuilder(64);
        for (String column : key.columns()) {
            Object value = row.data().get(column);
            if (value == null && !key.nullsNotDistinct()) {
                return NO_KEY;
            }
            TsvEscape.escape(text, value);
            text.append('\t');
        }
        long hash = fnv1a64(text);
        return hash == NO_KEY ? 1L : hash;
    }

    /** FNV-1a over the UTF-16 code units, then MurmurHash3's 64-bit finalizer. */
    private static long fnv1a64(CharSequence text) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            h ^= text.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    private record TrackedKey(UniqueKey key, LongHashSet seen) {
    }

    /** Open-addressing set of non-zero longs : 8 bytes per entry at most 60 % full. */
    static final class LongHashSet {
        private long[] slots = new long[1 << 10];
        private int size;

        boolean contains(long value) {
            int mask = slots.length - 1;
            for (int i = (int) (value ^ (value >>> 32)) & mask; ; i = (i + 1) & mask) {
                if (slots[i] == 0L) return false;
                if (slots[i] == value) return true;
            }
        }

        void add(long value) {
            if ((size + 1) * 10L > slots.length * 6L) {
                long[] old = slots;
                slots = new long[old.length * 2];
                size = 0;
                Arrays.stream(old).filter(v -> v != 0L).forEach(this::insert);
            }
            insert(value);
        }

        private void insert(long value) {
            int mask = slots.length - 1;
            for (int i = (int) (value ^ (value >>> 32)) & mask; ; i = (i + 1) & mask) {
                if (slots[i] == value) return;
                if (slots[i] == 0L) {
                    slots[i] = value;
                    size++;
                    return;
                }
            }
        }
    }
}
