package dev.luizloyola.anima.core.nav;

/**
 * The pathfinder's only view of the world: a classification per block-sized cell. Implementations
 * must be safe to read from a worker thread — in practice that means immutable snapshots
 * (the compat layer's {@code WorldSnapshot}) or fixed test grids; never a live level.
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
}
