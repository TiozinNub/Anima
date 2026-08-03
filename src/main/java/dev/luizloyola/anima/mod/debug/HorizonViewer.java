package dev.luizloyola.anima.mod.debug;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.CrescentSampler;
import dev.luizloyola.anima.core.brain.knowledge.HorizonBuffer;
import dev.luizloyola.anima.core.brain.knowledge.HorizonScanner;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Sighting;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.brain.Knowledges;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/**
 * The far sense's monocle: an agent's whole skyline painted over the live world, so <em>what it is
 * making out, and where it stopped</em> can be answered by looking.
 *
 * <ul>
 *   <li>Every swept bearing paints the cell that <em>topped</em> it: if a tree is not painted, the
 *       thing in front of it is.</li>
 *   <li>The cone edges are drawn in gold along the ground and the skyline stops between them — the
 *       passive sense never looks behind.</li>
 *   <li>A bearing that ended at an unloaded chunk rather than at its range wears a magenta rim:
 *       "I could see no further" is not "there was nothing there".</li>
 * </ul>
 *
 * <p>Skyline colour is elevation angle from her eye (blue below, green level, red above), and the
 * gist tier rides along in magenta with its range labelled.
 *
 * <p>Reads live sensor state, so it draws only a LOADED agent, unlike {@code KnowledgeViewer}.
 */
public final class HorizonViewer {
    private HorizonViewer() {}

    private static final String SOURCE = "anima:horizon";

    /** Brisk: the skyline is relative to where she stands, and she moves. */
    private static final int RESCAN_INTERVAL_TICKS = 10;
    private static final int TTL_TICKS = RESCAN_INTERVAL_TICKS * 3;

    private static final float SKYLINE_WIDTH = 2.0F;
    private static final float EDGE_WIDTH = 1.5F;
    private static final int HUE_BUCKETS = 8;

    private static final int TRUNCATED_STROKE = 0xFFFF3FD4;
    private static final int GLIMPSE_STROKE = 0xFFFF3FD4;
    private static final int GLIMPSE_FILL = 0x40FF3FD4;
    private static final int EDGE_STROKE = 0xB4FFD700;
    private static final int LABEL_COLOR = 0xFFFFFFFF;

    /** Elevation tangents at the ends of the colour ramp — a steep bank, and open sky. */
    private static final double COLDEST_TAN = -0.35;
    private static final double HOTTEST_TAN = 0.80;

    /** Which agent's skyline each watching player is looking at. */
    private static final Map<MinecraftServer, Map<UUID, AgentId>> WATCHERS = new HashMap<>();

    /** Call once from common mod init. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(WATCHERS::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % RESCAN_INTERVAL_TICKS != 0) {
                return;
            }
            Map<UUID, AgentId> watches = WATCHERS.get(server);
            if (watches == null) {
                return;
            }
            Iterator<Map.Entry<UUID, AgentId>> each = watches.entrySet().iterator();
            while (each.hasNext()) {
                Map.Entry<UUID, AgentId> entry = each.next();
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) {
                    each.remove();
                } else {
                    render(server, player, entry.getValue());
                }
            }
        });
    }

    /** Starts drawing {@code agent}'s skyline for this player, replacing any previous watch. */
    public static void watch(MinecraftServer server, ServerPlayer player, AgentId agent) {
        WATCHERS.computeIfAbsent(server, s -> new HashMap<>()).put(player.getUUID(), agent);
        render(server, player, agent);
    }

    /** Stops drawing; false when this player was not watching anything. */
    public static boolean unwatch(MinecraftServer server, ServerPlayer player) {
        Map<UUID, AgentId> watches = WATCHERS.get(server);
        boolean was = watches != null && watches.remove(player.getUUID()) != null;
        if (was) {
            CellOverlays.clear(player, SOURCE);
        }
        return was;
    }

    /** One frame, read straight off the agent's live buffer — nothing is recomputed. */
    private static void render(MinecraftServer server, ServerPlayer player, AgentId id) {
        AgentBody agent = AgentBodies.findLoaded(server, id);
        if (agent == null || agent.poiSensor() == null) {
            CellOverlays.clear(player, SOURCE);
            return;
        }
        HorizonBuffer buffer = agent.poiSensor().horizon();
        if (buffer == null) {
            return; // not ticked yet; the next frame will have one
        }
        AgentProfile profile = agent.profile();
        List<CellOverlayPayload.Group> groups = new ArrayList<>();
        List<CellOverlayPayload.Label> labels = new ArrayList<>();

        int drawn = paintSkyline(buffer, groups);
        paintConeEdges(agent, profile, groups);
        int glimpses = paintGlimpses(server, id, groups, labels);
        summarise(agent, profile, buffer, drawn, glimpses, labels);

        CellOverlays.show(player,
                new CellOverlayPayload(SOURCE, TTL_TICKS, groups, List.of(), labels));
    }

