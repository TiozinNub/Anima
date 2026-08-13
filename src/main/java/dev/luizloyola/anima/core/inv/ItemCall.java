package dev.luizloyola.anima.core.inv;

import java.util.Objects;

/**
 * One line of a {@link Kit}: work calling for items, at one of two strengths. A <b>need</b> is a
 * hard precondition — the work is impossible without it (a pickaxe for ore that drops nothing
 * bare-handed). A <b>want</b> improves the work but never blocks it; the wield step picks wants up
 * during the work.
 *
 * <p>{@code count} separates the two physical shapes: a <em>carried</em> tool is count 1 and
 * durable, a <em>consumed</em> material count 64 and draining — and because a kit is re-asked
 * against the pack rather than checked off, a draining stack re-arms its call.
 *
 * <p>Food is inexpressible on purpose: hunger belongs to the metabolism and the arbiter already
 * suspends work to eat.
 */
public record ItemCall(ItemSpec spec, int count, Strength strength) {

    /** How hard the call is: a {@code NEED} gates the work, a {@code WANT} only improves it. */
    public enum Strength { NEED, WANT }

    public ItemCall {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(strength, "strength");
        if (count < 1) {
            throw new IllegalArgumentException("a call is for at least one item: " + count);
        }
    }

    /** The work cannot happen without {@code count} of {@code spec} in the pack. */
    public static ItemCall need(ItemSpec spec, int count) {
        return new ItemCall(spec, count, Strength.NEED);
    }

    /** The work goes better with {@code count} of {@code spec} in the pack. */
    public static ItemCall want(ItemSpec spec, int count) {
        return new ItemCall(spec, count, Strength.WANT);
    }

    public boolean coveredBy(Inventory pack) {
        return pack.count(spec.matcher()) >= count;
    }
}
