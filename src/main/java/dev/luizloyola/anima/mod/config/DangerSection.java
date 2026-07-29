package dev.luizloyola.anima.mod.config;

import dev.luizloyola.anima.core.brain.instinct.Danger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Anima's per-species flee weights as a config section — how frightening each kind of mob is,
 * before a perceiving body's own {@code danger.*} multipliers apply.
 *
 * <p>Open-keyed: any entity id is a valid key, an id Anima has never heard of is a modded mob
 * rather than a typo, and anything unlisted falls back to {@value Danger#DEFAULT_KEY}. Values
 * clamp to a band because there is no knob to carry a range — see {@link OpenSection}.
 *
 * <p>The last global thing about danger: per-body multipliers ({@code danger.melee_mult} and
 * friends) are already {@link dev.luizloyola.anima.core.agent.ProfileAspect}s, and this table goes
 * per-species when the body mods take it over.
 */
public final class DangerSection implements OpenSection {

    private static final double MIN = 0.0;
    private static final double MAX = 8.0;

    @Override
    public String name() {
        return "danger";
    }

    @Override
    public String about() {
        return "Per-species flee weights. Any entity id is a valid key; mobs not listed use \""
                + Danger.DEFAULT_KEY + "\".";
    }

    @Override
    public Map<String, Double> entries() {
        Map<String, Double> entries = new LinkedHashMap<>();
        entries.put(Danger.DEFAULT_KEY, Danger.weight(Danger.DEFAULT_KEY));
        Danger.table().keySet().stream()
                .filter(species -> !species.equals(Danger.DEFAULT_KEY))
                .sorted()
                .forEach(species -> entries.put(species, Danger.weight(species)));
        return entries;
    }

    @Override
    public List<String> install(Map<String, Double> supplied) {
        List<String> problems = new java.util.ArrayList<>();
        Map<String, Double> overrides = new LinkedHashMap<>();
        Danger.reset();
        supplied.forEach((species, raw) -> {
            double clamped = clamp(raw);
            if (clamped != raw) {
                problems.add(String.format(Locale.ROOT,
                        "danger.%s: %s is out of range [%s, %s] — using %s",
                        species, raw, MIN, MAX, clamped));
            }
            overrides.put(species, clamped);
        });
        Danger.install(overrides);
        return List.copyOf(problems);
    }

    @Override
    public void reset() {
        Danger.reset();
    }

    @Override
    public double get(String key) {
        return Danger.weight(key);
    }

    @Override
    public double set(String key, double raw) {
        double clamped = clamp(raw);
        Map<String, Double> overrides = new LinkedHashMap<>(Danger.table());
        overrides.put(key, clamped);
        Danger.install(overrides);
        return clamped;
    }

    private static double clamp(double raw) {
        return Math.max(MIN, Math.min(MAX, raw));
    }
}
