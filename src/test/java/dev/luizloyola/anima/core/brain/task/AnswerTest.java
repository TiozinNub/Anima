package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Coming when called: walk over, then stand and LOOK at them.
 *
 * <p>The look is not decoration. A hearer's sensor spends the hail mark only at
 * {@code Identified.INDIVIDUAL}, which needs the caller inside the vision cone — and the cone
 * follows the head. The first cut ended on an {@code Idle}, which touches no actuator: the walk's
 * ten-tick glance lapsed mid-beat, the tier never climbed, and Converse re-granted an answer to a
 * call that was never spent. That is the pair shuffling in place the design set out to prevent.
 */
class AnswerTest {

    private final FakeContext ctx = new FakeContext();

    private List<Task> beat(Being caller) {
        Answer answer = new Answer(caller.id(), caller.pos());
        Method only = answer.methods().get(0);
        assertTrue(only.applicable(ctx));
        return only.decompose(ctx);
    }

    @Test
    void theWalkEndsInABeatSpentFacingTheCaller() {
        Being caller = FakePercepts.personAt(new Pos(6, 64, 0), 6.0, "");
        ctx.percepts.beings = List.of(caller);

        List<Task> steps = beat(caller);
        assertInstanceOf(GoTo.class, steps.get(0));
        Face face = assertInstanceOf(Face.class, steps.get(1));
        assertEquals(Answer.FACE_TICKS, face.ticks(), "the beat is the design's, unchanged");

        assertEquals(TaskStatus.RUNNING, face.tick(ctx));
        assertTrue(ctx.gazer.asked, "the beat is what points the head; an Idle asked for nothing");
        assertEquals(6.5, ctx.gazer.x, 1e-9, "the centre of their cell, not its corner");
        assertEquals(64 + Face.FACE_HEIGHT, ctx.gazer.y, 1e-9, "their face, not their boots");
        assertEquals(0.5, ctx.gazer.z, 1e-9);
        assertEquals(Gazer.Priority.WORK, ctx.gazer.priority,
                "standing in front of somebody IS the act — the walk's own glance must not win");
    }

    @Test
    void theLookGoesWhereTheyAreNowRatherThanWhereTheyCalledFrom() {
        // The whole reason a one-shot claim was not enough: they shouted from over there, and by
        // the time the walk ends they have taken a few steps.
        Being caller = FakePercepts.personAt(new Pos(33, 65, 4), 5.0, "");
        ctx.percepts.beings = List.of(caller);
        Face face = new Face(caller.id(), new Pos(30, 64, 0), Answer.FACE_TICKS);

        face.tick(ctx);
        assertEquals(33.5, ctx.gazer.x, 1e-9, "their cell now, not the one the hail carried");
        assertEquals(65 + Face.FACE_HEIGHT, ctx.gazer.y, 1e-9);
        assertEquals(4.5, ctx.gazer.z, 1e-9);
    }

    @Test
    void aCallerNoLongerPerceivedIsStillFacedWhereTheyWere() {
        Being caller = FakePercepts.personAt(new Pos(12, 64, -8), 12.0, "");
        ctx.percepts.beings = List.of(caller);
        Face face = assertInstanceOf(Face.class, beat(caller).get(1));

        ctx.percepts.beings = List.of(); // they walked off, or the linger ran out
        face.tick(ctx);
        assertEquals(12.5, ctx.gazer.x, 1e-9, "the last cell they were known to be in");
        assertEquals(-7.5, ctx.gazer.z, 1e-9);
    }

    @Test
    void theBeatHoldsTheLookForItsWholeLength() {
        Being caller = FakePercepts.personAt(new Pos(3, 64, 3), 3.0, "");
        ctx.percepts.beings = List.of(caller);
        Face face = assertInstanceOf(Face.class, beat(caller).get(1));

        for (int tick = 0; tick < Answer.FACE_TICKS; tick++) {
            assertEquals(TaskStatus.RUNNING, face.tick(ctx));
        }
        assertEquals(TaskStatus.SUCCESS, face.tick(ctx));
        assertEquals(Answer.FACE_TICKS, ctx.gazer.claims,
                "re-asked every tick — a claim held once expires halfway through the beat");
    }
}