    /**
     * One cell per swept bearing: what topped it, coloured by how high it stands from her eye.
     * Bucketed by hue so the frame carries a handful of groups rather than one per bearing.
     */
    private static int paintSkyline(HorizonBuffer buffer, List<CellOverlayPayload.Group> groups) {
        List<List<BlockPos>> buckets = new ArrayList<>();
        for (int i = 0; i < HUE_BUCKETS; i++) {
            buckets.add(new ArrayList<>());
        }
        List<BlockPos> truncated = new ArrayList<>();
        int drawn = 0;
        for (int bin = 0; bin < HorizonBuffer.BINS; bin++) {
            if (!buffer.wasSwept(bin) || !buffer.filled(bin)) {
                continue;
            }
            Pos top = buffer.top(bin);
            BlockPos cell = new BlockPos(top.x(), top.y(), top.z());
            drawn++;
            if (buffer.truncated(bin)) {
                truncated.add(cell);
            } else {
                buckets.get(bucketOf(buffer.tan(bin))).add(cell);
            }
        }
        for (int i = 0; i < HUE_BUCKETS; i++) {
            if (buckets.get(i).isEmpty()) {
                continue;
            }
            float hue = hueOf(i);
            groups.add(new CellOverlayPayload.Group(
                    Mth.hsvToArgb(hue, 0.85F, 1.0F, 0xC8), SKYLINE_WIDTH,
                    Mth.hsvToArgb(hue, 0.85F, 1.0F, 0x30), true, List.copyOf(buckets.get(i))));
        }
        if (!truncated.isEmpty()) {
            groups.add(new CellOverlayPayload.Group(
                    TRUNCATED_STROKE, SKYLINE_WIDTH, 0, true, truncated));
        }
        return drawn;
    }

    /**
     * The aperture, on the ground: the last bearing on each side that the sweep is allowed to
     * walk. Everything outside them is the sense's blind arc.
     */
    private static void paintConeEdges(AgentBody agent, AgentProfile profile,
            List<CellOverlayPayload.Group> groups) {
        double half = CrescentSampler.coneDegrees(profile) / 2.0;
        int near = CrescentSampler.radius(profile);
        int far = HorizonScanner.radius(profile);
        if (far <= near) {
            return;
        }
        double facing = agent.entity().getYHeadRot();
        BlockPos feet = agent.blockPosition();
        List<BlockPos> edge = new ArrayList<>();
        for (double side : new double[]{-half, half}) {
            double radians = Math.toRadians(facing + side);
            double dirX = -Math.sin(radians);
            double dirZ = Math.cos(radians);
            for (int d = near; d <= far; d += 4) {
                int x = feet.getX() + (int) Math.round(dirX * d);
                int z = feet.getZ() + (int) Math.round(dirZ * d);
                int y = agent.entity().level().getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                edge.add(new BlockPos(x, y, z));
            }
        }
        groups.add(new CellOverlayPayload.Group(EDGE_STROKE, EDGE_WIDTH, 0, true, edge));
    }

    /** The gist tier beside the skyline that produced it, each labelled with how far off it was. */
    private static int paintGlimpses(MinecraftServer server, AgentId id,
            List<CellOverlayPayload.Group> groups, List<CellOverlayPayload.Label> labels) {
        AgentKnowledge knowledge = Knowledges.of(server).forPerson(id);
        List<BlockPos> cells = new ArrayList<>();
        for (PoiKind kind : PoiKind.all()) {
            for (Sighting sighting : knowledge.glimpses(kind)) {
                Pos at = sighting.at();
                BlockPos cell = new BlockPos(at.x(), at.y(), at.z());
                cells.add(cell);
                labels.add(new CellOverlayPayload.Label(
                        "~" + kind.key().toUpperCase(Locale.ROOT) + " " + sighting.range(),
                        GLIMPSE_STROKE, cell.above()));
            }
        }
        if (!cells.isEmpty()) {
            groups.add(new CellOverlayPayload.Group(
                    GLIMPSE_STROKE, SKYLINE_WIDTH, GLIMPSE_FILL, true, cells));
        }
        return cells.size();
    }

    /** The one-line state of the sense, floating over her head. */
    private static void summarise(AgentBody agent, AgentProfile profile, HorizonBuffer buffer,
            int drawn, int glimpses, List<CellOverlayPayload.Label> labels) {
        int swept = 0;
        int cutShort = 0;
        for (int bin = 0; bin < HorizonBuffer.BINS; bin++) {
            if (buffer.wasSwept(bin)) {
                swept++;
                if (buffer.truncated(bin)) {
                    cutShort++;
                }
            }
        }
        String line = "skyline " + drawn + " cells / " + swept + " bearings, r"
                + HorizonScanner.radius(profile) + " cone "
                + CrescentSampler.coneDegrees(profile) + "°, " + glimpses + " glimpsed"
                + (cutShort > 0 ? ", " + cutShort + " ran out of world" : "");
        BlockPos feet = agent.blockPosition();
        labels.add(new CellOverlayPayload.Label(line, LABEL_COLOR, feet.above(3)));
    }

    private static int bucketOf(double tan) {
        double u = (tan - COLDEST_TAN) / (HOTTEST_TAN - COLDEST_TAN);
        int bucket = (int) Math.floor(Mth.clamp(u, 0.0, 0.9999) * HUE_BUCKETS);
        return Mth.clamp(bucket, 0, HUE_BUCKETS - 1);
    }

    /** Blue low, green about eye level, red high — the ramp runs 0.6 down to 0.0. */
    private static float hueOf(int bucket) {
        return 0.6F * (1.0F - bucket / (float) (HUE_BUCKETS - 1));
    }
}
