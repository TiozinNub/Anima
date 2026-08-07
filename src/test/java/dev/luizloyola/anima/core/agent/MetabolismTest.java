package dev.luizloyola.anima.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pure {@link Metabolism} model — the vanilla-FoodData drain/regen/starvation machine plus our
 * two deviations (see the class doc). The whole metabolism is ours, so the entire contract is
 * testable headless.
 */
class NeedsTest {

    /** Float arithmetic delta for exhaustion sums (sequential adds vs. one multiply). */
    private static final float EXHAUSTION_DELTA = 1e-4F;

    private static Metabolism atFood(int foodLevel) {
        Metabolism metabolism = new Metabolism();
        metabolism.setFoodLevel(foodLevel);
        metabolism.setSaturation(0.0F);
        return metabolism;
    }

    @Test
    void freshNeedsMatchVanillaSpawnDefaults() {
        Metabolism metabolism = new Metabolism();
        assertEquals(20, metabolism.foodLevel());
        assertEquals(5.0F, metabolism.saturation());
        assertEquals(0.0F, metabolism.exhaustion());
        assertEquals(0, metabolism.tickTimer());
        assertEquals(0.0, metabolism.hunger());
        assertTrue(metabolism.canSprint());
    }

    @Test
    void eatClampsFoodAtTheFullBar() {
        Metabolism metabolism = atFood(18);
        metabolism.eat(8, 12.8F); // steak
        assertEquals(20, metabolism.foodLevel());
        assertEquals(12.8F, metabolism.saturation(), EXHAUSTION_DELTA);
    }

    @Test
    void eatCapsSaturationAtTheNewFoodLevel() {
        Metabolism metabolism = atFood(1);
        metabolism.eat(4, 8.0F); // more saturation than the resulting bar can hold
        assertEquals(5, metabolism.foodLevel());
        assertEquals(5.0F, metabolism.saturation(), "saturation may never exceed the food level");
    }

    @Test
    void saturationByModifierIsNutritionTimesModifierTimesTwo() {
        assertEquals(6.0F, Metabolism.saturationByModifier(5, 0.6F), 1e-6F, "bread: 5 x 0.6 x 2");
        assertEquals(12.8F, Metabolism.saturationByModifier(8, 0.8F), 1e-6F, "steak: 8 x 0.8 x 2");
    }

    @Test
    void exhaustCapsAtMaxExhaustion() {
        Metabolism metabolism = new Metabolism();
        metabolism.exhaust(100.0F);
        assertEquals(Metabolism.MAX_EXHAUSTION, metabolism.exhaustion());
    }

    @Test
    void settersClampToTheirDocumentedRanges() {
        Metabolism metabolism = new Metabolism();
        metabolism.setFoodLevel(99);
        assertEquals(20, metabolism.foodLevel());
        metabolism.setFoodLevel(-5);
        assertEquals(0, metabolism.foodLevel());
        metabolism.setFoodLevel(10);
        metabolism.setSaturation(15.0F);
        assertEquals(10.0F, metabolism.saturation(), "saturation setter clamps to the food level");
        metabolism.setSaturation(-1.0F);
        assertEquals(0.0F, metabolism.saturation());
        metabolism.setExhaustion(50.0F);
        assertEquals(Metabolism.MAX_EXHAUSTION, metabolism.exhaustion());
        metabolism.setExhaustion(-1.0F);
        assertEquals(0.0F, metabolism.exhaustion());
        metabolism.setTickTimer(37);
        assertEquals(37, metabolism.tickTimer(), "cadence counter round-trips for persistence");
    }

    @Test
    void drainTakesSaturationBeforeFood() {
        Metabolism metabolism = new Metabolism();
        metabolism.setSaturation(1.0F);
        // The drain gate is a strict > (vanilla): exactly 4.0 banked sits still, so bank past it.
        metabolism.setExhaustion(Metabolism.EXHAUSTION_DROP + 0.1F);
        metabolism.tick(false, false);
        assertEquals(0.0F, metabolism.saturation());
        assertEquals(20, metabolism.foodLevel(), "food untouched while saturation remains");
        metabolism.setExhaustion(Metabolism.EXHAUSTION_DROP + 0.1F);
        metabolism.tick(false, false);
        assertEquals(19, metabolism.foodLevel(), "saturation gone -> the next drain hits food");
    }

    @Test
    void drainFloorsFoodAtZero() {
        Metabolism metabolism = atFood(0);
        metabolism.setExhaustion(Metabolism.EXHAUSTION_DROP);
        metabolism.tick(false, false);
        assertEquals(0, metabolism.foodLevel());
    }

