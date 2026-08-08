package dev.luizloyola.anima.mod.debug;

import dev.luizloyola.anima.compat.sense.LevelProbe;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.need.Gauge;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.HorizonBuffer;
import dev.luizloyola.anima.core.brain.knowledge.HorizonScanner;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.knowledge.Sighting;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.Path;
import dev.luizloyola.anima.core.nav.Waypoint;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.mod.brain.Knowledges;
import dev.luizloyola.anima.mod.command.AgentSelection;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.nav.Navigator;
import dev.luizloyola.anima.mod.net.DebugViewPayload;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The in-world debug view, server half: who is watching what, and the snapshot that feeds it.
 *
 * <p>Drawing happens on the client, so the facts travel: one {@link DebugViewPayload} per watching
 * player every {@link #SEND_INTERVAL_TICKS}, carrying only what the client cannot see for itself.
 *
 * <p><b>The view follows the selection.</b> Layers are switched per PLAYER; the body drawn is that
 * player's pin in {@code AgentSelection}, the slot the debug wand and {@code /anima select} share.
 *
 * <p>Layers all off, or a pin that is empty or unloaded, gets exactly one
 * {@link DebugViewPayload#clear()} and then silence — without that edge the client would redraw
 * its last snapshot forever. Transient state, one map per server, gone on stop: the
 * {@code Journals} lifecycle every viewer here follows.
 */
public final class DebugView {
    private DebugView() {}

    /**
     * Snapshot cadence, slower than a frame: the client interpolates the body's
     * position from the local entity, so only the slow-changing part travels. Four ticks reads as
     * instant and costs nothing in traffic.
     */
    private static final int SEND_INTERVAL_TICKS = 4;

    /**
     * How long a belief may go unconfirmed before the view draws it as a GHOST — five minutes of
     * game time. A rendering threshold only: the knowledge store has no such cliff, and the brain
     * prices staleness on a continuous curve.
     */
    private static final long STALE_AFTER_TICKS = 6000L;

    /** Watching player → the layers they have switched on. An empty set is removed, not kept. */
    private static final Map<MinecraftServer, Map<UUID, EnumSet<DebugLayer>>> WATCHERS =
            new HashMap<>();

    /** Players owed one final clear because their view just went dark. */
    private static final Map<MinecraftServer, Set<UUID>> PENDING_CLEAR = new HashMap<>();

    /** Call once from common mod init — the payload type must be registered on both sides. */
    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(DebugViewPayload.TYPE, DebugViewPayload.CODEC);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            WATCHERS.remove(server);
            PENDING_CLEAR.remove(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % SEND_INTERVAL_TICKS == 0) {
                broadcast(server);
            }
        });
    }

    /** The layers this player currently has on — never null, possibly empty. */
    public static EnumSet<DebugLayer> layers(MinecraftServer server, UUID player) {
        Map<UUID, EnumSet<DebugLayer>> watched = WATCHERS.get(server);
        EnumSet<DebugLayer> on = watched == null ? null : watched.get(player);
        return on == null ? EnumSet.noneOf(DebugLayer.class) : EnumSet.copyOf(on);
    }

    /** Switches one layer on or off for this player; returns the resulting layer set. */
    public static EnumSet<DebugLayer> set(
            MinecraftServer server, UUID player, DebugLayer layer, boolean on) {
        EnumSet<DebugLayer> next = layers(server, player);
        if (on) {
            next.add(layer);
        } else {
            next.remove(layer);
        }
        return replace(server, player, next);
    }

    /**
     * Replaces this player's whole layer set — the debug wand's one-at-a-time cycle, which must
     * REPLACE rather than accumulate (an empty set is the wand's "off" rung).
     */
    public static EnumSet<DebugLayer> replace(
            MinecraftServer server, UUID player, Set<DebugLayer> layers) {
        Map<UUID, EnumSet<DebugLayer>> watched =
                WATCHERS.computeIfAbsent(server, s -> new HashMap<>());
        if (layers.isEmpty()) {
            // Going dark owes the client one clear; leaving the entry in the map would keep
            // sending empty snapshots forever instead.
            if (watched.remove(player) != null) {
                PENDING_CLEAR.computeIfAbsent(server, s -> new HashSet<>()).add(player);
            }
            return EnumSet.noneOf(DebugLayer.class);
        }
        EnumSet<DebugLayer> copy = EnumSet.copyOf(layers);
        watched.put(player, copy);
        return EnumSet.copyOf(copy);
    }

    /** Switches everything off for this player; true when anything was on. */
    public static boolean clear(MinecraftServer server, UUID player) {
        boolean had = !layers(server, player).isEmpty();
        replace(server, player, EnumSet.noneOf(DebugLayer.class));
        return had;
    }

    /** One cadence tick: a fresh snapshot to every watcher, one last clear to anyone who stopped. */
    private static void broadcast(MinecraftServer server) {
        Set<UUID> clearing = PENDING_CLEAR.remove(server);
        if (clearing != null) {
            for (UUID id : clearing) {
                send(server.getPlayerList().getPlayer(id), DebugViewPayload.clear());
            }
        }
        Map<UUID, EnumSet<DebugLayer>> watched = WATCHERS.get(server);
        if (watched == null || watched.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, EnumSet<DebugLayer>> entry : watched.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                send(player, snapshot(server, player, entry.getValue()));
            }
        }
    }

    /** Skips clients that never registered the channel (vanilla clients, the headless harness). */
    private static void send(@Nullable ServerPlayer player, DebugViewPayload payload) {
        if (player != null && ServerPlayNetworking.canSend(player, DebugViewPayload.TYPE)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /**
     * This player's frame: their pinned AgentBody read through the requested layers. An unresolvable
     * pin yields the clear snapshot rather than an error — the selection going away should make the
     * drawing stop, not spam a player who is mid-experiment.
     */
    private static DebugViewPayload snapshot(
            MinecraftServer server, ServerPlayer player, EnumSet<DebugLayer> layers) {
        AgentId id = AgentSelection.pinned(player).orElse(null);
        AgentBody person = id == null ? null : AgentBodies.findLoaded(server, id);
        if (person == null) {
            return DebugViewPayload.clear();
        }
        Navigator navigator = person.navigator();
        boolean wantsPath = layers.contains(DebugLayer.PATH);
        Path path = wantsPath ? navigator.path() : null;

        List<DebugViewPayload.Step> steps = new ArrayList<>();
        if (path != null) {
            for (Waypoint waypoint : path.waypoints()) {
                steps.add(new DebugViewPayload.Step(
                        new BlockPos(waypoint.x(), waypoint.y(), waypoint.z()),
                        waypoint.move().ordinal()));
            }
        }
        Optional<BlockPos> goal = wantsPath
                ? Optional.ofNullable(navigator.goal())
                : Optional.empty();

        return new DebugViewPayload(
                person.entity().getId(),
                DebugLayer.mask(layers),
                steps,
                wantsPath ? navigator.pathIndex() : 0,
                goal,
                wantsPath ? navigator.describe() : "",
                layers.contains(DebugLayer.BRAIN) ? person.brain().describeLines() : List.of(),
                layers.contains(DebugLayer.MEMORY) ? beliefs(server, person) : List.of(),
                layers.contains(DebugLayer.PEERS) ? peers(person) : List.of(),
                sight(server, person, layers.contains(DebugLayer.HORIZON)),
                layers.contains(DebugLayer.NEEDS) ? needs(person) : List.of());
    }

    /**
     * What the body wants: one mark per gauge on its roster, in the order the body declared them.
     *
     * <p><b>Written against the roster, not a list of needs.</b> Nothing here names hunger or
     * company — it walks {@code needs().all()} and ships what each gauge says about itself, so a
     * gauge a consumer registers is drawn the day it exists.
     *
     * <p>The pressure travels as the raw number — see {@link DebugViewPayload.NeedMark}.
     */
    private static List<DebugViewPayload.NeedMark> needs(AgentBody person) {
        List<DebugViewPayload.NeedMark> out = new ArrayList<>();
        for (Gauge gauge : person.needs().all()) {
            out.add(new DebugViewPayload.NeedMark(
                    gauge.kind().key(), gauge.describe(), (float) gauge.pressure()));
        }
        return out;
    }

    /**
     * Their eyes: the dimensions both sense tiers are drawn from, plus the far sweep's readout when
     * that layer is up.
     *
     * <p>The sizes travel unconditionally (the peers layer draws the near cone from them), and
     * come from the body's own profile, not Anima's config.
     *
     * <p>The skyline is read straight off the live sensor, so this only answers for a LOADED agent;
     * re-walking the sweep would be a different sweep from the one being debugged.
     */
    private static DebugViewPayload.Sight sight(
            MinecraftServer server, AgentBody person, boolean wantsHorizon) {
        AgentProfile profile = person.profile();
        int cone = profile.i(ProfileAspect.SENSES_CONE_DEGREES);
        int near = profile.i(ProfileAspect.SENSES_RADIUS);
        int far = HorizonScanner.radius(profile);
        HorizonBuffer buffer = person.poiSensor() == null ? null : person.poiSensor().horizon();
        if (!wantsHorizon || buffer == null) {
            return new DebugViewPayload.Sight(cone, near, far, List.of(), List.of());
        }
        List<DebugViewPayload.Bearing> skyline = new ArrayList<>();
        for (int bin = 0; bin < HorizonBuffer.BINS; bin++) {
            // Swept but empty: the bearing was walked and nothing rose above the running maximum —
            // open sky. The hole it leaves in the ribbon is the correct picture.
            if (!buffer.wasSwept(bin) || !buffer.filled(bin)) {
                continue;
            }
            Pos top = buffer.top(bin);
            skyline.add(new DebugViewPayload.Bearing(
                    bin, new BlockPos(top.x(), top.y(), top.z()), buffer.truncated(bin)));
        }
        return new DebugViewPayload.Sight(cone, near, far, skyline, glimpses(server, person, profile));
    }

    /**
     * The gist tier: what they have made out at range and never been near enough to examine.
     *
     * <p>Each is asked whether it can still be SEEN from where the body stands now: a glimpse
     * outlives the look that produced it, and a line from the eye claims sight.
     *
     * <p>Through {@link HorizonScanner#viewCarriesTo}, the FAR sense's question with its
     * see-through reach. The near field's {@code visibleFromEyes} sees through any depth of canopy
     * at any distance, so using it here drew sight lines clean through a wood the sweep could not
     * see into.
     */
    private static List<DebugViewPayload.Glimpse> glimpses(
            MinecraftServer server, AgentBody person, AgentProfile profile) {
        AgentId id = person.agentId();
        if (id == null) {
            return List.of();
        }
        AgentKnowledge knowledge = Knowledges.of(server).forPerson(id);
        BlockProbe eyes = new LevelProbe(person.entity());
        int seeThrough = HorizonScanner.seeThroughRadius(profile);
        Vec3 eye = person.entity().getEyePosition();
        List<DebugViewPayload.Glimpse> out = new ArrayList<>();
        for (PoiKind kind : PoiKind.all()) {
            for (Sighting sighting : knowledge.glimpses(kind)) {
                Pos at = sighting.at();
                // The leading tilde is the tier: this is a rumour about a place, not a belief
                // about a thing, and it must not read like the labels the memory layer draws.
                out.add(new DebugViewPayload.Glimpse(
                        "~" + kind.key() + " " + sighting.range() + "m", cell(at),
                        HorizonScanner.viewCarriesTo(eyes, eye.x, eye.y, eye.z, at, seeThrough)));
            }
        }
        return out;
    }

    /** Everything they remember, of every kind, flattened with staleness resolved server-side. */
    private static List<DebugViewPayload.Belief> beliefs(MinecraftServer server, AgentBody person) {
        AgentId id = person.agentId();
        if (id == null) {
            return List.of();
        }
        long now = person.level().getGameTime();
        AgentKnowledge knowledge = Knowledges.of(server).forPerson(id);
        List<DebugViewPayload.Belief> out = new ArrayList<>();
        for (PoiKind kind : PoiKind.all()) {
            for (PoiMemory memory : knowledge.all(kind)) {
                Region bounds = memory.bounds();
                out.add(new DebugViewPayload.Belief(
                        memory.kind().key(),
                        PoiLabels.of(memory, now),
                        cell(memory.anchor()),
                        cell(bounds.min()),
                        cell(bounds.max()),
                        memory.age(now) > STALE_AFTER_TICKS));
            }
        }
        return out;
    }

    /**
     * The live being readings — through the BRAIN's own eyes ({@code percepts()}), not a fresh
     * sensor: the cache carries the movement history and the linger window, and a throwaway scan
     * would report everyone as freshly seen and standing still.
     */
    private static List<DebugViewPayload.PeerMark> peers(AgentBody person) {
        // The observer's pronoun, not the peer's: tell() ends with "watching him/her" about
        // the watcher — the same argument the chat readouts pass.
        String pronoun = person.pronouns().object();
        List<DebugViewPayload.PeerMark> out = new ArrayList<>();
        for (Being being : person.brain().percepts().beings()) {
            out.add(new DebugViewPayload.PeerMark(
                    being.knownAs(), being.tell(pronoun), cell(being.pos()), bodyId(person, being),
                    being.awareness().ordinal(), (float) being.distance()));
        }
        return out;
    }

    /**
     * The entity to interpolate a peer's mark from, or {@link DebugViewPayload.PeerMark#NO_BODY}.
     *
     * <p>SEEN only, gated here rather than on the client so no client can follow a body it has lost
     * track of. For HEARD (the position of a noise) and REMEMBERED (a frozen last sighting) the
     * believed cell is the fact.
     */
    private static int bodyId(AgentBody person, Being being) {
        if (being.awareness() != Being.Awareness.SEEN) {
            return DebugViewPayload.PeerMark.NO_BODY;
        }
        LivingEntity body = person.beingSense().bodyOf(being.id());
        return body == null ? DebugViewPayload.PeerMark.NO_BODY : body.getId();
    }

    private static BlockPos cell(Pos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }
}
