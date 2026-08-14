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
    private final DangerTable declared;

    public DangerStore(DangerTable initial) {
        this.declared = initial == null ? DangerTable.NEUTRAL : initial;
        this.current = this.declared;
    }

    /** The table in force right now. Never null. */
    public DangerTable get() {
        return current;
    }

    /**
     * The table this store was built with — the mod author's corrections, before any file was read
     * over them.
     *
     * <p>Kept for the defaults twin of the danger file, which {@link #get()} cannot answer once a
     * file lands; and as the fallback when the file is gone, since the live table would hand back
     * the last file's overrides on a second world load in the same JVM.
     */
    public DangerTable declared() {
        return declared;
    }

    /** Swaps in a new table; every subsequent read sees it whole. */
    public void install(DangerTable table) {
        this.current = table == null ? DangerTable.NEUTRAL : table;
    }
}
