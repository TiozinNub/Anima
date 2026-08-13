package dev.luizloyola.anima.core.brain.sense;

import java.util.List;

/**
 * Whether this body can get out of where it is — the search's own verdict, carried to the brain.
 *
 * <p>Not inferred from failures: a route search that runs out of anywhere to go has enumerated
 * every cell this body can reach, and (given the guards in {@code Pathfinder}) only the world
 * itself stopped it. The pattern would not work anyway — a body sealed in a small room succeeds at
 * most of what it tries and fails only intermittently, so no streak counter would ever fire.
 *
 * @param sealed whether the body is shut in
 * @param cells  how many cells it can reach; only a statement about its whole world when sealed
 * @param region every cell it can reach, when shut in — empty otherwise. A way out is rarely
 *               underfoot: for a body on the roof it has just dug up onto, the move that frees it
 *               is at the RIM, and a drive that only considers what is next to it never finds one
 *               (2026-08-12: a settler surfaced one block short of a rim, refused to dig level
 *               ground, and walked back down its own staircase).
 */
public record Confinement(boolean sealed, int cells, List<Pos> region) {

    public Confinement {
        region = List.copyOf(region);
    }

    /** A verdict with no map of where the body may go — every caller that only wants the answer. */
    public Confinement(boolean sealed, int cells) {
        this(sealed, cells, List.of());
    }

    /** Nothing known — what a body reports before anything has looked, and what a fake answers. */
    public static final Confinement NONE = new Confinement(false, 0, List.of());
}
