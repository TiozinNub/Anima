package dev.luizloyola.anima.core.agent;

import java.util.Locale;

/**
 * One reason a particular agent differs from the rest of its species — a trait, a skill, a job, an
 * injury.
 *
 * <p><b>Keyed by id</b>: changing jobs removes that job's contribution rather than
 * recomputing a total, and re-applying an id replaces rather than stacks, so a consumer restoring
 * its state on world load need not know whether it already did. Vanilla's attribute algebra:
 *
 * <pre>{@code (base + Σ ADD) × (1 + Σ ADD_FRACTION_OF_BASE) × Π (1 + ADD_FRACTION_OF_TOTAL)}</pre>
 *
 * <p>Not {@code AttributeModifier}: this resolves in {@code core/}, where {@code net.minecraft}
 * cannot be named. Anima never interprets an id — it tells two modifiers apart and shows it in a
 * readout; the consumer persists the job and re-applies the modifier on load.
 *
 * @param id     the consumer's name for the source, e.g. {@code "job:lumberjack"}. Unique per
 *               agent per aspect; re-applying the same id replaces rather than stacks.
 * @param aspect which aspect of the mind this shifts
 * @param op     how the amount combines with everything else
 * @param amount the shift, read according to {@code op}
 */
public record AspectModifier(String id, ProfileAspect aspect, Op op, double amount) {

    /** How a modifier's amount combines with the species value and with its fellows. */
    public enum Op {
        /** A flat shift in the aspect's own units, summed with every {@code ADD} before multiplying. */
        ADD,
        /** A fraction of the SPECIES value, summed with its fellows: +10% three times is +30%. */
        ADD_FRACTION_OF_BASE,
        /** A fraction of the running total, applied one after another — compounds. */
        ADD_FRACTION_OF_TOTAL;
    }

    public AspectModifier {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a modifier needs an id naming what it came from");
        }
        if (aspect == null || op == null) {
            throw new IllegalArgumentException("a modifier needs an aspect and an operation");
        }
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException(id + ": " + amount + " is not a number");
        }
    }

    /** A flat shift, in the aspect's own units. */
    public static AspectModifier add(String id, ProfileAspect aspect, double amount) {
        return new AspectModifier(id, aspect, Op.ADD, amount);
    }

    /** A fraction of the species value — {@code 0.25} is "a quarter more than my kind". */
    public static AspectModifier fractionOfBase(String id, ProfileAspect aspect, double fraction) {
        return new AspectModifier(id, aspect, Op.ADD_FRACTION_OF_BASE, fraction);
    }

    /** A fraction of the running total, compounding with the others of its kind. */
    public static AspectModifier fractionOfTotal(String id, ProfileAspect aspect, double fraction) {
        return new AspectModifier(id, aspect, Op.ADD_FRACTION_OF_TOTAL, fraction);
    }

    /** How this reads in the effective-values readout: {@code +4}, {@code +25% of base}. */
    public String describe() {
        return switch (op) {
            case ADD -> plus(amount);
            case ADD_FRACTION_OF_BASE -> plus(amount * 100) + "% of base";
            case ADD_FRACTION_OF_TOTAL -> plus(amount * 100) + "% of total";
        };
    }

    /** Signed, and without the trailing zeros that make {@code +7.0000} out of "seven more". */
    private static String plus(double value) {
        String magnitude = value == Math.rint(value)
                ? Long.toString((long) Math.abs(value))
                : String.format(Locale.ROOT, "%s", Math.abs(value));
        return (value < 0 ? "-" : "+") + magnitude;
    }
}
