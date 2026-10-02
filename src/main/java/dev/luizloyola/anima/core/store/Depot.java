package dev.luizloyola.anima.core.store;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.territory.ChunkKey;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Where a body's goods go. Anima knows no bases, so the consumer installs a {@link Policy}; with
 * none, every body offloads nowhere and never builds a store on its own initiative.
 *
 * <p><b>No base, no offloading</b> (decision: Luiz, 2026-09-30): a settler stowed 458 items into a
 * chest built at a scouting stop 450 blocks from where it later settled.
 */
public final class Depot {

    /** The consumer's answer: where a body's goods go, empty for nowhere. */
    public interface Policy {
        Optional<Site> of(AgentId body);
    }

    /**
     * Any store standing in {@code area} is the body's; when none will do, a new one goes at
     * {@code hint}. An area, not a point, because a home that grows keeps every chest it had.
     */
    public record Site(Pos hint, Set<ChunkKey> area) {

        public Site {
            Objects.requireNonNull(hint, "hint");
            area = Set.copyOf(area);
            if (area.isEmpty()) {
                throw new IllegalArgumentException("a site holds at least one chunk");
            }
        }

        /** Whether a store at {@code at} is one of this site's: in a chunk of the area, at any height. */
        public boolean holds(Pos at) {
            String dimension = area.iterator().next().dimension();
            return area.contains(ChunkKey.at(dimension, at.x(), at.z()));
        }
    }

    /**
     * The consumer's reading of how much of a spec a body's site holds — what the body could not
     * have seen arrive: another member's delivery, a cook's food carried home.
     */
    public interface Holdings {
        long of(AgentId body, ItemSpec spec);
    }

    /** Nowhere for everyone — what runs until a consumer installs its own. */
    public static final Policy NOWHERE = body -> Optional.empty();

    /** Nothing known of any site. */
    public static final Holdings UNREAD = (body, spec) -> 0L;

    private static volatile Policy policy = NOWHERE;
    private static volatile Holdings holdings = UNREAD;

    private Depot() {
    }

    public static void install(Policy installed) {
        policy = Objects.requireNonNull(installed, "policy");
    }

    public static Optional<Site> of(AgentId body) {
        return policy.of(body);
    }

    public static void install(Holdings installed) {
        holdings = Objects.requireNonNull(installed, "holdings");
    }

    /** What {@code body}'s site holds of {@code spec} as its consumer reads it; 0 when unread. */
    public static long held(AgentId body, ItemSpec spec) {
        return holdings.of(body, spec);
    }
}
