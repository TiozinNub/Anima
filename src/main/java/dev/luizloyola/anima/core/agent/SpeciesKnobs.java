package dev.luizloyola.anima.core.agent;

import dev.luizloyola.anima.core.config.ConfigStore;
import dev.luizloyola.anima.core.config.KnobSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One species' declaration, turned into tunables an operator can reach — a {@link KnobSpec} per
 * {@link ProfileAspect}, landing in the declaring mod's own config file:
 *
 * <pre>{@code
 * public static final SpeciesKnobs PERSON = SpeciesKnobs.of(PERSON_PROFILE);
 *
 * public static final KnobSet SET = KnobSet.of("autarkia", "Autarkia",
 *         Stream.concat(Arrays.stream(AutarkiaKnob.values()), PERSON.knobs().stream()).toList());
 * }</pre>
 *
 * <p><b>The whole schema is generated, never a hand-picked subset</b>, so a new aspect reaches
 * every species in every consumer with no consumer edit.
 *
 * <p><b>Keys are {@code <species>.anima_settings.<aspect>}</b> — the namespace segment keeps a
 * generated knob from colliding with a consumer's own species-scoped knobs. The file nests on every
 * dot.
 *
 * <p><b>The declared value is the knob's default</b>: a fresh file is the species as declared,
 * {@code config reset} returns to it, and a deleted key falls back and is rewritten with its doc
 * line. So "all species declare all aspects" holds at both layers — the code cannot build an
 * incomplete {@link SpeciesProfile}, and the file cannot be missing a key.
 */
public final class SpeciesKnobs {

    /** The path segment Anima reserves inside a consumer's species section. */
    public static final String NAMESPACE = "anima_settings";

    /** Where Anima keeps the one label per aspect that every species' knob points at. */
    public static final String LANG_ROOT = "anima.config.aspect.";

    private final SpeciesProfile declared;
    private final Map<ProfileAspect, KnobSpec> byAspect;
    private final List<KnobSpec> knobs;

    private SpeciesKnobs(SpeciesProfile declared) {
        this.declared = declared;
        Map<ProfileAspect, KnobSpec> byAspect = new EnumMap<>(ProfileAspect.class);
        List<KnobSpec> knobs = new ArrayList<>(ProfileAspect.values().length);
        for (ProfileAspect aspect : ProfileAspect.values()) {
            KnobSpec knob = new SpeciesKnob(
                    declared.species() + "." + NAMESPACE + "." + aspect.key(),
                    aspect,
                    declared.get(aspect));
            byAspect.put(aspect, knob);
            knobs.add(knob);
        }
        this.byAspect = Collections.unmodifiableMap(byAspect);
        this.knobs = List.copyOf(knobs);
    }

    public static SpeciesKnobs of(SpeciesProfile declared) {
        return new SpeciesKnobs(declared);
    }

    /** What was declared, before any operator touched the file. */
    public SpeciesProfile declared() {
        return declared;
    }

    /** Every generated knob, in schema order — hand this to {@code KnobSet.of}. */
    public List<KnobSpec> knobs() {
        return knobs;
    }

    /** The knob carrying one aspect, for a readout that wants to name the key an operator edits. */
    public KnobSpec knob(ProfileAspect aspect) {
        return byAspect.get(aspect);
    }

    /**
     * The profile this species' bodies read through — a LIVE view over {@code store}, so an organ
     * may hold it for a body's whole life and a {@code config reload} still retunes it.
     *
     * @throws IllegalArgumentException if this family was never added to the store's set, which
     *     would otherwise be a body reading defaults forever while the operator edits a file that
     *     does nothing.
     */
    public AgentProfile profile(ConfigStore store) {
        for (KnobSpec knob : knobs) {
            if (store.set().byKey(knob.key()).isEmpty()) {
                throw new IllegalArgumentException(knob.key() + " is not a knob of "
                        + store.set() + " — add " + declared.species()
                        + "'s generated family to the set that owns this store");
            }
        }
        return new AgentProfile() {
            @Override
            public String species() {
                return declared.species();
            }

            @Override
            public double raw(ProfileAspect aspect) {
                return store.get().get(byAspect.get(aspect));
            }

            @Override
            public String toString() {
                return "AgentProfile(" + declared.species() + ", from " + store.set().id() + ")";
            }
        };
    }

    /** A generated knob — everything but the default is the aspect's, which is the schema. */
    private record SpeciesKnob(String key, ProfileAspect aspect, double def) implements KnobSpec {

        @Override
        public Kind kind() {
            return aspect.kind();
        }

        @Override
        public double min() {
            return aspect.min();
        }

        @Override
        public double max() {
            return aspect.max();
        }

        @Override
        public String doc() {
            return aspect.doc();
        }

        @Override
        public String langKey(dev.luizloyola.anima.core.config.KnobSet set) {
            return LANG_ROOT + aspect.key(); // Anima's word for it, whoever is displaying it
        }

        @Override
        public String category() {
            return section() + "." + aspect.section(); // "person.senses"
        }
    }
}
