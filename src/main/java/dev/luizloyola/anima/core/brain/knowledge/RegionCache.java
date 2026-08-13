package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The level's memory of what the ground is SHAPED like — a fact about the world, not a belief
 * about it, so it is worked out once and lent to everybody. A flood fill is the most expensive
 * thing a mind does (twenty-six reads per frontier cell, thousands of cells in a welded conifer
 * stand), and fifty bodies in one wood used to run fifty identical ones.
 *
 * <p>It shares the READING, never the believing: the finder still notes its own
 * {@link PoiMemory}, stakes its own claims and writes its own journal line.
 *
 * <p>Two ways in. {@link #get}: the same seed and reach, so the finished scan comes back whole
 * with its anchors already right. {@link #covering}: a different cell of a mass somebody has
 * already walked — the expensive half (which cells connect) is reusable, the cheap half (which
 * are trees, which end you would walk to) is re-judged by the caller.
 *
 * <p><b>Only complete masses may be re-seeded</b>, because growth stops at a Chebyshev spread cap
 * measured from the seed: a partial mass is a fact about where somebody stood, and is served only
 * to that seed. A complete one goes to any seed that could have reached all of it — a comparison
 * against the box's far corner, on the axes growth measured, which for a
 * {@linkplain GrowthRule#standsTall stands tall} rule is the two horizontal ones.
 *
 * <p><b>Any block change in the footprint invalidates an entry, at any height</b> — hence
 * {@link #invalidate(int, int)} taking no Y: a rule may decide membership from a column's surface
 * ({@code WaterRule} joins water only at the top of its column). The footprint carries a one-cell
 * margin because growth is 26-way.
 *
 * <p><b>Bounded in cells, not entries</b> — a pumpkin is one cell and a fused spruce stand
 * thousands — and evicted least-recently-used.
 */
public final class RegionCache {

    /**
     * The one-cell skirt around a mass's footprint. Growth joins diagonally, so a cell touching
     * the mass at a corner is a cell that could join it tomorrow.
     */
    private static final int MARGIN = 1;

    /** How many cells of remembered shape one level may hold; 0 disables the cache. */
    public static int maxCells() {
        return Config.get().i(Knob.REGION_CACHE_CELLS);
    }

    /** What identifies a finished scan — see the class doc on why the seed and the reach are in it. */
    public record Key(PoiKind kind, Pos seed, int spread) {
    }

    /**
     * A class and not a record: these live in identity sets in the chunk index, and
     * a record's equals would deep-compare a four-thousand-entry block map to answer it.
     */
    private static final class Entry {
        final Key key;
        final GrownRegion region;
        final int minX;
        final int minY;
        final int minZ;
        final int maxX;
        final int maxY;
        final int maxZ;

        Entry(Key key, GrownRegion region,
                int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.key = key;
            this.region = region;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        /** Whether a block changing at this column could change what belongs to this mass. */
        boolean touchedBy(int x, int z) {
            return x >= minX - MARGIN && x <= maxX + MARGIN
                    && z >= minZ - MARGIN && z <= maxZ + MARGIN;
        }

        /**
         * Whether a body seeding here with this reach would have collected this mass —
         * the furthest cell of a box is a corner, so the widest span per axis is all this asks.
         * {@code standsTall} is the asking rule's: a height-measuring test would refuse exactly
         * the tall shapes the exemption exists for.
         */
        boolean reachableFrom(Pos seed, int spread, boolean standsTall) {
            int dx = Math.max(seed.x() - minX, maxX - seed.x());
            int dy = Math.max(seed.y() - minY, maxY - seed.y());
            int dz = Math.max(seed.z() - minZ, maxZ - seed.z());
            return RegionGrowth.withinReach(dx, dy, dz, spread, standsTall);
        }

        int cells() {
            return region.blocks().size();
        }
    }

    /** Access-ordered, so the iteration order is the eviction order. */
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    /** Chunk column → the entries whose footprint touches it. The whole spatial index. */
    private final Map<Long, Set<Entry>> byChunk = new HashMap<>();
    private int cells;
    private long hits;
    private long misses;
    private long drops;
    private long evictions;
    /** Why {@link #covering} declined — see {@link #attributeRefusal}. Cumulative, like the rest. */
    private long refusedPartial;
    private long refusedOutOfReach;
    private long unknownGround;

    /** What was grown from this seed last time, or null. */
    public synchronized GrownRegion get(Key key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            misses++;
            return null;
        }
        hits++;
        return entry.region;
    }

    /**
     * The cells only of a complete mass of this kind containing the seed and wholly within its
     * reach; the caller re-judges anchors from its own seed, an anchor being "the side you came
     * at it from". Asked speculatively on the miss path: no eviction-order move, and no hit until
     * {@link #tookCovering()}.
     */
    public synchronized Map<Pos, BlockKind> covering(PoiKind kind, Pos seed, int spread,
            boolean standsTall) {
        Set<Entry> candidates = byChunk.get(chunkKey(seed.x() >> 4, seed.z() >> 4));
        if (candidates == null) {
            unknownGround++;
            return null;
        }
        for (Entry entry : candidates) {
            if (!entry.region.partial() && entry.key.kind().equals(kind)
                    && entry.reachableFrom(seed, spread, standsTall)
                    && entry.region.blocks().containsKey(seed)) {
                return entry.region.blocks();
            }
        }
        attributeRefusal(kind, seed, spread, standsTall, candidates);
        return null;
    }

    /**
     * Why a mass we hold could not be lent out — asked only after {@link #covering} failed, where
     * a growth about to cost thousands of reads makes a second pass over a few candidates noise.
     * A third of hypotheses re-grew on already-walked ground (measured 2026-08-03, fifty walkers,
     * a cache big enough never to evict), a symptom with three different cures.
     */
    private void attributeRefusal(PoiKind kind, Pos seed, int spread, boolean standsTall,
            Set<Entry> candidates) {
        boolean sawPartial = false;
        boolean sawOutOfReach = false;
        for (Entry entry : candidates) {
            if (!entry.key.kind().equals(kind) || !entry.region.blocks().containsKey(seed)) {
                continue; 
            }
            if (entry.region.partial()) {
                sawPartial = true;
            } else if (!entry.reachableFrom(seed, spread, standsTall)) {
                sawOutOfReach = true;
            }
        }
        if (sawOutOfReach) {
            refusedOutOfReach++;
        } else if (sawPartial) {
            refusedPartial++;
        } else {
            unknownGround++;
        }
    }

    /** Books a {@link #covering} that the caller went on to use, so the hit rate stays right. */
    public synchronized void tookCovering() {
        hits++;
        misses--;
    }

    /**
     * Remembers a finished growth. A mass too big to fit the whole allowance is not cached at
     * all rather than evicting everything else to hold it alone.
     */
    public synchronized void put(Key key, GrownRegion region) {
        int cap = maxCells();
        int size = region.blocks().size();
        if (cap <= 0 || size == 0 || size > cap) {
            return;
        }
        Entry existing = entries.remove(key);
        if (existing != null) {
            cells -= existing.cells();
            unindex(existing);
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Pos cell : region.blocks().keySet()) {
            minX = Math.min(minX, cell.x());
            minY = Math.min(minY, cell.y());
            minZ = Math.min(minZ, cell.z());
            maxX = Math.max(maxX, cell.x());
            maxY = Math.max(maxY, cell.y());
            maxZ = Math.max(maxZ, cell.z());
        }
        Entry entry = new Entry(key, region, minX, minY, minZ, maxX, maxY, maxZ);
        entries.put(key, entry);
        cells += size;
        index(entry);
        evictDownTo(cap);
    }

    /**
     * A block changed at this column — forget everything whose shape could depend on it.
     *
     * <p><b>No Y parameter:</b> membership can be a whole-column question (water
     * joins only at its surface). Cheap enough to sit under every {@code setBlock}: one hash
     * lookup.
     */
    public synchronized void invalidate(int x, int z) {
        if (entries.isEmpty()) {
            return;
        }
        Set<Entry> candidates = byChunk.get(chunkKey(x >> 4, z >> 4));
        if (candidates == null) {
            return;
        }
        List<Entry> doomed = null;
        for (Entry entry : candidates) {
            if (entry.touchedBy(x, z)) {
                if (doomed == null) {
                    doomed = new ArrayList<>(2);
                }
                doomed.add(entry);
            }
        }
        if (doomed != null) {
            for (Entry entry : doomed) {
                drop(entry);
            }
        }
    }

    /**
     * Forgets everything touching a chunk, so nothing cached outlives the chunk it describes.
     * Hooked to chunk unload, the one hole {@link #invalidate} cannot see: worldgen writes
     * through a generating region, never the level's setBlock hook.
     */
    public synchronized void invalidateChunk(int chunkX, int chunkZ) {
        if (entries.isEmpty()) {
            return;
        }
        Set<Entry> touching = byChunk.get(chunkKey(chunkX, chunkZ));
        if (touching != null) {
            for (Entry entry : List.copyOf(touching)) {
                drop(entry);
            }
        }
    }

    /** Forgets everything — a config reload changes what a growth would even have collected. */
    public synchronized void clear() {
        entries.clear();
        byChunk.clear();
        cells = 0;
    }

    /** Masses remembered right now. */
    public synchronized int size() {
        return entries.size();
    }

    /** Cells of shape held against {@link #maxCells()} — the memory this is actually costing. */
    public synchronized int cells() {
        return cells;
    }

    /** Growths served from memory since the level loaded. */
    public synchronized long hits() {
        return hits;
    }

    /** Growths that had to be walked the slow way. */
    public synchronized long misses() {
        return misses;
    }

    /**
     * Shapes forgotten because the ground under them moved. {@link #hits} near zero with drops
     * near {@link #misses} is not a cache too small, it is a world too busy to remember.
     */
    public synchronized long drops() {
        return drops;
    }

    /** Shapes forgotten to stay inside {@link #maxCells()} — the "too small" reading. */
    public synchronized long evictions() {
        return evictions;
    }

    /**
     * A scan re-run because the mass we hold for it was cut short. High here means the growth
     * caps, not the cache size, are what everybody is paying for.
     */
    public synchronized long refusedPartial() {
        return refusedPartial;
    }

    /**
     * A scan re-run because the whole mass we hold does not fit this seed's reach. High here
     * means the sharing rule is the limit, not the room.
     */
    public synchronized long refusedOutOfReach() {
        return refusedOutOfReach;
    }

    /** A scan re-run because nothing cached covered that cell at all — genuine first sightings. */
    public synchronized long unknownGround() {
        return unknownGround;
    }

    private void drop(Entry entry) {
        if (entries.remove(entry.key) != null) {
            cells -= entry.cells();
            unindex(entry);
            drops++;
        }
    }

    private void evictDownTo(int cap) {
        var it = entries.values().iterator();
        while (cells > cap && it.hasNext()) {
            Entry eldest = it.next();
            it.remove();
            cells -= eldest.cells();
            unindex(eldest);
            evictions++;
        }
    }

    private void index(Entry entry) {
        forEachChunk(entry, (key, e) ->
                byChunk.computeIfAbsent(key, k -> new HashSet<>()).add(e));
    }

    private void unindex(Entry entry) {
        forEachChunk(entry, (key, e) -> {
            Set<Entry> at = byChunk.get(key);
            if (at != null && at.remove(e) && at.isEmpty()) {
                byChunk.remove(key);
            }
        });
    }

    private interface ChunkVisit {
        void at(long chunkKey, Entry entry);
    }

    private static void forEachChunk(Entry entry, ChunkVisit visit) {
        for (int cx = (entry.minX - MARGIN) >> 4; cx <= (entry.maxX + MARGIN) >> 4; cx++) {
            for (int cz = (entry.minZ - MARGIN) >> 4; cz <= (entry.maxZ + MARGIN) >> 4; cz++) {
                visit.at(chunkKey(cx, cz), entry);
            }
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFF_FFFFL);
    }
}
