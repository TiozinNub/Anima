package dev.luizloyola.anima.core.social;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.Optional;

/**
 * Grouping up as the species decides it (group-up spec): whether one body may ask another to merge
 * their parties, what a body answers, and which party moves on an accept. Anima carries the asks,
 * the hail and the merge; the rule is the consumer's, installed through {@link GroupUps}. With none
 * installed, nobody groups up.
 */
public interface GroupUp {

    /** "Can I join you?" moves the asker's party; "Want to join us?" moves the asked side's. */
    enum Ask {
        JOIN, INVITE
    }

    /** The merge an accepted ask means: {@code from}'s members move into {@code into}. */
    record Move(PartyId from, PartyId into) {
    }

    /**
     * The ask {@code self} would make of {@code other} now: the rule allows a merge, self's own party
     * is willing, and {@code other} has not turned self down lately. Empty when there is none.
     */
    Optional<Ask> ask(AgentId self, AgentId other);

    /** How hard {@code self} wants to go looking for a party to join; 0 when not at all. */
    double seeking(AgentId self);

    /** Whether {@code other} is somebody {@code self} would walk up to and ask. */
    boolean worthSeeking(AgentId self, AgentId other);

    /**
     * Why {@code self} turns down {@code asker}'s {@code ask} — a {@code refuse_join} topic — or empty
     * to accept. Read at the answering turn, so a party that settled since the ask refuses.
     */
    Optional<String> refusal(AgentId self, AgentId asker, Ask ask);

    /** The merge {@code ask}, made by {@code asker} of {@code asked}, means now; empty if it no longer holds. */
    Optional<Move> move(AgentId asker, AgentId asked, Ask ask);

    /** The rule of a species that does not group up. */
    GroupUp NEVER = new GroupUp() {
        @Override
        public Optional<Ask> ask(AgentId self, AgentId other) {
            return Optional.empty();
        }

        @Override
        public double seeking(AgentId self) {
            return 0;
        }

        @Override
        public boolean worthSeeking(AgentId self, AgentId other) {
            return false;
        }

        @Override
        public Optional<String> refusal(AgentId self, AgentId asker, Ask ask) {
            return Optional.of("not_now");
        }

        @Override
        public Optional<Move> move(AgentId asker, AgentId asked, Ask ask) {
            return Optional.empty();
        }
    };
}
