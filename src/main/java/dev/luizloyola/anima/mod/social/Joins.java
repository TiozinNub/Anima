package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.GroupUp;
import dev.luizloyola.anima.core.social.GroupUps;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Handover;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.anima.mod.log.Journals;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;

/** The line two parties become one on: an {@code accept_join} answering an ask to join or an invitation. */
final class Joins {

    private Joins() {
    }

    /** Which way {@code ask} goes, if it is an ask to group up at all. */
    static Optional<GroupUp.Ask> askOf(Utterance ask) {
        if (ask.act().equals(SpeechActs.ASK_JOIN.key())) {
            return Optional.of(GroupUp.Ask.JOIN);
        }
        if (ask.act().equals(SpeechActs.INVITE.key())) {
            return Optional.of(GroupUp.Ask.INVITE);
        }
        return Optional.empty();
    }

    /**
     * The accept said at {@code index}: the rule is asked once more, since both answerers re-read it
     * at their turn and the world may still have moved between; a move that no longer holds merges
     * nothing and says so in the journal.
     */
    static void accepted(MinecraftServer server, Encounter e, int index) {
        AgentId asked = e.transcript().get(index).author();
        Optional<Utterance> pending = Handover.answered(e, index);
        if (asked == null || pending.isEmpty()) {
            return;
        }
        Utterance ask = pending.get();
        Optional<GroupUp.Ask> kind = askOf(ask);
        if (kind.isEmpty() || ask.author() == null) {
            return;
        }
        Optional<GroupUp.Move> move = GroupUps.get().move(ask.author(), asked, kind.get());
        if (move.isPresent()) {
            PartyData.get(server).merge(move.get().from(), move.get().into());
        } else {
            for (AgentId who : e.participants()) {
                Journals.of(server).record(who, Category.BRAIN, "party", "agreed to group up, but it no longer held");
            }
        }
    }
}
