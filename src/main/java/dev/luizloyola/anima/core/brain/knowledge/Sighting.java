package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Objects;
import java.util.Optional;

/**
 * Something made out but never examined — the gist tier, below {@link PoiMemory}. A
 * belief says "a tree here, four logs, anchored on that cell"; a sighting only "something of that
 * sort about there".
 *
 * <p><b>Not a PoiMemory with a flag:</b> a flag would let questions a glimpse cannot answer — how
 * much, where to stand, still that shape — compile and be wrong at runtime. The tiers merge
 * oppositely too: beliefs tight and individuated, glimpses coarse, so a forest is <em>one</em> of
 * these.
 *
 * <p><b>It resolves by being visited</b>, never on its own: inspection range either grows a real
 * belief, {@linkplain AgentKnowledge#supersede superseding} this, or finds nothing of the sort
 * and {@linkplain AgentKnowledge#disprove disproves} it.
 *
 * <p><b>Only a sense that looked where the thing would BE may settle a rumour</b>, and the near
 * field looks at one cell per column. A kind that does not stand at a column's surface says so
 * ({@link PoiKind.Settling#DELIBERATE}) and its rumours outlive being walked past, or the far
 * sense would keep making them and the near field keep deleting them.
 *
 * @param kind       what it looked like from there
 * @param at         where it looked to be — the cell that topped the skyline, so a canopy rather
 *                   than a trunk: right about the place, vague about the thing
 * @param seenFrom   where it was made out from, and that is how far off it was and therefore how
 *                   much salt to take it with
 * @param whenTick   game time it was made out; a sighting ages and is never re-confirmed
 * @param provenance how it came to be believed at all
 */
public record Sighting(PoiKind kind, Pos at, Pos seenFrom, long whenTick, Provenance provenance) {

    /**
     * How a body came by a sighting. All three exist already because hearsay has a distant
     * glimpse's properties (approximate, unverified, settled only by going), so gossip lands
     * here rather than growing a parallel machinery.
     */
    public enum Provenance {
        /** Made out on the skyline while doing something else. */
        PASSIVE,
        /** Found by a deliberate survey from a vantage. */
        SURVEY,
        /** Someone said so. Not yet produced by anything. */
        TOLD;

        /** The constant by name, or empty — an unknown label must not cost a whole save file. */
        public static Optional<Provenance> byName(String name) {
            for (Provenance value : values()) {
                if (value.name().equals(name)) {
                    return Optional.of(value);
                }
            }
            return Optional.empty();
        }
    }

    public Sighting {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(seenFrom, "seenFrom");
        Objects.requireNonNull(provenance, "provenance");
    }

    /** Ticks since it was made out. A sighting only ever gets staler. */
    public long age(long now) {
        return now - whenTick;
    }

    /** How far off it was when made out — the measure of how vague it is. */
    public int range() {
        long dx = (long) at.x() - seenFrom.x();
        long dz = (long) at.z() - seenFrom.z();
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }
}
