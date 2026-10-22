package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.GroupUp;
import dev.luizloyola.anima.core.social.GroupUps;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Handover;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.anima.mod.command.AgentCommands;
import dev.luizloyola.anima.mod.log.Journals;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;

/** The line two parties become one on: an {@code accept_join} answering an ask to join or an invitation. */
final class Joins {

    private Joins() {
    }

    /** A player said they are leaving: they go, and those left are told. */
    static void left(MinecraftServer server, Encounter e, AgentId who) {
        PartyData parties = PartyData.get(server);
        Optional<PartyId> party = parties.currentPartyOf(who);
        if (party.isEmpty() || !parties.leave(who)) {
            SocialLog.write(server, e, SocialLog.LEAVE, SocialLog.name(server, who)
                    + " said they are leaving, but " + (party.isEmpty() ? "has no party" : "could not leave"));
            return;
        }
        SocialLog.write(server, e, SocialLog.LEAVE, SocialLog.name(server, who) + " left "
                + party(server, party.get()));
        String line = AgentCommands.label(server, who) + " left the party.";
        for (AgentId member : parties.members(party.get())) {
            Journals.of(server).record(member, Category.BRAIN, "party", line);
        }
        Journals.of(server).record(who, Category.BRAIN, "party", "left the party");
    }

    /** {@code party 9c41a2b0 (Tia, Bram)}, members as they stand now. */
    static String party(MinecraftServer server, PartyId party) {
        List<String> names = new ArrayList<>();
        for (AgentId member : PartyData.get(server).members(party)) {
            names.add(SocialLog.name(server, member));
        }
        return "party " + party.value().toString().substring(0, 8) + " (" + String.join(", ", names) + ")";
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
            SocialLog.write(server, e, SocialLog.JOIN, party(server, move.get().from()) + " into "
                    + party(server, move.get().into()) + ", by " + ask.act());
            PartyData.get(server).merge(move.get().from(), move.get().into());
        } else {
            SocialLog.write(server, e, SocialLog.JOIN, ask.act() + " accepted, but the move no longer held");
            for (AgentId who : e.participants()) {
                Journals.of(server).record(who, Category.BRAIN, "party", "agreed to group up, but it no longer held");
            }
        }
    }
}
