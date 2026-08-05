package dev.luizloyola.anima.mod.brain;

import com.mojang.serialization.Codec;
import java.util.Map;

/**
 * Codecs for the parts of a brain a body carries across a reload, supplied by Anima so every
 * consumer spells them the same way.
 *
 * <p>The rule (decision: Luiz, 2026-08-05): only what a tick recomputes from the senses may be
 * forgotten; anything outliving the tick that made it has to survive.
 */
public final class BrainState {

    private BrainState() {
    }

    /** Drives sitting out a fail-cooldown, by {@code Instinct.key()}, with the ticks they have left. */
    public static final Codec<Map<String, Integer>> COOLDOWNS =
            Codec.unboundedMap(Codec.STRING, Codec.INT);
}
