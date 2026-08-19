package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.instinct.Drives;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The lonely end of company, and the guardrail that keeps a settlement from becoming a chorus:
 * a hail needs a REASON, and "I already called that one" is the reason going away.
 */
class SeekCompanyTest {

    private final FakeContext ctx = new FakeContext();

    @Test
    void nobodyInSightMeansTheDriveFails() {
        ctx.percepts.company.setValue(0.0);
        ctx.percepts.beings = List.of();

        assertEquals(TaskStatus.FAILED, new SeekCompany().tick(ctx),
                "nothing is known that was not perceived — a lonely body with nobody in earshot "
                        + "does not go looking. That is curiosity's job, and it is not built.");
    }

    @Test
    void aStrangerFarOffIsWorthShoutingAt() {
        ctx.percepts.company.setValue(0.0);
        Being stranger = FakePercepts.personAt(new Pos(40, 64, 0), 40.0, "");
        ctx.percepts.beings = List.of(stranger);

        new SeekCompany().tick(ctx);
        assertTrue(ctx.voice.hailed, "I don't know you — and you are past shouting distance");
    }

    @Test
    void somebodyAlreadyCalledIsNotCalledAgain() {
        ctx.percepts.company.setValue(0.0);
        Being stranger = FakePercepts.personAt(new Pos(40, 64, 0), 40.0, "");
        ctx.percepts.beings = List.of(stranger);
        ctx.percepts.called.add(stranger.id());

        new SeekCompany().tick(ctx);
        assertFalse(ctx.voice.hailed,
                "the reason is \"I don't know you AND have not tried lately\"");
    }

    @Test
    void somebodyAlreadyCalledIsNotWalkedToEither() {
        ctx.percepts.company.setValue(0.0);
        Being stranger = FakePercepts.personAt(new Pos(40, 64, 0), 40.0, "");
        ctx.percepts.beings = List.of(stranger);
        ctx.percepts.called.add(stranger.id());

        assertEquals(TaskStatus.FAILED, new SeekCompany().tick(ctx),
                "one mark stops the second shout AND the second walk — a body does not trudge "
                        + "back to whoever it just gave up on");
    }

    @Test
    void nobodyShoutsAcrossTwoMetres() {
        ctx.percepts.company.setValue(0.0);
        double close = ctx.profile.i(ProfileAspect.SENSES_HEARING_RADIUS) - 1.0;
        ctx.percepts.beings = List.of(FakePercepts.personAt(new Pos(0, 64, (int) close), close, ""));

        new SeekCompany().tick(ctx);
        assertFalse(ctx.voice.hailed, "an ordinary voice already carries this far");
    }

    /**
     * The steady state this guardrail exists to stop, and the one a mark spent by SHOUTING could
     * never stop: a neighbour inside the hearing radius is never shouted at, so nothing marked
     * them — and the drive picked them again, walked the two steps to their cell, SUCCEEDED, and
     * was re-granted next tick. Two settlers standing on each other while the board waits.
     */
    @Test
    void aNeighbourWalkedToIsNotWalkedToTwice() {
        ctx.percepts.company.setValue(0.0);
        double close = ctx.profile.i(ProfileAspect.SENSES_HEARING_RADIUS) - 1.0;
        Being neighbour = FakePercepts.personAt(new Pos(0, 64, (int) close), close, "");
        ctx.percepts.beings = List.of(neighbour);

        new SeekCompany().tick(ctx);
        assertFalse(ctx.voice.hailed, "inside earshot, so the shout never happens");
        assertTrue(ctx.percepts.calledLately(neighbour.id()),
                "targeting is what spends the mark — walking over IS the reaching out");

        assertEquals(TaskStatus.FAILED, new SeekCompany().tick(ctx),
                "so the next grant has nobody left worth walking to, and wander gets the wheel");
    }

    @Test
    void aContentBodyDoesNotSeekAtAll() {
        ctx.percepts.company.setValue(0.6); // inside the band
        assertEquals(0.0, Drives.SEEK_PEOPLE.pressure(ctx));
    }
}
