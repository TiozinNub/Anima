package dev.luizloyola.anima.core.appearance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the compositor must get right: order, transparency, and the promise that a missing texture
 * costs a part and never a person.
 */
class CompositorTest {

    private static final int OPAQUE = 0xFF000000;
    private static final int RED = OPAQUE | 0xFF0000;
    private static final int BLUE = OPAQUE | 0x0000FF;

    private static Sprite solid(int size, int argb) {
        int[] pixels = new int[size * size];
        java.util.Arrays.fill(pixels, argb);
        return new Sprite(size, size, pixels);
    }

    private static Sprites library(Map<String, Sprite> byId) {
        return byId::get;
    }

    @Test
    void laterPartsCoverEarlierOnes() {
        Sprites art = library(Map.of("under", solid(4, RED), "over", solid(4, BLUE)));
        Recipe recipe = Recipe.of(4, 4, List.of(Part.whole("under", 4, 4), Part.whole("over", 4, 4)));
        assertEquals(BLUE, Compositor.bake(recipe, art).image().pixel(0, 0));
    }

    /** The whole point of the id buffer: who owns a pixel, without hit-test geometry. */
    @Test
    void thePartIdBufferNamesTheTopmostPartAtEachPixel() {
        Sprites art = library(Map.of("body", solid(4, RED), "eye", solid(1, BLUE)));
        Recipe recipe = new Recipe(4, 4,
                List.of(Part.whole("body", 4, 4)),
                List.of(Part.of("eye", 2, 1, 1, 1)));
        Compositor.Bake bake = Compositor.bake(recipe, art);
        assertEquals(1, bake.partAt(2, 1), "the eye owns its own pixel");
        assertEquals(0, bake.partAt(0, 0), "the body owns everything else");
    }

    /** A pixel nobody drew is nobody's. That is what makes clicking empty space do nothing. */
    @Test
    void anUndrawnPixelBelongsToNobody() {
        Sprites art = library(Map.of("eye", solid(1, BLUE)));
        Recipe recipe = Recipe.of(4, 4, List.of(Part.of("eye", 0, 0, 1, 1)));
        assertEquals(Compositor.NOBODY, Compositor.bake(recipe, art).partAt(3, 3));
    }

    /** Transparent pixels are skipped outright — they neither draw nor claim ownership. */
    @Test
    void aTransparentPixelNeitherDrawsNorOwns() {
        Sprite hollow = new Sprite(2, 1, new int[] {0x00FF00FF, RED});
        Sprites art = library(Map.of("body", solid(2, BLUE), "mark", hollow));
        Recipe recipe = new Recipe(2, 1,
                List.of(Part.of("body", 0, 0, 2, 1)),
                List.of(Part.of("mark", 0, 0, 2, 1)));
        Compositor.Bake bake = Compositor.bake(recipe, art);
        assertEquals(BLUE, bake.image().pixel(0, 0), "the body shows through");
        assertEquals(0, bake.partAt(0, 0), "and still owns the pixel");
        assertEquals(RED, bake.image().pixel(1, 0));
        assertEquals(1, bake.partAt(1, 0));
    }

    /** A catalog naming art nobody ships must cost that art and nothing else. */
    @Test
    void aMissingTextureCostsItsPartAndNotThePerson() {
        Sprites art = library(Map.of("body", solid(4, RED)));
        Recipe recipe = Recipe.of(4, 4,
                List.of(Part.whole("body", 4, 4), Part.of("autarkia:hair/nonesuch", 0, 0, 4, 4)));
        assertEquals(RED, Compositor.bake(recipe, art).image().pixel(0, 0));
    }

    /** A sprite bigger than its slot is cropped; one smaller covers less. */
    @Test
    void aSpriteThatDoesNotMatchItsSlotIsClippedRatherThanRejected() {
        Sprites art = library(Map.of("big", solid(8, RED), "small", solid(1, BLUE)));
        Compositor.Bake cropped = Compositor.bake(
                Recipe.of(4, 4, List.of(Part.of("big", 0, 0, 4, 4))), art);
        assertEquals(RED, cropped.image().pixel(3, 3));

        Compositor.Bake sparse = Compositor.bake(
                Recipe.of(4, 4, List.of(Part.of("small", 0, 0, 4, 4))), art);
        assertEquals(BLUE, sparse.image().pixel(0, 0));
        assertEquals(Compositor.NOBODY, sparse.partAt(3, 3));
    }

    /** A part hanging off the canvas draws what fits instead of throwing. */
    @Test
    void aPartOverhangingTheCanvasIsClipped() {
        Sprites art = library(Map.of("mark", solid(2, RED)));
        Compositor.Bake bake = Compositor.bake(
                Recipe.of(4, 4, List.of(Part.of("mark", 3, 3, 2, 2))), art);
        assertEquals(RED, bake.image().pixel(3, 3));
    }

    // --- colour arithmetic -------------------------------------------------------------------

