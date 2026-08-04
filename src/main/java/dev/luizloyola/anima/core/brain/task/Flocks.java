package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Groups scattered drops into "flocks" (decision: Luiz) so collection walks to cluster
 * centers instead of chasing single items: complete beats optimal — the walk-over pickup
 * vacuums whatever the path crosses, the caller re-scans after each leg, and stragglers form
 * their own one-item flocks until the ground is clean.
 */
public final class Flocks {
    /** Two drops within this chebyshev distance belong to the same flock. */
    public static final int FLOCK_RADIUS = 2;

    private Flocks() {
    }

    /** How many flocks the given drops form — the collection phase's lap-guard input. */
    public static int count(List<Pos> drops) {
        return clusters(drops).size();
    }

    /** The centroid of the flock nearest to {@code from}, or null when there are no drops. */
    public static Pos nearestCentroid(List<Pos> drops, Pos from) {
        Pos best = null;
        long bestDist = Long.MAX_VALUE;
        for (List<Pos> flock : clusters(drops)) {
            Pos centroid = centroid(flock);
            long dx = centroid.x() - from.x();
            long dy = centroid.y() - from.y();
            long dz = centroid.z() - from.z();
            long dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = centroid;
            }
        }
        return best;
    }

    private static List<List<Pos>> clusters(List<Pos> drops) {
        List<List<Pos>> flocks = new ArrayList<>();
        Set<Pos> unvisited = new HashSet<>(drops);
        for (Pos seed : drops) {
            if (!unvisited.remove(seed)) {
                continue;
            }
            List<Pos> flock = new ArrayList<>();
            Deque<Pos> frontier = new ArrayDeque<>();
            frontier.add(seed);
            flock.add(seed);
            while (!frontier.isEmpty()) {
                Pos p = frontier.poll();
                for (Pos other : new ArrayList<>(unvisited)) {
                    if (Math.abs(other.x() - p.x()) <= FLOCK_RADIUS
                            && Math.abs(other.y() - p.y()) <= FLOCK_RADIUS
                            && Math.abs(other.z() - p.z()) <= FLOCK_RADIUS) {
                        unvisited.remove(other);
                        flock.add(other);
                        frontier.add(other);
                    }
                }
            }
            flocks.add(flock);
        }
        return flocks;
    }

    private static Pos centroid(List<Pos> flock) {
        int x = 0;
        int y = 0;
        int z = 0;
        for (Pos p : flock) {
            x += p.x();
            y += p.y();
            z += p.z();
        }
        int n = flock.size();
        return new Pos(Math.round((float) x / n), Math.round((float) y / n), Math.round((float) z / n));
    }

    /**
     * Whether a drop can actually be walked over: gatherable unless the only thing holding it up is
     * leaves. One perched ON the CANOPY leaves the body waiting beneath for a fall only decay
     * delivers, with a crowd drawn to the same untakeable bait (2026-08-02).
     *
     * <p>Asked of the whole {@linkplain Drop#box footprint}: an item is a quarter of a block wide, so
     * one settling near an edge overhangs into a neighbouring column and the cell its centre rounds
     * to is often the empty one — a log on the lip of a leaf drew twenty-nine gatherers that never
     * got it (2026-08-03).
     *
     * <p>Across cells, what physically holds the item decides: leaves strand it, anything solid does
     * not, air holds nothing, and {@code UNKNOWN} does neither — so an unseen footprint still reads
     * as gatherable. One budgeted block read per cell, and a solid one returns early.
     *
     * <p>Ownership first: a drop inside somebody else's live work site is not this body's however
     * reachable, or a felled tree's logs go to the crowd that gathers to watch (2026-08-03).
     */
    public static boolean gatherable(Drop drop, dev.luizloyola.anima.core.brain.BrainContext ctx) {
        // Asked first: a plain map walk, while everything below it spends the perception wallet.
        if (ctx.claims().claimedByOther(drop.pos(), ctx.percepts().time())) {
            return false;
        }
        Region box = drop.box();
        int floor = box.min().y() - 1;
        boolean leafUnderneath = false;
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                BlockKind under = ctx.percepts().blocks().at(x, floor, z);
                if (under == BlockKind.LEAVES) {
                    leafUnderneath = true;
                } else if (under != BlockKind.AIR && under != BlockKind.WATER
                        && under != BlockKind.UNKNOWN) {
                    // Named by what cannot bear weight rather than by what can, because the
                    // vocabulary is open: a consumer's own kind is something to stand on unless
                    // it is one of the three ways of saying "nothing there".
                    return true;
                }
            }
        }
        return !leafUnderneath;
    }
}
