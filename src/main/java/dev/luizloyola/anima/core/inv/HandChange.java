package dev.luizloyola.anima.core.inv;

/**
 * A change of what a body holds or wears, under way: a timed item move (combat spec, *Timed
 * wield*). Held by the {@link Inventory} and saved with it, so a restart mid-swap finishes the
 * same swap. Times are game ticks, which a restart keeps. {@link HandChanges} runs it.
 *
 * @param from    the storage slot the stack comes from; -1 for a stow
 * @param to      the armour slot it goes to for an equip; -1 otherwise
 * @param readyAt the tick it is done
 * @param askedAt the last tick it was asked for; a tick nobody asks abandons it
 * @param restored loaded from a save, and not yet seen by a tick since: the first tick after a
 *                 reload counts as asked, however many the reload skipped. Never saved itself.
 */
public record HandChange(Kind kind, int from, int to, long readyAt, long askedAt, boolean restored) {

    public HandChange(Kind kind, int from, int to, long readyAt, long askedAt) {
        this(kind, from, to, readyAt, askedAt, false);
    }

    public enum Kind {
        /** A stack into the hand: a hotbar slot selected, or a backpack stack swapped in. */
        WIELD,
        /** The hand emptied. */
        STOW,
        /** A piece from storage onto its armour slot, the piece it replaces going back to storage. */
        EQUIP
    }

    boolean is(Kind kind, int from, int to) {
        return this.kind == kind && this.from == from && this.to == to;
    }

    HandChange askedAt(long now) {
        return new HandChange(kind, from, to, readyAt, now);
    }

    /** This change as a save left it, to be taken up by the first tick after the reload. */
    public HandChange asRestored() {
        return new HandChange(kind, from, to, readyAt, askedAt, true);
    }
}
