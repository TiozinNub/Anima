package dev.luizloyola.anima.core.config;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What a single tunable is: a dotted key, a type, a default, safety bounds and a sentence for the
 * operator. Anima's own set is {@link Knob}.
 *
 * <p><b>An interface, not an enum,</b> because none of the config stack cares whose knobs it
 * drives: every mod keeps its own enum, file and command. A knob never knows where its value is
 * stored — {@link KnobSet#indexOf} decides, so a hand-written enum and a generated family can share
 * one set.
 *
 * <p>Everything below the accessors is derived: six values per knob buy parsing, clamping,
 * formatting and error phrasing.
 *
 * <p><b>Ranges are safety bounds, not taste:</b> they only stop a hand-edited file producing an
 * unusable agent or a stalled server, and {@link ConfigValues} clamps rather than rejects.
 */
public interface KnobSpec {

    /** What a knob holds. Everything is stored as a double; the kind decides how it reads back. */
    enum Kind { DOUBLE, INT, BOOL }

    /** The dotted, snake_case key — the name used in JSON, in commands, and in log messages. */
    String key();

    Kind kind();

    /** The documented default — also what an absent file means. */
    double def();

    double min();

    double max();

    /** One sentence for the operator — shown by {@code config show} and in the GUI. */
    String doc();

    /**
     * The constant's own name, for diagnostics. Derived from the key; an enum keeps its own for
     * free, since a class method always beats an interface default.
     */
    default String name() {
        return key().toUpperCase(Locale.ROOT).replace('.', '_');
    }

    /**
     * The key split on its dots — the nesting the file is written with, one object per segment
     * before the last. A hand-written key nests one deep, a generated species family three or four
     * ({@code person.anima_settings.senses.radius}), so the file reads as a species.
     */
    default List<String> path() {
        return List.of(key().split("\\."));
    }

    /**
     * The outermost JSON object this knob nests under, and its GUI category — {@code "perception"}
     * for {@code perception.*}, the species for a generated family.
     */
    default String section() {
        return key().substring(0, key().indexOf('.'));
    }

    /** The knob's own name within the object that immediately holds it. */
    default String leaf() {
        return key().substring(key().lastIndexOf('.') + 1);
    }

    /**
     * Where this knob's GUI label lives — the owning mod's namespace, right for a knob that mod
     * wrote. A GENERATED knob overrides it to point at Anima's single label for the aspect it
     * carries: the aspects of a mind are Anima's vocabulary, and a set of labels per species per
     * consumer would be thirty translations each, drifting apart.
     */
    default String langKey(KnobSet set) {
        return set.langRoot() + ".option." + key();
    }

    /**
     * Which GUI tab this belongs on. The outermost path segment, except for a generated family,
     * where one species' whole schema on one tab would be a wall — those group by species and by
     * what part of a mind the aspect describes.
     */
    default String category() {
        return section();
    }

    /** What this knob will accept, phrased for an error message ("a whole number"). */
    default String expects() {
        return switch (kind()) {
            case BOOL -> "true or false";
            case INT -> "a whole number in [" + format(min()) + ", " + format(max()) + "]";
            case DOUBLE -> "a number in [" + format(min()) + ", " + format(max()) + "]";
        };
    }

    /** Constrains {@code raw} to this knob's range, rounding to whole numbers for INT and BOOL. */
    default double clamp(double raw) {
        double bounded = Math.min(max(), Math.max(min(), raw));
        return kind() == Kind.DOUBLE ? bounded : Math.rint(bounded);
    }

    /** Whether {@code raw} would survive {@link #clamp} untouched — the "is this file sane" check. */
    default boolean accepts(double raw) {
        return Double.isFinite(raw) && clamp(raw) == raw;
    }

    /** Renders a stored value the way it should appear in JSON and in command output. */
    default String format(double value) {
        return switch (kind()) {
            case BOOL -> value != 0.0 ? "true" : "false";
            case INT -> Long.toString((long) value);
            case DOUBLE -> String.format(Locale.ROOT, "%s", value);
        };
    }

    /**
     * Reads an operator-typed value ({@code "12"}, {@code "0.45"}, {@code "true"}) for this knob,
     * or empty when it isn't a value of this knob's kind. Out-of-range but well-formed input parses
     * fine — clamping is {@link ConfigValues}'s job, so the caller can report what it did.
     */
    default Optional<Double> parse(String text) {
        String trimmed = text.trim();
        if (kind() == Kind.BOOL) {
            if (trimmed.equalsIgnoreCase("true")) {
                return Optional.of(1.0);
            }
            if (trimmed.equalsIgnoreCase("false")) {
                return Optional.of(0.0);
            }
            return Optional.empty();
        }
        try {
            double parsed = Double.parseDouble(trimmed);
            if (!Double.isFinite(parsed)) {
                return Optional.empty();
            }
            return kind() == Kind.INT && parsed != Math.rint(parsed)
                    ? Optional.empty()
                    : Optional.of(parsed);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
