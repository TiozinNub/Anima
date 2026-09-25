package dev.luizloyola.anima.mod.debug;

import dev.luizloyola.anima.compat.terrain.GroundReader;
import dev.luizloyola.anima.core.terrain.GroundSample;
import dev.luizloyola.anima.core.terrain.Terrain;
import dev.luizloyola.anima.core.terrain.TerrainRules;
import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The ground around the WATCHING PLAYER as {@link Terrain} judges it, painted over the live world:
 * clearings, flat ground under trees, used ground, and the best building sites outlined. No agent
 * involved — the same reader and rules a site chooser would use, pointed at wherever you stand.
 *
 * <p>Each kind of ground is merged into rectangles of one height before it is sent: a cell apiece
 * would be 16,000 boxes at the default radius, each drawn every frame. The panes are fill only,
 * since their edges are where the merge happened to cut, not anything about the ground.
 */
public final class TerrainViewer {
    private TerrainViewer() {}

    private static final String SOURCE = "anima:terrain";

    private static final int RESCAN_INTERVAL_TICKS = 40;

    /** Outlives one missed rescan. */
    private static final int TTL_TICKS = RESCAN_INTERVAL_TICKS * 3;

    public static final int DEFAULT_RADIUS = 64;
    public static final int MIN_RADIUS = 16;
    public static final int MAX_RADIUS = 128;

    /** Boxes per frame. Kinds are added most telling first; what does not fit is dropped whole. */
    private static final int MAX_BOXES = 6000;

    private static final int CLEARING_FILL = 0x66FFE040;
    private static final int FLAT_FILL = 0x40B8A040;
    private static final int USED_FILL = 0x80FF3030;
    private static final int SITE_STROKE = 0xFFFF40FF;
    private static final float SITE_WIDTH = 2.5F;
    private static final int LABEL = 0xFFFFC0FF;

    private static final class Watch {
        int radius;

        Watch(int radius) {
            this.radius = radius;
        }
    }

    private static final Map<MinecraftServer, Map<UUID, Watch>> WATCHERS = new HashMap<>();

    /**
     * Each player's rules, kept while the view is off so turning it back on shows what they set.
     * Per player, not a config knob: the rules are a caller's opinion, and this view is one caller.
     */
    private static final Map<MinecraftServer, Map<UUID, TerrainRules>> RULES = new HashMap<>();

    /** Call once from mod init. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(WATCHERS::remove);
        ServerLifecycleEvents.SERVER_STOPPING.register(RULES::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % RESCAN_INTERVAL_TICKS != 0) {
                return;
            }
            Map<UUID, Watch> watches = WATCHERS.get(server);
            if (watches == null) {
                return;
            }
            Iterator<Map.Entry<UUID, Watch>> each = watches.entrySet().iterator();
            while (each.hasNext()) {
                Map.Entry<UUID, Watch> entry = each.next();
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) {
                    each.remove(); // logged off: the view dies with them, the TTL fades it
                } else {
                    render(server, player, entry.getValue());
                }
            }
        });
    }

    /**
     * Toggles the view for this player, or retunes its radius while it is on. {@code radius} 0
     * means none was given. Returns the radius now active, or 0 when the toggle switched it off.
     */
    public static int toggle(MinecraftServer server, ServerPlayer player, int radius) {
        Map<UUID, Watch> watches = WATCHERS.computeIfAbsent(server, s -> new HashMap<>());
        Watch existing = watches.get(player.getUUID());
        if (existing != null && radius <= 0) {
            watches.remove(player.getUUID());
            CellOverlays.clear(player, SOURCE);
            return 0;
        }
        Watch watch = existing != null ? existing : new Watch(DEFAULT_RADIUS);
        if (radius > 0) {
            watch.radius = radius;
        }
        watches.put(player.getUUID(), watch);
        render(server, player, watch); // the first frame lands with the reply, not a cadence later
        return watch.radius;
    }

    /** The rules this player's view judges by. */
    public static TerrainRules rules(MinecraftServer server, ServerPlayer player) {
        return RULES.getOrDefault(server, Map.of()).getOrDefault(player.getUUID(), TerrainRules.DEFAULTS);
    }

    /** Sets the rules this player's view judges by, and repaints at once if it is on. */
    public static void rules(MinecraftServer server, ServerPlayer player, TerrainRules rules) {
        RULES.computeIfAbsent(server, s -> new HashMap<>()).put(player.getUUID(), rules);
        Watch watch = WATCHERS.getOrDefault(server, Map.of()).get(player.getUUID());
        if (watch != null) {
            render(server, player, watch);
        }
    }

