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

    /** The records holding both {@code a} and {@code b} that closed at or after {@code since}, oldest first. */
    public List<Encounter> closedBetween(AgentId a, AgentId b, long since) {
        List<Encounter> out = new ArrayList<>();
        for (Encounter e : closed) {
            if (e.closedAt() >= since && e.includes(a) && e.includes(b)) {
                out.add(e);
            }
        }
        out.sort(java.util.Comparator.comparingLong(Encounter::closedAt));
        return out;
    }

    /**
     * Whether {@code by} said {@code act} to {@code to} at or after {@code since}, in any record still
     * kept — "has she turned me down lately", read off what was said rather than stored beside it.
     */
    public boolean saidLately(AgentId by, AgentId to, String act, long since) {
        for (List<Encounter> list : List.of(open, closed)) {
            for (Encounter e : list) {
                if (!e.includes(by) || !e.includes(to)) {
                    continue;
                }
                for (Utterance u : e.transcript()) {
                    if (by.equals(u.author()) && u.act().equals(act) && u.tick() >= since) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean stale(Encounter e, long now, long staleGapTicks) {
        return !e.closed() && now - e.lastActivityTick() > staleGapTicks;
    }

    /**
     * Drops closed records past retention, after closing any open one silent for more than
     * {@link #ABANDONED_GAPS} stale gaps.
     *
     * <p>A stale record is otherwise closed only by a participant re-reading it, so one whose
     * participants all died or never load again stayed open for the life of the world. A
     * stopgap: the proper reap is a death line that closes the record like any other ending.
     *
     * @return the records this closed, for the social log — no engine's listener saw them end
     */
    public List<Encounter> prune(long now, long retentionTicks, long staleGapTicks) {
        List<Encounter> reaped = new ArrayList<>();
        for (Encounter e : new ArrayList<>(open)) {
            if (now - e.lastActivityTick() > ABANDONED_GAPS * staleGapTicks) {
                // The subject is nominal: nobody came back to notice.
                e.append(Utterance.system(SpeechActs.STALE.key(), e.participants().get(0), now));
                close(e, now);
                reaped.add(e);
            }
        }
        closed.removeIf(e -> now - e.closedAt() > retentionTicks);
        return reaped;
    }

    /** Far past the gap a returning participant would close at, so this never pre-empts one. */
    static final int ABANDONED_GAPS = 10;

    /** The load path — seats a rebuilt record without a word being said. */
    public void restore(Encounter e) {
        (e.closed() ? closed : open).add(e);
    }
}
