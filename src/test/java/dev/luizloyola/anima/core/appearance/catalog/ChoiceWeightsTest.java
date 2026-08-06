package dev.luizloyola.anima.core.appearance.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** How often each member of a family comes up — the rare hat problem. */
class ChoiceWeightsTest {

    private static Map<String, Integer> tally(ChoiceWeights odds, List<String> values, int draws) {
        SplittableRandom random = new SplittableRandom(20260806L);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < draws; i++) {
            counts.merge(String.valueOf(odds.pick(values, random)), 1, Integer::sum);
        }
        return counts;
    }

    /** A family nothing was said about is drawn FLAT, never humped: its members come out of a
     *  folder in filesystem order, so a positional bias would be nonsense. */
    @Test
    void anUnweightedFamilyIsUniform() {
        Map<String, Integer> counts = tally(ChoiceWeights.UNIFORM, List.of("a", "b", "c", "d"), 20_000);
        for (String value : List.of("a", "b", "c", "d")) {
            double share = counts.get(value) / 20_000.0;
            assertTrue(share > 0.22 && share < 0.28, value + " came up " + share + " — that is not flat");
        }
    }

    @Test
    void aRareMemberIsRare() {
        ChoiceWeights odds = new ChoiceWeights(Map.of("mohawk", 1), 10);
        Map<String, Integer> counts = tally(odds, List.of("short", "long", "mohawk"), 21_000);
        double share = counts.get("mohawk") / 21_000.0;
        assertTrue(share > 0.03 && share < 0.06, "the 1-in-21 hairstyle came up " + share);
    }

    /** The default is what lets a new PNG be common without anybody adding it here. */
    @Test
    void anUnnamedMemberTakesTheDefault() {
        ChoiceWeights odds = new ChoiceWeights(Map.of("mohawk", 1), 10);
        assertEquals(10, odds.weightOf("a_style_drawn_after_this_was_written"));
        assertEquals(1, odds.weightOf("mohawk"));
    }

    /** Naming art that is not there cannot skew what everybody else gets. */
    @Test
    void aWeightForAbsentArtIsIgnored() {
        ChoiceWeights odds = new ChoiceWeights(Map.of("deleted", 1000), 1);
        Map<String, Integer> counts = tally(odds, List.of("a", "b"), 4_000);
        assertNull(counts.get("deleted"), "a member that does not exist was chosen");
        assertTrue(counts.get("a") > 1_600 && counts.get("b") > 1_600, "the absent weight skewed the rest");
    }

    /** Drawing nothing is right for an optional slot, and a visible mistake for a required one. */
    @Test
    void allZeroDrawsNothing() {
        assertNull(new ChoiceWeights(Map.of("a", 0, "b", 0), 0).pick(List.of("a", "b"), new SplittableRandom(1L)));
    }

    /** Zero excludes one member without touching the others. */
    @Test
    void aZeroWeightedMemberNeverComesUp() {
        ChoiceWeights odds = new ChoiceWeights(Map.of("b", 0), 5);
        assertNull(tally(odds, List.of("a", "b", "c"), 5_000).get("b"));
    }

    @Test
    void nothingToChooseFromIsNotAFailure() {
        assertNull(ChoiceWeights.UNIFORM.pick(List.of(), new SplittableRandom(1L)));
    }

    @Test
    void negativeWeightsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ChoiceWeights(Map.of("a", -1), 1));
        assertThrows(IllegalArgumentException.class, () -> new ChoiceWeights(Map.of(), -1));
    }

    /** In the catalog, "*" sets the default. */
    @Test
    void oddsParseFromTheCatalog() {
        Catalog catalog = CatalogReader.read("""
                {
                  "canvas": [64, 64],
                  "anchors": { "SHEET": [0, 0, 64, 64] },
                  "odds": { "hairstyle": { "*": 10, "mohawk": 1 } },
                  "slots": []
                }
                """);
        ChoiceWeights odds = catalog.odds("hairstyle");
        assertEquals(1, odds.weightOf("mohawk"));
        assertEquals(10, odds.weightOf("short"));
        assertEquals(ChoiceWeights.UNIFORM, catalog.odds("a_parameter_nobody_mentioned"));
    }
}
