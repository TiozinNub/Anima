package dev.luizloyola.anima.core.brain.sense;

/**
 * The live {@link DangerTable} for one species — swapped whole, read through.
 *
 * <p>Same reasoning as {@code ConfigStore}: the shared table is immutable, so a regeneration at
 * world load or an operator's edit lands between ticks without a reader seeing half a table.
 * Bodies ask on use. That is what lets a reload reach an agent already afraid of something.
 *
 * <p>One per species, held by the mod that ships that body.
 */
public final class DangerStore {

    private volatile DangerTable current;

    public DangerStore(DangerTable initial) {
        this.current = initial == null ? DangerTable.NEUTRAL : initial;
    }

    /** The table in force right now. Never null. */
    public DangerTable get() {
        return current;
    }

    /** Swaps in a new table; every subsequent read sees it whole. */
    public void install(DangerTable table) {
        this.current = table == null ? DangerTable.NEUTRAL : table;
    }
}
