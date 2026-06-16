package com.fungle.brume.anonymization;

import com.fungle.brume.anonymization.strategies.AnonymizationStrategy;
import com.fungle.brume.anonymization.strategies.FakeStrategy;
import com.fungle.brume.anonymization.strategies.FpeIdStrategy;
import com.fungle.brume.anonymization.strategies.FpeUuidStrategy;
import com.fungle.brume.anonymization.strategies.HashStrategy;
import com.fungle.brume.anonymization.strategies.KeepStrategy;
import com.fungle.brume.anonymization.strategies.MaskStrategy;
import com.fungle.brume.anonymization.strategies.NullifyStrategy;
import com.fungle.brume.config.model.Strategy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Resolves an {@link AnonymizationStrategy} implementation from a {@link Strategy} enum value.
 *
 * <p>All seven strategy implementations are injected via constructor, then stored in an immutable
 * {@link Map}. This avoids any reflection or classpath scanning at resolution time.
 *
 * <p>Used by {@link AnonymizationEngine} to dispatch anonymization work to the correct
 * implementation for each configured column.
 */
@Component
public class StrategyResolver {

    private final Map<Strategy, AnonymizationStrategy> strategies;

    /**
     * Creates a new {@code StrategyResolver} with all strategy implementations.
     *
     * @param fakeStrategy     strategy for generating deterministic fake data
     * @param fpeIdStrategy    strategy for format-preserving encryption of numeric IDs
     * @param fpeUuidStrategy  strategy for deterministic UUID anonymization via HMAC
     * @param maskStrategy     strategy for partial masking
     * @param hashStrategy     strategy for one-way SHA-256 hashing
     * @param nullifyStrategy  strategy that returns null
     * @param keepStrategy     strategy that returns the value unchanged
     */
    public StrategyResolver(
            FakeStrategy fakeStrategy,
            FpeIdStrategy fpeIdStrategy,
            FpeUuidStrategy fpeUuidStrategy,
            MaskStrategy maskStrategy,
            HashStrategy hashStrategy,
            NullifyStrategy nullifyStrategy,
            KeepStrategy keepStrategy) {
        this.strategies = Map.of(
                Strategy.FAKE,     fakeStrategy,
                Strategy.FPE_ID,   fpeIdStrategy,
                Strategy.FPE_UUID, fpeUuidStrategy,
                Strategy.MASK,     maskStrategy,
                Strategy.HASH,     hashStrategy,
                Strategy.NULLIFY,  nullifyStrategy,
                Strategy.KEEP,     keepStrategy
        );
    }

    /**
     * Returns the {@link AnonymizationStrategy} for the given {@link Strategy} enum value.
     *
     * @param strategy the strategy enum value to resolve
     * @return the corresponding strategy implementation — never {@code null}
     * @throws IllegalArgumentException if no implementation is registered for the given strategy
     */
    public AnonymizationStrategy resolve(Strategy strategy) {
        AnonymizationStrategy impl = strategies.get(strategy);
        if (impl == null) {
            throw new IllegalArgumentException("No AnonymizationStrategy registered for: " + strategy);
        }
        return impl;
    }
}

