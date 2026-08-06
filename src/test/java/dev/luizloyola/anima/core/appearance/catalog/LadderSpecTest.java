package dev.luizloyola.anima.core.appearance.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * How often each colour comes up. Distributions are checked over enough draws to mean something: a
 * test that asserts one sample is in range would pass on a ladder that ignored its weights entirely.
 */
class LadderSpecTest {

    private static int[] histogram(LadderSpec ladder, int draws) {
        SplittableRandom random = new SplittableRandom(20260806L);
        int[] counts = new int[Math.max(ladder.size(), 1)];
        for (int i = 0; i < draws; i++) {
            counts[ladder.pick(random)]++;
        }
        return counts;
    }

    @Test
    void anAuthoredWeightIsTheProportionYouGet() {
        LadderSpec ladder = new LadderSpec(List.of(0x111111, 0x222222), List.of(9, 1));
        int[] counts = histogram(ladder, 10_000);
        double share = counts[0] / 10_000.0;
        assertTrue(share > 0.87 && share < 0.93, "the 9:1 colour came up " + share + " of the time");
    }

    /** Zero excludes without renumbering — which is the whole reason it is not just deleted. */
    @Test
    void aZeroWeightNeverComesUpButKeepsItsPosition() {
        LadderSpec ladder = new LadderSpec(List.of(0xAA, 0xBB, 0xCC), List.of(1, 0, 1));
        assertEquals(0, histogram(ladder, 5_000)[1], "a zero-weighted colour was drawn");
        assertEquals(0xCC, ladder.color(2), "excluding a colour moved the ones after it");
    }

    @Test
    void anUnweightedLadderIsTriangularRatherThanUniform() {
        int[] counts = histogram(LadderSpec.of(List.of(1, 2, 3, 4, 5, 6, 7, 8)), 20_000);
        int ends = counts[0] + counts[7];
        int middle = counts[3] + counts[4];
        assertTrue(middle > ends * 2, "middle " + middle + " vs ends " + ends + " — that is nearly flat");
    }

    /** And weights override that hump entirely, including reversing it. */
    @Test
    void weightsBeatThePositionalDefault() {
        LadderSpec ladder = new LadderSpec(List.of(1, 2, 3, 4), List.of(10, 1, 1, 10));
        int[] counts = histogram(ladder, 10_000);
        assertTrue(counts[0] + counts[3] > counts[1] + counts[2] * 3,
                "the authored ends did not beat the default's middle");
    }

    /** A ladder switched off entirely is a mistake, and must look like one rather than divide by zero. */
    @Test
    void allZeroWeightsFallBackInsteadOfFailing() {
        assertEquals(0, new LadderSpec(List.of(0xAA, 0xBB), List.of(0, 0)).pick(new SplittableRandom(1L)));
    }

    /** One weight per colour, or it is a typo that would silently shift every colour after it. */
    @Test
    void aMismatchedWeightListIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new LadderSpec(List.of(0xAA, 0xBB, 0xCC), List.of(1, 2)));
    }

    @Test
    void aNegativeWeightIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new LadderSpec(List.of(0xAA, 0xBB), List.of(1, -3)));
    }

    /** An index outside the ladder wraps rather than breaking a bake mid-frame. */
    @Test
    void anOutOfRangeIndexWraps() {
        LadderSpec ladder = LadderSpec.of(List.of(0xAA, 0xBB, 0xCC));
        assertEquals(0xAA, ladder.color(3));
        assertEquals(0xCC, ladder.color(-1));
    }

    /** A bare array stays the right way to say "no opinion". */
    @Test
    void bothSpellingsParse() {
        Catalog catalog = CatalogReader.read("""
                {
                  "canvas": [64, 64],
                  "anchors": { "SHEET": [0, 0, 64, 64] },
                  "ladders": {
                    "plain": ["FF0000", "00FF00"],
                    "weighted": { "colors": ["FF0000", "00FF00"], "weights": [3, 1] }
                  },
                  "slots": []
                }
                """);
        assertTrue(!catalog.ladders().get("plain").weighted(), "a bare array claimed to be weighted");
        assertTrue(catalog.ladders().get("weighted").weighted(), "an object's weights were dropped");
        assertEquals(List.of(3, 1), catalog.ladders().get("weighted").weights());
    }

    /** Naming the ladder in the message is what makes it a two-second fix rather than a hunt. */
    @Test
    void aMismatchedLadderNamesItselfWhenItIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                CatalogReader.read("""
                        {
                          "canvas": [64, 64],
                          "anchors": { "SHEET": [0, 0, 64, 64] },
                          "ladders": { "skin": { "colors": ["FF0000", "00FF00"], "weights": [1] } },
                          "slots": []
                        }
                        """));
        assertTrue(thrown.getMessage().contains("skin"), "the message did not name the ladder: " + thrown.getMessage());
    }
}
