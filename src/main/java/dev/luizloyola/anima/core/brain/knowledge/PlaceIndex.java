package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What THINGS are out there, worked out once and lent to everybody — "whose tree is this leaf?"
 * in one lookup, no walking.
 *
 * <p>It replaces {@link RegionCache}'s unit, a connected <em>mass</em> keyed by whatever cell
 * somebody started from, which measured wrong twice over (staged wood, fifty walkers, 2026-08-03):
 * stored once per seed, a 147-oak wood of 8,767 cells occupied 62,615 of them; and three quarters
 * of all re-grown scans were a mass somebody held but could not lend, its growth cut short at the
 * spread cap. A tree is some sixty cells and sits well inside a scan clipped far further out, so
 * <b>a cut-short scan still yields dozens of whole trees</b> — only the ones straddling the cut
 * are dropped ({@link GrownRegion.Part#complete}).
 *
 * <p><b>What it does not share is anybody's mind.</b> A hit skips the LOOKING, never the
 * believing: the finder notes its own {@link PoiMemory}, stakes its own claims, writes its own
 * journal line, and files an anchor chosen for where it stands ({@link Anchors#choose}).
 *
 * <p><b>Invalidation reaches one hop past the thing that changed</b>: a block inside a tree drops
 * it and everything sharing a seam with it, since felling one moves the other's boundary without
 * touching a cell of it. Seams are computed once at insert, from the cells, so a rule never
 * declares them. Chunk unload sweeps whole — worldgen writes through a generating region and never
 * passes the block hook.
 *
 * <p>Bounded in cells rather than entries, least-recently-asked-about first.
 */
public final class PlaceIndex {

    /** How many cells of recognised things one level may hold; 0 disables the index. */
    public static int maxCells() {
        return Config.get().i(Knob.PLACE_INDEX_CELLS);
    }

    /**
     * One recognised thing, held for the whole level. Not a record: these live in identity sets
     * and a seam graph, where a record's equals would deep-compare a block map to answer whether
     * two references are the same tree.
     */
    public static final class Place {
        private final PoiKind kind;
        private final List<Pos> approach;
        private final Region bounds;
        private final int units;
        private final Map<Pos, BlockKind> blocks;
        /** Things whose boundary was decided jointly with this one — see the class doc. */
        private final Set<Place> seam = new HashSet<>();

        private Place(PoiKind kind, List<Pos> approach, Region bounds, int units,
                Map<Pos, BlockKind> blocks) {
            this.kind = kind;
            this.approach = approach;
            this.bounds = bounds;
            this.units = units;
            this.blocks = blocks;
        }

        public PoiKind kind() {
            return kind;
        }

        /** The cells a body may walk to — {@link Anchors#choose} picks for whoever is asking. */
        public List<Pos> approach() {
            return approach;
        }

        public Region bounds() {
            return bounds;
        }

        public int units() {
            return units;
        }

        /** The cells this thing owns — the claim payload, exactly as a fresh scan would give it. */
        public Map<Pos, BlockKind> blocks() {
            return blocks;
        }

        /** This thing as a belief held by a body at {@code from}, stamped now. */
        public PoiMemory toMemory(Pos from, long now) {
            return new PoiMemory(kind, Anchors.choose(approach, from), bounds, units, false, now);
        }
    }

    /** Cell → the thing that owns it. The whole lookup, and the reason this exists. */
    private final Map<Pos, Place> byCell = new HashMap<>();
    /** Chunk column → the things whose footprint touches it. The invalidation index. */
    private final Map<Long, Set<Place>> byChunk = new HashMap<>();
    /** Access-ordered over identity, so iteration order is eviction order. */
    private final LinkedHashMap<Place, Boolean> order = new LinkedHashMap<>(64, 0.75f, true);
    private int cells;
    private long hits;
    private long misses;
    private long drops;
    private long replaced;
    private long evictions;

    /**
     * The thing of this kind that owns {@code cell}, or null. One hash lookup, on the hot path of
     * every hypothesis.
     */
    public synchronized Place at(PoiKind kind, Pos cell) {
        Place place = byCell.get(cell);
        if (place == null || !place.kind.equals(kind)) {
            misses++;
            return null;
        }
        order.get(place); // touch: least recently ASKED ABOUT is what we evict
        hits++;
        return place;
    }

    /**
     * Files everything a finished scan recognised WHOLE. Parts straddling the cut are dropped: a
     * truncated tree lent to the next body outlives the scan that made it.
     *
     * @return how many things were filed
     */
    public synchronized int putAll(GrownRegion region) {
        int cap = maxCells();
        if (cap <= 0) {
            return 0;
        }
        int filed = 0;
        for (GrownRegion.Part part : region.parts()) {
            if (part.complete() && put(region.kind(), part, cap)) {
                filed++;
            }
        }
        evictDownTo(cap);
        return filed;
    }

    private boolean put(PoiKind kind, GrownRegion.Part part, int cap) {
        int size = part.blocks().size();
        if (size == 0 || size > cap) {
            return false; // too big to hold without evicting everything else to hold it alone
        }
        // A later, better-informed scan wins outright: boundaries genuinely move when a wood
        // changes, and two things claiming one leaf is what this index exists to prevent.
        for (Pos cell : part.blocks().keySet()) {
            Place existing = byCell.get(cell);
            if (existing != null && order.remove(existing) != null) {
                // Counted apart from drops(): a better-informed scan re-reading the same wood is
                // ordinary and arrives in bulk. Filing it as "the ground moved" showed 4,421 drops
                // in a wood where nothing had changed (2026-08-04).
                forget(existing);
                replaced++;
            }
        }
        Place place = new Place(kind, part.approach(), part.bounds(), part.units(),
                Collections.unmodifiableMap(part.blocks()));
        for (Pos cell : place.blocks.keySet()) {
            byCell.put(cell, place);
        }
        linkSeams(place);
        index(place);
        order.put(place, Boolean.TRUE);
        cells += size;
        return true;
    }

    /**
     * Records which already-known things this one touches. Growth is 26-way, so anything within
     * that reach shares a boundary both of them drew.
     */
    private void linkSeams(Place place) {
        for (Pos cell : place.blocks.keySet()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        Place other = byCell.get(
                                new Pos(cell.x() + dx, cell.y() + dy, cell.z() + dz));
                        if (other != null && other != place) {
                            place.seam.add(other);
                            other.seam.add(place);
                        }
                    }
                }
            }
        }
    }

    /**
     * A block changed — forget whatever owned that column, and whatever shared a seam with it.
     *
     * <p><b>No Y parameter, on purpose</b>, the same as the scan cache: membership can be a
     * question about a whole column, so a block dropped at any height over a thing can change what
     * belongs to it. Cheap enough to sit under every {@code setBlock} on the server.
     */
    public synchronized void invalidate(int x, int z) {
        if (order.isEmpty()) {
            return;
        }
        Set<Place> candidates = byChunk.get(chunkKey(x >> 4, z >> 4));
        if (candidates == null) {
            return;
        }
        Set<Place> doomed = null;
        for (Place place : candidates) {
            if (place.bounds.min().x() - MARGIN <= x && x <= place.bounds.max().x() + MARGIN
                    && place.bounds.min().z() - MARGIN <= z && z <= place.bounds.max().z() + MARGIN) {
                if (doomed == null) {
                    doomed = new HashSet<>();
                }
                // The whole set is worked out before anything is dropped: a place's seams are
                // cleared when it goes, so dropping as we went made the result depend on the
                // candidate set's iteration order.
                doomed.add(place);
                doomed.addAll(place.seam);
            }
        }
        if (doomed != null) {
            for (Place place : doomed) {
                drop(place);
            }
        }
    }

    /** Forgets everything touching a chunk — hooked to chunk unload. */
    public synchronized void invalidateChunk(int chunkX, int chunkZ) {
        if (order.isEmpty()) {
            return;
        }
        Set<Place> touching = byChunk.get(chunkKey(chunkX, chunkZ));
        if (touching != null) {
            Set<Place> doomed = new HashSet<>(touching);
            for (Place place : touching) {
                doomed.addAll(place.seam);
            }
            for (Place place : doomed) {
                drop(place);
            }
        }
    }

    /** Forgets everything — a config reload changes what a scan would even have collected. */
    public synchronized void clear() {
        byCell.clear();
        byChunk.clear();
        order.clear();
        cells = 0;
    }

    /** Things known right now. */
    public synchronized int size() {
        return order.size();
    }

    /** Cells of recognised things held against {@link #maxCells()}. */
    public synchronized int cells() {
        return cells;
    }

    /** Hypotheses answered without walking anything. */
    public synchronized long hits() {
        return hits;
    }

    /** Hypotheses this index could not answer — a scan had to run, or the cell is nothing. */
    public synchronized long misses() {
        return misses;
    }

    /** Things forgotten because the ground under or beside them moved. */
    public synchronized long drops() {
        return drops;
    }

    /**
     * Things re-filed by a later scan that read the same wood again — not a fault: several bodies
     * growing overlapping masses each individuate the trees they both reach. Kept apart from
     * {@link #drops}.
     */
    public synchronized long replaced() {
        return replaced;
    }

    /** Things forgotten to stay inside {@link #maxCells()} — the "too small" reading. */
    public synchronized long evictions() {
        return evictions;
    }

    private void drop(Place place) {
        if (order.remove(place) == null) {
            return; // already gone: the doomed set can name one thing by two routes
        }
        forget(place);
        drops++;
    }

    private void evictDownTo(int cap) {
        var it = order.keySet().iterator();
        while (cells > cap && it.hasNext()) {
            Place eldest = it.next();
            it.remove();
            forget(eldest);
            evictions++;
        }
    }

    /** Unpicks a thing from all three structures. The only place that decrements {@link #cells}. */
    private void forget(Place place) {
        for (Pos cell : place.blocks.keySet()) {
            byCell.remove(cell, place); // only if it is still ours: a newer place may own it now
        }
        unindex(place);
        for (Place neighbour : place.seam) {
            neighbour.seam.remove(place);
        }
        place.seam.clear();
        cells -= place.blocks.size();
    }

    /**
     * The one-cell skirt around a footprint, for the same reason the scan cache has one: growth
     * joins diagonally, so a cell touching a thing at a corner is a cell that could join it.
     */
    private static final int MARGIN = 1;

    private void index(Place place) {
        forEachChunk(place, (key, p) ->
                byChunk.computeIfAbsent(key, k -> new HashSet<>()).add(p));
    }

    private void unindex(Place place) {
        forEachChunk(place, (key, p) -> {
            Set<Place> at = byChunk.get(key);
            if (at != null && at.remove(p) && at.isEmpty()) {
                byChunk.remove(key);
            }
        });
    }

    private interface ChunkVisit {
        void at(long chunkKey, Place place);
    }

    private static void forEachChunk(Place place, ChunkVisit visit) {
        for (int cx = (place.bounds.min().x() - MARGIN) >> 4;
                cx <= (place.bounds.max().x() + MARGIN) >> 4; cx++) {
            for (int cz = (place.bounds.min().z() - MARGIN) >> 4;
                    cz <= (place.bounds.max().z() + MARGIN) >> 4; cz++) {
                visit.at(chunkKey(cx, cz), place);
            }
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFF_FFFFL);
    }
}
