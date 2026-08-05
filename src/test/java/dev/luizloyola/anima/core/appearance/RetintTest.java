package dev.luizloyola.anima.core.appearance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.appearance.catalog.Catalog;
import dev.luizloyola.anima.core.appearance.catalog.CatalogReader;
import dev.luizloyola.anima.core.appearance.catalog.OpSpec;
import dev.luizloyola.anima.core.appearance.catalog.Tint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Retint: for painted art rather than a shade map. A ramp's listed keys suit a six-tone garment,
 * not a hand-painted body carrying a hundred and thirty; this measures one reference colour against
 * the target and applies the difference to <em>every</em> pixel, so what an artist painted keeps
 * its relationships.
 */
class RetintTest {

    /** The dominant tone of Autarkia's body art, and the mid entry of its skin ladder. */
    private static final int REFERENCE = 0xAA7C65;
    private static final int PALE = 0xF5D0B0;
    private static final int DEEP = 0x3A2015;

    private static int applied(int rgb, OpSpec spec, Map<String, Integer> bindings) {
        ColorOp.Hsv hsv = (ColorOp.Hsv) spec.resolve(bindings, Map.of());
        return Colors.shift(0xFF000000 | rgb, hsv.hueDegrees(), hsv.satMul(), hsv.valMul()) & 0xFFFFFF;
    }

    private static int channelError(int a, int b) {
        int worst = 0;
        for (int shift : new int[] {16, 8, 0}) {
            worst = Math.max(worst, Math.abs((a >> shift & 0xFF) - (b >> shift & 0xFF)));
        }
        return worst;
    }

    @Test
    void theReferenceBecomesTheTarget() {
        for (int target : new int[] {PALE, DEEP, 0xCE8E68, 0x613724}) {
            OpSpec spec = new OpSpec.Retint(REFERENCE, Tint.literal(target));
            assertTrue(channelError(applied(REFERENCE, spec, Map.of()), target) <= 1,
                    () -> "#" + Integer.toHexString(target) + " from #" + Integer.toHexString(REFERENCE));
        }
    }

    /** …and it resolves to a plain Hsv, so nothing downstream learns a new case. */
    @Test
    void itIsJustAnHsvWithMeasuredDeltas() {
        ColorOp resolved = new OpSpec.Retint(REFERENCE, Tint.literal(PALE)).resolve(Map.of(), Map.of());
        assertTrue(resolved instanceof ColorOp.Hsv, () -> "was " + resolved);
    }

    /** Every pixel moves. That is what a ramp does not do. */
    @Test
    void itShiftsColoursItWasNeverToldAbout() {
        OpSpec spec = new OpSpec.Retint(REFERENCE, Tint.literal(DEEP));
        for (int unlisted : new int[] {0x965F41, 0x6A4030, 0x513125, 0x9D6A4F}) {
            assertNotEquals(unlisted, applied(unlisted, spec, Map.of()),
                    () -> "#" + Integer.toHexString(unlisted) + " should move with the rest");
        }
    }

    /** Relative order survives: what was darker stays darker. */
    @Test
    void itKeepsTheRelationshipsTheArtistPainted() {
        OpSpec spec = new OpSpec.Retint(REFERENCE, Tint.literal(DEEP));
        float darkBefore = Colors.toHsv(0x513125)[2];
        float lightBefore = Colors.toHsv(0xAA7D66)[2];
        float darkAfter = Colors.toHsv(applied(0x513125, spec, Map.of()))[2];
        float lightAfter = Colors.toHsv(applied(0xAA7D66, spec, Map.of()))[2];
        assertTrue(darkBefore < lightBefore && darkAfter < lightAfter, "the shading does not invert");
    }

    /** The target comes from a binding, like every other colour a catalog cannot know. */
    @Test
    void theTargetMayBeBound() {
        OpSpec spec = new OpSpec.Retint(REFERENCE, Tint.bound("SKIN", 0xFFFFFF));
        assertTrue(channelError(applied(REFERENCE, spec, Map.of("SKIN", DEEP)), DEEP) <= 1);
        assertTrue(channelError(applied(REFERENCE, spec, Map.of("SKIN", PALE)), PALE) <= 1);
    }

    /**
     * ⚠️ A greyscale reference can never reach a coloured target: saturation is a ratio and zero
     * times anything is zero, so it is left alone rather than divided by. {@link ColorOp.Multiply}
     * is the tool for grey art.
     */
    @Test
    void aGreyReferenceLeavesSaturationAloneRatherThanDividingByZero() {
        ColorOp.Hsv hsv = (ColorOp.Hsv) new OpSpec.Retint(0x808080, Tint.literal(0xC46A2B))
                .resolve(Map.of(), Map.of());
        assertEquals(1.0F, hsv.satMul(), 1e-6F);
        assertTrue(Float.isFinite(hsv.valMul()) && Float.isFinite(hsv.hueDegrees()));
    }

    /** …and a black reference has no value to scale from either. */
    @Test
    void aBlackReferenceLeavesValueAlone() {
        ColorOp.Hsv hsv = (ColorOp.Hsv) new OpSpec.Retint(0x000000, Tint.literal(PALE))
                .resolve(Map.of(), Map.of());
        assertEquals(1.0F, hsv.valMul(), 1e-6F);
        assertTrue(Float.isFinite(hsv.satMul()));
    }

    @Test
    void readsFromACatalog() {
        Catalog catalog = CatalogReader.read("""
                { "canvas": [8, 8], "anchors": { "SHEET": [0, 0, 8, 8] },
                  "ladders": { "skin": ["F5D0B0"] },
                  "slots": [{ "name": "body", "anchor": "SHEET",
                              "ops": [{ "type": "retint", "from": "AA7C65", "bind": "SKIN" }],
                              "select": [{ "texture": "a:b" }] }] }
                """);
        OpSpec spec = catalog.slot("body").ops().get(0);
        assertEquals(new OpSpec.Retint(REFERENCE, Tint.bound("SKIN", 0xFFFFFF)), spec);

        // …and composes against the ladder's own first entry.
        List<ColorOp> ops = catalog.compose(Map.of(), catalog.defaultBindings()).all().get(0).ops();
        assertTrue(ops.get(0) instanceof ColorOp.Hsv);
    }
}
