package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.Circle;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.command.AgentCommands;
import dev.luizloyola.anima.mod.log.Journals;
import dev.luizloyola.anima.mod.net.ContactsSync;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Joining a party introduces you to everyone in it, as if someone went round the circle (social
 * foundations §6) — but only those within sight are learned in full. The rest are names without
 * faces until first seen, and that first sight is their meeting (group-up spec, decisions 18–19).
 */
public final class Introductions {

    /**
     * How far a player is taken to see a member they have only heard of. A player has no senses to
     * ask, so this stands in for them: in loaded range and in line of sight. A starting value.
     */
    static final double PLAYER_SIGHT = 32.0;

    private static final int PLAYER_SWEEP_TICKS = 20;

    private Introductions() {
    }

    /** Once per server start: arrivals introduce, and players' names find their faces. */
    public static void install(MinecraftServer server) {
        PartyData.get(server).onArrival((into, who) -> arrived(server, into, who));
    }

    /** Registered once at mod init: the players' half of first sight. */
    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PLAYER_SWEEP_TICKS == 0) {
                sweepPlayers(server);
            }
        });
    }

    /**
     * Every newcomer and every member who was there learn each other, in full if they can see each
     * other now, by name otherwise. However many names it taught, for each learner it was one
     * meeting. One line tells it.
     */
    static void arrived(MinecraftServer server, PartyId into, List<AgentId> newcomers) {
        ContactData contacts = ContactData.get(server);
        List<AgentId> there = new ArrayList<>(PartyData.get(server).members(into));
        there.removeAll(newcomers);
        Set<AgentId> learned = new LinkedHashSet<>();
        for (Circle.Step step : Circle.round(newcomers, there, contacts::knows, (a, b) -> sees(server, a, b))) {
            boolean gained = step.face()
                    ? face(server, contacts, step.learner(), step.whom())
                    : contacts.hear(step.learner(), step.whom());
            if (gained) {
                learned.add(step.learner());
            }
        }
        learned.forEach(who -> ContactData.met(server, who));
        narrate(server, into, newcomers, there);
    }

    /**
     * A body's being sense, which names an individual only by sight; for a player, range and line of
     * sight. Somebody not loaded sees nobody.
     */
    private static boolean sees(MinecraftServer server, AgentId learner, AgentId whom) {
        AgentBody body = AgentBodies.findLoaded(server, learner);
        if (body != null) {
            return body.beingSense().being(BeingId.of(whom))
                    .filter(being -> being.identified() == Being.Identified.INDIVIDUAL).isPresent();
        }
        ServerPlayer player = server.getPlayerList().getPlayer(learner.value());
        Entity other = entityOf(server, whom);
        return player != null && other != null && playerSees(player, other);
    }

    private static boolean playerSees(ServerPlayer player, Entity other) {
        return other.level() == player.level()
                && player.distanceToSqr(other) <= PLAYER_SIGHT * PLAYER_SIGHT
                && player.hasLineOfSight(other);
    }

    private static @Nullable Entity entityOf(MinecraftServer server, AgentId who) {
        AgentBody body = AgentBodies.findLoaded(server, who);
        return body != null ? body.entity() : server.getPlayerList().getPlayer(who.value());
    }

    /** The name gets its face: the nameplate, the percept and the journal all learn it. */
    private static boolean face(MinecraftServer server, ContactData contacts, AgentId learner, AgentId whom) {
        if (!contacts.learnQuietly(learner, whom)) {
            return false;
        }
        ContactsSync.learned(server, learner, whom);
        AgentBody body = AgentBodies.findLoaded(server, learner);
        if (body != null) {
            String name = AgentCommands.label(server, whom);
            body.journal().record(Category.BRAIN, "converse", "learned their name — " + name);
            body.beingSense().renamed(whom, name);
        }
        return true;
    }

    /**
     * {@code learner} has just told {@code whom} apart by sight. A name heard without a face finds
     * it here, and that is their meeting. Called by the being sense on every individual it names.
     */
    public static void sighted(MinecraftServer server, AgentId learner, AgentId whom) {
        ContactData contacts = ContactData.get(server);
        if (contacts.heardOf(learner, whom) && face(server, contacts, learner, whom)) {
            ContactData.met(server, learner);
            Journals.of(server).record(learner, Category.BRAIN, "converse",
                    "met " + AgentCommands.label(server, whom) + ", whose name they knew");
        }
    }

    private static void sweepPlayers(MinecraftServer server) {
        ContactData contacts = ContactData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            AgentId self = ContactsSync.idOf(player);
            for (AgentId whom : List.copyOf(contacts.heardBy(self))) {
                Entity other = entityOf(server, whom);
                if (other != null && playerSees(player, other)) {
                    sighted(server, self, whom);
                }
            }
        }
    }

    /** "Bram joins Ada, Cai and Doris." — in every member's journal and every member player's chat. */
    private static void narrate(MinecraftServer server, PartyId into, List<AgentId> newcomers, List<AgentId> there) {
        if (there.isEmpty()) {
            return;
        }
        Component line = Component.translatable(newcomers.size() == 1 ? "anima.party.joins" : "anima.party.join",
                names(server, newcomers), names(server, there));
        // Journal lines are the mod's English, as every other is; only the chat line is translated.
        String plain = plain(server, newcomers) + (newcomers.size() == 1 ? " joins " : " join ")
                + plain(server, there) + ".";
        for (AgentId member : PartyData.get(server).members(into)) {
            Journals.of(server).record(member, Category.BRAIN, "party", plain);
            ServerPlayer player = server.getPlayerList().getPlayer(member.value());
            if (player != null) {
                player.sendSystemMessage(line);
            }
        }
    }

    private static String plain(MinecraftServer server, List<AgentId> who) {
        List<String> labels = who.stream().map(id -> AgentCommands.label(server, id)).toList();
        return labels.size() == 1 ? labels.get(0)
                : String.join(", ", labels.subList(0, labels.size() - 1)) + " and " + labels.get(labels.size() - 1);
    }

    /** "Ada", "Ada and Cai", "Ada, Cai and Doris". */
    static MutableComponent names(MinecraftServer server, List<AgentId> who) {
        List<String> labels = who.stream().map(id -> AgentCommands.label(server, id)).toList();
        if (labels.size() == 1) {
            return Component.literal(labels.get(0));
        }
        String head = String.join(", ", labels.subList(0, labels.size() - 1));
        return Component.translatable("anima.list.and", head, labels.get(labels.size() - 1));
    }
}
