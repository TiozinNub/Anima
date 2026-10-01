package dev.luizloyola.anima.core.store;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a body's goods go. Anima knows no bases, so the consumer installs a {@link Policy}; with
 * none, every body offloads nowhere and never builds a store on its own initiative.
 *
 * <p><b>No base, no offloading</b> (decision: Luiz, 2026-09-30): a settler stowed 458 items into a
 * chest built at a scouting stop 450 blocks from where it later settled.
 */
public final class Depot {

    /** The consumer's answer: the cell a body's goods go to, empty for nowhere. */
    public interface Policy {
        Optional<Pos> of(AgentId body);
    }

    /** Nowhere for everyone — what runs until a consumer installs its own. */
    public static final Policy NOWHERE = body -> Optional.empty();

    private static volatile Policy policy = NOWHERE;

    private Depot() {
    }

    public static void install(Policy installed) {
        policy = Objects.requireNonNull(installed, "policy");
    }

    public static Optional<Pos> of(AgentId body) {
        return policy.of(body);
    }
}
