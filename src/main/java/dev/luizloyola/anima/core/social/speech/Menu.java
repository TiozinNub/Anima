package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

/**
 * What a participant with no chooser may say right now, and whether a line it proposes is one of
 * those — the pure half of a player's buttons and of the command a button runs.
 *
 * <p>The picker is the law; the menu is the picker read by somebody who cannot want. {@link
 * #offered} is exactly {@link Picker#applicable} gated on {@link Picker#maySpeak}, and {@link
 * #pick} checks a proposal against the same two before anything is said, because the buttons a
 * player clicks are a HINT rendered a beat ago — the record may have moved since.
 */
public final class Menu {

    /** Why a proposed line was or was not accepted — the player-facing reply keys off this. */
    public enum Reason { OK, UNKNOWN, NOT_OFFERED, TOO_SOON, BAD_TOPIC }

    /** A checked proposal: the line to say when {@link #ok}, the refusal otherwise. */
    public record Pick(Reason reason, @Nullable Chooser.Line line) {
        public boolean ok() {
            return reason == Reason.OK;
        }

        private static Pick refused(Reason reason) {
            return new Pick(reason, null);
        }
    }

    private Menu() {
    }

    /** The acts {@code self} may say into {@code e} this tick — empty while the beat is unspent. */
    public static List<SpeechAct> offered(Encounter e, AgentId self, long now, int turnCap) {
        return Picker.maySpeak(e, self, now) ? Picker.applicable(e, self, turnCap) : List.of();
    }

    /**
     * Checks {@code key} (and {@code topic}, which may be null) against what is offered. A
     * topic-bearing act with no topic given draws one from what the act declares — the one place
     * a line is chosen for a speaker rather than by one, and it is only ever the flavour.
     *
     * <p>Refusals are ordered from "you cannot mean that" to "not quite yet": an act the registry
     * does not hold, then one the picker would not offer whatever the tick, then the beat, then
     * the topic — so the reason reported is the one the player can act on.
     */
    public static Pick pick(Encounter e, AgentId self, long now, int turnCap, String key,
            @Nullable String topic, RandomGenerator random) {
        SpeechAct act = SpeechActs.byKey(key).orElse(null);
        if (act == null) {
            return Pick.refused(Reason.UNKNOWN);
        }
        if (!Picker.applicable(e, self, turnCap).contains(act)) {
            return Pick.refused(Reason.NOT_OFFERED);
        }
        if (!Picker.maySpeak(e, self, now)) {
            return Pick.refused(Reason.TOO_SOON);
        }
        if (act.topics().isEmpty()) {
            return topic == null ? new Pick(Reason.OK, Chooser.Line.of(act))
                    : Pick.refused(Reason.BAD_TOPIC);
        }
        if (topic == null) {
            topic = act.topics().get(random.nextInt(act.topics().size()));
        } else if (!act.topics().contains(topic)) {
            return Pick.refused(Reason.BAD_TOPIC);
        }
        return new Pick(Reason.OK, new Chooser.Line(act, Map.of(Utterance.TOPIC, topic)));
    }
}
