package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * Perception's only window onto the world. The compat layer implements it over the live level
 * (heightmap lookups, tag checks, a voxel ray from the eyes); tests implement it over fake grids.
 * Every call is assumed to cost one block read against the sensor's per-tick wallet — the reason
 * the interface is this narrow.
 */
public interface BlockProbe {
    /**
     * The y of the topmost surface block at this column (motion-blocking heightmap), or
     * {@link Integer#MIN_VALUE} when the column is out of reach (unloaded chunk).
     *
     * <p>What would hold a body up — <b>not</b> what stands on this column. See {@link #topY}.
     */
    int surfaceY(int x, int z);

    /**
     * The y of the topmost cell here holding anything at all, or {@link Integer#MIN_VALUE} when the
     * column is out of reach.
     *
     * <p><b>What a glance lands on</b>, where {@link #surfaceY} is what a boot lands on — usually
     * the same cell. They part over anything that grows without being solid: sugar cane and sweet
     * berry bushes have no collision, so they are absent from the motion-blocking heightmap and
     * {@code surfaceY} answers with the sand <em>underneath</em>, which made them invisible to the
     * near field. One heightmap lookup instead of the other, not as well as, so it costs nothing.
     *
     * <p>Grass and flowers land here too: they classify as {@link BlockKind#AIR}, no rule claims
     * them, and the column settles as before.
     */
    int topY(int x, int z);

    /** What stands at the cell. Out-of-reach cells return {@link BlockKind#UNKNOWN}. */
    BlockKind at(int x, int y, int z);

    /**
     * Exactly what stands at the cell, as the world names it — {@code "minecraft:oak_log"}. Empty
     * when the cell is out of reach or the probe cannot say.
     *
     * <p>Deliberately NOT memoised, and deliberately separate from {@link #at}: the coarse question
     * is asked hundreds of times per body per tick and is cached for it; this one is asked once per
     * thing a rule individuates, by the few consumers that care what species something is.
     */
    default String idAt(int x, int y, int z) {
        return "";
    }

    /**
     * What a ray meets at this cell, answered in one read.
     *
     * <p>Separate from {@link #at} because the vocabularies differ: {@link BlockKind} is a botany
     * and minds whether a leaf grew or was placed; an eye does not, and does mind the flowers and
     * grass {@code at} flattens into air. Deriving one from the other got hedges and dying canopy
     * rims wrong in opposite directions.
     */
    Sight sightAt(int x, int y, int z);

    /** What a ray finds in a cell. */
    enum Sight {
        /** It passes and there was nothing to see: air, grass, flowers. */
        CLEAR,
        /**
         * It passes, but there is something here to notice — a canopy, a water surface.
         *
         * <p>The distinction {@link #CLEAR} cannot make, and the far sense is useless without it:
         * leaves are see-through, so a ray at a wood does not stop, and one treating a canopy as
         * air could find a tree only by threading its trunk — at fifty blocks the bearings are
         * three apart, so it almost never would.
         */
        VEILED,
        /** It stops here. Something solid enough to hide what is behind it. */
        BLOCKED,
        /**
         * There is no world here to look at — an unloaded chunk. A ray ends, but the bearing it
         * belongs to reports "I could see no further", which is a different claim from "there was
         * nothing there" and must never be collapsed into it.
         */
        OUTSIDE,
        /**
         * It passes, there is something here, and it is too slight for a passing glance — grass, a
         * flower, a cane stalk, a berry bush. {@link #CLEAR}'s twin, split off it.
         *
         * <p>The passive fan treats it exactly as {@code CLEAR}, at no extra read; the distinction
         * buys a <em>deliberate</em> look that can ask what it passed through rather than only what
         * stopped it. Hence the survey tier is not the same fan with more rays: sugar cane stops
         * nothing, so no ray density would find it — the mechanism had to change, not the budget.
         *
         * <p><b>Declared last.</b> {@code LevelProbe} memoises this enum by ORDINAL into
         * a table a hot swap leaves standing — a redefinition never re-runs the initialiser that
         * built it — so inserting a constant anywhere but the end would silently re-interpret every
         * verdict already in that table on a running server.
         */
        THIN;
    }

    /**
     * The confirm-ray: can the person currently see this cell from their eyes? One voxel walk,
     * air/leaves/water transparent — fired once per <em>discovery</em> (before an expansion is
     * spent on a hypothesis), never per column, so the proof costs almost nothing.
     */
    boolean visibleFromEyes(Pos target);

    /**
     * The same walk between two arbitrary cells: has something at {@code from} a clear line to
     * {@code to}?
     *
     * <p>"Could I be seen from there", not "can I see it" — what a body asks looking for somewhere
     * out of an archer's line. Rays are expensive: ask at a decision point, never per tick.
     */
    boolean sightClearBetween(Pos from, Pos to);
}