    @Test
    void aRampReplacesReservedIndicesAndLeavesEverythingElseAlone() {
        RampSpec spec = new RampSpec("skin", List.of(
                new RampSpec.Shade(80, 1150, 780),
                new RampSpec.Shade(0, 1000, 1000)));
        Sprite indexed = new Sprite(3, 1, new int[] {
                OPAQUE | Shades.color(0), OPAQUE | Shades.color(1), OPAQUE | 0x00FF00});
        Sprites art = library(Map.of("body", indexed));
        Recipe recipe = Recipe.of(3, 1, List.of(
                new Part("body", 0, 0, 3, 1, List.of(new ColorOp.Ramp(0xC68642, spec)))));
        Sprite baked = Compositor.bake(recipe, art).image();

        int[] shades = Colors.ramp(0xC68642, spec);
        assertEquals(OPAQUE | shades[0], baked.pixel(0, 0));
        assertEquals(OPAQUE | shades[1], baked.pixel(1, 0));
        assertEquals(OPAQUE | 0x00FF00, baked.pixel(2, 0),
                "a colour that is not a shade index stays exactly as authored, and stays visible");
    }

    /** Shade 1 here is the untouched base, so the ramp must reproduce it exactly. */
    @Test
    void anIdentityShadeReturnsTheBaseColour() {
        RampSpec spec = new RampSpec("flat", List.of(new RampSpec.Shade(0, 1000, 1000)));
        assertEquals(0xC68642, Colors.ramp(0xC68642, spec)[0]);
    }

    /** Skin shadows go warmer, not cooler — the starting curve in the spec, checked as arithmetic. */
    @Test
    void aShadowStepIsDarkerAndMoreSaturated() {
        RampSpec spec = new RampSpec("skin", List.of(new RampSpec.Shade(80, 1150, 780)));
        int shadow = Colors.ramp(0xC68642, spec)[0];
        float[] base = Colors.toHsv(0xC68642);
        float[] shaded = Colors.toHsv(shadow);
        assertTrue(shaded[2] < base[2], "a shadow is darker");
        assertTrue(shaded[1] > base[1], "and more saturated, which a multiply cannot do");
    }

    @Test
    void hsvRoundTripsThroughItsOwnConversion() {
        for (int rgb : new int[] {0x000000, 0xFFFFFF, 0xC68642, 0x3A2A1A, 0x00FF00, 0x123456}) {
            float[] hsv = Colors.toHsv(rgb);
            assertEquals(rgb, Colors.toRgb(hsv[0], hsv[1], hsv[2]), () -> "round trip " + rgb);
        }
    }

    /**
     * Ops apply left to right and do not commute, so list order is load-bearing. A value-only
     * {@link ColorOp.Hsv} is a per-channel scaling exactly like {@link ColorOp.Multiply}, and those
     * commute — a hue rotation is the cheapest op that does not.
     */
    @Test
    void opsApplyInOrder() {
        Sprites art = library(Map.of("body", solid(1, OPAQUE | 0x8040C0)));
        ColorOp multiply = new ColorOp.Multiply(0x00FF00);
        ColorOp brighten = new ColorOp.Hsv(120.0F, 1.0F, 1.0F);
        int multiplyFirst = Compositor.bake(
                Recipe.of(1, 1, List.of(new Part("body", 0, 0, 1, 1, List.of(multiply, brighten)))), art)
                .image().pixel(0, 0);
        int brightenFirst = Compositor.bake(
                Recipe.of(1, 1, List.of(new Part("body", 0, 0, 1, 1, List.of(brighten, multiply)))), art)
                .image().pixel(0, 0);
        assertNotEquals(multiplyFirst, brightenFirst);
    }

    /** Alpha survives every op untouched — a recolour must never change what is see-through. */
    @Test
    void alphaIsCarriedThroughUnchanged() {
        int half = 0x80000000 | 0x808080;
        assertEquals(0x80, Colors.multiply(half, 0xFF0000) >>> 24);
        assertEquals(0x80, Colors.shift(half, 90.0F, 1.5F, 0.5F) >>> 24);
        Map<String, Sprite> art = new HashMap<>();
        art.put("body", new Sprite(1, 1, new int[] {half}));
        Sprite baked = Compositor.bake(Recipe.of(1, 1, List.of(
                new Part("body", 0, 0, 1, 1, List.of(new ColorOp.Hsv(0.0F, 1.0F, 1.0F))))),
                library(art)).image();
        assertEquals(0x80, baked.pixel(0, 0) >>> 24);
    }

    @Test
    void aMultiplyCanOnlyDarken() {
        int grey = OPAQUE | 0x808080;
        int result = Colors.multiply(grey, 0xFFFFFF);
        assertEquals(grey, result, "multiplying by white is the identity");
        assertTrue((Colors.multiply(grey, 0x808080) & 0xFF) < 0x80, "and by anything else, darker");
    }
}
