package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every conversation in the world, open and recently closed. Closed records are kept — the
 * debugging artifact, and what an overheard line resolves against — until retention prunes
 * them. Linear scans: a world holds dozens of these, not thousands.
 */
public final class Encounters {

    private final List<Encounter> open = new ArrayList<>();
    private final List<Encounter> closed = new ArrayList<>();

    public Optional<Encounter> openFor(AgentId who) {
        for (Encounter e : open) {
            if (e.includes(who)) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    public Encounter join(AgentId a, AgentId b, long now) {
        for (Encounter e : open) {
            if (e.includes(a) && e.includes(b)) {
                return e;
            }
        }
        Encounter fresh = new Encounter(UUID.randomUUID(), List.of(a, b), now);
        open.add(fresh);
        return fresh;
    }

    public void close(Encounter e, long now) {
        if (open.remove(e)) {
            e.close(now);
            closed.add(e);
        }
    }

    public List<Encounter> open() { return Collections.unmodifiableList(open); }

    public List<Encounter> closed() { return Collections.unmodifiableList(closed); }

    public boolean stale(Encounter e, long now, long staleGapTicks) {
        return !e.closed() && now - e.lastActivityTick() > staleGapTicks;
    }

    public void prune(long now, long retentionTicks) {
        closed.removeIf(e -> now - e.closedAt() > retentionTicks);
    }

    /** The load path — seats a rebuilt record without a word being said. */
    public void restore(Encounter e) {
        (e.closed() ? closed : open).add(e);
    }
}
