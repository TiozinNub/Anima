package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link Face}'s own coverage, split out here because nothing in production drives it since
 * Task 7 moved {@code Answer} onto {@link Converse} (which faces every tick itself, on its own
 * terms). Face persists anyway — {@code AnimaTasks} still registers its codec and
 * {@code TaskCodecsTest} still round-trips it — so these four lessons, previously pinned through
 * {@code Answer}, still need a home.
 *
 * <p>The look is not decoration. A hearer's sensor only spends the hail mark at
 * {@code Identified.INDIVIDUAL}, which needs the caller inside the vision cone — and the cone
 * follows the head. The first cut of a beat like this was an {@link Idle} beside a one-shot gaze
 * claim, and it lapses: the claim is issued once, the caller moves, and the head stays pointed at
 * a patch of grass. That is the pair shuffling in place the design set out to prevent.
 */
class FaceTest {

    /** What {@code Answer} used to order this beat for — kept local now nothing else does. */
    private static final int FACE_TICKS = 30;

    private final FakeContext ctx = new FakeContext();

    @Test
    void facesTheirLiveCellAtWorkPriority() {
        Being who = FakePercepts.personAt(new Pos(6, 64, 0), 6.0, "");
        ctx.percepts.beings = List.of(who);
        Face face = new Face(who.id(), who.pos(), FACE_TICKS);

        assertEquals(TaskStatus.RUNNING, face.tick(ctx));
        assertTrue(ctx.gazer.asked, "the beat is what points the head; an Idle asked for nothing");
        assertEquals(6.5, ctx.gazer.x, 1e-9, "the centre of their cell, not its corner");
        assertEquals(64 + Face.FACE_HEIGHT, ctx.gazer.y, 1e-9, "their face, not their boots");
        assertEquals(0.5, ctx.gazer.z, 1e-9);
        assertEquals(Gazer.Priority.WORK, ctx.gazer.priority,
                "standing in front of somebody IS the act — a walk's own NAV glance must not win");
    }

    @Test
    void theLookGoesWhereTheyAreNowRatherThanWhereTheBeatWasOrdered() {
        // The whole reason a one-shot claim was not enough: they were somewhere else when the
        // beat was ordered, and by the time it runs they have taken a few steps.
        Being who = FakePercepts.personAt(new Pos(33, 65, 4), 5.0, "");
        ctx.percepts.beings = List.of(who);
        Face face = new Face(who.id(), new Pos(30, 64, 0), FACE_TICKS);

        face.tick(ctx);
        assertEquals(33.5, ctx.gazer.x, 1e-9, "their cell now, not the one the beat was ordered at");
        assertEquals(65 + Face.FACE_HEIGHT, ctx.gazer.y, 1e-9);
        assertEquals(4.5, ctx.gazer.z, 1e-9);
    }

    @Test
    void unperceivedFallsBackToTheLastKnownCell() {
        Being who = FakePercepts.personAt(new Pos(12, 64, -8), 12.0, "");
        ctx.percepts.beings = List.of(who);
        Face face = new Face(who.id(), who.pos(), FACE_TICKS);

        ctx.percepts.beings = List.of(); // they walked off, or the linger ran out
        face.tick(ctx);
        assertEquals(12.5, ctx.gazer.x, 1e-9, "the last cell they were known to be in");
        assertEquals(-7.5, ctx.gazer.z, 1e-9);
    }

    @Test
    void theBeatHoldsTheLookForItsWholeLengthReclaimingEveryTick() {
        Being who = FakePercepts.personAt(new Pos(3, 64, 3), 3.0, "");
        ctx.percepts.beings = List.of(who);
        Face face = new Face(who.id(), who.pos(), FACE_TICKS);

        for (int tick = 0; tick < FACE_TICKS; tick++) {
            assertEquals(TaskStatus.RUNNING, face.tick(ctx));
        }
        assertEquals(TaskStatus.SUCCESS, face.tick(ctx));
        assertEquals(FACE_TICKS, ctx.gazer.claims,
                "re-asked every tick — a claim held once expires halfway through the beat");
    }
}
