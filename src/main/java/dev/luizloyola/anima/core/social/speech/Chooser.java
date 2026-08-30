package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The personality behind a conversation — what a body says next. Anima ships only
 * {@link Choosers#BASIC}; a consumer's own chooser reads from the same {@link Turn} and knows
 * nothing about the engine that built it.
 */
public interface Chooser {

    /** One line to say — an act plus whatever payload it needs. */
    record Line(SpeechAct act, Map<String, String> payload) {
        public Line {
            payload = Map.copyOf(payload);
        }

        public static Line of(SpeechAct act) {
            return new Line(act, Map.of());
        }
    }

    /**
     * Everything self-relative, pre-computed by the engine — a chooser has no self-id
     * (BrainContext deliberately lacks one): what may be said, the ask pending on me,
     * whether a non-system GREETING of mine is already in the transcript, and who the
     * other party is.
     */
    record Turn(Encounter encounter, List<SpeechAct> applicable, Optional<Utterance> pending,
            boolean greeted, Optional<AgentId> counterpart) {
        public Turn {
            applicable = List.copyOf(applicable);
        }
    }

    /** The next line, or {@code null} for silence — proximity alone is still company. */
    @Nullable Line choose(BrainContext ctx, Turn turn);

    /**
     * How this chooser would narrate its own priorities on {@code turn} — one line per rule, for
     * the {@code /anima chat} readout an author writes new conversations against.
     *
     * <p>Empty means mute, and that is the honest default: a chooser owes nobody an account of
     * itself, and {@link Choosers#BASIC} has three lines of logic to read instead.
     */
    default List<String> explain(BrainContext ctx, Turn turn) {
        return List.of();
    }
}
