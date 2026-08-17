package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.TestSpecies;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import dev.luizloyola.anima.core.brain.instinct.Drives;
import dev.luizloyola.anima.core.brain.instinct.Instinct;
import dev.luizloyola.anima.core.brain.instinct.WanderInstinct;
import org.junit.jupiter.api.Test;

/**
 * Pins the two layer-1 instincts: Eat's pressure is the body's hunger, Wander's is the constant
 * idle floor, and each hands out a FRESH root every grant (the continuous-behavior loop depends on
 * root() never returning a cached tree).
 */
class InstinctTest {

    private final FakeContext ctx = new FakeContext();

    @Test
    void eatPressureTracksHungerExactly() {
        Instinct eat = Drives.EAT;
        ctx.percepts.metabolism.setFoodLevel(20);
        assertEquals(0.0, eat.pressure(ctx), "full bar -> no hunger pressure");
        ctx.percepts.metabolism.setFoodLevel(8); // hunger 1 - 8/20 = 0.6
        assertEquals(ctx.percepts.metabolism.hunger(), eat.pressure(ctx));
        assertEquals(0.6, eat.pressure(ctx), 1e-9);
        ctx.percepts.metabolism.setFoodLevel(0);
        assertEquals(1.0, eat.pressure(ctx), "empty bar -> full pressure");
        assertEquals("eat", eat.describe());
    }

    @Test
    void wanderPressureIsTheConstantIdleFloor() {
        Instinct wander = new WanderInstinct();
        assertEquals(WanderInstinct.idlePressure(TestSpecies.PROFILE), wander.pressure(ctx));
        assertEquals(0.15, wander.pressure(ctx), "the documented idle floor");
        // ... and it does not move with the body's state
        ctx.percepts.metabolism.setFoodLevel(0);
        assertEquals(0.15, wander.pressure(ctx));
        assertEquals("wander", wander.describe());
    }

    @Test
    void eatRootIsAFreshSatisfyHungerEachGrant() {
        Instinct eat = Drives.EAT;
        var a = eat.root(ctx);
        var b = eat.root(ctx);
        assertInstanceOf(SatisfyHunger.class, a);
        assertNotSame(a, b, "each grant builds a new tree — never a cached instance");
    }

    @Test
    void wanderRootIsAFreshWanderStepEachGrant() {
        Instinct wander = new WanderInstinct(8);
        var a = wander.root(ctx);
        var b = wander.root(ctx);
        assertInstanceOf(WanderStep.class, a);
        assertNotSame(a, b);
    }

    @Test
    void defaultRadiusConstructorUsesTheDocumentedRadius() {
        assertEquals(8, WanderInstinct.defaultRadius(TestSpecies.PROFILE));
        // The single-arg ctor must still hand out a working WanderStep (radius wired through).
        assertInstanceOf(WanderStep.class, new WanderInstinct().root(ctx));
    }
}
