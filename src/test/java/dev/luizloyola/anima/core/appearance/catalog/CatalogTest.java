package dev.luizloyola.anima.core.appearance.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.appearance.ColorOp;
import dev.luizloyola.anima.core.appearance.Part;
import dev.luizloyola.anima.core.appearance.Recipe;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Anchors decide where, selectors decide what, bindings decide which colour — and nothing an
 * author can get wrong at runtime costs more than the layer it is wrong about.
 */
class CatalogTest {

    private static final String JSON = """
            {
              "canvas": [64, 64],
              "anchors": {
                "SKIN": [0, 0, 64, 64],
                "FACE": [8, 8, 8, 8]
              },
              "ramps": {
                "skin": [[80, 1150, 780], [0, 1000, 1000]]
              },
              "ladders": {
                "skin": ["C68642", "8E573A"],
                "eye":  ["4A2C17", "3F6480"]
              },
              "slots": [
                { "name": "body", "anchor": "SKIN",
                  "ops": [{ "type": "ramp", "bind": "SKIN", "ramp": "skin" }],
                  "select": [{ "texture": "autarkia:body/{build}" }] },
                { "name": "eyes", "anchor": "FACE", "offset": [1, 1], "size": [6, 2],
                  "dynamic": true,
                  "ops": [{ "type": "palette",
                            "swaps": [{ "from": "FF0000", "bind": "EYE" }] }],
                  "select": [
                    { "when": { "blink": "true" }, "texture": "autarkia:face/eyes_blink" },
                    { "when": { "mood": "HAPPY" }, "texture": "autarkia:face/eyes_happy" },
                    { "texture": "autarkia:face/eyes_neutral" }
                  ] },
                { "name": "blood", "anchor": "FACE",
                  "select": [{ "when": { "hurt": "true" }, "texture": "autarkia:face/blood" }] }
              ]
            }
            """;

    private static final Catalog CATALOG = CatalogReader.read(JSON);

    private static Recipe compose(Map<String, String> params) {
        return CATALOG.compose(params, CATALOG.defaultBindings());
    }

    @Test
    void placesAPartAtItsAnchorPlusOffset() {
        Part eyes = compose(Map.of("build", "a")).dynamics().get(0);
        assertEquals(9, eyes.x(), "FACE is at x=8, the slot offsets by 1");
        assertEquals(9, eyes.y());
        assertEquals(6, eyes.w());
        assertEquals(2, eyes.h());
    }

    /** A slot with no size is the whole of its anchor — what makes a body one line of JSON. */
    @Test
    void aSlotWithNoSizeFillsItsAnchor() {
        Part body = compose(Map.of("build", "a")).statics().get(0);
        assertEquals(0, body.x());
        assertEquals(64, body.w());
        assertEquals(64, body.h());
    }

    @Test
    void templatesParametersIntoTheTextureId() {
        assertEquals("autarkia:body/slim", compose(Map.of("build", "slim")).statics().get(0).texture());
    }

    /**
     * An unfilled placeholder means nobody chose from that family, so the slot draws nothing.
     *
     * <p>⚠️ A MISTYPED placeholder is now silent too, where it used to surface as a missing texture.
     * The editor catches it instead, reporting a non-optional slot that drew nothing — see
     * {@code ValidationTest}.
     */
    @Test
    void anUnfilledPlaceholderDrawsNothing() {
        assertEquals(List.of(), compose(Map.of()).statics(), "no build chosen, so no body");
        assertEquals(1, compose(Map.of()).dynamics().size(), "the eyes are unaffected");
        assertEquals("autarkia:body/slim",
                compose(Map.of("build", "slim")).statics().get(0).texture());
    }

    @Test
    void firstMatchingRuleWins() {
        assertEquals("autarkia:face/eyes_happy",
                compose(Map.of("build", "a", "mood", "HAPPY")).dynamics().get(0).texture());
        assertEquals("autarkia:face/eyes_blink",
                compose(Map.of("build", "a", "mood", "HAPPY", "blink", "true")).dynamics().get(0).texture(),
                "blink is listed first, so it beats a mood that also matches");
    }

    @Test
    void theWildcardRuleCatchesEverythingElse() {
        assertEquals("autarkia:face/eyes_neutral",
                compose(Map.of("build", "a", "mood", "FURIOUS")).dynamics().get(0).texture());
    }

    /** A slot with no matching rule draws nothing — ordinary for blood, grime, anything usually absent. */
    @Test
    void aSlotThatMatchesNothingIsSimplyAbsent() {
        Recipe calm = compose(Map.of("build", "a"));
        assertEquals(1, calm.statics().size(), "only the body");
        assertEquals(1, calm.dynamics().size(), "only the eyes");
        assertEquals(3, compose(Map.of("build", "a", "hurt", "true")).all().size());
    }

