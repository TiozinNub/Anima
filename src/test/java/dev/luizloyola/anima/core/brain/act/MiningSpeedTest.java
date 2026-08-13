package dev.luizloyola.anima.core.brain.act;

import static dev.luizloyola.anima.core.brain.act.MiningSpeed.ABSENT;
import static dev.luizloyola.anima.core.brain.act.MiningSpeed.DRY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mining formula against vanilla's own numbers: every expectation was read out of
 * {@code Player.getDestroySpeed} in 26.1.2 bytecode rather than reasoned about. A mirror's failure
 * mode is drift.
 */
class MiningSpeedTest {

    /** A diamond axe on oak: fast enough to be past the Efficiency gate. */
    private static final float AXE = 8.0F;
    /** Bare hands: exactly 1.0. That is the gate. */
    private static final float HAND = 1.0F;

    private static float plain(float toolSpeed) {
        return MiningSpeed.of(toolSpeed, 0.0, ABSENT, ABSENT, 1.0, DRY, true);
    }

    @Test
    @DisplayName("with nothing acting on it, the tool's own speed comes back untouched")
    void bareTool() {
        assertEquals(AXE, plain(AXE), 1.0E-5F);
        assertEquals(HAND, plain(HAND), 1.0E-5F);
    }

    @Test
    @DisplayName("Efficiency adds to the tool, and is gated on the tool being faster than a fist")
    void efficiencyGate() {
        // Efficiency V = 5² + 1 = 26 on the mining_efficiency attribute.
        assertEquals(AXE + 26.0F, MiningSpeed.of(AXE, 26.0, ABSENT, ABSENT, 1.0, DRY, true), 1.0E-5F);
        assertEquals(HAND, MiningSpeed.of(HAND, 26.0, ABSENT, ABSENT, 1.0, DRY, true), 1.0E-5F,
                "vanilla's f > 1.0 gate: Efficiency on a bare hand does nothing");
    }

    @Test
    @DisplayName("Haste is +20% per level, counting from amplifier 0")
    void haste() {
        assertEquals(AXE * 1.2F, MiningSpeed.of(AXE, 0.0, 0, ABSENT, 1.0, DRY, true), 1.0E-5F);
        assertEquals(AXE * 1.4F, MiningSpeed.of(AXE, 0.0, 1, ABSENT, 1.0, DRY, true), 1.0E-5F);
    }

    @Test
    @DisplayName("Mining Fatigue is a table, and everything past level IV is the same wall")
    void miningFatigue() {
        assertEquals(0.3F, MiningSpeed.fatigueFactor(0), 1.0E-9F);
        assertEquals(0.09F, MiningSpeed.fatigueFactor(1), 1.0E-9F);
        assertEquals(0.0027F, MiningSpeed.fatigueFactor(2), 1.0E-9F);
        assertEquals(8.1E-4F, MiningSpeed.fatigueFactor(3), 1.0E-9F);
        assertEquals(MiningSpeed.fatigueFactor(3), MiningSpeed.fatigueFactor(200), 1.0E-9F,
                "the default case swallows every higher amplifier");
        assertEquals(AXE * 0.09F, MiningSpeed.of(AXE, 0.0, ABSENT, 1, 1.0, DRY, true), 1.0E-5F);
    }

    @Test
    @DisplayName("an elder guardian beats a beacon: Haste and Fatigue both apply, fatigue last")
    void hasteAndFatigueCompose() {
        assertEquals(AXE * 1.2F * 0.3F, MiningSpeed.of(AXE, 0.0, 0, 0, 1.0, DRY, true), 1.0E-5F);
    }

    @Test
    @DisplayName("block_break_speed multiplies whatever came before it")
    void blockBreakSpeedAttribute() {
        assertEquals(AXE * 2.0F, MiningSpeed.of(AXE, 0.0, ABSENT, ABSENT, 2.0, DRY, true), 1.0E-5F);
        assertEquals(0.0F, MiningSpeed.of(AXE, 0.0, ABSENT, ABSENT, 0.0, DRY, true), 1.0E-5F,
                "zeroed out is a body that cannot mine at all, not a body that mines slowly");
    }

    @Test
    @DisplayName("eyes under water costs the player's default fifth; Aqua Affinity buys it back")
    void submerged() {
        assertEquals(AXE * 0.2F, MiningSpeed.of(AXE, 0.0, ABSENT, ABSENT, 1.0, 0.2, true), 1.0E-5F);
        assertEquals(AXE, MiningSpeed.of(AXE, 0.0, ABSENT, ABSENT, 1.0, 1.0, true), 1.0E-5F);
    }

    @Test
    @DisplayName("mining in mid-air costs a fifth, and stacks with being underwater")
    void airborne() {
        assertEquals(AXE / 5.0F, MiningSpeed.of(AXE, 0.0, ABSENT, ABSENT, 1.0, DRY, false), 1.0E-5F);
        assertEquals(AXE * 0.2F / 5.0F,
                MiningSpeed.of(AXE, 0.0, ABSENT, ABSENT, 1.0, 0.2, false), 1.0E-5F,
                "swimming down to mine is the 25× penalty vanilla means it to be");
    }

    @Test
    @DisplayName("the whole stack at once, in vanilla's order")
    void everythingTogether() {
        // Efficiency V axe, Haste II, block_break_speed 1, dry, standing:
        // (8 + 26) * (1 + 3*0.2) = 34 * 1.6
        assertEquals(34.0F * 1.6F, MiningSpeed.of(AXE, 26.0, 2, ABSENT, 1.0, DRY, true), 1.0E-4F);
    }
}
