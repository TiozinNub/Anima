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
     */
    int surfaceY(int x, int z);

    /** What stands at the cell. Out-of-reach cells return {@link BlockKind#UNKNOWN}. */
    BlockKind at(int x, int y, int z);

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