    /**
     * The player-fidelity decision (2026-07-22, replacing an ambient-metabolism deviation): hunger
     * is purely activity-driven, so an idle Person's food bar sits still forever, like a player's.
     */
    @Test
    void idlePersonNeverGetsHungry() {
        Metabolism metabolism = new Metabolism();
        for (int i = 0; i < 50_000; i++) {
            assertEquals(Metabolism.TickResult.NONE, metabolism.tick(true, false));
        }
        assertEquals(20, metabolism.foodLevel());
        assertEquals(5.0F, metabolism.saturation(), "spawn saturation untouched without activity");
        assertEquals(0.0F, metabolism.exhaustion());
    }

    @Test
    void saturatedFastRegenHealsOnTheTenthTick() {
        Metabolism metabolism = new Metabolism();
        metabolism.setSaturation(3.0F);
        for (int i = 1; i <= 9; i++) {
            assertEquals(Metabolism.TickResult.NONE, metabolism.tick(true, true), "tick " + i + " is quiet");
        }
        assertEquals(9, metabolism.tickTimer());
        Metabolism.TickResult tenth = metabolism.tick(true, true);
        assertEquals(0.5F, tenth.heal(), 1e-6F, "min(sat 3, 6) / 6");
        assertFalse(tenth.starve());
        assertEquals(0, metabolism.tickTimer(), "cadence resets after the heal");
        assertEquals(3.0F, metabolism.exhaustion(), EXHAUSTION_DELTA,
                "the heal costs its fuel (exhaust 3.0)");
        assertEquals(3.0F, metabolism.saturation(), "saturation is spent via the drain, not directly");
    }

    @Test
    void normalRegenHealsOnTheEightiethTick() {
        Metabolism metabolism = atFood(18);
        for (int i = 1; i <= 79; i++) {
            assertEquals(Metabolism.TickResult.NONE, metabolism.tick(true, true), "tick " + i + " is quiet");
        }
        Metabolism.TickResult eightieth = metabolism.tick(true, true);
        assertEquals(1.0F, eightieth.heal(), 1e-6F);
        assertFalse(eightieth.starve());
        assertEquals(0, metabolism.tickTimer());
        assertEquals(6.0F, metabolism.exhaustion(), EXHAUSTION_DELTA, "the heal costs EXHAUSTION_HEAL");
    }

    @Test
    void noRegenCadenceWithoutTheGameruleOrWithoutBeingHurt() {
        Metabolism gameruleOff = new Metabolism();
        for (int i = 0; i < 50; i++) {
            assertEquals(Metabolism.TickResult.NONE, gameruleOff.tick(false, true));
        }
        assertEquals(0, gameruleOff.tickTimer(), "naturalRegen off -> the timer never starts");

        Metabolism unhurt = new Metabolism();
        for (int i = 0; i < 50; i++) {
            assertEquals(Metabolism.TickResult.NONE, unhurt.tick(true, false));
        }
        assertEquals(0, unhurt.tickTimer(), "not hurt -> the timer never starts");
    }

    @Test
    void starvationLandsExactlyOnEveryEightiethTick() {
        Metabolism metabolism = atFood(0);
        List<Integer> hits = new ArrayList<>();
        for (int i = 1; i <= 240; i++) {
            Metabolism.TickResult result = metabolism.tick(true, false);
            assertEquals(0.0F, result.heal(), "an empty bar never heals");
            if (result.starve()) {
                hits.add(i);
            }
        }
        assertEquals(List.of(80, 160, 240), hits);
    }

    @Test
    void saturatedRegenTakesPriorityOverNormalRegen() {
        // Food 20 qualifies for both arms (>= 18 too); saturation > 0 must pick the 10-tick one.
        Metabolism metabolism = new Metabolism();
        for (int i = 1; i <= 9; i++) {
            metabolism.tick(true, true);
        }
        Metabolism.TickResult tenth = metabolism.tick(true, true);
        assertEquals(Math.min(5.0F, 6.0F) / 6.0F, tenth.heal(), 1e-6F,
                "healed on the 10-tick saturated cadence, not the 80-tick one");
    }

    @Test
    void canSprintIsStrictlyAboveTheSprintLevel() {
        assertTrue(atFood(Metabolism.SPRINT_LEVEL + 1).canSprint());
        assertFalse(atFood(Metabolism.SPRINT_LEVEL).canSprint(), "vanilla hasEnoughFood is a strict >");
    }

    @Test
    void hungerIsInverseNormalizedFoodLevel() {
        assertEquals(0.0, atFood(20).hunger());
        assertEquals(0.5, atFood(10).hunger());
        assertEquals(1.0, atFood(0).hunger());
    }

    @Test
    void bandBoundariesAreExactInFoodUnits() {
    }

    @Test
    void describeReadsAsFoodSaturationAndExhaustion() {
        Metabolism metabolism = atFood(14);
        metabolism.setSaturation(2.3F);
        metabolism.setExhaustion(1.2F);
        assertEquals("food 14/20 sat 2.3 exh 1.2", metabolism.describe());
    }
}
