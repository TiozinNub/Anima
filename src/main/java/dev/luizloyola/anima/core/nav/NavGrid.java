package dev.luizloyola.anima.core.nav;

/**
 * A classification per block-sized cell: the pathfinder's only view of the world, and the brain's
 * terrain sense ({@code Percepts.terrain()}).
 *
 * <p><b>The thread rule is the reader's, not this interface's.</b> {@link Pathfinder#find} and
 * {@link Pathfinder#survey} run their A* off the server thread, so they must be handed an immutable
 * snapshot (the compat layer's {@code WorldSnapshot}) or a fixed test grid — never a live grid,
 * which would shift under a search already halfway through it. Every other reader is free to be
 * server-thread-only and live, which is what perception wants.
 */
public interface NavGrid {
    /**
     * Classifies the cell at these coordinates; outside the implementation's known bounds it must
     * return {@link CellType#OBSTACLE}, so the search never routes through unknown space.
     */
    CellType cell(int x, int y, int z);

    /**
     * How high the standable surface inside a cell sits above that cell's floor, as a fraction of
     * one block — {@code 0.5} for a bottom slab, {@code 0.9375} for a dirt path, {@code 0.0625}
     * for a carpet.
     *
     * <p>Only {@link CellType#STEP} cells lie between the extremes: {@link CellType#GROUND} reads
     * {@code 1.0}, everything else {@code 0.0}. So the feet of a body in feet-cell {@code y} sit at
     * {@code y + surface(y)} in every case, with no special casing at the call site.
     *
     * <p>The default covers grids with no partial blocks (a drawn test map): they answer as they
     * always did, so a path over one is unchanged down to the node.
     */
    default double surface(int x, int y, int z) {
        return cell(x, y, z) == CellType.GROUND ? 1.0 : 0.0;
    }

    /**
     * Whether this grid actually has data for a cell, as opposed to answering
     * {@link CellType#OBSTACLE} because it has none.
     *
     * <p>{@link #cell} cannot tell the two apart by design — the search must treat unknown space as
     * unwalkable — but one question needs to: whether a search that ran out of anywhere to go was
     * stopped by <em>the world</em> or by the edge of the capture.
     *
     * <p>The default says every cell is known, correct for a fixed test grid. A grid that is a
     * WINDOW onto a larger world (the compat layer's snapshot) must override it, or a confinement
     * verdict over it is a claim about the capture, not the terrain.
     */
    default boolean inBounds(int x, int y, int z) {
        return true;
    }

    /**
     * A grid that knows nothing: nowhere is reachable and nowhere is standable. What a rig with no
     * terrain sense reads, and the honest answer for one — a body that cannot see the ground has no
     * business claiming it could stand on it.
     */
    NavGrid UNKNOWN = new NavGrid() {
        @Override
        public CellType cell(int x, int y, int z) {
            return CellType.OBSTACLE;
        }

        @Override
        public boolean inBounds(int x, int y, int z) {
            return false;
        }
    };
}
