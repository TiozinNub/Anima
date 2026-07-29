package dev.luizloyola.anima.mod.config;

import java.util.List;
import java.util.Map;

/**
 * A top-level object in a config file whose KEYS are open — a table rather than a schema.
 *
 * <p>Knobs ({@link dev.luizloyola.anima.core.config.KnobSpec}) are a closed set, so an unknown key
 * is a typo worth reporting; flee weights are keyed by entity id, where an unknown id is a modded
 * mob, so that section validates values and never keys.
 *
 * <p>A mod hands its open sections to its own {@link ConfigFile}, which renders and loads them,
 * excludes them from the unknown-key report, and routes {@code config get}/{@code set} into them.
 * <b>A section belongs to the file it was registered with</b>: the danger table was once
 * written into whatever file was being saved, so the first consumer with its own {@code KnobSet}
 * would have found Anima's mob weights in its file.
 *
 * <p>Implementations own the live table; this only moves values across the JSON boundary, as
 * doubles.
 */
public interface OpenSection {

    /** The top-level object this owns, e.g. {@code "danger"}. Must not collide with a knob's. */
    String name();

    /** One sentence for the operator, written as this section's generated doc line. */
    String about();

    /** Every entry to write out, in the order they should appear. */
    Map<String, Double> entries();

    /**
     * Replaces the live table with what the file held. Every value has already been checked for
     * being a finite number; range is this section's business, so return an operator-facing
     * sentence for anything corrected. An empty map means the section was present and empty.
     */
    List<String> install(Map<String, Double> supplied);

    /** Back to the built-in table — what an absent file, and {@code config reset all}, mean. */
    void reset();

    /** The value in force for {@code key}, including whatever this section falls back to. */
    double get(String key);

    /** Installs one entry, returning the value that actually landed (this section may clamp). */
    double set(String key, double raw);
}
