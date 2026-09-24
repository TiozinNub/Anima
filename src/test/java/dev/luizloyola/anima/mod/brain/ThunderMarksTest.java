package dev.luizloyola.anima.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Surroundings.Thunderclap;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A strike is heard by whoever was near enough, for as long as it is worth remarking on. */
class ThunderMarksTest {

    private static final String OVERWORLD = "minecraft:overworld";

    @AfterEach
    void forget() {
        ThunderMarks.clear();
    }

    @Test
    void theLatestStrikeInEarshotIsHeardWithItsDistanceAndAge() {
        ThunderMarks.mark(OVERWORLD, 100, 64, 0, 1_000);
        ThunderMarks.mark(OVERWORLD, 30, 64, 40, 1_050);

        assertEquals(Optional.of(new Thunderclap(10, 50.0)),
                ThunderMarks.heard(OVERWORLD, 0, 64, 0, 1_060), "the newer strike, 50 blocks off");
    }

    @Test
    void tooFarTooOldOrAnotherWorldIsNotHeard() {
        ThunderMarks.mark(OVERWORLD, ThunderMarks.EARSHOT + 1, 64, 0, 1_000);
        assertTrue(ThunderMarks.heard(OVERWORLD, 0, 64, 0, 1_010).isEmpty(), "past earshot");

        ThunderMarks.mark(OVERWORLD, 10, 64, 0, 1_000);
        assertTrue(ThunderMarks.heard(OVERWORLD, 0, 64, 0, 1_001 + ThunderMarks.MEMORY_TICKS)
                .isEmpty(), "long past");
        assertTrue(ThunderMarks.heard("minecraft:the_nether", 0, 64, 0, 1_010).isEmpty(),
                "a strike in another dimension is nobody's here");
    }
}
