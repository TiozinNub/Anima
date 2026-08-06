package dev.luizloyola.anima.core.appearance.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What a folder of files means as a list of <em>choices</em>. One implementation serves both the
 * editor's picker and the game's genotype roll: a roll the editor cannot show would read as a
 * rendering bug.
 */
class ChoicesTest {

    /** A catalog shaped like the real one where it matters: families, model cuts, and states. */
    private static Catalog catalog() {
        return CatalogReader.read("""
                {
                  "canvas": [64, 64],
                  "anchors": { "SHEET": [0, 0, 64, 64], "FACE": [8, 8, 8, 8] },
                  "slots": [
                    { "name": "body", "anchor": "SHEET",
                      "select": [ { "texture": "a:person/body/{model}" } ] },
                    { "name": "hair", "anchor": "SHEET",
                      "select": [ { "texture": ["a:person/hair/{hairstyle}_{model}",
                                                "a:person/hair/{hairstyle}"] } ] },
                    { "name": "eyes", "anchor": "FACE", "dynamic": true,
                      "select": [ { "when": { "blink": "true" },
                                    "texture": ["a:person/eyes/{mood}_blink", "a:person/eyes/{mood}"] },
                                  { "texture": "a:person/eyes/{mood}" } ] }
                  ]
                }
                """);
    }

    private static final Set<String> ART = Set.of(
            "a:person/body/slim", "a:person/body/wide",
            "a:person/hair/long_slim", "a:person/hair/long_wide", "a:person/hair/short",
            "a:person/eyes/neutral", "a:person/eyes/neutral_blink", "a:person/eyes/happy");

    /**
     * Three hair files, two hairstyles: {@code long} is drawn in two cuts and {@code short} in one,
     * and a picker must offer the styles rather than the files.
     */
    @Test
    void modelCutsCollapseOntoTheStyleTheyCut() {
        assertEquals(List.of("long", "short"), Choices.of(catalog(), ART).get("hairstyle"));
    }

    /** A state is a cut by another name: a blink is not an expression. */
    @Test
    void statesCollapseOntoTheExpressionTheyDrawStateFor() {
        assertEquals(List.of("happy", "neutral"), Choices.of(catalog(), ART).get("mood"));
    }

    /**
     * {@code model} is the specialiser that picks between the cuts, so it keeps both values —
     * collapsing it would leave nothing to pick with.
     */
    @Test
    void theSpecialiserKeepsItsOwnValues() {
        assertEquals(List.of("slim", "wide"), Choices.of(catalog(), ART).get("model"));
    }

    /** Drawing a new PNG is the whole edit — nothing lists the members, so nothing needs touching. */
    @Test
    void aNewFileBecomesAChoiceWithNoCatalogEdit() {
        Set<String> withMohawk = new java.util.HashSet<>(ART);
        withMohawk.add("a:person/hair/mohawk");
        assertEquals(List.of("long", "mohawk", "short"), Choices.of(catalog(), withMohawk).get("hairstyle"));
    }

    /** A family is one folder. A file one level down is a different family, not a member with a
     *  slash in its name. */
    @Test
    void aValueNeverSpansAFolderBoundary() {
        Set<String> nested = new java.util.HashSet<>(ART);
        nested.add("a:person/hair/curly/tight");
        assertTrue(Choices.of(catalog(), nested).get("hairstyle").stream().noneMatch(v -> v.contains("/")),
                "a nested file leaked into the family as a slashed value");
    }

    /** No art, no choices, and not a crash: a pack that ships none is a configuration, not an error. */
    @Test
    void noArtYieldsNoChoicesRatherThanFailing() {
        assertEquals(Map.of(), Choices.of(catalog(), Set.of()));
    }

    /** The values of one placeholder in one template, which is the primitive the rest is built on. */
    @Test
    void oneTemplateReadsItsOwnValues() {
        assertEquals(List.of("slim", "wide"),
                Choices.valuesFor("a:person/body/{model}", "model", ART));
    }

    @Test
    void aTemplateWithoutThatParameterOffersNothing() {
        assertEquals(List.of(), Choices.valuesFor("a:person/body/{model}", "hairstyle", ART));
    }

    // --- families split by another parameter ------------------------------------------------

    /** Art split into folders per gender: two placeholders in one path. */
    private static Catalog split() {
        return CatalogReader.read("""
                {
                  "canvas": [64, 64],
                  "anchors": { "SHEET": [0, 0, 64, 64] },
                  "slots": [
                    { "name": "shirt", "anchor": "SHEET",
                      "select": [ { "texture": "a:person/{gender}/shirts/{shirt}" } ] }
                  ]
                }
                """);
    }

    private static final Set<String> SPLIT_ART = Set.of(
            "a:person/male/shirts/steve", "a:person/male/shirts/zuri",
            "a:person/female/shirts/alex", "a:person/female/shirts/ari");

    /**
     * A picker wants both parameters so it can offer both controls and let a mismatch be seen. A
     * two-placeholder path used to answer nothing, and the slot then stopped drawing.
     */
    @Test
    void bothParametersOfASplitFamilyAreAnswered() {
        Map<String, List<String>> all = Choices.of(split(), SPLIT_ART);
        assertEquals(List.of("female", "male"), all.get("gender"));
        assertEquals(List.of("alex", "ari", "steve", "zuri"), all.get("shirt"));
    }

    /**
     * A roll must have the narrower answer: a man in a woman-only shirt resolves to a path with no
     * file behind it — not an error, just a torso that never draws, for the life of the world.
     */
    @Test
    void aDecidedParameterNarrowsTheRest() {
        assertEquals(List.of("steve", "zuri"),
                Choices.of(split(), SPLIT_ART, Map.of("gender", "male")).get("shirt"));
        assertEquals(List.of("alex", "ari"),
                Choices.of(split(), SPLIT_ART, Map.of("gender", "female")).get("shirt"));
    }

    /** A split a gender has no folder for offers nothing for it — which is how only men have beards
     *  without a line of code saying so. */
    @Test
    void aSplitWithNoArtForThatValueOffersNothing() {
        Catalog beards = CatalogReader.read("""
                {
                  "canvas": [64, 64],
                  "anchors": { "SHEET": [0, 0, 64, 64] },
                  "slots": [
                    { "name": "beard", "anchor": "SHEET", "optional": true,
                      "select": [ { "texture": "a:person/{gender}/beard/{beard}" } ] }
                  ]
                }
                """);
        Set<String> onlyMale = Set.of("a:person/male/beard/plain");
        assertEquals(List.of("plain"), Choices.of(beards, onlyMale, Map.of("gender", "male")).get("beard"));
        assertNull(Choices.of(beards, onlyMale, Map.of("gender", "female")).get("beard"));
    }
}
