package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One in-flight structure scan — an incremental, budgeted BFS. Give it reads via {@link #step}
 * until {@link #isDone()}; an unfinished scan keeps its frontier and resumes next tick, so an oak
 * (~400 reads) completes across a handful of ticks while the person walks. It never touches the
 * store; the sensor decides what a finished {@link GrownRegion} becomes.
 *
 * <p>Connectivity is <b>26-way</b> (see {@link #NEIGHBORS}), because worldgen hangs branches off
 * trunks diagonally and a face-only walk loses them silently. It costs roughly four times the probe
 * reads, and groves touching at a corner fuse into one region — which is why the rule individuates
 * what the growth fused ({@link GrowthRule#evaluate}).
 *
 * <p>Bounded three ways, each marking the result {@code partial}: the block cap, the spread cap
 * (Chebyshev from seed), and unloaded borders ({@link BlockKind#UNKNOWN}).
 */
public final class RegionGrowth {
    /** Block cap on one scan — hitting it marks the region partial. */
    public static int maxBlocks() {
        return Config.get().i(Knob.REGION_MAX_BLOCKS);
    }

    /** Chebyshev spread cap from the seed — what splits a fused mega-forest into groves. */
    public static int maxSpread(AgentProfile profile) {
        return profile.i(ProfileAspect.PLACES_REGION_MAX_SPREAD);
    }

    /**
     * The 26 cells touching a cell — faces first, then edges and corners. Diagonals are
     * load-bearing: worldgen attaches branches to a trunk at a CORNER, and a face-only walk does
     * not see them. Measured on a saved fancy oak (2026-07-26): of 28 logs a 6-neighbour fill
     * from the stump reached 24, and the 4 it missed are the 4 the chopper left standing.
     *
     * <p>Faces first so that a scan out of budget or at {@link #maxBlocks()} has collected as
     * close as possible to the old face-first shape.
     */
    private static final int[][] NEIGHBORS = touchingCells();

    private static int[][] touchingCells() {
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int[][] all = new int[26][];
        System.arraycopy(faces, 0, all, 0, faces.length);
        int i = faces.length;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int touched = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (touched > 1) { // 0 is the cell itself, 1 is a face — already listed
                        all[i++] = new int[] {dx, dy, dz};
                    }
                }
            }
        }
        return all;
    }

    private final GrowthRule rule;
    private final Pos seed;
    private final Map<Pos, BlockKind> blocks = new LinkedHashMap<>();
    private final Set<Pos> seen = new HashSet<>();
    private final Deque<Pos> frontier = new ArrayDeque<>();
    /**
     * The cells standing against a truncation — a cap or an unloaded border, not the rule saying
     * "not mine". Anything not touching it was seen whole. That is what makes a shape worth
     * lending: three quarters of re-grown scans at fifty walkers were a mass someone already had
     * but could not share for one clipped edge (measured 2026-08-03).
     */
    private final Set<Pos> cutEdge = new HashSet<>();
    private boolean partial;
    private GrownRegion result;

    /** Whose scan this is — the spread cap is that body's judgment about places. */
    private final AgentProfile profile;

    public RegionGrowth(GrowthRule rule, Pos seed, BlockKind seedKind, AgentProfile profile) {
        this.profile = profile;
        this.rule = rule;
        this.seed = seed;
        this.blocks.put(seed, seedKind);
        this.seen.add(seed);
        this.frontier.add(seed);
    }

    /**
     * Advances the scan by at most {@code maxReads} probe reads; returns the reads actually
     * spent. Call again next tick while {@link #isDone()} is false.
     */
    public int step(BlockProbe probe, int maxReads) {
        // Hoisted so one step is always bounded by one consistent pair of caps, even if a
        // reload lands mid-scan.
        int spreadCap = maxSpread(this.profile);
        int blockCap = maxBlocks();
        int reads = 0;
        // Frontier cells visited this step, bounded alongside the reads: a cell whose neighbours
        // are all seen costs no READS, only twenty-six set lookups, so a dense mass could drain
        // the whole frontier for free — 3.9 ms sensor ticks at 150 walkers once an aggregate
        // ceiling cut wallets to a handful, against 0.6 ms unbounded. Paced, not skipped: what
        // this step misses stays at the front of the frontier.
        int visits = 0;
        while (result == null && reads < maxReads && visits < maxReads) {
            if (frontier.isEmpty()) {
                finish(probe);
                break;
            }
            Pos p = frontier.pollFirst();
            visits++;
            boolean outOfBudget = false;
            for (int[] d : NEIGHBORS) {
                Pos n = new Pos(p.x() + d[0], p.y() + d[1], p.z() + d[2]);
                if (seen.contains(n)) {
                    continue;
                }
                if (reads >= maxReads) {
                    // Requeue p at the front: its remaining neighbors get their turn next step;
                    // the seen-set makes re-visiting the checked ones free.
                    frontier.addFirst(p);
                    outOfBudget = true;
                    break;
                }
                seen.add(n);
                BlockKind kind = probe.at(n.x(), n.y(), n.z());
                reads++;
                if (kind == BlockKind.UNKNOWN) {
                    partial = true;
                    cutEdge.add(p); // the world ran out here, not the structure
                    continue;
                }
                if (!rule.joins(n, kind, probe)) {
                    continue; // a real boundary: the structure really does end here
                }
                if (chebyshev(n, seed) > spreadCap) {
                    partial = true;
                    cutEdge.add(p);
                    continue;
                }
                if (blocks.size() >= blockCap) {
                    partial = true;
                    // Everything still queued had neighbours it never got to look at, so the
                    // whole remaining frontier is cut edge — not just the cell we were on.
                    cutEdge.add(p);
                    cutEdge.addAll(frontier);
                    frontier.clear();
                    break;
                }
                blocks.put(n, kind);
                frontier.addLast(n);
            }
            if (outOfBudget) {
                break;
            }
        }
        return reads;
    }

    public boolean isDone() {
        return result != null;
    }

    /** The finished scan; only valid once {@link #isDone()}. */
    public GrownRegion result() {
        if (result == null) {
            throw new IllegalStateException("growth still running");
        }
        return result;
    }

    private void finish(BlockProbe probe) {
        this.result = judge(rule, blocks, cutEdge, partial, probe);
    }

    /**
     * Asks the rule what a collected mass amounts to. Split out of {@link #finish} because a mass
     * somebody else walked arrives read but unjudged, and because nothing here takes an observer's
     * position — which lets {@link PlaceIndex} keep the result. Bounded by the mass's size, not
     * wallet-budgeted.
     *
     * <p>{@code cutEdge} is where the walk was truncated (empty when the mass is whole); a part is
     * complete when it does not touch that edge, and that is how a capped scan still yields entire
     * trees rather than one unusable "partial grove".
     */
    public static GrownRegion judge(GrowthRule rule, Map<Pos, BlockKind> blocks,
            Set<Pos> cutEdge, boolean partial, BlockProbe probe) {
        List<GrownRegion.Part> parts = new ArrayList<>();
        for (GrowthRule.Evaluation eval : rule.evaluate(blocks, probe)) {
            parts.add(new GrownRegion.Part(eval.approach(), boundsOf(eval.blocks().keySet()),
                    eval.units(), Collections.unmodifiableMap(eval.blocks()),
                    whole(eval.blocks().keySet(), cutEdge)));
        }
        return new GrownRegion(rule.kind(), partial, Collections.unmodifiableMap(blocks),
                List.copyOf(parts));
    }

    /** Whether none of a part's cells stands against the edge where the walk was cut short. */
    private static boolean whole(Iterable<Pos> cells, Set<Pos> cutEdge) {
        if (cutEdge.isEmpty()) {
            return true;
        }
        for (Pos cell : cells) {
            if (cutEdge.contains(cell)) {
                return false;
            }
        }
        return true;
    }

    /** The smallest box holding every cell — a part's "where", folded once at the end. */
    private static Region boundsOf(Iterable<Pos> cells) {
        Region folded = null;
        for (Pos p : cells) {
            folded = folded == null ? Region.of(p) : folded.including(p);
        }
        return folded;
    }

    private static int chebyshev(Pos a, Pos b) {
        int dx = Math.abs(a.x() - b.x());
        int dy = Math.abs(a.y() - b.y());
        int dz = Math.abs(a.z() - b.z());
        return Math.max(dx, Math.max(dy, dz));
    }
}
