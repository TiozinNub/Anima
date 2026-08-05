package dev.luizloyola.anima.core.appearance.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.appearance.Recipe;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * "Specific if it exists, shared otherwise". Some layers must differ by body model and most need
 * not: narrowing an arm moves its faces on the sheet, so a sleeve drawn for four-pixel arms lands
 * on the wrong pixels of a three-pixel one, while a hat does not care.
 *
 * <p>⚠️ This cannot be two selector rules: a rule matches on <em>parameters</em>, so a
 * {@code model=slim} rule matches whether or not anybody drew the slim file, and the layer would
 * vanish rather than fall back. Existence is a different question from state.
 */
class TextureFallbackTest {

    private static Catalog catalog() {
        return CatalogReader.read("""
                { "canvas": [64, 64], "anchors": { "SHEET": [0, 0, 64, 64] },
                  "slots": [{ "name": "shirt", "anchor": "SHEET",
                              "select": [{ "texture": ["a:shirt_{model}", "a:shirt"] }] }] }
                """);
    }

    private static String chosen(Map<String, String> params, Set<String> onDisk) {
        Recipe recipe = catalog().compose(params, Map.of(), onDisk::contains);
        return recipe.all().isEmpty() ? null : recipe.all().get(0).texture();
    }

    @Test
    void takesTheModelSpecificFileWhenSomebodyDrewIt() {
        assertEquals("a:shirt_slim",
                chosen(Map.of("model", "slim"), Set.of("a:shirt", "a:shirt_slim")));
    }

    @Test
    void fallsBackToTheSharedFileWhenNobodyDid() {
        assertEquals("a:shirt",
                chosen(Map.of("model", "slim"), Set.of("a:shirt")));
        assertEquals("a:shirt",
                chosen(Map.of("model", "wide"), Set.of("a:shirt")));
    }

    @Test
    void oneModelMayBeSpecialisedWithoutTheOther() {
        Set<String> disk = Set.of("a:shirt", "a:shirt_slim");
        assertEquals("a:shirt_slim", chosen(Map.of("model", "slim"), disk));
        assertEquals("a:shirt", chosen(Map.of("model", "wide"), disk), "wide still shares");
    }

    /**
     * With nothing on disk the last candidate wins: it is the author's general case, so the more
     * useful name in a missing-texture report. The part is skipped at bake time either way.
     */
    @Test
    void withNothingDrawnItNamesTheGeneralCase() {
        assertEquals("a:shirt", chosen(Map.of("model", "slim"), Set.of()));
    }

    @Test
    void asingleTextureRuleIsUnaffected() {
        Catalog plain = CatalogReader.read("""
                { "canvas": [64, 64], "anchors": { "SHEET": [0, 0, 64, 64] },
                  "slots": [{ "name": "hat", "anchor": "SHEET",
                              "select": [{ "texture": "a:hat" }] }] }
                """);
        assertEquals("a:hat", plain.compose(Map.of(), Map.of()).all().get(0).texture());
        assertEquals("a:hat",
                plain.compose(Map.of(), Map.of(), texture -> false).all().get(0).texture());
    }

    /** The two-argument compose keeps its old meaning: the preferred candidate, no questions asked. */
    @Test
    void withoutAnExistenceCheckThePreferredOneWins() {
        assertEquals("a:shirt_slim",
                catalog().compose(Map.of("model", "slim"), Map.of()).all().get(0).texture());
    }

    // --- the file format ----------------------------------------------------------------------

    @Test
    void readsOneTextureOrSeveral() {
        Selector.Rule single = CatalogReader.read("""
                { "canvas": [8, 8], "anchors": { "S": [0, 0, 8, 8] },
                  "slots": [{ "name": "s", "anchor": "S", "select": [{ "texture": "a:b" }] }] }
                """).slot("s").selector().rules().get(0);
        assertEquals(List.of("a:b"), single.textures());
        assertEquals("a:b", single.texture(), "the preferred one is still just the texture");

        Selector.Rule several = catalog().slot("shirt").selector().rules().get(0);
        assertEquals(List.of("a:shirt_{model}", "a:shirt"), several.textures());
    }

    @Test
    void aRuleWithNoTextureIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CatalogReader.read("""
                { "canvas": [8, 8], "anchors": { "S": [0, 0, 8, 8] },
                  "slots": [{ "name": "s", "anchor": "S", "select": [{ "when": {} }] }] }
                """));
        assertThrows(IllegalArgumentException.class, () -> CatalogReader.read("""
                { "canvas": [8, 8], "anchors": { "S": [0, 0, 8, 8] },
                  "slots": [{ "name": "s", "anchor": "S", "select": [{ "texture": [] }] }] }
                """));
    }

    @Test
    void everyCandidateGetsItsParametersFilled() {
        assertEquals(List.of("a:shirt_slim", "a:shirt"),
                catalog().slot("shirt").selector().candidates(Map.of("model", "slim")));
    }

    @Test
    void aSlotThatMatchesNothingStillDrawsNothing() {
        Catalog optional = CatalogReader.read("""
                { "canvas": [8, 8], "anchors": { "S": [0, 0, 8, 8] },
                  "slots": [{ "name": "blood", "anchor": "S", "optional": true,
                              "select": [{ "when": { "hurt": "true" },
                                           "texture": ["a:blood_{model}", "a:blood"] }] }] }
                """);
        assertTrue(optional.compose(Map.of(), Map.of(), texture -> true).all().isEmpty());
    }
}