    @Test
    void aDynamicSlotLandsInTheDynamicsAndNotTheBase() {
        Recipe recipe = compose(Map.of("build", "a"));
        assertEquals("autarkia:body/a", recipe.statics().get(0).texture());
        assertEquals("autarkia:face/eyes_neutral", recipe.dynamics().get(0).texture());
        Recipe blinking = compose(Map.of("build", "a", "blink", "true"));
        assertEquals(recipe.staticHash(), blinking.staticHash(),
                "a blink must not re-bake the body underneath it");
    }

    @Test
    void bindingsBecomeConcreteColours() {
        Part eyes = compose(Map.of("build", "a")).dynamics().get(0);
        ColorOp.Palette palette = (ColorOp.Palette) eyes.ops().get(0);
        assertEquals(0xFF0000, palette.swaps().get(0).fromRgb());
        assertEquals(0x4A2C17, palette.swaps().get(0).toRgb(), "EYE bound to the ladder's first entry");
    }

    @Test
    void aRampIsResolvedFromTheCatalogsOwnTable() {
        Part body = compose(Map.of("build", "a")).statics().get(0);
        ColorOp.Ramp ramp = (ColorOp.Ramp) body.ops().get(0);
        assertEquals(0xC68642, ramp.baseRgb());
        assertEquals("skin", ramp.spec().name());
        assertEquals(2, ramp.spec().steps());
    }

    @Test
    void anUnboundTintFallsBackToItsLiteral() {
        Part body = CATALOG.compose(Map.of("build", "a"), Map.of()).statics().get(0);
        assertEquals(0xFFFFFF, ((ColorOp.Ramp) body.ops().get(0)).baseRgb());
    }

    @Test
    void anUnknownRampIsNotSomethingToGuessAt() {
        OpSpec.Ramp missing = new OpSpec.Ramp(Tint.literal(0xC68642), "nonesuch");
        assertThrows(IllegalArgumentException.class,
                () -> missing.resolve(Map.of(), CATALOG.ramps()));
    }

    @Test
    void slotNamesLineUpWithTheComposedParts() {
        Map<String, String> params = Map.of("build", "a", "hurt", "true");
        assertEquals(List.of("body", "blood", "eyes"), CATALOG.slotNamesFor(params));
        assertEquals(CATALOG.slotNamesFor(params).size(), compose(params).all().size(),
                "the editor maps a clicked part to a slot by index — they must not drift");
    }

    @Test
    void aLadderIndexWrapsRatherThanBreakingABake() {
        assertEquals(0xC68642, CATALOG.ladder("skin", 0));
        assertEquals(0xC68642, CATALOG.ladder("skin", 2));
        assertEquals(0x8E573A, CATALOG.ladder("skin", -1));
        assertEquals(0xFFFFFF, CATALOG.ladder("nonesuch", 0));
    }

    @Test
    void anchorsKnowWhatFitsInsideThem() {
        assertTrue(CATALOG.slot("eyes").fitsWithin(CATALOG.anchor("FACE")));
        SlotSpec overflowing = new SlotSpec("wide", "FACE", 4, 0, 6, 2, true,
                List.of(), new Selector(List.of(new Selector.Rule(Map.of(), "x:y"))));
        assertFalse(overflowing.fitsWithin(CATALOG.anchor("FACE")),
                "4 + 6 runs past an 8-wide anchor — the editor's red outline");
    }

    @Test
    void aSelectorKnowsWhetherItCanFallThrough() {
        assertTrue(CATALOG.slot("eyes").selector().hasFallback());
        assertFalse(CATALOG.slot("blood").selector().hasFallback());
        assertNull(CATALOG.slot("blood").selector().pick(Map.of()));
    }

    // --- the reader ---------------------------------------------------------------------------

    /** A catalog is authored, so a mistake in it is loud rather than degrading at runtime. */
    @Test
    void aMalformedCatalogSaysWhatIsWrongWithIt() {
        IllegalArgumentException wrongAnchor = assertThrows(IllegalArgumentException.class,
                () -> CatalogReader.read("""
                        { "canvas": [64,64], "anchors": {},
                          "slots": [{ "name": "hat", "anchor": "HEAD",
                                      "select": [{ "texture": "a:b" }] }] }
                        """));
        assertTrue(wrongAnchor.getMessage().contains("hat"), wrongAnchor.getMessage());
        assertTrue(wrongAnchor.getMessage().contains("HEAD"), wrongAnchor.getMessage());

        assertThrows(IllegalArgumentException.class, () -> CatalogReader.read("""
                { "canvas": [64,64], "anchors": { "A": [0,0,4,4] },
                  "slots": [{ "name": "s", "anchor": "A",
                              "ops": [{ "type": "sepia" }],
                              "select": [{ "texture": "a:b" }] }] }
                """));
    }

    @Test
    void readsColoursWithOrWithoutAHash() {
        assertEquals(0xC68642, CatalogReader.rgb("C68642"));
        assertEquals(0xC68642, CatalogReader.rgb("#c68642"));
        assertThrows(IllegalArgumentException.class, () -> CatalogReader.rgb("nope"));
    }
}
