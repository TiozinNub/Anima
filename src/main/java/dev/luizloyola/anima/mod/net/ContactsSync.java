package dev.luizloyola.anima.mod.net;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.mod.identity.Graves;
import dev.luizloyola.anima.mod.social.ContactData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side wiring for the contact book: registers {@link ContactsPayload} and keeps each
 * player's client shadow current. The whole book goes out on login and after a respawn (a new
 * {@code ServerPlayer}, so the client state is fresh); every later learn pushes its one entry, so
 * a nameplate appears at the introduction instead of at the next reconnect.
 *
 * <p>Only names the {@link AgentDirectory} can resolve are sent; a player knowing another PLAYER
 * needs no wire, since vanilla has always shown player names.
 *
 * <p><b>Creative and spectator players know everyone</b> (decision: Luiz): both are outside the
 * fiction the book protects, so {@link #seesEveryone} players get the whole directory. A view,
 * not a learning — nothing is written to {@link ContactData}, so dropping back into survival
 * drops them back to the names they earned.
 *
 * <p>Call {@link #install()} from common mod init (the payload type must be registered on both
 * sides).
 */
public final class ContactsSync {
    private ContactsSync() {}

    /**
     * How often a player's vantage point is re-examined. There is no game-mode-change event in the
     * public Fabric API — and a mixin for a cosmetic view would be a version-specific surface for
     * no gain — so this polls instead, which costs one game-mode read per player per second and
     * catches the other two moments for free: an agent spawned or renamed while someone is
     * watching from outside gets their nameplate at the same cadence.
     */
    private static final int WATCH_INTERVAL_TICKS = 20;

    /**
     * Per server, what was last pushed to each player who is watching from outside. Presence is
     * the "they were in creative/spectator last we looked" flag and the value is the exact list
     * they hold, so a poll re-sends only on a genuine difference — a swap back to survival, a new
     * agent, a rename. Players living in the fiction are absent: their client shadow is their own
     * book, which the learn/forget paths already keep current.
     */
    private static final Map<MinecraftServer, Map<UUID, List<ContactsPayload.Known>>> OUTSIDE =
            new HashMap<>();

    public static void install() {
        PayloadTypeRegistry.clientboundPlay().register(ContactsPayload.TYPE, ContactsPayload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> resync(handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> resync(newPlayer));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Map<UUID, List<ContactsPayload.Known>> sent = OUTSIDE.get(server);
            if (sent != null) {
                sent.remove(handler.player.getUUID());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(OUTSIDE::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.overworld().getGameTime() % WATCH_INTERVAL_TICKS == 0) {
                watch(server);
            }
        });
    }

    /**
     * Whether this player is looking at the world rather than living in it — creative or
     * spectator. Public because the {@code contacts} listing has to agree with the nameplates:
     * one rule, read from both places, or a creative player reads names over heads that their own
     * contact list denies knowing.
     */
    public static boolean seesEveryone(ServerPlayer player) {
        return player.isCreative() || player.isSpectator();
    }

    /** Pushes this player's whole view, replacing whatever their client held. */
    public static void resync(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)
                || !ServerPlayNetworking.canSend(player, ContactsPayload.TYPE)) {
            return;
        }
        MinecraftServer server = level.getServer();
        push(server, player, seesEveryone(player) ? allNames(server) : bookOf(server, player));
    }

    /**
     * Pushes one just-learned name, if the knower is an online player and the id names an agent.
     * Safe to call for any knower — an agent learning a name has no client to tell.
     */
    public static void learned(MinecraftServer server, AgentId knower, AgentId whom) {
        ServerPlayer player = server.getPlayerList().getPlayer(knower.value());
        if (player == null || !ServerPlayNetworking.canSend(player, ContactsPayload.TYPE)) {
            return;
        }
        AgentDirectory.of(server).nameOf(whom).ifPresent(name ->
                ServerPlayNetworking.send(player, ContactsPayload.learned(whom, name)));
    }

    /**
     * The once-a-second sweep behind {@link #seesEveryone}: everyone outside the fiction is held
     * at the whole directory, and anyone who has stepped back into it is returned to their own
     * book. The directory list is built at most once per sweep and shared — every omniscient
     * player is being told the same thing.
     */
    private static void watch(MinecraftServer server) {
        Map<UUID, List<ContactsPayload.Known>> sent = OUTSIDE.get(server);
        List<ContactsPayload.Known> everyone = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            List<ContactsPayload.Known> was = sent == null ? null : sent.get(player.getUUID());
            if (!seesEveryone(player)) {
                if (was != null) {
                    resync(player); // back inside: the names they earned, and only those
                }
                continue;
            }
            if (everyone == null) {
                everyone = allNames(server);
            }
            if (!everyone.equals(was) && ServerPlayNetworking.canSend(player, ContactsPayload.TYPE)) {
                push(server, player, everyone);
            }
        }
    }

    /** Sends a whole-book payload and records what an outside watcher is now holding. */
    private static void push(
            MinecraftServer server, ServerPlayer player, List<ContactsPayload.Known> known) {
        ServerPlayNetworking.send(player, ContactsPayload.whole(known));
        Map<UUID, List<ContactsPayload.Known>> sent =
                OUTSIDE.computeIfAbsent(server, key -> new HashMap<>());
        if (seesEveryone(player)) {
            sent.put(player.getUUID(), known);
        } else {
            sent.remove(player.getUUID());
        }
    }

    /**
     * Every LIVING agent every directory knows, loaded or not — the creative/spectator view.
     *
     * <p>Living, not known: identity outlives the body by decision, so the raw map keeps every
     * settler that has ever existed, and pushing that would grow the payload without bound with
     * names nobody can meet.
     */
    private static List<ContactsPayload.Known> allNames(MinecraftServer server) {
        List<ContactsPayload.Known> known = new ArrayList<>();
        AgentDirectory.of(server).living(server).forEach((id, identity) ->
                known.add(ContactsPayload.Known.of(id, identity.name())));
        return known;
    }

    /** The names this player has actually earned, minus any the directory can no longer resolve. */
    private static List<ContactsPayload.Known> bookOf(MinecraftServer server, ServerPlayer player) {
        AgentDirectory directory = AgentDirectory.of(server);
        List<ContactsPayload.Known> known = new ArrayList<>();
        // The dead are filtered rather than forgotten: the book still names them (deleting the
        // entry would edit the living's memory of the dead), but a client-side "who do I know"
        // list is about people to deal with.
        for (AgentId contact : Graves.get(server).living(ContactData.get(server).contactsOf(idOf(player)))) {
            directory.nameOf(contact)
                    .ifPresent(name -> known.add(ContactsPayload.Known.of(contact, name)));
        }
        return known;
    }

    /** A player's identity handle: their account UUID, exactly as the sense mints it. */
    public static AgentId idOf(ServerPlayer player) {
        return AgentId.of(player.getUUID());
    }
}
