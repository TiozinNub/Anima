package dev.luizloyola.anima.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.config.ConfigStore;
import dev.luizloyola.anima.core.config.KnobSet;
import dev.luizloyola.anima.core.config.KnobSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The seam between a mind and the body wearing it: what a body is like, and who answers. */
class AgentProfileTest {

    /** A consumer's set: nothing of its own yet, just the species it ships. */
    private static KnobSet setFor(SpeciesKnobs knobs) {
        return KnobSet.of("testmod", "Test Mod", knobs.knobs());
    }

    @Test
    @DisplayName("a species answers every aspect, and the profile hands back what it declared")
    void theProfileReadsTheDeclaration() {
        SpeciesKnobs knobs = SpeciesKnobs.of(TestSpecies.BIPED);
        ConfigStore store = new ConfigStore(setFor(knobs));
        AgentProfile profile = knobs.profile(store);

        assertEquals("test_biped", profile.species());
        for (ProfileAspect aspect : ProfileAspect.values()) {
            assertEquals(TestSpecies.BIPED.get(aspect), profile.raw(aspect), aspect.key());
        }
        assertTrue(profile.b(ProfileAspect.BODY_CAN_SWIM));
        assertEquals(2, profile.i(ProfileAspect.BODY_HEIGHT));
    }

    @Test
    @DisplayName("it is a live view, not a snapshot — a reload retunes a body already in the world")
    void theProfileReadsThroughToTheStore() {
        SpeciesKnobs knobs = SpeciesKnobs.of(TestSpecies.BIPED);
        KnobSet set = setFor(knobs);
        ConfigStore store = new ConfigStore(set);
        AgentProfile held = knobs.profile(store); // as an organ holds it, for the body's whole life

        KnobSpec knob = knobs.knob(ProfileAspect.SENSES_RADIUS);
        int before = held.i(ProfileAspect.SENSES_RADIUS);

        store.install(set.defaults().with(knob, before + 8.0));
        assertEquals(before + 8, held.i(ProfileAspect.SENSES_RADIUS),
                "the same object must see the new configuration; caching it would strand the agent");

        store.reset();
        assertEquals(before, held.i(ProfileAspect.SENSES_RADIUS));
    }

    @Test
    @DisplayName("an incomplete species does not build, and the message names what is missing")
    void anIncompleteSpeciesFailsLoudly() {
        SpeciesProfile.Builder half = SpeciesProfile.of("half")
                .set(ProfileAspect.SENSES_RADIUS, 10);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, half::build);
        assertTrue(thrown.getMessage().startsWith("half is missing "));
        assertTrue(thrown.getMessage().contains(ProfileAspect.MIND_PREEMPT.key()),
                "the message must name the aspects, not just the count: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a declared value outside the aspect's range is the mod's bug, not the operator's")
    void anIllegalDeclarationThrowsRatherThanClamping() {
        // ConfigValues clamps a hand-edited file; a declaration is code and must throw.
        assertThrows(IllegalArgumentException.class,
                () -> SpeciesProfile.of("wolf").set(ProfileAspect.SENSES_RADIUS, 900));
        assertThrows(IllegalArgumentException.class,
                () -> SpeciesProfile.of("wolf").set(ProfileAspect.BODY_HEIGHT, 1.5));
        assertThrows(IllegalArgumentException.class, () -> SpeciesProfile.of("Wolf"));
        assertThrows(IllegalArgumentException.class, () -> SpeciesProfile.of("my.wolf"));
    }

    @Test
    @DisplayName("the same aspect answered twice is a mistake worth naming")
    void answeringTwiceThrows() {
        SpeciesProfile.Builder builder = SpeciesProfile.of("wolf")
                .set(ProfileAspect.SENSES_RADIUS, 10);
        assertThrows(IllegalArgumentException.class,
                () -> builder.set(ProfileAspect.SENSES_RADIUS, 12));
    }

    @Test
    @DisplayName("a family the store never adopted is caught at wiring time, not silently ignored")
    void aFamilyOutsideItsStoreIsRejected() {
        SpeciesKnobs knobs = SpeciesKnobs.of(TestSpecies.BIPED);
        ConfigStore strangersStore = new ConfigStore(
                KnobSet.of("other", "Other", dev.luizloyola.anima.core.config.Knob.values()));

        assertThrows(IllegalArgumentException.class, () -> knobs.profile(strangersStore));
    }

    @Test
    @DisplayName("generated keys are namespaced per species, so two species never collide")
    void generatedKeysAreNamespaced() {
        SpeciesKnobs knobs = SpeciesKnobs.of(TestSpecies.BIPED);
        KnobSpec radius = knobs.knob(ProfileAspect.SENSES_RADIUS);

        assertEquals("test_biped.anima_settings.senses.radius", radius.key());
        assertEquals("test_biped", radius.section());
        assertEquals("radius", radius.leaf());
        assertEquals(TestSpecies.BIPED.get(ProfileAspect.SENSES_RADIUS), radius.def(),
                "the declared value IS the knob's default — reset returns to the species");

        // Two species in one set: the whole reason the namespace segment exists.
        SpeciesProfile.Builder other = SpeciesProfile.of("test_other")
                .set(ProfileAspect.SENSES_RADIUS, 8);
        for (ProfileAspect aspect : ProfileAspect.values()) {
            if (aspect != ProfileAspect.SENSES_RADIUS) {
                other.set(aspect, TestSpecies.BIPED.get(aspect));
            }
        }
        KnobSet both = KnobSet.of("testmod", "Test Mod",
                java.util.stream.Stream.concat(knobs.knobs().stream(),
                        SpeciesKnobs.of(other.build()).knobs().stream()).toList());
        assertEquals(ProfileAspect.values().length * 2, both.size());
    }
}
