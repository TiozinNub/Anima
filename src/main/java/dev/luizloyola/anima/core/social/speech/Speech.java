package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import java.util.List;
import java.util.Optional;

/**
 * The port onto a body's one conversation — everything a task like {@code Converse} touches
 * without caring whether a real {@link SpeechEngine} or the mute {@link #NONE} answers
 * underneath. {@code BrainContext.speech()} is the one way in.
 */
public interface Speech {

    /** Who is credited with opening a fresh encounter — the hail is prefilled to match. */
    enum Opening { I_HAILED, THEY_HAILED, QUIET }

    /**
     * This body's open encounter — unless it just went stale, which is noticed and closed right
     * here (a STALE line written, the record ended) so nothing downstream ever acts on a
     * conversation the other side walked away from ticks ago.
     */
    Optional<Encounter> current();

    /**
     * Opens or rejoins the encounter with {@code other}, crediting the hail as {@code opening}
     * says — empty when either body is already talking to somebody else, which is a refusal to
     * retry later rather than a failure; see {@link Encounters#join}.
     */
    Optional<Encounter> join(BeingId other, Opening opening);

    /** Says {@code line}, authored by this body; closes the record when the act ends it. */
    void say(Encounter e, Chooser.Line line);

    /** Writes a SYSTEM line about {@code subject} — a no-op the second time for the same pair. */
    void system(Encounter e, SpeechAct act, AgentId subject);

    /** Ends the record, if it is not ended already. */
    void close(Encounter e);

    /** Whether this body may speak into {@code e} right now — every line waits its beat but the first. */
    boolean maySpeak(Encounter e);

    /** The other party, once their unanswered obligation has outrun this body's patience. */
    Optional<AgentId> expiredObligation(Encounter e);

    /** This body's view of {@code e} — what a {@link Chooser} picks from. */
    Chooser.Turn turn(Encounter e);

    /** The other participant in {@code e}. */
    Optional<AgentId> counterpart(Encounter e);

    /** The chooser this body's conversation defers to. */
    Chooser chooser();

    /**
     * A body with no conversation machinery wired up — every context predating this task, and
     * any body a consumer never gives a real {@link Speech}. Queries answer as an honestly mute
     * body would: nothing open, nothing to say, nobody there. Mutators throw instead of quietly
     * doing nothing — a task that reaches one has a wiring bug to fix, not a silence to render.
     */
    Speech NONE = new Speech() {
        @Override
        public Optional<Encounter> current() {
            return Optional.empty();
        }

        @Override
        public Optional<Encounter> join(BeingId other, Opening opening) {
            // Still a throw, not an empty: a mute body has no conversation to refuse, it has no
            // machinery at all — the difference between "busy" and "miswired".
            throw mute();
        }

        @Override
        public void say(Encounter e, Chooser.Line line) {
            throw mute();
        }

        @Override
        public void system(Encounter e, SpeechAct act, AgentId subject) {
            throw mute();
        }

        @Override
        public void close(Encounter e) {
            throw mute();
        }

        @Override
        public boolean maySpeak(Encounter e) {
            return false;
        }

        @Override
        public Optional<AgentId> expiredObligation(Encounter e) {
            return Optional.empty();
        }

        @Override
        public Chooser.Turn turn(Encounter e) {
            return new Chooser.Turn(e, List.of(), Optional.empty(), Optional.empty(), false,
                    Optional.empty());
        }

        @Override
        public Optional<AgentId> counterpart(Encounter e) {
            return Optional.empty();
        }

        @Override
        public Chooser chooser() {
            return Choosers.BASIC;
        }
    };

    private static IllegalStateException mute() {
        return new IllegalStateException("a mute body has no conversation");
    }
}
