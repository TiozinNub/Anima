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
     * Blocks bodies but not standable (fences, walls, open trapdoors, unmodeled partial collision)
     * — also the out-of-bounds sentinel: unknown space must stay unwalkable.
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
    WATER('W');

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
