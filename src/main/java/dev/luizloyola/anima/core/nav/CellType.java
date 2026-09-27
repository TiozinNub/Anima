package dev.luizloyola.anima.core.nav;

/**
 * The pathfinder's entire vocabulary for the world. The compat layer collapses real blockstates
 * to one of these per cell; collision shapes, block ids and fluids stay behind that seam.
 */
public enum CellType {
    /** Empty enough to occupy: air, grass, flowers — anything with no collision. */
    PASSABLE('.'),
    /** Solid with a sturdy top: blocks bodies, and the cell above it can be stood in. */
    GROUND('G'),
    /**
     * Blocks bodies but cannot be stood on: fences, walls, anything reaching past the top of its
     * own cell. Also everything outside a grid's bounds — unknown space must be unwalkable.
     */
    OBSTACLE('X'),
    /** Harmful to touch or stand on: lava, fire, cactus, magma. Never entered, never a floor. */
    DANGER('D'),
    /**
     * Swimmable liquid — one classification for surface and submerged alike. Neither
     * {@link #GROUND} nor {@link #PASSABLE}, so a land-only agent
     * ({@link MoveCapabilities#canSwim()} false) routes around it; a swimmer occupies it. The
     * waterline is derived geometrically (water with air above), so these cells serve future
     * underwater routing without a second value.
     */
    WATER('W'),
    /**
     * A floor that stops <em>inside</em> its own cell: slab, stair, snow layer, carpet, dirt path,
     * closed trapdoor, cauldron bowl. The body stands <b>in this cell</b>, feet at
     * {@link NavGrid#surface} — not in the cell above, the way {@link #GROUND} works. Only this
     * when the surface lies strictly between the cell's floor and its top: full height is
     * {@link #GROUND}, past it (fence, wall) {@link #OBSTACLE}.
     *
     * <p>These once read {@code OBSTACLE}, walling off streets, fields and hillsides the body
     * crosses fine on vanilla's 0.6 step height.
     */
    STEP('S'),
    /**
     * A panel on one face of the cell, or a gate across it: every door, an open trapdoor, a shut
     * fence gate. A body crosses it along one axis and not the other, and a hand may swing it —
     * {@link NavGrid#doorway} says which, and {@link Doorway} reads it. Room for a body wherever
     * some passage is open to it; footing over {@link #GROUND}, like air.
     *
     * <p>These read {@link #OBSTACLE} until 2026-09-26, so a house was a box with no way in.
     */
    DOOR('O'),
    /**
     * Something a body can climb, and whose collision leaves it room to: a ladder, vines. Over
     * {@link #GROUND} it is ordinary footing; with nothing under it, it is a hold — somewhere a
     * climber can be but not rest. Room for a body, like air.
     *
     * <p><b>Last.</b> The ordinals are memoised ({@code WorldSnapshot} packs a byte per cell) and
     * a hot swap never re-runs a static initialiser, so a value inserted before the end decodes
     * stale entries as the wrong type. Append; never reorder.
     */
    CLIMB('H');

    private final char code;

    CellType(char code) {
        this.code = code;
    }

    /**
     * A stable one-character name, so a classified region can be written down and read back.
     * Changing one invalidates every recorded region — treat these as permanent. It lives on the
     * enum so a writer and a reader cannot keep tables that drift as values are added.
     *
     * <p>None is {@code '#'}: a capture carries {@code #} comment lines, and {@code # 594 -50 34}
     * would be both a comment and a cell.
     */
    public char code() {
        return this.code;
    }

    /** The type {@link #code()} names. Throws on an unknown character rather than guessing. */
    public static CellType byCode(char code) {
        for (CellType type : values()) {
            if (type.code == code) return type;
        }
        throw new IllegalArgumentException("no cell type with code '" + code + "'");
    }
}
