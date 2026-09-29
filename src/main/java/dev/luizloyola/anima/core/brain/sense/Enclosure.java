package dev.luizloyola.anima.core.brain.sense;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * How the space around a body opens: can anything get in, and can the body get out
 * (spec: {@code 2026-09-28-shelter-design.md}).
 *
 * @param openness    how the weakest way out is closed — see {@link Openness}
 * @param holes       whether something smaller than this body gets out where it cannot
 * @param roofed      every cell of the space has something solid over the head, so nothing falls
 *                    or climbs in from above. Never true for an {@code OPEN} space
 * @param from        the cell the check started from
 * @param space       everything a body without hands reaches from {@code from} as the doors stand
 *                    — once the doors to shut are shut, for {@code CLOSEABLE}. Empty when
 *                    {@code OPEN}
 * @param doorsToShut for {@code CLOSEABLE}: the open doors, each by its lowest cell, whose far
 *                    side leads out
 * @param doors       every door on the space's edge, by its lowest cell — what to watch for a
 *                    change that makes this stale
 * @param at          the game tick the check was dispatched
 */
public record Enclosure(Openness openness, Holes holes, boolean roofed, @Nullable Pos from,
                        Set<Pos> space, List<Pos> doorsToShut, List<Pos> doors, long at) {

    /** Every way out is judged by what stands in it; the verdict is the weakest one. */
    public enum Openness {
        /** A way out needs no door at all. */
        OPEN,
        /** Every way out passes a door, and one passes only open ones: shutting them closes it. */
        CLOSEABLE,
        /** Every way out passes a door that is shut. A hand opens one; nothing without one does. */
        OPENABLE,
        /** No way out, even through doors. */
        CLOSED
    }

    /** Whether a body smaller than this one gets out where this one cannot. */
    public enum Holes {
        /** Nothing small gets out where this body cannot. */
        NONE,
        /**
         * A one-by-one body gets out without opening a door — from where this body is shut in,
         * or would be once the doors to shut are shut.
         */
        SMALL,
        /** This body itself gets out with no door: the same fact as {@link Openness#OPEN}. */
        PASSABLE
    }

    private static final long NEVER = Long.MIN_VALUE;

    /** No check has answered — what a body has before its first, and while it walks. */
    public static final Enclosure UNKNOWN =
            new Enclosure(Openness.OPEN, Holes.PASSABLE, false, null, Set.of(), List.of(),
                    List.of(), NEVER);

    public Enclosure {
        space = Set.copyOf(space);
        doorsToShut = List.copyOf(doorsToShut);
        doors = List.copyOf(doors);
    }

    /** An open space: nothing to hand back but where it was asked. */
    public static Enclosure open(Pos from, long at) {
        return new Enclosure(Openness.OPEN, Holes.PASSABLE, false, from, Set.of(), List.of(),
                List.of(), at);
    }

    /** Whether a check has answered at all. */
    public boolean known() {
        return this.at != NEVER;
    }

    /** Whether nothing without hands gets in: shut all round and roofed. */
    public boolean shelter() {
        return this.roofed
                && (this.openness == Openness.OPENABLE || this.openness == Openness.CLOSED);
    }

    /** Whether this cell is on the body's side of every wall and shut door. */
    public boolean contains(Pos cell) {
        return this.space.contains(cell);
    }

    /**
     * {@link #contains}, a cell up or down allowed: a goal named by the floor under it, or a body
     * standing on a slab, is still where it is.
     */
    public boolean covers(Pos cell) {
        return contains(cell) || contains(new Pos(cell.x(), cell.y() + 1, cell.z()))
                || contains(new Pos(cell.x(), cell.y() - 1, cell.z()));
    }

    /** Whether two answers say the same thing about the space, whenever they were given. */
    public boolean sameAs(Enclosure other) {
        return this.openness == other.openness && this.holes == other.holes
                && this.roofed == other.roofed && this.space.size() == other.space.size()
                && this.doorsToShut.equals(other.doorsToShut);
    }

    /** One line for the journal: the verdict, the size, and what would change it. */
    public String describe() {
        if (!known()) {
            return "not known";
        }
        String verdict = this.openness.name().toLowerCase(Locale.ROOT);
        if (this.openness == Openness.OPEN) {
            return verdict;
        }
        StringBuilder line = new StringBuilder(verdict).append(", ").append(this.space.size())
                .append(" cells, ").append(this.roofed ? "roofed" : "open to the sky")
                .append(", holes ").append(this.holes.name().toLowerCase(Locale.ROOT));
        if (!this.doorsToShut.isEmpty()) {
            line.append(", doors to shut:");
            for (Pos door : this.doorsToShut) {
                line.append(" (").append(door.x()).append(", ").append(door.y()).append(", ")
                        .append(door.z()).append(')');
            }
        }
        return line.toString();
    }
}
