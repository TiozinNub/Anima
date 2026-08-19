package dev.luizloyola.anima.mod.brain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import org.junit.jupiter.api.Test;

/**
 * The half of the radius rule that fails silently: {@code GameEventDispatcher#post} visits chunk
 * sections by the EVENT's own notification radius, so an event registered narrower than the widest
 * radius any species may ask for is simply swallowed part-way out — in-world, with nothing in any
 * log, and pointing at the ear, which is innocent.
 */
class BeingHailsTest {

    @Test
    void theEventOutrangesEveryHailAnySpeciesCouldAskFor() {
        assertTrue(BeingHails.RADIUS >= ProfileAspect.SOCIAL_HAIL_RADIUS.max(),
                "registration happens once at bootstrap; the aspect is per-species and editable "
                        + "at runtime, so the event must cover its MAXIMUM, not any one default");
    }
}
