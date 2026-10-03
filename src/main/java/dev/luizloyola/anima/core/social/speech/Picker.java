package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Whose turn it is and what may be said — the interface into the chat. The chooser (the part
 * with the personality) picks FROM this; nothing here wants anything.
 */
public final class Picker {

    /**
     * The floor a replier waits before answering — the pace a player reads a conversation at.
     * Not enforced here (decision: Luiz, 2026-09-14): a pause is the REPLIER's own manner.
     * {@code Converse} waits this plus its jitter before a settler's line; a player answers when
     * they click; and a player's seat gives a body this long to answer before offering a second
     * line. Kept on the record it put every quick answer, a person's included, behind a refusal.
     */
    public static final int REPLY_GRACE_TICKS = 20;

    /** Two lines in a row is a thought; three is a monologue. */
    public static final int MAX_CONSECUTIVE = 2;

    /**
     * One act's standing on a turn: whether it is on offer, and the branch that decided it.
     *
     * <p>{@code reason} is dev-facing English, not a lang key — this is the readout an operator
     * reads while WRITING new kinds of conversation, and it names branches of this class rather
     * than anything a player ever sees.
     */
    public record Verdict(SpeechAct act, boolean applicable, String reason) {
    }

    private Picker() {
    }

    public static Optional<Utterance> pendingOn(Encounter e, AgentId self) {
        List<Utterance> lines = e.transcript();
        for (int i = lines.size() - 1; i >= 0; i--) {
            Utterance u = lines.get(i);
            if (u.system()) {
                continue;
            }
            if (self.equals(u.author())) {
                return Optional.empty();   // anything I said since discharges
            }
            SpeechAct act = SpeechActs.byKey(u.act()).orElse(null);
            if (act != null && act.obliges()) {
                return Optional.of(u);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code self} may say something into {@code e} right now: the record open, and —
     * unless an answer is owed — no more than {@link #MAX_CONSECUTIVE} lines of their own in a
     * row. What this refuses is a monologue, never a quick answer: the beat between lines is the
     * replier's own manner, not the record's rule (see {@link #REPLY_GRACE_TICKS}).
     */
    public static boolean maySpeak(Encounter e, AgentId self) {
        if (e.closed()) {
            return false;
        }
        // An owed answer outranks the monologue cap — stated even though a line of one's own
        // discharges what was pending, so the two cannot actually co-occur.
        return pendingOn(e, self).isPresent() || consecutiveBy(e, self) < MAX_CONSECUTIVE;
    }

    /** What {@code self} may say into {@code e} — the applicable half of {@link #explain}. */
    public static List<SpeechAct> applicable(Encounter e, AgentId self, int turnCap) {
        List<SpeechAct> out = new ArrayList<>();
        for (Verdict verdict : explain(e, self, turnCap)) {
            if (verdict.applicable()) {
                out.add(verdict.act());
            }
        }
        return out;
    }

    /**
     * Every registered act with the reason it is or is not on offer — what {@code /anima chat}
     * prints, and the one place the rules live: {@link #applicable} is a filter over this, so a
     * readout that disagrees with the filter is not expressible.
     */
    public static List<Verdict> explain(Encounter e, AgentId self, int turnCap) {
        boolean capped = e.transcript().size() >= turnCap;
        Optional<Utterance> pending = pendingOn(e, self);
        SpeechAct pendingAct = pending.flatMap(u -> SpeechActs.byKey(u.act())).orElse(null);
        List<String> constrained = pendingAct == null || pendingAct.responses().isEmpty()
                ? null : pendingAct.responses();
        List<Verdict> out = new ArrayList<>();
        for (SpeechAct act : SpeechActs.all()) {
            if (!act.negotiable()) {
                out.add(new Verdict(act, false,
                        "a system verdict — written by whoever notices, never chosen"));
            } else if (act == SpeechActs.HAIL) {
                out.add(new Verdict(act, false, "the hail opens a record; it is not said"));
            } else if (act.ends()) {
                // Whatever is pending and whatever the cap: leaving discharges what was owed, so
                // nothing turns a goodbye away. With a goodbye pending the constraint is [end_chat]
                // anyway, and the acknowledgement is the only thing on offer.
                out.add(new Verdict(act, true, "a goodbye is always on offer"));
            } else if (constrained != null && !constrained.contains(act.key())) {
                out.add(new Verdict(act, false, "constrained by the pending " + pendingAct.key()
                        + " → " + String.join(", ", constrained)));
            } else if (capped) {
                out.add(new Verdict(act, false, "the cap leaves only farewells"));
            } else {
                out.add(new Verdict(act, true, "applicable"));
            }
        }
        return out;
    }

    /**
     * The other party, once a question of ours they never answered has outrun patience — the
     * snub. An unacknowledged goodbye is deliberately not one: see {@link #lapsedFarewell}.
     */
    public static Optional<AgentId> expiredObligation(Encounter e, AgentId self, long now,
            int patienceTicks) {
        for (AgentId other : e.participants()) {
            if (other.equals(self)) {
                continue;
            }
            Optional<Utterance> pending = pendingOn(e, other).filter(u -> !isEnding(u));
            if (pending.isPresent()
                    && now - pending.get().tick() > patienceFor(pending.get(), patienceTicks)) {
                return Optional.of(other);
            }
        }
        return Optional.empty();
    }

    /** How long {@code owed} is waited on, by a body whose patience is {@code patienceTicks}. */
    public static long patienceFor(Utterance owed, int patienceTicks) {
        return (long) patienceTicks * SpeechActs.byKey(owed.act()).map(SpeechAct::patience).orElse(1);
    }

    /**
     * Whether {@code self}'s own goodbye has gone unacknowledged past patience — the one wait that
     * ends without a verdict. A snub is a question nobody answered; a goodbye nobody answered is a
     * body that had already left, and the record's last line is the goodbye, which is the reason
     * it ended. So the waiter closes the door quietly instead of writing IGNORED about somebody who
     * owed only an "okay".
     */
    public static boolean lapsedFarewell(Encounter e, AgentId self, long now, int patienceTicks) {
        for (AgentId other : e.participants()) {
            if (other.equals(self)) {
                continue;
            }
            Optional<Utterance> pending = pendingOn(e, other)
                    .filter(u -> self.equals(u.author()) && isEnding(u));
            if (pending.isPresent() && now - pending.get().tick() > patienceTicks) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEnding(Utterance u) {
        return SpeechActs.byKey(u.act()).map(SpeechAct::ends).orElse(false);
    }

    /**
     * Whether {@code other} has yet to say one line into {@code e}, and the wait has outrun
     * patience — how a body that never came is noticed. Only an obligation runs the snub clock,
     * and a HAIL obliges nobody: it is an opener, so a record holding nothing but the caller's
     * hail would otherwise sit open until staleness swept it, with the caller none the wiser.
     * A hail of {@code other}'s own does not count as an answer either — {@code Converse}'s
     * contact rule reads it the same way: a shout is what a body does before it arrives.
     */
    public static boolean unanswered(Encounter e, AgentId other, long now, int patienceTicks) {
        for (Utterance u : e.transcript()) {
            if (!u.system() && other.equals(u.author())
                    && !SpeechActs.HAIL.key().equals(u.act())) {
                return false;
            }
        }
        return now - e.lastActivityTick() > patienceTicks;
    }

    /**
     * How long nobody has SAID anything — ticks since the last spoken line, or since the record
     * opened while nothing has been said. What a chooser reads before saying goodbye: leaving the
     * moment nothing is pressing reads as bolting, and on the first client run it gave a player who
     * had just answered "who are you?" two seconds to ask anything back (2026-09-13).
     */
    public static long silence(Encounter e, long now) {
        return now - lastSpoken(e).map(Utterance::tick).orElse(e.openedAt());
    }

    /**
     * The last line somebody SAID — the one the beat is measured from. A SYSTEM line is the world
     * reporting on the conversation rather than a turn in it, so it never restarts the clock.
     *
     * <p>Public because the {@code /anima chat} readout has to print the very tick this class
     * counts from; a second scan of the transcript would be a second opinion.
     */
    public static Optional<Utterance> lastSpoken(Encounter e) {
        return Optional.ofNullable(lastSpokenOrNull(e));
    }

    private static @Nullable Utterance lastSpokenOrNull(Encounter e) {
        List<Utterance> lines = e.transcript();
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (!lines.get(i).system()) {
                return lines.get(i);
            }
        }
        return null;
    }

    /** How many lines in a row {@code self} has just said — what {@link #MAX_CONSECUTIVE} caps. */
    public static int consecutiveBy(Encounter e, AgentId self) {
        int run = 0;
        List<Utterance> lines = e.transcript();
        for (int i = lines.size() - 1; i >= 0; i--) {
            Utterance u = lines.get(i);
            if (u.system()) {
                continue;
            }
            if (!self.equals(u.author())) {
                break;
            }
            run++;
        }
        return run;
    }
}
