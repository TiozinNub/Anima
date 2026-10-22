package dev.luizloyola.anima.core.agent.need;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AspectModifier;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.TestSpecies;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Time awake: counted in the open, held under cover at night, gone in a bed. */
class RestTest {

    private final Rest rest = new Rest(() -> TestSpecies.PROFILE);

    private void tick(int ticks, boolean asleep, boolean underCover) {
        rest.observe(asleep, underCover);
        for (int i = 0; i < ticks; i++) {
            rest.tick();
        }
    }

    @Test
    void restedForADayTiredToThreeThenExhausted() {
        tick(24_000, false, false);
        assertEquals("rested", rest.level().key());
        tick(1, false, false);
        assertEquals("tired", rest.level().key());
        rest.setValue(72_001);
        assertEquals("exhausted", rest.level().key());
    }

    @Test
    void freshFromABedNothingPresses() {
        tick(1, true, false);
        assertEquals("slept", rest.level().key());
        assertEquals(0.0, rest.pressure());
        tick(100, false, false);
        assertEquals("rested", rest.level().key());
        assertEquals(0.0, rest.pressure());
    }

    @Test
    void aNightUnderCoverHoldsTheCountAndABedClearsIt() {
        tick(10_000, false, false);
        tick(10_000, false, true);
        assertEquals(10_000, rest.value(), "a hut is not a night in the open");
        tick(1, true, false);
        assertEquals(0, rest.value());
    }

    @Test
    void theCostGrowsWithTheLevel() {
        assertTrue(Rest.costOf(level("rested")).isEmpty());
        List<AspectModifier> tired = Rest.costOf(level("tired"));
        assertTrue(tired.stream().anyMatch(m -> m.aspect() == ProfileAspect.HANDLING_STACK_TICKS
                && m.amount() == Rest.TIRED_HANDLING && m.id().equals(Rest.MODIFIER)));
        assertTrue(Rest.costOf(level("exhausted")).stream().allMatch(m -> m.amount() == Rest.EXHAUSTED_HANDLING));
        assertFalse(Rest.slowsWalking(level("tired")));
        assertTrue(Rest.slowsWalking(level("exhausted")));
    }

    private static NeedLevel level(String key) {
        return NeedKind.REST.levels().stream().filter(l -> l.key().equals(key)).findFirst().orElseThrow();
    }
}
