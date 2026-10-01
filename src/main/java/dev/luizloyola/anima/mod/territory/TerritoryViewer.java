package dev.luizloyola.anima.mod.territory;

import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.core.territory.Claimed;
import dev.luizloyola.anima.core.territory.Territory;
import dev.luizloyola.anima.mod.debug.CellOverlays;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Every party's ground around the watching player: each chunk a faint pane in the party's colour,
 * the area's edge drawn through the world, and a chunk claimed in the last 30 seconds filled
 * brighter and labelled with why — so a growth can be watched as it happens.
 */
public final class TerritoryViewer {

    private TerritoryViewer() {
    }

    public static final String SOURCE = "anima:territory";
    public static final int RANGE_BLOCKS = 256;

    private static final int REFRESH_TICKS = 20;
    private static final int TTL_TICKS = REFRESH_TICKS * 3;
    private static final long RECENT_TICKS = 600;
    private static final int MAX_BOXES = 4000;
    private static final float EDGE_WIDTH = 3.0F;

    private static final Map<MinecraftServer, Set<UUID>> WATCHERS = new HashMap<>();

    static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(WATCHERS::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % REFRESH_TICKS != 0) {
                return;
            }
            Set<UUID> watching = WATCHERS.get(server);
            if (watching == null) {
                return;
            }
            Iterator<UUID> each = watching.iterator();
            while (each.hasNext()) {
                ServerPlayer player = server.getPlayerList().getPlayer(each.next());
                if (player == null) {
                    each.remove();
                } else {
                    render(player);
                }
            }
        });
    }

    public static boolean watching(MinecraftServer server, ServerPlayer player) {
        return WATCHERS.getOrDefault(server, Set.of()).contains(player.getUUID());
    }

    public static void watch(MinecraftServer server, ServerPlayer player, boolean on) {
        Set<UUID> watching = WATCHERS.computeIfAbsent(server, key -> new HashSet<>());
        if (on) {
            watching.add(player.getUUID());
            render(player);
        } else if (watching.remove(player.getUUID())) {
            CellOverlays.clear(player, SOURCE);
        }
    }

    private static void render(ServerPlayer player) {
        ServerLevel level = player.level();
        MinecraftServer server = level.getServer();
        Territory territory = Territories.of(server);
        String dimension = level.dimension().identifier().toString();
        ChunkKey here = ChunkKey.at(dimension, player.getBlockX(), player.getBlockZ());
        int reach = RANGE_BLOCKS >> 4;
        long now = Territories.now(server);

        List<CellOverlayPayload.BoxGroup> boxes = new ArrayList<>();
        List<CellOverlayPayload.Label> labels = new ArrayList<>();
        int budget = MAX_BOXES;
        for (PartyId party : territory.parties()) {
            SortedSet<ChunkKey> area = territory.area(party);
            Map<ChunkKey, String> recent = recent(territory, party, now);
            int colour = Territory.colour(party);
            List<CellOverlayPayload.Box> panes = new ArrayList<>();
            List<CellOverlayPayload.Box> fresh = new ArrayList<>();
            List<CellOverlayPayload.Box> edges = new ArrayList<>();
            long sumX = 0;
            long sumZ = 0;
            int near = 0;
            for (ChunkKey chunk : area) {
                if (!chunk.dimension().equals(dimension) || Math.abs(chunk.x() - here.x()) > reach
                        || Math.abs(chunk.z() - here.z()) > reach || budget <= 0) {
                    continue;
                }
                int y = groundAt(level, chunk, player.getBlockY());
                BlockPos min = new BlockPos(chunk.minBlockX(), y, chunk.minBlockZ());
                BlockPos max = new BlockPos(chunk.maxBlockX(), y, chunk.maxBlockZ());
                (recent.containsKey(chunk) ? fresh : panes).add(new CellOverlayPayload.Box(min, max));
                budget--;
                budget -= edges(area, chunk, y, edges);
                String why = recent.get(chunk);
                if (why != null) {
                    labels.add(new CellOverlayPayload.Label(why, 0xFF000000 | colour,
                            new BlockPos(chunk.minBlockX() + 8, y + 2, chunk.minBlockZ() + 8)));
                }
                sumX += chunk.x();
                sumZ += chunk.z();
                near++;
            }
            if (near == 0) {
                continue;
            }
            boxes.add(new CellOverlayPayload.BoxGroup(0, 0F, 0x30000000 | colour, false, panes));
            boxes.add(new CellOverlayPayload.BoxGroup(0, 0F, 0x80000000 | colour, false, fresh));
            boxes.add(new CellOverlayPayload.BoxGroup(0xFF000000 | colour, EDGE_WIDTH, 0, true, edges));
            ChunkKey middle = nearest(area, dimension, Math.round((float) sumX / near), Math.round((float) sumZ / near));
            labels.add(new CellOverlayPayload.Label(territory.name(party) + " — " + area.size()
                    + " chunks", 0xFF000000 | colour, new BlockPos(middle.minBlockX() + 8,
                    groundAt(level, middle, player.getBlockY()) + 4, middle.minBlockZ() + 8)));
        }
        CellOverlays.show(player, new CellOverlayPayload(SOURCE, TTL_TICKS, List.of(), List.of(),
                boxes, labels));
    }

    /** A strip along every side of the chunk that does not face more of the area. */
    private static int edges(Set<ChunkKey> area, ChunkKey chunk, int y, List<CellOverlayPayload.Box> out) {
        int before = out.size();
        int x0 = chunk.minBlockX();
        int x1 = chunk.maxBlockX();
        int z0 = chunk.minBlockZ();
        int z1 = chunk.maxBlockZ();
        if (!area.contains(chunk.offset(1, 0))) {
            out.add(new CellOverlayPayload.Box(new BlockPos(x1, y, z0), new BlockPos(x1, y, z1)));
        }
        if (!area.contains(chunk.offset(-1, 0))) {
            out.add(new CellOverlayPayload.Box(new BlockPos(x0, y, z0), new BlockPos(x0, y, z1)));
        }
        if (!area.contains(chunk.offset(0, 1))) {
            out.add(new CellOverlayPayload.Box(new BlockPos(x0, y, z1), new BlockPos(x1, y, z1)));
        }
        if (!area.contains(chunk.offset(0, -1))) {
            out.add(new CellOverlayPayload.Box(new BlockPos(x0, y, z0), new BlockPos(x1, y, z0)));
        }
        return out.size() - before;
    }

    /** Chunks a granted event added in the last 30 seconds, each with why. */
    private static Map<ChunkKey, String> recent(Territory territory, PartyId party, long now) {
        Map<ChunkKey, String> recent = new HashMap<>();
        for (Claimed event : territory.history(party)) {
            if (event.granted() && now - event.tick() <= RECENT_TICKS) {
                for (ChunkKey chunk : event.added()) {
                    recent.put(chunk, event.why().toString());
                }
            }
        }
        return recent;
    }

    private static ChunkKey nearest(SortedSet<ChunkKey> area, String dimension, int x, int z) {
        ChunkKey best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ChunkKey chunk : area) {
            if (!chunk.dimension().equals(dimension)) {
                continue;
            }
            long distance = (long) (chunk.x() - x) * (chunk.x() - x) + (long) (chunk.z() - z) * (chunk.z() - z);
            if (distance < bestDistance) {
                best = chunk;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The ground at the chunk's middle; the player's height where the chunk is not loaded, since
     *  asking an unloaded chunk for its height would load it. */
    private static int groundAt(ServerLevel level, ChunkKey chunk, int fallback) {
        if (!level.hasChunk(chunk.x(), chunk.z())) {
            return fallback;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, chunk.minBlockX() + 8,
                chunk.minBlockZ() + 8) - 1;
    }
}
