package dev.luizloyola.anima.core.brain.gate;

import java.util.Objects;

/**
 * Something a body does that a consumer may forbid — enter the Nether, enchant, trade. A named key,
 * declared in {@link Acts} by whoever owns the machinery that performs it, and gated on the doing
 * wherever the station came from.
 */
public record Act(String key) {

    public Act {
        Objects.requireNonNull(key, "key");
    }
}
