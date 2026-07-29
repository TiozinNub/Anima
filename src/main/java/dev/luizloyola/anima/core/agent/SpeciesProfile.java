package dev.luizloyola.anima.core.agent;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * One species, declared completely. The consumer writes one per kind of body it ships; Anima turns
 * it into that species' knob family and into the {@link AgentProfile} its agents read through.
 *
 * <pre>{@code
 * public static final SpeciesProfile PERSON = SpeciesProfile.of("person")
 *         .set(SENSES_RADIUS, 24)
 *         .set(SENSES_CONE_DEGREES, 120)
 *         ...
 *         .build();
 * }</pre>
 *
 * <p><b>Completeness is mandatory, and the one place in the config stack that hard-fails</b> — a
 * deliberate exception to {@code ConfigFile}'s never-take-a-server-down posture.
 * {@link Builder#build()} throws naming every aspect the species did not answer: Anima ships no
 * values, so an unanswered aspect has no default to fall back to. An aspect Anima grows fails every
 * consumer at mod init.
 *
 * <p><b>The species key is a free string</b>: a config path and a readout label, never branched on.
 *
 * <p>Immutable, and pure core; {@link SpeciesKnobs} turns it into knobs.
 */
public final class SpeciesProfile {

    private final String species;
    private final Map<ProfileAspect, Double> values;

    private SpeciesProfile(String species, Map<ProfileAspect, Double> values) {
        this.species = species;
        this.values = values;
    }

    /** Starts a declaration. The key lands in a config path, so it is lower-cased and checked. */
    public static Builder of(String species) {
        return new Builder(species);
    }

    /** What this species is called — a config section and a readout label. */
    public String species() {
        return species;
    }

    /** This species' declared value for {@code aspect}. Never absent: the builder saw to that. */
    public double get(ProfileAspect aspect) {
        return values.get(aspect);
    }

    /** Every aspect and its declared value, in schema order. */
    public Map<ProfileAspect, Double> values() {
        return values;
    }

    /**
     * A profile that reads these declared values directly, with no file behind it and no
     * modifiers on top. What a test rig or a bare tool wants; a body in a world reads through
     * {@link SpeciesKnobs#profile} instead, so that {@code config reload} retunes it.
     */
    public AgentProfile fixed() {
        return new AgentProfile() {
            @Override
            public String species() {
                return species;
            }

            @Override
            public double raw(ProfileAspect aspect) {
                return values.get(aspect);
            }

            @Override
            public String toString() {
                return "AgentProfile(" + species + ", as declared)";
            }
        };
    }

    @Override
    public String toString() {
        return "SpeciesProfile(" + species + ")";
    }

    /** Collects a complete declaration. Not thread-safe; build it once, in a static initializer. */
    public static final class Builder {

        private final String species;
        private final EnumMap<ProfileAspect, Double> values = new EnumMap<>(ProfileAspect.class);

        private Builder(String species) {
            if (species == null || species.isBlank()) {
                throw new IllegalArgumentException("species key must not be blank");
            }
            if (!species.equals(species.toLowerCase(Locale.ROOT)) || species.indexOf('.') >= 0) {
                throw new IllegalArgumentException(
                        "species key is a config path segment — lower case, no dots: " + species);
            }
            this.species = species;
        }

        /**
         * Answers one aspect.
         *
         * @throws IllegalArgumentException if the value is outside the aspect's legal range, or if
         *     the same aspect is answered twice — both are the declaring mod's bug, and a silent
         *     clamp here would hide a species that cannot do what its author thinks it can.
         */
        public Builder set(ProfileAspect aspect, double value) {
            if (!aspect.accepts(value)) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "%s: %s is not a legal value for %s (expected %s in [%s, %s])",
                        species, value, aspect.key(), aspect.kind().name().toLowerCase(Locale.ROOT),
                        aspect.min(), aspect.max()));
            }
            if (values.put(aspect, value) != null) {
                throw new IllegalArgumentException(species + " declares " + aspect.key() + " twice");
            }
            return this;
        }

        /** Answers a {@link dev.luizloyola.anima.core.config.KnobSpec.Kind#BOOL} aspect. */
        public Builder set(ProfileAspect aspect, boolean value) {
            return set(aspect, value ? 1.0 : 0.0);
        }

        /**
         * The finished species.
         *
         * @throws IllegalStateException naming every aspect left unanswered — see the class note
         *     on why this is the one hard failure in the config stack.
         */
        public SpeciesProfile build() {
            if (values.size() != ProfileAspect.values().length) {
                StringJoiner missing = new StringJoiner(", ");
                for (ProfileAspect aspect : ProfileAspect.values()) {
                    if (!values.containsKey(aspect)) {
                        missing.add(aspect.key());
                    }
                }
                throw new IllegalStateException(species + " is missing " + missing);
            }
            // An EnumMap copy, not Map.copyOf: iteration order is schema order. That is what the
            // generated knob family, the file and the readout all present.
            return new SpeciesProfile(species,
                    Collections.unmodifiableMap(new EnumMap<>(values)));
        }
    }
}
