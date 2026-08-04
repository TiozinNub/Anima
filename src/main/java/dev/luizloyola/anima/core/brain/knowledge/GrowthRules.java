package dev.luizloyola.anima.core.brain.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which seed blocks grow into which kind of place — the registry the POI sensor consults when a
 * glimpsed block might be worth a memory.
 *
 * <p>Anima owns the growing (crescent sampler, region flood, merge radius, claim/confirm dance)
 * but not the botany: that a log or a leaf means a tree, water a lake, is a fact about the world a
 * consuming mod is modelling.
 *
 * <p>Registered per seed {@link BlockKind}. That is what the sampler has in hand when it decides
 * whether to grow; several seeds may share one rule. Anima registers nothing here — its own
 * {@link PoiKind#HERD} memories come from the being sense, not from growing blocks.
 */
public final class GrowthRules {

    /** Keyed on the canonical instances, so a plain map is an identity map in practice. */
    private static final Map<BlockKind, GrowthRule> REGISTERED = new LinkedHashMap<>();

    private GrowthRules() {
    }

    /**
     * Declares that seeing {@code seed} should try to grow {@code rule}'s kind of place. Call
     * during mod initialization. Registering the same seed twice replaces the rule — last mod
     * wins, so a consumer may override the meaning of a block another mod claimed.
     */
    public static void register(BlockKind seed, GrowthRule rule) {
        REGISTERED.put(seed, rule);
    }

    /** The rule {@code seed} grows, or empty when nothing has claimed that block. */
    public static Optional<GrowthRule> forSeed(BlockKind seed) {
        return Optional.ofNullable(REGISTERED.get(seed));
    }

    /** Whether any rule would grow from this seed — the sampler's cheap pre-check. */
    public static boolean grows(BlockKind seed) {
        return REGISTERED.containsKey(seed);
    }

    /** Forgets every registration — test teardown only. */
    public static void reset() {
        REGISTERED.clear();
    }
}
