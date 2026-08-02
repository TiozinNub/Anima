package dev.luizloyola.anima.core.nav;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Where a route is allowed to exist — the hard-constraint sibling of
 * {@link dev.luizloyola.anima.core.brain.sense.DangerField}: that field says "rather not", this one
 * says "nowhere else". Outside it, cells do not exist to the search: no cost, no detour, no path.
 * Written for work that must not leave its site — Autarkia's chop, where stable leaves read as
 * ground and an unfenced Person walked off across the next tree's canopy (live, 2026-08-02);
 * fenced, "there is no path" comes back, which the task can act on.
 *
 * <p>A SNAPSHOT: built on the server thread when the request is made, immutable for the worker. It
 * constrains where the body STANDS — the feet cell of every node admitted; a leap may still arc
 * over a cell the domain does not contain.
 */
public final class NavDomain {

    /** No fence at all — every request that does not say otherwise. */
    public static final NavDomain EVERYWHERE = new NavDomain(null);

    private final Set<Long> cells;

    private NavDomain(Set<Long> cells) {
        this.cells = cells;
    }

    /** A domain of exactly these cells. The collection is copied; later changes do not leak in. */
    public static NavDomain of(Collection<Pos> allowed) {
        Set<Long> packed = new HashSet<>(allowed.size() * 2);
        for (Pos cell : allowed) {
            packed.add(pack(cell.x(), cell.y(), cell.z()));
        }
        return new NavDomain(packed);
    }

    /** Whether a body may stand here. */
    public boolean contains(int x, int y, int z) {
        return cells == null || cells.contains(pack(x, y, z));
    }

    /** Whether this is {@link #EVERYWHERE} in behaviour — no fence to check. */
    public boolean isEverywhere() {
        return cells == null;
    }

    /** How many cells the fence admits — only meaningful when not {@link #isEverywhere()}. */
    public int size() {
        return cells == null ? 0 : cells.size();
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFF);
    }
}
