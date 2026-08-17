package dev.luizloyola.anima.core.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An immutable set of values for every knob in one {@link KnobSet}. Values live in a flat array at
 * the slot {@link KnobSet#indexOf} assigns, so adding a knob to a set's enum is the only edit a new
 * tunable needs.
 *
 * <p><b>Always valid by construction.</b> Every entry point runs {@link KnobSpec#clamp}, so loading
 * a hand-edited file cannot fail: {@link #from} returns the nearest legal configuration alongside
 * what it corrected, and a typo need not refuse to boot a server.
 *
 * <p>Pure core: no Minecraft, no Fabric, no I/O. TOML is the mod layer's job; {@link ConfigStore}
 * holds the live one.
 */
public final class ConfigValues {

    private final KnobSet set;
    private final double[] values;
    /**
     * Text for the {@link KnobSpec.Kind#STRING} knobs, at the same slots. A parallel array rather
     * than a boxed {@code Object[]}: every numeric read stays a primitive load on a hot path, and
     * the slot a knob owns is still whatever {@link KnobSet#indexOf} says. Entries for numeric
     * knobs are never read.
     */
    private final String[] texts;

    private ConfigValues(KnobSet set, double[] values, String[] texts) {
        this.set = set;
        this.values = values;
        this.texts = texts;
    }

    public KnobSet set() {
        return set;
    }

    public static ConfigValues defaults(KnobSet set) {
        return new ConfigValues(set, defaultValues(set), defaultTexts(set));
    }

    /** The raw stored value. Prefer {@link #i}/{@link #b}/{@link #d} at call sites. */
    public double get(KnobSpec knob) {
        return values[set.indexOf(knob)];
    }

    /** A {@link KnobSpec.Kind#DOUBLE} knob. */
    public double d(KnobSpec knob) {
        return values[set.indexOf(knob)];
    }

    /** An {@link KnobSpec.Kind#INT} knob, already rounded by the clamp. */
    public int i(KnobSpec knob) {
        return (int) values[set.indexOf(knob)];
    }

    /** A {@link KnobSpec.Kind#BOOL} knob. */
    public boolean b(KnobSpec knob) {
        return values[set.indexOf(knob)] != 0.0;
    }

    /** A {@link KnobSpec.Kind#STRING} knob. Never null — an unset one reads as its default. */
    public String s(KnobSpec knob) {
        return texts[set.indexOf(knob)];
    }

    /**
     * This knob's current value rendered for display, whatever its kind — what {@code config show},
     * {@code config get} and {@link #describeOverrides} print, so none of them has to branch.
     */
    public String text(KnobSpec knob) {
        return knob.kind().textual() ? knob.formatText(s(knob)) : knob.format(get(knob));
    }

    /**
     * This configuration with one numeric knob changed (clamped), leaving the original untouched.
     *
     * @throws IllegalArgumentException for a {@link KnobSpec.Kind#STRING} knob — its value would
     *     land in the double array where nothing reads it, silently keeping the old text.
     */
    public ConfigValues with(KnobSpec knob, double raw) {
        if (knob.kind().textual()) {
            throw new IllegalArgumentException(knob.key() + " holds text — use with(knob, String)");
        }
        double[] copy = values.clone();
        copy[set.indexOf(knob)] = knob.clamp(raw);
        return new ConfigValues(set, copy, texts);
    }

    /**
     * This configuration with one text knob changed (sanitised), leaving the original untouched.
     *
     * @throws IllegalArgumentException for a numeric knob, for the mirror of the reason above.
     */
    public ConfigValues with(KnobSpec knob, String raw) {
        if (!knob.kind().textual()) {
            throw new IllegalArgumentException(knob.key() + " holds a number — use with(knob, double)");
        }
        String[] copy = texts.clone();
        copy[set.indexOf(knob)] = knob.sanitise(raw);
        return new ConfigValues(set, values, copy);
    }

    /** Every NUMERIC knob and its current value — the encoding side of the file round trip. */
    public Map<KnobSpec, Double> toMap() {
        Map<KnobSpec, Double> map = new LinkedHashMap<>();
        for (KnobSpec knob : set.knobs()) {
            if (knob.kind().numeric()) {
                map.put(knob, values[set.indexOf(knob)]);
            }
        }
        return map;
    }

    /** Every {@link KnobSpec.Kind#STRING} knob and its current text — {@link #toMap}'s other half. */
    public Map<KnobSpec, String> toTextMap() {
        Map<KnobSpec, String> map = new LinkedHashMap<>();
        for (KnobSpec knob : set.knobs()) {
            if (knob.kind().textual()) {
                map.put(knob, texts[set.indexOf(knob)]);
            }
        }
        return map;
    }

    /** Whether this knob still sits at its default — what {@code show} marks. */
    public boolean isDefault(KnobSpec knob) {
        int slot = set.indexOf(knob);
        return knob.kind().textual()
                ? texts[slot].equals(knob.defText())
                : values[slot] == knob.def();
    }

    /**
     * Builds a configuration from whatever a file (or a command, or a GUI) supplied. Knobs absent
     * from {@code raw} keep their default; knobs present but out of range are clamped and reported.
     * The returned {@link Loaded#problems()} are operator-facing sentences, empty when the input was
     * already clean.
     */
    public static Loaded from(KnobSet set, Map<KnobSpec, Double> raw) {
        return from(set, raw, Map.of());
    }

    /**
     * {@link #from(KnobSet, Map)} with the {@link KnobSpec.Kind#STRING} knobs too. Two maps rather
     * than one of {@code Object}: the kinds are disjoint by knob, and a single map would make every
     * caller cast and every mistake a runtime one.
     */
    public static Loaded from(KnobSet set, Map<KnobSpec, Double> raw, Map<KnobSpec, String> rawText) {
        double[] built = defaultValues(set);
        String[] builtText = defaultTexts(set);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<KnobSpec, Double> entry : raw.entrySet()) {
            KnobSpec knob = entry.getKey();
            double supplied = entry.getValue() == null ? knob.def() : entry.getValue();
            double clamped = knob.clamp(supplied);
            built[set.indexOf(knob)] = clamped;
            if (clamped != supplied) {
                problems.add(String.format(Locale.ROOT, "%s: %s is out of range [%s, %s] — using %s",
                        knob.key(), knob.format(supplied), knob.format(knob.min()),
                        knob.format(knob.max()), knob.format(clamped)));
            }
        }
        for (Map.Entry<KnobSpec, String> entry : rawText.entrySet()) {
            KnobSpec knob = entry.getKey();
            String supplied = entry.getValue() == null ? knob.defText() : entry.getValue();
            String sane = knob.sanitise(supplied);
            builtText[set.indexOf(knob)] = sane;
            if (!sane.equals(supplied)) {
                problems.add(String.format(Locale.ROOT, "%s: %s is not %s — using %s",
                        knob.key(), knob.formatText(supplied), knob.expects(),
                        knob.formatText(sane)));
            }
        }
        return new Loaded(new ConfigValues(set, built, builtText), List.copyOf(problems));
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
            if (knob.kind().numeric()) {
                built[set.indexOf(knob)] = knob.def();
            }
        }
        return built;
    }

    /**
     * Text defaults for every slot. Numeric slots hold {@code ""} rather than null, so
     * {@link #s} can never return null and {@link Arrays#equals} needs no null handling.
     */
    private static String[] defaultTexts(KnobSet set) {
        String[] built = new String[set.size()];
        Arrays.fill(built, "");
        for (KnobSpec knob : set.knobs()) {
            if (knob.kind().textual()) {
                built[set.indexOf(knob)] = knob.defText();
            }
        }
        return built;
    }

    /** Key/value lines for the knobs that differ from the defaults — the compact "what's custom". */
    public List<String> describeOverrides() {
        List<String> lines = new ArrayList<>();
        for (KnobSpec knob : set.knobs()) {
            if (!isDefault(knob)) {
                lines.add(knob.key() + " = " + text(knob)
                        + " (default " + knob.formatDefault() + ")");
            }
        }
        return Collections.unmodifiableList(lines);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConfigValues that
                && set.equals(that.set)
                && Arrays.equals(values, that.values)
                && Arrays.equals(texts, that.texts);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * set.hashCode() + Arrays.hashCode(values)) + Arrays.hashCode(texts);
    }

    @Override
    public String toString() {
        List<String> overrides = describeOverrides();
        return overrides.isEmpty()
                ? "ConfigValues(" + set.id() + ": defaults)"
                : "ConfigValues(" + set.id() + ")" + overrides;
    }
}
