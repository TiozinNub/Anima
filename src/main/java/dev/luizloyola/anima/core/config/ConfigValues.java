package dev.luizloyola.anima.core.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An immutable set of values for every knob in one {@link KnobSet}. Values sit in a flat array
 * indexed by {@link KnobSpec#ordinal()}, so adding a knob to a set's enum is the only edit a new
 * tunable needs: this class never names one.
 *
 * <p><b>Always valid by construction</b>: every entry point runs {@link KnobSpec#clamp}, so loading
 * a hand-edited file cannot fail — {@link #from} returns the nearest legal configuration plus what
 * it had to correct, so a caller warns about a typo instead of refusing to boot a server.
 *
 * <p>Pure core: no Minecraft, no Fabric, no I/O. JSON on disk is the mod layer's job; the live one
 * is {@link ConfigStore}'s.
 */
public final class ConfigValues {

    private final KnobSet set;
    private final double[] values;

    private ConfigValues(KnobSet set, double[] values) {
        this.set = set;
        this.values = values;
    }

    public KnobSet set() {
        return set;
    }

    public static ConfigValues defaults(KnobSet set) {
        return new ConfigValues(set, defaultValues(set));
    }

    /** The raw stored value. Prefer {@link #i}/{@link #b}/{@link #d} at call sites. */
    public double get(KnobSpec knob) {
        return values[knob.ordinal()];
    }

    /** A {@link KnobSpec.Kind#DOUBLE} knob. */
    public double d(KnobSpec knob) {
        return values[knob.ordinal()];
    }

    /** An {@link KnobSpec.Kind#INT} knob, already rounded by the clamp. */
    public int i(KnobSpec knob) {
        return (int) values[knob.ordinal()];
    }

    /** A {@link KnobSpec.Kind#BOOL} knob. */
    public boolean b(KnobSpec knob) {
        return values[knob.ordinal()] != 0.0;
    }

    /** This configuration with one knob changed (clamped), leaving the original untouched. */
    public ConfigValues with(KnobSpec knob, double raw) {
        double[] copy = values.clone();
        copy[knob.ordinal()] = knob.clamp(raw);
        return new ConfigValues(set, copy);
    }

    /** Every knob and its current value — the encoding side of the JSON round trip. */
    public Map<KnobSpec, Double> toMap() {
        Map<KnobSpec, Double> map = new LinkedHashMap<>();
        for (KnobSpec knob : set.knobs()) {
            map.put(knob, values[knob.ordinal()]);
        }
        return map;
    }

    /** Whether this knob still sits at its default — what {@code show} marks. */
    public boolean isDefault(KnobSpec knob) {
        return values[knob.ordinal()] == knob.def();
    }

    /**
     * Builds a configuration from whatever a file (or a command, or a GUI) supplied. Knobs absent
     * from {@code raw} keep their default; knobs present but out of range are clamped and reported.
     * The returned {@link Loaded#problems()} are operator-facing sentences, empty when the input was
     * already clean.
     */
    public static Loaded from(KnobSet set, Map<KnobSpec, Double> raw) {
        double[] built = defaultValues(set);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<KnobSpec, Double> entry : raw.entrySet()) {
            KnobSpec knob = entry.getKey();
            double supplied = entry.getValue() == null ? knob.def() : entry.getValue();
            double clamped = knob.clamp(supplied);
            built[knob.ordinal()] = clamped;
            if (clamped != supplied) {
                problems.add(String.format(Locale.ROOT, "%s: %s is out of range [%s, %s] — using %s",
                        knob.key(), knob.format(supplied), knob.format(knob.min()),
                        knob.format(knob.max()), knob.format(clamped)));
            }
        }
        return new Loaded(new ConfigValues(set, built), List.copyOf(problems));
    }

    /** A configuration plus whatever had to be corrected to make it legal. @see #from */
    public record Loaded(ConfigValues config, List<String> problems) {
        public Loaded {
            problems = List.copyOf(problems);
        }

        /** True when the input needed no correction — nothing to warn about. */
        public boolean clean() {
            return problems.isEmpty();
        }
    }

    private static double[] defaultValues(KnobSet set) {
        double[] built = new double[set.size()];
        for (KnobSpec knob : set.knobs()) {
            built[knob.ordinal()] = knob.def();
        }
        return built;
    }

    /** Key/value lines for the knobs that differ from the defaults — the compact "what's custom". */
    public List<String> describeOverrides() {
        List<String> lines = new ArrayList<>();
        for (KnobSpec knob : set.knobs()) {
            if (!isDefault(knob)) {
                lines.add(knob.key() + " = " + knob.format(get(knob))
                        + " (default " + knob.format(knob.def()) + ")");
            }
        }
        return Collections.unmodifiableList(lines);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConfigValues that
                && set.equals(that.set)
                && Arrays.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return 31 * set.hashCode() + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        List<String> overrides = describeOverrides();
        return overrides.isEmpty()
                ? "ConfigValues(" + set.id() + ": defaults)"
                : "ConfigValues(" + set.id() + ")" + overrides;
    }
}
