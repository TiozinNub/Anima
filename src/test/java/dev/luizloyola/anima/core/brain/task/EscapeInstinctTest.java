package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.instinct.EscapeInstinct;
import dev.luizloyola.anima.core.brain.sense.Confinement;
import org.junit.jupiter.api.Test;

/**
 * Being shut in is binary, so the escape bid is flat. A body <em>mid-operation on the ground
 * around it</em> does not get to call itself stuck: mining, chopping and building put a body
 * somewhere precarious on purpose and carry their own way back down. Without that gate the
 * chop's one-block mast read as a prison and the drive preempted the fell at 0.90 (in-world,
 * 2026-08-12).
 */
class EscapeInstinctTest {

    private final FakeContext ctx = new FakeContext();
    private final EscapeInstinct escape = new EscapeInstinct();

    @Test
    void aBodyThatCanWalkOutWantsNothing() {
        ctx.percepts.confinement = Confinement.NONE;
        assertEquals(0.0, escape.pressure(ctx));
    }

    @Test
    void aSealedBodyBidsItsWholePressure() {
        ctx.percepts.confinement = new Confinement(true, 4);
        assertEquals(EscapeInstinct.pressure(TestSpecies.PROFILE), escape.pressure(ctx));
    }

    /** The gate: the same sealed verdict, with an operation under way, is not acted on. */
    @Test
    void aBodyReshapingTheGroundDoesNotCallItselfStuck() {
        ctx.percepts.confinement = new Confinement(true, 1); // the chop mast, exactly
        ctx.reshapingGround = true;
        assertEquals(0.0, escape.pressure(ctx),
                "an operation that placed the body there owns getting it back down");
    }

    /** A gate on the BELIEF, not on acting: the verdict is never asked for, so no survey is
     * paid for. A percept that would explode if consulted is the proof. */
    @Test
    void theQuestionIsNotEvenPut() {
        ctx.percepts.confinement = null;
        ctx.reshapingGround = true;
        assertDoesNotThrow(() -> escape.pressure(ctx));
        assertEquals(0.0, escape.pressure(ctx));
    }

    @Test
    void theDriveComesBackWhenTheWorkStops() {
        ctx.percepts.confinement = new Confinement(true, 1);
        ctx.reshapingGround = true;
        assertEquals(0.0, escape.pressure(ctx));
        ctx.reshapingGround = false;
        assertEquals(EscapeInstinct.pressure(TestSpecies.PROFILE), escape.pressure(ctx));
    }
}
