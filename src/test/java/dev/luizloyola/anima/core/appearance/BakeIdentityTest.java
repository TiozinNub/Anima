package dev.luizloyola.anima.core.appearance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The proof that the riskiest step of this feature changes nothing. A bake moves the whole render
 * path (decode, composite, upload, register, resolve) for a recipe that is still one whole-canvas
 * part naming the vanilla skin it always named, so what the bake owes the source is stated here:
 * for a single unmodified part covering the canvas, the pixels out are the pixels in.
 *
 * <p>Core tests, with no Minecraft near them.
 */
class BakeIdentityTest {

    private static final int CANVAS = 64;

    /** Pixels with every kind of alpha in them: opaque, clear, and the partial values that a shaded
     *  garment actually uses — those are what a careless composite quietly changes. */
    private static Sprite noisy(long seed) {
        Random random = new Random(seed);
        int[] pixels = new int[CANVAS * CANVAS];
        for (int i = 0; i < pixels.length; i++) {
            int alpha = switch (i % 4) {
                case 0 -> 0xFF;
                case 1 -> 0x00;
                default -> random.nextInt(1, 0xFF);
            };
            pixels[i] = alpha << 24 | random.nextInt(0x1000000);
        }
        return new Sprite(CANVAS, CANVAS, pixels);
    }

    private static Sprite bakeOf(Sprite source) {
        Recipe recipe = Recipe.of(CANVAS, CANVAS, List.of(Part.whole("skin", CANVAS, CANVAS)));
        return Compositor.bake(recipe, Map.of("skin", source)::get).image();
    }

    /**
     * Compared only where the source has any alpha at all: a fully transparent pixel is the one
     * place the bake does <em>not</em> promise the bits back. The compositor skips it, so
     * {@code 0x0000FF00} (clear, but green underneath) comes back {@code 0x00000000}. Both are
     * invisible and upload the same, though a byte comparison of the two PNGs would flag it.
     */
    @Test
    void aSingleUnmodifiedPartBakesToItsSourcePixels() {
        Sprite source = noisy(20260805L);
        Sprite baked = bakeOf(source);

        assertEquals(source.width(), baked.width());
        assertEquals(source.height(), baked.height());
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                if ((source.pixel(x, y) >>> 24) != 0) {
                    assertEquals(source.pixel(x, y), baked.pixel(x, y),
                            "pixel " + x + "," + y + " came back changed");
                }
            }
        }
    }

    /** And a fully transparent source pixel stays fully transparent, rather than picking up whatever
     *  the canvas was initialised to. */
    @Test
    void clearPixelsStayClear() {
        Sprite baked = bakeOf(noisy(7L));
        for (int i = 1; i < baked.argb().length; i += 4) {
            assertEquals(0, baked.argb()[i], "a clear source pixel came back drawn");
        }
    }

    /**
     * Two agents wearing the same look must land on one texture, or the cache that keys textures by
     * recipe hash is decoration. The other half — that two <em>different</em> looks do not collide —
     * is {@code CanonicalFormTest}'s.
     */
    @Test
    void anIdenticalRecipeHashesToTheSameTexture() {
        Recipe one = Recipe.of(CANVAS, CANVAS, List.of(Part.whole("skin", CANVAS, CANVAS)));
        Recipe other = Recipe.of(CANVAS, CANVAS, List.of(Part.whole("skin", CANVAS, CANVAS)));
        assertEquals(one.hash(), other.hash());
        assertEquals(Canonical.hex(one.hash()).length(), 16);
    }

    /** A recipe naming art nothing ships bakes an empty canvas — which is what tells the resolver to
     *  hand back the missing texture rather than an invisible agent. */
    @Test
    void aRecipeWhoseArtIsAbsentDrawsNothingAtAll() {
        Recipe recipe = Recipe.of(CANVAS, CANVAS, List.of(Part.whole("nobody_ships_this", CANVAS, CANVAS)));
        Compositor.Bake baked = Compositor.bake(recipe, id -> null);
        assertArrayEquals(new int[CANVAS * CANVAS], baked.image().argb());
        for (int owner : baked.partIds()) {
            assertEquals(Compositor.NOBODY, owner);
        }
    }
}
