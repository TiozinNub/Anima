package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whose turn it is and what may be said — the interface into the chat. The chooser (the part
 * with the personality) picks FROM this; nothing here wants anything.
 */
public final class Picker {

    /** The beat between any two lines — a conversation that lands one per tick is unreadable. */
    public static final int REPLY_GRACE_TICKS = 20;

    /** Two lines in a row is a thought; three is a monologue. */
    public static final int MAX_CONSECUTIVE = 2;

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
        Utterance last = lastSpoken(e);
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

    public static List<SpeechAct> applicable(Encounter e, AgentId self, int turnCap) {
        boolean capped = e.transcript().size() >= turnCap;
        Optional<Utterance> pending = pendingOn(e, self);
        List<String> constrained = pending
                .flatMap(u -> SpeechActs.byKey(u.act()))
                .map(SpeechAct::responses)
                .filter(r -> !r.isEmpty())
                .orElse(null);
        boolean endAsked = pending
                .map(u -> u.act().equals(SpeechActs.REQUEST_END_CHAT.key()))
                .orElse(false);
        List<SpeechAct> out = new ArrayList<>();
        for (SpeechAct act : SpeechActs.all()) {
            if (!act.negotiable() || act == SpeechActs.HAIL) {
                continue;   // system verdicts are written, not chosen; the hail opens, it is not said
            }
            if (constrained != null && !constrained.contains(act.key())) {
                continue;
            }
            if (act.ends() && !endAsked && !capped) {
                continue;   // goodbye answers a proposal — or a record that has run its cap
            }
            if (capped && !act.ends() && act != SpeechActs.REQUEST_END_CHAT) {
                continue;
            }
            out.add(act);
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

    private static Utterance lastSpoken(Encounter e) {
        List<Utterance> lines = e.transcript();
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (!lines.get(i).system()) {
                return lines.get(i);
            }
        }
        return null;
    }

    private static int consecutiveBy(Encounter e, AgentId self) {
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