    private static void render(MinecraftServer server, ServerPlayer player, Watch watch) {
        TerrainRules rules = rules(server, player);
        BlockPos centre = player.blockPosition();
        int r = watch.radius;
        int minX = centre.getX() - r;
        int minZ = centre.getZ() - r;
        int maxX = centre.getX() + r;
        int maxZ = centre.getZ() + r;
        // Ground read past the painted edge, so the windows at the edge — smoothing, the area
        // square, a footprint — see ground on both sides rather than the edge of the read.
        int margin = Math.max(rules.footprint(), Math.max(2 * rules.smoothRadius(), rules.areaSize()));
        GroundSample sample = GroundReader.read(player.level(),
                minX - margin, minZ - margin, maxX + margin, maxZ + margin);
        Terrain terrain = Terrain.analyse(sample, rules);

        List<CellOverlayPayload.BoxGroup> boxes = new ArrayList<>();
        int budget = MAX_BOXES;
        budget = add(boxes, budget, USED_FILL,
                panes(terrain, Terrain.Kind.USED, minX, minZ, maxX, maxZ));
        budget = add(boxes, budget, CLEARING_FILL,
                panes(terrain, Terrain.Kind.CLEARING, minX, minZ, maxX, maxZ));
        add(boxes, budget, FLAT_FILL, panes(terrain, Terrain.Kind.FLAT_AREA, minX, minZ, maxX, maxZ));

        List<CellOverlayPayload.Box> outlines = new ArrayList<>();
        List<CellOverlayPayload.Label> labels = new ArrayList<>();
        int rank = 0;
        for (Terrain.Site site : terrain.sites()) {
            if (Math.abs(site.x() - centre.getX()) > r || Math.abs(site.z() - centre.getZ()) > r) {
                continue; // found in the margin: ground the view does not show
            }
            rank++;
            int y = (int) Math.round(site.y());
            int h = site.size() / 2;
            outlines.add(new CellOverlayPayload.Box(new BlockPos(site.x() - h, y, site.z() - h),
                    new BlockPos(site.x() + h, y, site.z() + h)));
            // Dev-only debug text, read by whoever toggled the view on.
            labels.add(new CellOverlayPayload.Label(String.format(
                    "#%d  tilt 1:%s  prep %d  trees %d", rank,
                    site.tilt() < 1e-6 ? "-" : String.valueOf(Math.round(1 / site.tilt())),
                    site.preparation(), site.trees()),
                    LABEL, new BlockPos(site.x(), y + 3, site.z())));
        }
        if (!outlines.isEmpty()) {
            boxes.add(new CellOverlayPayload.BoxGroup(SITE_STROKE, SITE_WIDTH, 0, true, outlines));
        }
        CellOverlays.show(player,
                new CellOverlayPayload(SOURCE, TTL_TICKS, List.of(), List.of(), boxes, labels));
    }

    /** Adds one kind's panes when they fit the budget, and returns what is left. */
    private static int add(List<CellOverlayPayload.BoxGroup> boxes, int budget, int fill,
                           List<CellOverlayPayload.Box> panes) {
        if (panes.isEmpty() || panes.size() > budget) {
            return budget;
        }
        boxes.add(new CellOverlayPayload.BoxGroup(0, 0F, fill, false, panes));
        return budget - panes.size();
    }

    private record Run(int start, int end, int y) {
    }

    /**
     * One kind of ground as rectangles of one height: runs along x, carried down z while the next
     * row has the same run. The box sits on the ground block itself, so its top is the surface.
     */
    static List<CellOverlayPayload.Box> panes(Terrain terrain, Terrain.Kind kind,
                                              int minX, int minZ, int maxX, int maxZ) {
        List<CellOverlayPayload.Box> out = new ArrayList<>();
        Map<Run, Integer> open = new HashMap<>();
        for (int z = minZ; z <= maxZ; z++) {
            Map<Run, Integer> next = new HashMap<>();
            int x = minX;
            while (x <= maxX) {
                if (terrain.kind(x, z) != kind) {
                    x++;
                    continue;
                }
                int y = terrain.ground(x, z);
                int start = x;
                while (x + 1 <= maxX && terrain.kind(x + 1, z) == kind
                        && terrain.ground(x + 1, z) == y) {
                    x++;
                }
                Run run = new Run(start, x, y);
                Integer since = open.remove(run);
                next.put(run, since != null ? since : z);
                x++;
            }
            close(open, z - 1, out);
            open = next;
        }
        close(open, maxZ, out);
        return out;
    }

    private static void close(Map<Run, Integer> open, int lastZ, List<CellOverlayPayload.Box> out) {
        for (Map.Entry<Run, Integer> rect : open.entrySet()) {
            Run run = rect.getKey();
            out.add(new CellOverlayPayload.Box(new BlockPos(run.start(), run.y(), rect.getValue()),
                    new BlockPos(run.end(), run.y(), lastZ)));
        }
    }
}
