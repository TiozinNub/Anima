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

    /** How long a body waits after the other's line before initiating — lets follow-ups land. */
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

    public static boolean maySpeak(Encounter e, AgentId self, long now) {
        if (e.closed()) {
            return false;
        }
        if (pendingOn(e, self).isPresent()) {
            return true;
        }
        Utterance last = lastSpoken(e);
        if (last == null) {
            return true;
        }
        if (self.equals(last.author())) {
            return consecutiveBy(e, self) < MAX_CONSECUTIVE;
        }
        return now - last.tick() >= REPLY_GRACE_TICKS;
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
