package dev.luizloyola.anima.core.nav;

/**
 * One step of a computed {@link Path}.
 *
 * @param x        feet-cell x
 * @param y        feet-cell y
 * @param z        feet-cell z
 * @param move     how this waypoint is entered from the one before it, or from the start position
 *                 for the first
 * @param surface16 feet height above the floor of the feet-cell, in sixteenths: {@code 0} on a
 *                 full block below, {@code 8} on a bottom slab in this cell, {@code 15} on a dirt
 *                 path. Never 16 — a surface level with the top of a cell puts the feet in the
 *                 next one up.
 *                 <p>Carried rather than re-derived because neither reader has the grid:
 *                 {@link PathIntegrity} must know whether the floor to re-check is the
 *                 {@link CellType#GROUND} below or a {@link CellType#STEP} in this very cell, and
 *                 the follower needs the true feet height to tell "standing on the waypoint" from
 *                 "a block above it".
 */
public record Waypoint(int x, int y, int z, MoveType move, int surface16) {

    /** A waypoint whose feet rest on the floor of their cell — the ordinary case. */
    public Waypoint(int x, int y, int z, MoveType move) {
        this(x, y, z, move, 0);
    }

    public double feetY() {
        return this.y + this.surface16 / 16.0;
    }
}
