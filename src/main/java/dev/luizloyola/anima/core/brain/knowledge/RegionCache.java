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
 * What the ground is SHAPED like, worked out once and lent to everybody — the one part of
 * perception that is a fact about the world rather than a belief about it.
 *
 * <p>{@link RegionGrowth} is by far the most expensive thing a mind does: 26-way connectivity is
 * twenty-six block reads per frontier cell, and a welded conifer stand is thousands of cells. Fifty
 * bodies walking through one wood used to run fifty identical flood fills.
 *
 * <p><b>What it does not share is anybody's mind.</b> A hit skips the READING, never the believing:
 * the finder still notes its own {@link PoiMemory}, stakes its own claims and writes its own
 * journal line.
 *
 * <p><b>Two ways in.</b>
 * <ul>
 *   <li>{@link #get} — same seed, same reach: the finished scan whole, anchors already right.</li>
 *   <li>{@link #covering} — a different cell of a mass somebody already walked. That is what
 *       carries a crowd arriving at a wood from every side. The expensive half (which cells are
 *       connected) is reusable; the cheap half (which of them is a tree, and which end you would
 *       walk to) is re-judged for the new seed by the caller. Only <em>complete</em> masses
 *       qualify.</li>
 * </ul>
 *
 * <p><b>Why only complete masses may be re-seeded.</b> Any cell of a flood fill finds the same
 * component, but growth also stops at a Chebyshev spread cap measured from the seed, and a scan
 * that hit that cap is a fact about where somebody stood. So a partial mass is served only to its
 * own seed, a complete one to any seed that could have reached all of it — a comparison against the
 * box's far corner.
 *
 * <p><b>What invalidates an entry: any block change in its footprint, at any height.</b> Hence
 * {@link #invalidate(int, int)} taking no Y — the omission is the rule, not an oversight. A rule
 * may ask a column's surface to decide membership ({@code WaterRule} joins water only where it is
 * the top of its column), so a block dropped anywhere above can change what belongs. The footprint
 * carries a one-cell margin because growth is 26-way: a log touching a trunk joins the mass.
 *
 * <p><b>Bounded in cells, not entries</b> — a pumpkin is one cell, a fused spruce stand thousands.
 * Eviction is least-recently-used.
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
         * Whether a body seeding at this cell, willing to look this far, would have collected
         * this mass. The furthest cell of a box from any point is one of its corners, so
         * the widest span on each axis is all this has to ask.
         */
        boolean reachableFrom(Pos seed, int spread) {
            int dx = Math.max(seed.x() - minX, maxX - seed.x());
            int dy = Math.max(seed.y() - minY, maxY - seed.y());
            int dz = Math.max(seed.z() - minZ, maxZ - seed.z());
            return Math.max(dx, Math.max(dy, dz)) <= spread;
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
    public synchronized Map<Pos, BlockKind> covering(PoiKind kind, Pos seed, int spread) {
        Set<Entry> candidates = byChunk.get(chunkKey(seed.x() >> 4, seed.z() >> 4));
        if (candidates == null) {
            unknownGround++;
            return null;
        }
        for (Entry entry : candidates) {
            if (!entry.region.partial() && entry.key.kind().equals(kind)
                    && entry.reachableFrom(seed, spread)
                    && entry.region.blocks().containsKey(seed)) {
                return entry.region.blocks();
            }
        }
        attributeRefusal(kind, seed, spread, candidates);
        return null;
    }

    /**
     * Why a mass we hold could not be lent out — asked only after {@link #covering} failed, where
     * a growth about to cost thousands of reads makes a second pass over a few candidates noise.
     * A third of hypotheses re-grew on already-walked ground (measured 2026-08-03, fifty walkers,
     * a cache big enough never to evict), a symptom with three different cures.
     */
    private void attributeRefusal(PoiKind kind, Pos seed, int spread, Set<Entry> candidates) {
        boolean sawPartial = false;
        boolean sawOutOfReach = false;
        for (Entry entry : candidates) {
            if (!entry.key.kind().equals(kind) || !entry.region.blocks().containsKey(seed)) {
                continue; 
            }
            if (entry.region.partial()) {
                sawPartial = true;
            } else if (!entry.reachableFrom(seed, spread)) {
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
