package dev.luizloyola.anima.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.config.ConfigStore;
import dev.luizloyola.anima.core.config.KnobSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The third tier: what makes one agent different from the rest of its species. */
class ModifiedProfileTest {

    private static final ProfileAspect RADIUS = ProfileAspect.SENSES_RADIUS;

    private static AgentProfile species() {
        return TestSpecies.PROFILE; // senses.radius = 24
    }

    @Test
    @DisplayName("an agent with nothing special about it keeps reading its species, unwrapped")
    void nothingToModifyMeansNoWrapper() {
        AgentProfile species = species();
        assertSame(species, ModifiedProfile.of(species, AgentModifiers.NONE));
        assertSame(species, ModifiedProfile.of(species, null));
    }

    @Test
    @DisplayName("flat shifts sum before anything multiplies")
    void addsSumFirst() {
        AgentModifiers modifiers = new AgentModifiers();
        modifiers.apply(AspectModifier.add("job:scout", RADIUS, 4));
        modifiers.apply(AspectModifier.add("trait:keen", RADIUS, 2));
        AgentProfile profile = ModifiedProfile.of(species(), modifiers);

        assertEquals(30, profile.i(RADIUS));
        assertEquals(24, profile.base(RADIUS), "the species value is still there to be shown");
    }

    @Test
    @DisplayName("fractions of base add together; fractions of total compound")
    void theTwoMultiplyingFormsDifferOnPurpose() {
        AgentModifiers ofBase = new AgentModifiers();
        ofBase.apply(AspectModifier.fractionOfBase("a", RADIUS, 0.25));
        ofBase.apply(AspectModifier.fractionOfBase("b", RADIUS, 0.25));
        // 24 x (1 + 0.5) = 36. Two +25% bonuses make +50%. That is what people expect.
        assertEquals(36, ModifiedProfile.of(species(), ofBase).i(RADIUS));

        AgentModifiers ofTotal = new AgentModifiers();
        ofTotal.apply(AspectModifier.fractionOfTotal("a", RADIUS, 0.25));
        ofTotal.apply(AspectModifier.fractionOfTotal("b", RADIUS, 0.25));
        // 24 x 1.25 x 1.25 = 37.5, rounded for a whole-number aspect.
        assertEquals(38, ModifiedProfile.of(species(), ofTotal).i(RADIUS));
    }

    @Test
    @DisplayName("re-applying the same id replaces it — the whole point of keying by source")
    void sameIdReplacesRatherThanStacks() {
        AgentModifiers modifiers = new AgentModifiers();
        AgentProfile profile = ModifiedProfile.of(species(), modifiers);

        modifiers.apply(AspectModifier.add("job:scout", RADIUS, 4));
        modifiers.apply(AspectModifier.add("job:scout", RADIUS, 4));
        modifiers.apply(AspectModifier.add("job:scout", RADIUS, 4));

        assertEquals(28, profile.i(RADIUS),
                "re-applying what an agent already has must be safe — a consumer re-runs this on "
                        + "every world load and cannot be expected to know whether it already did");
    }

    @Test
    @DisplayName("dropping a source removes exactly its contribution, on every aspect it touched")
    void removingBySourceRestoresTheSpecies() {
        AgentModifiers modifiers = new AgentModifiers();
        AgentProfile profile = ModifiedProfile.of(species(), modifiers);
        modifiers.apply(AspectModifier.add("job:scout", RADIUS, 4));
        modifiers.apply(AspectModifier.add("job:scout", ProfileAspect.WANDER_RADIUS, 8));
        modifiers.apply(AspectModifier.add("trait:keen", RADIUS, 2));

        assertTrue(modifiers.remove("job:scout"));

        assertEquals(26, profile.i(RADIUS), "the trait stays, the job goes");
        assertEquals(8, profile.i(ProfileAspect.WANDER_RADIUS),
                "and the aspect only the job touched is exactly the species again");
    }

    @Test
    @DisplayName("a modifier cannot push an aspect outside its declared range")
    void theResultIsAlwaysLegal() {
        AgentModifiers absurd = new AgentModifiers();
        absurd.apply(AspectModifier.add("bug", RADIUS, -1000));
        assertEquals((int) RADIUS.min(), ModifiedProfile.of(species(), absurd).i(RADIUS),
                "every organ downstream assumes its aspect is inside its bounds");

        AgentModifiers huge = new AgentModifiers();
        huge.apply(AspectModifier.fractionOfTotal("bug", RADIUS, 1000));
        assertEquals((int) RADIUS.max(), ModifiedProfile.of(species(), huge).i(RADIUS));
    }

    @Test
    @DisplayName("a modifier change is seen by an organ holding the profile, without re-fetching")
    void aJobChangeReachesAnAgentAlreadyWalking() {
        AgentModifiers modifiers = new AgentModifiers();
        AgentProfile held = ModifiedProfile.of(species(), modifiers); // as an organ holds it

        assertEquals(24, held.i(RADIUS));
        modifiers.apply(AspectModifier.add("job:scout", RADIUS, 4));
        assertEquals(28, held.i(RADIUS));
        modifiers.remove("job:scout");
        assertEquals(24, held.i(RADIUS));
    }

    @Test
    @DisplayName("so is a config reload underneath it — folding must not freeze the species")
    void aReloadUnderneathAModifierIsSeenToo() {
        SpeciesKnobs knobs = SpeciesKnobs.of(TestSpecies.BIPED);
        KnobSet set = KnobSet.of("testmod", "Test Mod", knobs.knobs());
        ConfigStore store = new ConfigStore(set);

        AgentModifiers modifiers = new AgentModifiers();
        modifiers.apply(AspectModifier.add("job:scout", RADIUS, 4));
        AgentProfile held = ModifiedProfile.of(knobs.profile(store), modifiers);

        assertEquals(28, held.i(RADIUS));
        store.install(set.defaults().with(knobs.knob(RADIUS), 40.0));
        assertEquals(44, held.i(RADIUS),
                "the fold is a cache, not a snapshot; a reload has to invalidate it");
    }

    @Test
    @DisplayName("the shared empty set refuses to be modified")
    void theSharedEmptySetIsNotSomebodysScratchpad() {
        assertThrows(UnsupportedOperationException.class,
                () -> AgentModifiers.NONE.apply(AspectModifier.add("oops", RADIUS, 1)));
    }

    @Test
    @DisplayName("the readout can name every contribution, in the order it was applied")
    void modifiersAreVisibleForTheReadout() {
        AgentModifiers modifiers = new AgentModifiers();
        modifiers.apply(AspectModifier.add("job:scout", RADIUS, 4));
        modifiers.apply(AspectModifier.fractionOfBase("trait:keen", RADIUS, 0.1));
        AgentProfile profile = ModifiedProfile.of(species(), modifiers);

        assertEquals(2, profile.modifiers(RADIUS).size());
        assertEquals("job:scout", profile.modifiers(RADIUS).get(0).id());
        assertTrue(profile.modifiers(RADIUS).get(1).describe().contains("of base"));
        assertEquals(0, profile.modifiers(ProfileAspect.MIND_PREEMPT).size());
        assertNotSame(profile.base(RADIUS), profile.raw(RADIUS));
    }
}
