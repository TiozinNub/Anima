package dev.luizloyola.anima.core.brain.instinct;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.task.DigIn;
import dev.luizloyola.anima.core.brain.task.NightShelterStep;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import java.util.OptionalLong;

/**
 * The pull to be under cover at night: "the pull to shelter ramps from dusk and wins over work once
 * night falls" (Luiz, 2026-10-20). Nothing by day; from sunset it rises to
 * {@link ProfileAspect#NIGHT_SHELTER_PRESSURE} at nightfall and holds there until sunrise, when
 * the sun starts burning what spawned in the dark.
 *
 * <p>It bids only while there is somewhere to go ({@link NightShelterStep#somewhere}). Nothing it
 * acts on is its own to keep: the hour is the clock's, the shelter the body's memory and its own
 * check, so a body restarted mid-night carries on.
 */
public final class NightShelterInstinct implements Instinct {

    /** Sunset, where dusk starts, in ticks into the day. */
    public static final long SUNSET = 12_000L;

    /** Nightfall, where dusk ends and the pull is at full. */
    public static final long NIGHTFALL = 13_500L;

    /** Longer than the default: a body with nowhere to go at night tries again every 10 s, not 5. */
    static final int FAIL_COOLDOWN = 200;

    /** The pull at {@code timeOfDay} for a body whose night pull is {@code full}. */
    public static double pull(long timeOfDay, double full) {
        long t = Math.floorMod(timeOfDay, 24_000L);
        if (t < SUNSET) {
            return 0.0;
        }
        if (t >= NIGHTFALL) {
            return full;
        }
        return full * (t - SUNSET) / (double) (NIGHTFALL - SUNSET);
    }

    /** This body's pull now; 0 where the sun keeps no hours. */
    public static double pull(BrainContext ctx) {
        double full = ctx.profile().d(ProfileAspect.NIGHT_SHELTER_PRESSURE);
        if (full <= 0.0) {
            return 0.0;
        }
        OptionalLong time = ctx.percepts().timeOfDay();
        return time.isPresent() ? pull(time.getAsLong(), full) : 0.0;
    }

    /**
     * Whether this body is in a pocket it dug for tonight, shut in on purpose: the escape drive
     * leaves it there until sunrise.
     */
    public static boolean dugInHere(BrainContext ctx) {
        return pull(ctx) > 0.0 && DigIn.begun(ctx) != null;
    }

    @Override
    public double pressure(BrainContext ctx) {
        double pull = pull(ctx);
        return pull > 0.0 && NightShelterStep.somewhere(ctx) ? pull : 0.0;
    }

    @Override
    public Task root(BrainContext ctx) {
        return new NightShelterStep();
    }

    @Override
    public void ended(BrainContext ctx, Task root, TaskStatus status) {
        if (status == TaskStatus.FAILED && root instanceof NightShelterStep step) {
            step.failed(ctx);
        }
    }

    @Override
    public Deed doing(BrainContext ctx) {
        return Deed.of(Doings.SHELTERING);
    }

    @Override
    public String describe() {
        return "shelter";
    }

    @Override
    public int failCooldown() {
        return FAIL_COOLDOWN;
    }
}
