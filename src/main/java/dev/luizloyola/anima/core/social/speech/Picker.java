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

    /** The beat between any two lines — a conversation that lands one per tick is unreadable. */
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
     * Whether {@code self} may say something into {@code e} right now. EVERY line waits out
     * {@link #REPLY_GRACE_TICKS} after the previous one — whoever said it, obligation pending or
     * not; only an empty record speaks at once. An obligation waives the monologue cap (an answer
     * owed is still owed after two lines of one's own) but never the beat: the grace is the pace
     * a player reads the conversation at, and letting an owed reply skip it put a whole
     * conversation on one tick.
     */
    public static boolean maySpeak(Encounter e, AgentId self, long now) {
        if (e.closed()) {
            return false;
        }
        Utterance last = lastSpokenOrNull(e);
        if (last == null) {
            return true;
        }
        if (now - last.tick() < REPLY_GRACE_TICKS) {
            return false;
        }
        // An owed answer outranks the monologue cap — stated even though a line of one's own
        // discharges what was pending, so the two cannot actually co-occur. Patience (300) dwarfs
        // the beat: waiting one out can never read as the snub expiredObligation names.
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
        boolean endAsked = pending
                .map(u -> u.act().equals(SpeechActs.REQUEST_END_CHAT.key()))
                .orElse(false);
        List<Verdict> out = new ArrayList<>();
        for (SpeechAct act : SpeechActs.all()) {
            if (!act.negotiable()) {
                out.add(new Verdict(act, false,
                        "a system verdict — written by whoever notices, never chosen"));
            } else if (act == SpeechActs.HAIL) {
                out.add(new Verdict(act, false, "the hail opens a record; it is not said"));
            } else if (constrained != null && !constrained.contains(act.key())) {
                out.add(new Verdict(act, false, "constrained by the pending " + pendingAct.key()
                        + " → " + String.join(", ", constrained)));
            } else if (act.ends() && !endAsked && !capped) {
                out.add(new Verdict(act, false,
                        "a goodbye answers a request_end_chat, or a record at its cap"));
            } else if (capped && !act.ends() && act != SpeechActs.REQUEST_END_CHAT) {
                out.add(new Verdict(act, false, "the cap leaves only farewells"));
            } else {
                out.add(new Verdict(act, true, "applicable"));
            }
        }
        return out;
    }

    public static Optional<AgentId> expiredObligation(Encounter e, AgentId self, long now,
            int patienceTicks) {
        for (AgentId other : e.participants()) {
            if (other.equals(self)) {
                continue;
            }
            Optional<Utterance> pending = pendingOn(e, other);
            if (pending.isPresent() && now - pending.get().tick() > patienceTicks) {
                return Optional.of(other);
            }
        }
        return Optional.empty();
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
