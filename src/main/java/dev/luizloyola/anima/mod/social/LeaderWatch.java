package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.Deposing;
import dev.luizloyola.anima.core.social.GroupUps;
import dev.luizloyola.anima.core.social.Ledger;
import dev.luizloyola.anima.core.social.Ledgers;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.command.AgentCommands;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.mod.log.Journals;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the clock on player leaders who are away — offline, or out of reach by the consumer's
 * measure — and gives the lead to the next member when the party's discontent says so (group-up
 * spec, decision 17). Settler leaders are never watched: they go only by dying or leaving.
 */
public final class LeaderWatch {

    private static final int EVERY_TICKS = 200;

    private LeaderWatch() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % EVERY_TICKS == 0) {
                watch(server);
            }
        });
    }

    static void watch(MinecraftServer server) {
        PartyData parties = PartyData.get(server);
        LedgerData ledgers = LedgerData.get(server);
        long now = LedgerData.now(server);
        for (PartyId party : List.copyOf(parties.parties())) {
            Optional<AgentId> leader = parties.leader(party);
            if (leader.isEmpty() || AgentDirectory.of(server).identity(leader.get()).isPresent()) {
                ledgers.back(party);
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(leader.get().value());
            if (player != null && !GroupUps.get().awayFromParty(leader.get())) {
                ledgers.back(party);
                continue;
            }
            long since = ledgers.away(party, now);
            AgentBody weigher = weigher(server, parties.members(party));
            if (weigher == null) {
                continue; // nobody loaded to feel it
            }
            Ledgers.Reading discontent = ledgers.ledgers().read(Ledger.DISCONTENT, party, weigher.profile(), now);
            double away = discontent.lines().stream().filter(line -> line.key().equals(LedgerData.AWAY))
                    .mapToDouble(Ledgers.Line::points).sum();
            double days = (double) (now - since) / Ledgers.DAY_TICKS;
            if (Deposing.deposes(days, discontent.total(), discontent.total() - away, weigher.profile())) {
                parties.depose(party).ifPresent(next -> deposed(server, party, leader.get(), next, player));
                ledgers.back(party);
            }
        }
    }

    /** The most senior member with a body to weigh it by. */
    private static @Nullable AgentBody weigher(MinecraftServer server, List<AgentId> members) {
        for (AgentId member : members) {
            AgentBody body = AgentBodies.findLoaded(server, member);
            if (body != null) {
                return body;
            }
        }
        return null;
    }

    private static void deposed(MinecraftServer server, PartyId party, AgentId was, AgentId next,
            @Nullable ServerPlayer player) {
        String line = AgentCommands.label(server, next) + " took the lead while " + AgentCommands.label(server, was)
                + " was away.";
        for (AgentId member : PartyData.get(server).members(party)) {
            Journals.of(server).record(member, Category.BRAIN, "party", line);
        }
        if (player != null) {
            player.sendSystemMessage(Component.translatable("anima.party.deposed", AgentCommands.label(server, next)));
        }
    }
}
