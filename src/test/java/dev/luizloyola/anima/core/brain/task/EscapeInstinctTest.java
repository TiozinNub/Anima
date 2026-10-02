package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.agent.need.BreathNeed;
import dev.luizloyola.anima.core.brain.instinct.EscapeInstinct;
import dev.luizloyola.anima.core.brain.sense.Confinement;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.CellType;
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

    /**
     * Ground work is let off the question — unless its own walks keep failing from here. A settler
     * in a crevice retried the tree above it for good while its chop kept the drive from asking.
     */
    @Test
    void groundWorkThatKeepsFailingToWalkIsAskedAfterAll() {
        ctx.percepts.confinement = new Confinement(true, 97);
        ctx.reshapingGround = true;
        ctx.percepts.strandedHere = true;
        assertEquals(EscapeInstinct.pressure(TestSpecies.PROFILE), escape.pressure(ctx));
    }

    @Test
    void theDriveComesBackWhenTheWorkStops() {
        ctx.percepts.confinement = new Confinement(true, 1);
        ctx.reshapingGround = true;
        assertEquals(0.0, escape.pressure(ctx));
        ctx.reshapingGround = false;
        assertEquals(EscapeInstinct.pressure(TestSpecies.PROFILE), escape.pressure(ctx));
    }

    /**
     * In water and short of air, the drive bids though nothing proves a seal, and whatever work the
     * body is doing: a settler in a flooded gap under a lake read as free once the search could
     * stroke out of it, and nothing else would have moved it before it drowned.
     */
    @Test
    void aBodyShortOfAirInWaterBidsWhateverElseIsTrue() {
        int[] air = {60};
        ctx.percepts.needs.add(new BreathNeed(() -> air[0], () -> 300, () -> TestSpecies.PROFILE));
        Pos feet = ctx.percepts.position;
        int[] surface = {feet.y() + 1}; // the top water cell: the head is under while this is it
        ctx.percepts.terrain = (x, y, z) -> x == feet.x() && z == feet.z()
                && y >= feet.y() && y <= surface[0] ? CellType.WATER : CellType.PASSABLE;
        ctx.percepts.confinement = Confinement.NONE;
        ctx.reshapingGround = true;
        assertEquals(EscapeInstinct.pressure(TestSpecies.PROFILE), escape.pressure(ctx));

        surface[0] = feet.y();
        assertEquals(EscapeInstinct.pressure(TestSpecies.PROFILE), escape.pressure(ctx),
                "open air over its cell is not a head out: a swimmer's eyes ride under the surface");

        surface[0] = feet.y() + 1;
        air[0] = 300;
        assertEquals(0.0, escape.pressure(ctx), "a full lungful under water is a swim, not a plight");
    }

    /**
     * From the moment breath goes short, nothing outranks it: a settler at the surface with her
     * eyes under ate and wandered every hundred ticks until she drowned (forest, 2026-10-02).
     */
    @Test
    void breathGoingShortInWaterIsUrgentAndNotBefore() {
        int[] air = {141};
        ctx.percepts.needs.add(new BreathNeed(() -> air[0], () -> 300, () -> TestSpecies.PROFILE));
        Pos feet = ctx.percepts.position;
        ctx.percepts.terrain = (x, y, z) -> x == feet.x() && z == feet.z() && y == feet.y()
                ? CellType.WATER : y < feet.y() ? CellType.GROUND : CellType.PASSABLE;
        ctx.percepts.confinement = Confinement.NONE;
        assertFalse(escape.urgent(ctx), "easy, if only just");
        air[0] = 140;
        assertTrue(escape.urgent(ctx), "short");
        air[0] = 0;
        assertTrue(escape.urgent(ctx), "drowning");

        ctx.percepts.terrain = (x, y, z) -> y < feet.y() ? CellType.GROUND : CellType.PASSABLE;
        assertFalse(escape.urgent(ctx), "out of the water the air comes back on its own");
    }
}
