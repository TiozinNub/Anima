package dev.luizloyola.anima.core.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What a single tunable is: a dotted key, a type, a default, safety bounds and a sentence for the
 * operator. One constant per knob, in an enum the owning mod declares; Anima's own set is
 * {@link Knob}.
 *
 * <p><b>An interface, not an enum</b>: the config stack — file schema, atomic write, unknown-key
 * report, completions, clamp, GUI — does not care whose knobs it drives, so every mod keeps its
 * own enum, file and command. An enum is the natural implementor but nothing requires one; a knob
 * does not know where its value is stored ({@link KnobSet#indexOf} decides), which lets a set mix
 * a hand-written enum with a generated family without collision. Everything below the accessors is
 * derived: six values per knob buy parsing, clamping, formatting and error phrasing.
 *
 * <p><b>Ranges are safety bounds, not taste.</b> {@code min}/{@code max} stop a hand-edited file
 * producing an agent that cannot function or a server that stalls; values inside are the
 * operator's business. {@link ConfigValues} clamps rather than rejects, so one bad line degrades
 * to a warning instead of failing the whole file.
 */
public interface KnobSpec {

    /**
     * What a knob holds. The three numeric kinds are stored as a double and the kind decides how
     * it reads back; {@link #STRING} and {@link #LIST} are stored as text beside them.
     *
     * <p><b>The text kinds are knob kinds only.</b> {@code ProfileAspect} and {@code NeedKind}
     * share this enum, and both are numeric dials — a species aspect or a need gauge holding text
     * has no meaning, so both reject them at registration.
     */
    enum Kind {
        DOUBLE, INT, BOOL, STRING,
        /**
         * Several short text values — a TOML array in the file, {@code ["one", "two"]}.
         *
         * <p><b>Stored as one comma-joined string</b>, in the same slot a {@link #STRING} would
         * use, and split at the edges by {@link #splitList}. That is what keeps a list kind from
         * rippling through {@link ConfigValues}, whose whole shape is two flat arrays: a third
         * would touch every read, every write and every round trip to add one knob. The joining is
         * an encoding and nothing else — {@code min}/{@code max} bound the joined text, an
         * operator sees only the array, and {@code config set} takes either form.
         *
         * <p>Only for values that cannot contain a comma; the encoding has no escape and is not
         * getting one. Today: the web debugger's accepted browser keys.
         */
        LIST;

        /** Whether values of this kind live in the double array rather than beside it. */
        public boolean numeric() {
            return this != STRING && this != LIST;
        }

        /** Whether values of this kind live in the text array beside it. */
        public boolean textual() {
            return !numeric();
        }
    }

    /** The dotted, snake_case key — the name used in the file, in commands, and in log messages. */
    String key();

    Kind kind();

    /** The documented default — also what an absent file means. Unused by the text kinds. */
    double def();

    /** For a text kind, the shortest legal value rather than a numeric floor. */
    double min();

    /** For a text kind, the longest legal value rather than a numeric ceiling. */
    double max();

    /**
     * A text knob's documented default — the text-side twin of {@link #def()}; the length bounds
     * stay on {@link #min()}/{@link #max()} rather than growing two more accessors every numeric
     * knob would have to answer for.
     */
    default String defText() {
        return "";
    }

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
     * The key split on its dots — one TOML table per segment before the last. A hand-written key
     * has one dot and nests one deep; a generated species family's is three or four
     * ({@code person.anima_settings.senses.radius}), so the file reads as a description of a
     * species rather than one flat object of long names.
     */
    default List<String> path() {
        return List.of(key().split("\\."));
    }

    /**
     * The outermost TOML table this knob nests under, and its GUI category — {@code "perception"}
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
            case STRING -> "text of " + (long) min() + " to " + (long) max() + " characters";
            case LIST -> "a comma-separated list, " + (long) max() + " characters in all";
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

    /**
     * {@link #clamp} for the text kinds: always returns a legal value, so a hand-edited file
     * degrades to the default instead of failing.
     *
     * <p>Out-of-range falls back to {@link #defText()} rather than truncating — half a URL is a
     * value that looks set and does not work, which is worse than the default it replaced. The
     * text is trimmed first: trailing whitespace in a quoted TOML string is invisible in an editor
     * and would otherwise be a length violation nobody can see.
     */
    default String sanitise(String raw) {
        if (kind() == Kind.LIST) {
            // Normalised rather than rejected: one stray space between entries would otherwise
            // take the fall-back below and drop the operator's whole list to the default.
            String joined = joinList(splitList(raw));
            return joined.length() <= (long) max() ? joined : defText();
        }
        String trimmed = raw.strip();
        return acceptsText(trimmed) ? trimmed : defText();
    }

    /** Whether {@code raw} would survive {@link #sanitise} untouched. */
    default boolean acceptsText(String raw) {
        if (!raw.equals(raw.strip())) {
            return false;
        }
        if (kind() == Kind.LIST) {
            return sanitise(raw).equals(raw);
        }
        return raw.length() >= (long) min() && raw.length() <= (long) max();
    }

    /**
     * Renders a text value the way it should appear in command output — a {@link Kind#LIST} as the
     * array the file holds rather than as its stored comma-joined spelling, which is an encoding
     * nobody reading {@code config show} should have to know about.
     */
    default String formatText(String value) {
        if (kind() == Kind.LIST) {
            List<String> entries = splitList(value);
            return entries.isEmpty()
                    ? "[]"
                    : entries.stream().map(entry -> "\"" + entry + "\"")
                            .collect(Collectors.joining(", ", "[", "]"));
        }
        return "\"" + value + "\"";
    }

    /**
     * What a {@link Kind#LIST} knob's stored text holds: comma-separated, each entry trimmed,
     * blanks dropped, first occurrence of a repeat kept.
     */
    static List<String> splitList(String text) {
        List<String> entries = new ArrayList<>();
        for (String part : text.split(",", -1)) {
            String entry = part.strip();
            if (!entry.isEmpty() && !entries.contains(entry)) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    /**
     * {@link #splitList}'s inverse. Round-trips through it rather than joining straight, so the
     * stored spelling is the same whoever built the list — a caller's stray blank or repeat cannot
     * write a value that reads back as something else.
     */
    static String joinList(Collection<String> entries) {
        return String.join(",", splitList(String.join(",", entries)));
    }

    /**
     * Renders a stored numeric value the way it should appear in the file and in command output.
     *
     * @throws UnsupportedOperationException for a {@link Kind#STRING} knob, which has no numeric
     *     value — the caller wants {@link #formatText} or {@link ConfigValues#text}. Thrown rather
     *     than fudged so a generic loop that forgot the kind fails where the bug is.
     */
    default String format(double value) {
        return switch (kind()) {
            case BOOL -> value != 0.0 ? "true" : "false";
            case INT -> Long.toString((long) value);
            case DOUBLE -> String.format(Locale.ROOT, "%s", value);
            case STRING, LIST -> throw new UnsupportedOperationException(
                    key() + " holds text — use formatText or ConfigValues.text");
        };
    }

    /** This knob's documented default, rendered for display, whatever the kind. */
    default String formatDefault() {
        return kind().textual() ? formatText(defText()) : format(def());
    }

    /**
     * Reads an operator-typed value ({@code "12"}, {@code "0.45"}, {@code "true"}) for this knob,
     * or empty when it isn't a value of this knob's kind. Out-of-range but well-formed input parses
     * fine — clamping is {@link ConfigValues}'s job, so the caller can report what it did.
     */
    default Optional<Double> parse(String text) {
        String trimmed = text.trim();
        if (kind().textual()) {
            return Optional.empty(); // no numeric reading exists; sanitise() is the text path
        }
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
