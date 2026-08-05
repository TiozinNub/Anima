package dev.luizloyola.anima.core.appearance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.appearance.catalog.Catalog;
import dev.luizloyola.anima.core.appearance.catalog.CatalogReader;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A ramp may be drawn in the reference art's own colours rather than {@link Shades}' reserved
 * encoding, so an artist can paint the layer.
 *
 * <p>What that needs: keys per-<em>ramp</em>, never per-op (two parts naming one ramp would hash
 * alike and bake differently), exactly one key per shade, no two keys the same colour, and a ramp
 * with no keys behaving as it did before keys existed.
 */
class RampKeysTest {

    /** Steve's hair, and the curve calibrated to reproduce it — the real case this exists for. */
    private static final List<Integer> HAIR_KEYS =
            List.of(0x241808, 0x261A0A, 0x2B1E0D, 0x332411, 0x342512, 0x3F2A15);

    private static final RampSpec HAIR = new RampSpec("hair", List.of(
            new RampSpec.Shade(3, 1115, 837),
            new RampSpec.Shade(3, 1056, 884),
            new RampSpec.Shade(0, 1000, 1000),
            new RampSpec.Shade(-5, 956, 1186),
            new RampSpec.Shade(-5, 937, 1209),
            new RampSpec.Shade(-40, 956, 1465)), HAIR_KEYS);

    private static final RampSpec PLAIN = new RampSpec("skin", List.of(
            new RampSpec.Shade(80, 1150, 780),
            new RampSpec.Shade(0, 1000, 1000)));

    // --- which colour a shade replaces --------------------------------------------------------

    @Test
    void withNoKeysAShadeReplacesItsReservedIndex() {
        assertEquals(Shades.color(0), PLAIN.keyAt(0));
        assertEquals(Shades.color(1), PLAIN.keyAt(1));
        assertTrue(PLAIN.keys().isEmpty(), "the default is stated by absence, not by a flag");
    }

    @Test
    void withKeysAShadeReplacesTheAuthoredColour() {
        for (int index = 0; index < HAIR_KEYS.size(); index++) {
            assertEquals(HAIR_KEYS.get(index), HAIR.keyAt(index));
        }
    }

    // --- what counts as a key. That is what the editor's stray-pixel count asks ---------------

    @Test
    void withNoKeysOnlyReservedIndicesWithinRangeCount() {
        assertTrue(PLAIN.isKey(Shades.color(0)));
        assertTrue(PLAIN.isKey(Shades.color(1)));
        assertFalse(PLAIN.isKey(Shades.color(2)), "a two-shade ramp does not own a third index");
        assertFalse(PLAIN.isKey(0x2B1E0D), "and owns no ordinary colour at all");
    }

    @Test
    void withKeysOnlyTheDeclaredColoursCount() {
        HAIR_KEYS.forEach(key -> assertTrue(HAIR.isKey(key), () -> "declared key " + key));
        assertFalse(HAIR.isKey(Shades.color(0)), "the reserved encoding stops applying");
        assertFalse(HAIR.isKey(0x2B1E0E), "and a near miss is a miss — one off is still stray");
    }

    @Test
    void alphaIsNotPartOfAKey() {
        assertTrue(HAIR.isKey(0xFF000000 | 0x2B1E0D));
        assertEquals(0x2B1E0D, HAIR.keyAt(2), "and a key is returned without one");
    }

    // --- what a ramp refuses to be ------------------------------------------------------------

    @Test
    void aKeyPerShadeOrNone() {
        IllegalArgumentException tooFew = assertThrows(IllegalArgumentException.class,
                () -> new RampSpec("hair", HAIR.shades(), List.of(0x241808, 0x261A0A)));
        assertTrue(tooFew.getMessage().contains("one key per shade"), tooFew.getMessage());
    }

