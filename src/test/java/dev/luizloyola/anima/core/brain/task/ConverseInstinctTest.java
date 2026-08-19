package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.Arbiter;
import dev.luizloyola.anima.core.brain.instinct.ConverseInstinct;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Answering a call is its own drive, not company's — a CONTENT body must still be reachable, or
 * nobody can get anyone's attention and the player's right-click has nothing to land on.
 *
 * <p>Where its bid sits is the whole of "ignoring is not a behaviour, it is Converse losing the
 * bid": above the wander floor so an idle body comes, below {@code mind.preempt} so a shout waits
 * for a task boundary instead of cutting into work.
 */
class ConverseInstinctTest {

    private final FakeContext ctx = new FakeContext();
    private final ConverseInstinct converse = new ConverseInstinct();

    @Test
    void aQuietFieldAsksForNothing() {
        assertEquals(0.0, converse.pressure(ctx), "zero pressure is not a bid");
    }

    @Test
    void aCallIsWorthAnsweringEvenWhenNothingIsWrong() {
        ctx.percepts.beings = List.of(FakePercepts.hailingPersonAt(new Pos(30, 64, 0), 30.0));

        double bid = converse.pressure(ctx);
        assertEquals(ctx.profile.d(ProfileAspect.SOCIAL_HAIL_ANSWER_PRESSURE), bid, 1e-9);
        assertTrue(bid > ctx.profile.d(ProfileAspect.WANDER_IDLE_PRESSURE),
                "an idle body comes when called");
        assertTrue(bid < Arbiter.preempt(ctx.profile),
                "but a shout does not cut into work mid-errand — it waits for the boundary");
    }

    @Test
    void itWalksToWhoeverCalled() {
        ctx.percepts.beings = List.of(FakePercepts.hailingPersonAt(new Pos(30, 64, 0), 30.0));

        var a = converse.root(ctx);
        var b = converse.root(ctx);
        assertInstanceOf(Answer.class, a);
        assertNotSame(a, b, "a fresh tree every grant — never a cached instance");
    }

    @Test
    void itWillNotCrossTheWorldForAShout() {
        ctx.percepts.beings = List.of(FakePercepts.hailingPersonAt(new Pos(30, 64, 0), 30.0));
        assertEquals(2.0 * ctx.profile.i(ProfileAspect.SOCIAL_HAIL_RADIUS),
                converse.costTolerance(ctx), 1e-9,
                "a hail is within earshot by definition; twice that covers a detour and no more");
    }
}
