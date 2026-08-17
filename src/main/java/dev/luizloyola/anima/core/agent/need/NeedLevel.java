package dev.luizloyola.anima.core.agent.need;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.config.KnobSpec.Kind;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One named step of a need ({@code peckish}, {@code alone}, {@code starving}), and the three
 * per-species numbers behind it.
 *
 * <p><b>A level's {@code value} is a boundary; its {@code pressure} is an anchor.</b> You
 * <em>are</em> this level once the need's value has reached {@code value}, moving away from comfort
 * — with {@code sated} at 20 and {@code peckish} at 14, food 17 is still sated. The pressure there
 * is one corner of the ramp, which goes on rising between corners, so wanting starts before the
 * word for it does.
 *
 * <p><b>The numbers are per species and live in config</b>, at
 * {@code needs.<need>.<level>.{value,pressure,tolerance}}. The labels are shared (see
 * {@code SpeciesKnobs}) so forty generated aspects cost three translations rather than eighty.
 */
public final class NeedLevel {

    private final String key;
    private final ProfileAspect value;
    private final ProfileAspect pressure;
    private final ProfileAspect tolerance;

    /**
     * The need this belongs to — where {@link #nameKey()} gets its namespace. Written once, from
     * that need's own constructor, because a level is declared while its need is still being built
     * and {@code lang()} may be said after the levels are.
     */
    private NeedKind need;

    NeedLevel(NeedKind need, String key, double axisMin, double axisMax,
            double defaultValue, double defaultPressure, double defaultTolerance) {
        this.key = Objects.requireNonNull(key, "key");
        String path = "needs." + need.key() + "." + key + ".";
        this.value = ProfileAspect.register(path + "value", need.kind(), axisMin, axisMax,
                String.format(Locale.ROOT, "The %s at which this body is %s — that value and "
                        + "further from comfort. Declared in %s.",
                        need.key(), key, need.unit()));
        this.pressure = ProfileAspect.register(path + "pressure", Kind.DOUBLE, 0.0, 1.0,
                String.format(Locale.ROOT, "How badly a %s body wants something done about its "
                        + "%s, at that exact boundary. The ramp goes on rising past it.",
                        key, need.key()));
        this.tolerance = ProfileAspect.register(path + "tolerance", Kind.DOUBLE, -1.0, 100_000.0,
                String.format(Locale.ROOT, "What a %s body will spend on its %s, in walk blocks. "
                        + "-1 is unbounded: pay anything.", key, need.key()));
        this.defaultValue = defaultValue;
        this.defaultPressure = defaultPressure;
        this.defaultTolerance = defaultTolerance;
    }

    private final double defaultValue;
    private final double defaultPressure;
    private final double defaultTolerance;

    void attach(NeedKind owner) {
        this.need = owner;
    }

    /** Stable id — the config path segment, the lang key, and what a readout prints. */
    public String key() {
        return key;
    }

    /** What to call this level: {@code anima.needs.vigor.healthy.name} → "Healthy". */
    public String nameKey() {
        return need.lang() + "." + key + ".name";
    }

    /**
     * How to say a body <em>is</em> this, mid-sentence: {@code …person_is} → "healthy", for
     * {@code "John is healthy because:"}. A second string rather than lower-casing the first,
     * because which words a language capitalises mid-sentence is not ours to guess.
     */
    public String personIsKey() {
        return need.lang() + "." + key + ".person_is";
    }

    /** The boundary at which a body becomes this level, in the source's own units. */
    public double value(AgentProfile profile) {
        return profile.d(value);
    }

    /** The ramp's pressure anchor at that boundary. */
    public double pressure(AgentProfile profile) {
        return profile.d(pressure);
    }

    /** What a body at this level will spend, in walk blocks; negative is unbounded. */
    public double tolerance(AgentProfile profile) {
        return profile.d(tolerance);
    }

    /** The three aspects, for a species declaring itself — see {@link NeedKind#declare}. */
    public ProfileAspect valueAspect() {
        return value;
    }

    public ProfileAspect pressureAspect() {
        return pressure;
    }

    public ProfileAspect toleranceAspect() {
        return tolerance;
    }

    /**
     * What this level's three aspects default to — the need's own declaration of its shape, which
     * a species may override and mostly does not. See {@link NeedKind#levelDefaults()}.
     */
    public Map<ProfileAspect, Double> defaults() {
        return Map.of(value, defaultValue, pressure, defaultPressure, tolerance, defaultTolerance);
    }

    @Override
    public String toString() {
        return key;
    }
}