    /**
     * A repeated key keeps a single mapping, so one shade becomes unreachable and two bands of the
     * art collapse into one — a slightly flat texture rather than anything that looks like a bug.
     */
    @Test
    void twoShadesMayNotShareAKey() {
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> new RampSpec("hair", PLAIN.shades(), List.of(0x2B1E0D, 0x2B1E0D)));
        assertTrue(duplicate.getMessage().contains("2b1e0d"), duplicate.getMessage());
        assertTrue(duplicate.getMessage().contains("must be distinct"), duplicate.getMessage());
    }

    // --- the whole point: the art comes back as itself, and moves when the binding does --------

    /**
     * At the base its identity shade was calibrated against, a keyed ramp reproduces the reference
     * palette <em>exactly</em>: the default render is the art unchanged, every other colour a true
     * rotation of it.
     */
    @Test
    void atItsCalibratedBaseAKeyedRampReturnsTheArtUntouched() {
        int[] shades = Colors.ramp(HAIR_KEYS.get(2), HAIR);
        for (int index = 0; index < shades.length; index++) {
            assertEquals(HAIR_KEYS.get(index), shades[index],
                    "shade " + index + " should reproduce the colour it replaces");
        }
    }

    @Test
    void aKeyedRampRepaintsTheArtItIsGiven() {
        Sprite art = new Sprite(3, 1, new int[] {
                0xFF000000 | HAIR_KEYS.get(0),
                0xFF000000 | HAIR_KEYS.get(2),
                0xFF000000 | 0x00FF00});
        Recipe recipe = Recipe.of(3, 1, List.of(
                new Part("hair", 0, 0, 3, 1, List.of(new ColorOp.Ramp(0xC46A2B, HAIR)))));
        Sprite baked = Compositor.bake(recipe, id -> art).image();

        int[] shades = Colors.ramp(0xC46A2B, HAIR);
        assertEquals(0xFF000000 | shades[0], baked.pixel(0, 0));
        assertEquals(0xFF000000 | shades[2], baked.pixel(1, 0));
        assertEquals(0xFF000000 | 0x00FF00, baked.pixel(2, 0),
                "a colour the ramp does not know stays exactly as authored, and stays visible");
    }

    // --- both spellings in the catalog file ---------------------------------------------------

    private static Catalog read(String ramps) {
        return CatalogReader.read("""
                { "canvas": [8, 8], "anchors": { "SHEET": [0, 0, 8, 8] },
                  "ramps": %s,
                  "slots": [{ "name": "s", "anchor": "SHEET",
                              "select": [{ "texture": "a:b" }] }] }
                """.formatted(ramps));
    }

    @Test
    void abareArrayIsTheShadesAloneInTheReservedEncoding() {
        RampSpec spec = read("""
                { "skin": [[80, 1150, 780], [0, 1000, 1000]] }
                """).ramps().get("skin");
        assertEquals(2, spec.steps());
        assertTrue(spec.keys().isEmpty());
        assertEquals(Shades.color(0), spec.keyAt(0));
    }

    @Test
    void anObjectMayDeclareTheColoursTheArtIsDrawnIn() {
        RampSpec spec = read("""
                { "hair": { "keys": ["241808", "#2B1E0D"],
                            "shades": [[3, 1115, 837], [0, 1000, 1000]] } }
                """).ramps().get("hair");
        assertEquals(List.of(0x241808, 0x2B1E0D), spec.keys(), "with or without a leading hash");
        assertEquals(0x2B1E0D, spec.keyAt(1));
    }

    @Test
    void anObjectWithoutShadesSaysWhichRampIsWrong() {
        IllegalArgumentException broken = assertThrows(IllegalArgumentException.class,
                () -> read("""
                        { "hair": { "keys": ["241808"] } }
                        """));
        assertTrue(broken.getMessage().contains("hair"), broken.getMessage());
    }

    @Test
    void aBadKeyCountIsCaughtOnTheWayInFromTheFile() {
        assertThrows(IllegalArgumentException.class, () -> read("""
                { "hair": { "keys": ["241808"],
                            "shades": [[3, 1115, 837], [0, 1000, 1000]] } }
                """));
    }

    /** A ramp is spelled by name, so its keys must be part of what the name means — see Canonical. */
    @Test
    void keysBelongToTheRampAndNotToTheOperationUsingIt() {
        Part reserved = new Part("hair", 0, 0, 8, 8,
                List.of(new ColorOp.Ramp(0xC46A2B, new RampSpec("hair", PLAIN.shades()))));
        Part keyed = new Part("hair", 0, 0, 8, 8,
                List.of(new ColorOp.Ramp(0xC46A2B,
                        new RampSpec("hair", PLAIN.shades(), List.of(0x241808, 0x2B1E0D)))));
        assertEquals(Canonical.stream(List.of(reserved)), Canonical.stream(List.of(keyed)),
                "two spellings of one NAME hash alike — which is exactly why a name may only ever "
                        + "mean one key palette, and why the catalog holds them per ramp");
    }
}
