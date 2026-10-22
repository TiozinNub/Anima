package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.luizloyola.anima.core.brain.sense.Pos;
import org.junit.jupiter.api.Test;

/** Lying in a bed until something gets the body up. */
class SleepTest {

    private static final Pos BED = new Pos(2, 64, 0);

    private final FakeContext ctx = new FakeContext();

    {
        ctx.percepts.timeOfDay = 18_000L;
    }

    @Test
    void liesDownAndStaysAsleep() {
        ctx.bed.beds.add(BED);
        Sleep sleep = new Sleep(BED.x(), BED.y(), BED.z());

        for (int tick = 0; tick < 100; tick++) {
            assertEquals(TaskStatus.RUNNING, sleep.tick(ctx));
        }
        assertEquals(BED, ctx.bed.in);
    }

    @Test
    void atSunriseItGetsUp() {
        ctx.bed.beds.add(BED);
        Sleep sleep = new Sleep(BED.x(), BED.y(), BED.z());
        sleep.tick(ctx);

        ctx.percepts.timeOfDay = 24_000L + 100L;

        assertEquals(TaskStatus.SUCCESS, sleep.tick(ctx));
        assertNull(ctx.bed.in);
    }

    @Test
    void aBedSomeoneLiesInFails() {
        ctx.bed.beds.add(BED);
        ctx.bed.taken.add(BED);

        assertEquals(TaskStatus.FAILED, new Sleep(BED.x(), BED.y(), BED.z()).tick(ctx));
        assertNull(ctx.bed.in);
    }

    @Test
    void wokenByAnythingElseSucceeds() {
        ctx.bed.beds.add(BED);
        Sleep sleep = new Sleep(BED.x(), BED.y(), BED.z());
        sleep.tick(ctx);

        ctx.bed.in = null; // a hit, the bed broken

        assertEquals(TaskStatus.SUCCESS, sleep.tick(ctx));
    }

    @Test
    void cancelledItGetsUp() {
        ctx.bed.beds.add(BED);
        Sleep sleep = new Sleep(BED.x(), BED.y(), BED.z());
        sleep.tick(ctx);

        sleep.cancel(ctx);

        assertNull(ctx.bed.in);
        assertEquals(1, ctx.bed.getUps);
    }

    @Test
    void aRestartAsleepResumesWithoutGettingUp() {
        ctx.bed.beds.add(BED);
        ctx.bed.in = BED; // vanilla kept the sleeper lying across the restart

        assertEquals(TaskStatus.RUNNING, new Sleep(BED.x(), BED.y(), BED.z()).tick(ctx));
        assertEquals(0, ctx.bed.getUps);
    }
}
