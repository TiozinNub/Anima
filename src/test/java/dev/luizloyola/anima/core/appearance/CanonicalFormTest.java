package dev.luizloyola.anima.core.appearance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The canonical form's contract, which the whole appearance cache rests on: one recipe spells
 * exactly one string, two recipes spell two, and the spelling does not drift between JVMs. A wrong
 * canonicaliser fails silently — two agents on one texture, or one look split into two bakes.
 */
class CanonicalFormTest {

    private static final RampSpec SKIN = new RampSpec("skin", List.of(
            new RampSpec.Shade(80, 1150, 780),
            new RampSpec.Shade(0, 1000, 1000),
            new RampSpec.Shade(-30, 900, 1120),
            new RampSpec.Shade(-50, 800, 1220)));

    private static Recipe worked() {
        return new Recipe(64, 64,
                List.of(new Part("minecraft:entity/person/body_a", 0, 0, 64, 64,
                        List.of(new ColorOp.Ramp(0xC68642, SKIN)))),
                List.of(new Part("autarkia:hair/long", 40, 8, 8, 8,
                        List.of(new ColorOp.Palette(List.of(new ColorOp.Swap(0x3A2A1A, 0x6B4423))),
                                new ColorOp.Hsv(-4.0F, 1.05F, 0.98F)))));
    }

    /** The golden spelling: a grammar change fails here with a readable diff, not as a split cache. */
    @Test
    void spellsTheDocumentedGrammar() {
        assertEquals("|minecraft:entity/person/body_a@0,0,64,64:ramp(c68642,skin)"
                        + "|autarkia:hair/long@40,8,8,8:pal(3a2a1a-6b4423),hsv(-40,1050,980)",
                Canonical.stream(worked().all()));
    }

    /**
     * The golden hash: the spelling test cannot catch a change to the hash function, and a changed
     * hash renames every cached texture in every running world.
     */
    @Test
    void hashesToAKnownValue() {
        assertEquals("2e2a66ac359e08f3", Canonical.hex(worked().hash()));
    }

    @Test
    void aPartWithNoOpsStillEndsInItsColon() {
        assertEquals("|autarkia:body@0,0,64,64:",
                Canonical.stream(List.of(Part.whole("autarkia:body", 64, 64))));
    }

    /** Floats are quantised, so differences below the fixed-point resolution are not differences. */
    @Test
    void hueBelowATenthOfADegreeIsTheSameColour() {
        assertEquals(hashOf(new ColorOp.Hsv(4.000F, 1.0F, 1.0F)),
                hashOf(new ColorOp.Hsv(4.004F, 1.0F, 1.0F)));
    }

    /** …and differences above it are. */
    @Test
    void hueAboveATenthOfADegreeIsADifferentColour() {
        assertNotEquals(hashOf(new ColorOp.Hsv(4.00F, 1.0F, 1.0F)),
                hashOf(new ColorOp.Hsv(4.06F, 1.0F, 1.0F)));
    }

    /** The ops are not commutative, so their order has to reach the hash. */
    @Test
    void opOrderIsSignificant() {
        ColorOp multiply = new ColorOp.Multiply(0x808080);
        ColorOp hsv = new ColorOp.Hsv(10.0F, 1.0F, 1.0F);
        assertNotEquals(hashOf(List.of(multiply, hsv)), hashOf(List.of(hsv, multiply)));
    }

    /** Composite order decides what covers what, so part order has to reach the hash too. */
    @Test
    void partOrderIsSignificant() {
        Part body = Part.whole("autarkia:body", 64, 64);
        Part shirt = Part.whole("autarkia:shirt", 64, 64);
        assertNotEquals(Canonical.hash(Canonical.stream(List.of(body, shirt))),
                Canonical.hash(Canonical.stream(List.of(shirt, body))));
    }

    /** Placement is part of the look; the same sprite in two places is two looks. */
    @Test
    void placementIsSignificant() {
        assertNotEquals(Canonical.hash(Canonical.stream(List.of(Part.of("autarkia:eyes", 9, 9, 6, 2)))),
                Canonical.hash(Canonical.stream(List.of(Part.of("autarkia:eyes", 9, 10, 6, 2)))));
    }

    /**
     * A wholly static recipe's two hashes agree: a portrait of an unchanging agent shares the cache
     * entry the world already baked.
     */
    @Test
    void withNothingDynamicTheStaticBaseIsTheWholeTexture() {
        Recipe still = Recipe.of(64, 64, List.of(Part.whole("autarkia:body", 64, 64)));
        assertEquals(still.hash(), still.staticHash());
    }

    /** …and once something is dynamic they part company, which is the point of keeping both. */
    @Test
    void aDynamicPartDoesNotDisturbTheSharedBase() {
        Recipe neutral = Recipe.of(64, 64, List.of(Part.whole("autarkia:body", 64, 64)));
        Recipe smiling = new Recipe(64, 64, neutral.statics(),
                List.of(Part.of("autarkia:mouth/smile", 10, 13, 4, 2)));
        assertNotEquals(neutral.hash(), smiling.hash(), "a different face is a different texture");
        assertEquals(neutral.staticHash(), smiling.staticHash(),
                "…but it must not re-bake the body underneath it");
    }

    /**
     * A ramp is spelled by name alone: the hash is blind to the shade values, so
     * retuning them does not change it and the bake cache must be cleared when the ramp table
     * reloads.
     */
    @Test
    void aRampIsSpelledByNameNotByItsShades() {
        RampSpec retuned = new RampSpec("skin", List.of(new RampSpec.Shade(999, 1, 1)));
        assertEquals(hashOf(new ColorOp.Ramp(0xC68642, SKIN)),
                hashOf(new ColorOp.Ramp(0xC68642, retuned)));
    }

    @Test
    void theHexFormIsAlwaysSixteenCharacters() {
        assertEquals(16, Canonical.hex(Canonical.hash("")).length());
        assertEquals(16, Canonical.hex(worked().hash()).length());
    }

    @Test
    void aPartCoveringNothingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Part.of("autarkia:body", 0, 0, 0, 8));
    }

    private static long hashOf(ColorOp op) {
        return hashOf(List.of(op));
    }

    private static long hashOf(List<ColorOp> ops) {
        return Canonical.hash(Canonical.stream(
                List.of(new Part("autarkia:body", 0, 0, 64, 64, ops))));
    }
}
