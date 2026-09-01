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

    /**
     * The pair's open record: rejoined if they already have one, freshly opened if neither is
     * busy, and REFUSED — empty — if either of them is mid-conversation with somebody else.
     *
     * <p><b>One open conversation per body.</b> Without the refusal a crowd churns: four bodies
     * standing together each open a record against every other, so the moment one closes its
     * parties are pulled straight into the next, and a name learned in one record is unknown to
     * the one that opens a tick later. A bystander must wait its turn rather than queue a claim on
     * a busy body.
     */
    public Optional<Encounter> join(AgentId a, AgentId b, long now) {
        for (Encounter e : open) {
            if (e.includes(a) && e.includes(b)) {
                return Optional.of(e);
            }
        }
        if (openFor(a).isPresent() || openFor(b).isPresent()) {
            return Optional.empty();
        }
        Encounter fresh = new Encounter(UUID.randomUUID(), List.of(a, b), now);
        open.add(fresh);
        return Optional.of(fresh);
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
