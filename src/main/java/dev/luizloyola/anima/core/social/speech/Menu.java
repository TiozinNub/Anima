package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.speech.Chooser.Line;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

/**
 * What a participant with no chooser may say right now, and whether a line it proposes is one of
 * those — the pure half of a player's buttons and of the command a button runs.
 *
 * <p>The picker is the law; the menu is the picker read by somebody who cannot want — and then
 * narrowed by what they already did and already know, the facts a chooser reads for a settler and
 * nobody read for a player until 2026-09-13 (decision: Luiz): seven buttons a beat, Greet after
 * greeting and Introduce yourself after introducing, was a menu that meant nothing. {@link
 * #offered} is {@link Picker#applicable} gated on {@link Picker#maySpeak} less those, and {@link
 * #pick} checks a proposal against the same set before anything is said, because the buttons a
 * player clicks are a HINT rendered a moment ago — the record may have moved since.
 *
 * <p><b>Narrowed by flags and the book, never by a consumer's act key.</b> Anima does not know
 * what {@code ask_identity} is; it knows that an act whose declared responses include an
 * introducing act is asking for a name, and that a name already in the book makes the ask
 * pointless. The four trims:
 * <ul>
 *   <li>never the greeting — the hail that opened the record was the greeting, and the other
 *       side greets back on its own;</li>
 *   <li>a line that says nothing (non-obliging, non-introducing, non-ending, no topics: Anima's
 *       {@code deflect}, and any word a consumer shapes the same way) only while an obligation
 *       is pending on self — it is the way to not answer, never an opener;</li>
 *   <li>a request for a name only while self does not know the counterpart;</li>
 *   <li>an introduction only while the counterpart does not know self, and once per record.</li>
 * </ul>
 */
public final class Menu {

    /** Why a proposed line was or was not accepted — the player-facing reply keys off this. */
    public enum Reason { OK, UNKNOWN, NOT_OFFERED, BAD_TOPIC }

    /** A checked proposal: the line to say when {@link #ok}, the refusal otherwise. */
    public record Pick(Reason reason, @Nullable Line line) {
        public boolean ok() {
            return reason == Reason.OK;
        }

        private static Pick refused(Reason reason) {
            return new Pick(reason, null);
        }
    }

    private Menu() {
    }

    /**
     * The acts {@code self} may say into {@code e} right now — empty while the picker refuses
     * them a turn (the record closed, or two lines of their own already said).
     *
     * @param knows the contact book as a question, {@code (knower, whom)}
     */
    public static List<SpeechAct> offered(Encounter e, AgentId self, int turnCap,
            BiPredicate<AgentId, AgentId> knows) {
        return Picker.maySpeak(e, self) ? offerable(e, self, turnCap, knows) : List.of();
    }

    /**
     * Checks {@code key} (and {@code topic}, which may be null) against what is offered. A
     * topic-bearing act with no topic given draws one from what the act declares — the one place
     * a line is chosen for a speaker rather than by one, and it is only ever the flavour.
     *
     * <p>Refusals are ordered from "you cannot mean that" inward: an act the registry does not
     * hold, then one this menu would not offer, then the topic — so the reason reported is the
     * one the player can act on.
     */
    public static Pick pick(Encounter e, AgentId self, int turnCap,
            BiPredicate<AgentId, AgentId> knows, String key, @Nullable String topic,
            RandomGenerator random) {
        SpeechAct act = SpeechActs.byKey(key).orElse(null);
        if (act == null) {
            return Pick.refused(Reason.UNKNOWN);
        }
        if (!Picker.maySpeak(e, self) || !offerable(e, self, turnCap, knows).contains(act)) {
            return Pick.refused(Reason.NOT_OFFERED);
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

    /**
     * The picker's set less what this speaker has already done or already knows — what
     * {@link #offered} will hold once the beat is spent. Public so a panel can draw the buttons
     * greyed the moment a line lands and light them on the beat, instead of the row appearing a
     * second late (decision: Luiz, 2026-09-14).
     */
    public static List<SpeechAct> offerable(Encounter e, AgentId self, int turnCap,
            BiPredicate<AgentId, AgentId> knows) {
        boolean owes = Picker.pendingOn(e, self).isPresent();
        Optional<AgentId> them = e.other(self);
        boolean knowsThem = them.map(other -> knows.test(self, other)).orElse(false);
        boolean knownByThem = them.map(other -> knows.test(other, self)).orElse(false);
        List<SpeechAct> out = new ArrayList<>();
        for (SpeechAct act : Picker.applicable(e, self, turnCap)) {
            if (act == SpeechActs.GREETING
                    || (saysNothing(act) && !owes)
                    || (asksForAName(act) && knowsThem)
                    || (act.introduces() && (knownByThem || alreadySaid(e, self, act)))) {
                continue;
            }
            out.add(act);
        }
        return out;
    }

    /** A word with no effect and no subject — the shape of a line that says nothing. */
    private static boolean saysNothing(SpeechAct act) {
        return !act.obliges() && !act.introduces() && !act.ends() && act.topics().isEmpty();
    }

    /** An ask whose declared answers include an introduction is an ask for a name. */
    private static boolean asksForAName(SpeechAct act) {
        for (String response : act.responses()) {
            if (SpeechActs.byKey(response).map(SpeechAct::introduces).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean alreadySaid(Encounter e, AgentId self, SpeechAct act) {
        for (Utterance u : e.transcript()) {
            if (!u.system() && self.equals(u.author()) && u.act().equals(act.key())) {
                return true;
            }
        }
        return false;
    }
}
