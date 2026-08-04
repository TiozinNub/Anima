package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Map;

/**
 * A finished growth: everything a completed scan learned about one connected mass. Each
 * {@link Part} becomes a {@link PoiMemory} with positive claims over its cells; cells no part
 * claimed become negative claims. {@link #blocks} is that claim payload and lives only for the
 * hand-off — the durable memory keeps anchor + bounds + units.
 *
 * <p>A mass is not a thing: it is however many things the rule found in it, which is
 * {@link #parts}.
 *
 * @param kind    what the rule was looking for
 * @param partial true when growth stopped at a cap or an unloaded border — there is AT LEAST
 *                this much <em>of the mass</em>. Per-part truth lives on {@link Part#complete}:
 *                a cut-short mass is mostly whole things.
 * @param blocks  every joined cell and what it was — the whole mass
 * @param parts   one entry per recognized thing; empty means the rule recognized nothing here
 */
public record GrownRegion(PoiKind kind, boolean partial, Map<Pos, BlockKind> blocks,
                          List<Part> parts) {

    /**
     * One recognized thing inside the mass.
     *
     * @param approach the cells a body may walk to — a list, not an anchor, because
     *                 {@link Anchors#choose} picks the near one for whoever is asking
     * @param bounds   the box this part spans (not the mass's)
     * @param units    the rule's size measure — this tree's logs, this body's surface cells
     * @param blocks   the cells this part owns, its share of the mass
     * @param complete whether this thing was seen whole — no cell of it against the edge where
     *                 growth was cut short. The distinction the mass-wide {@code partial} flag
     *                 cannot draw: a scan stopped at its spread cap still holds dozens of entire
     *                 trees; only the ones straddling the cut are provisional.
     */
    public record Part(List<Pos> approach, Region bounds, int units, Map<Pos, BlockKind> blocks,
                       boolean complete) {

        /** Where a body standing at {@code from} would walk to reach this thing. */
        public Pos anchorFrom(Pos from) {
            return Anchors.choose(approach, from);
        }
    }

    /** Whether the rule recognized anything at all here. */
    public boolean accepted() {
        return !parts.isEmpty();
    }

    /**
     * One part as a belief held by a body standing at {@code from}, stamped with the current game
     * time — the anchor depends on who is looking, so the rule does not choose it.
     *
     * <p>The memory's {@code partial} flag is the PART's, not the mass's: calling a whole tree in a
     * cut-short scan partial used to cost it the crown test.
     */
    public PoiMemory toMemory(Part part, Pos from, long now) {
        return new PoiMemory(kind, Anchors.choose(part.approach(), from), part.bounds(),
                part.units(), !part.complete(), now);
    }
}
