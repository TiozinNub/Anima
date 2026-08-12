package dev.luizloyola.anima.core.brain.instinct;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.task.EscapeStep;
import dev.luizloyola.anima.core.brain.task.Task;

/**
 * The drive to get out of somewhere this body cannot walk out of.
 *
 * <p>Layer 1: the legs must not decide to change the world — "the pathfinder reports, the brain
 * decides". It outranks every other want, since every other want is about somewhere the body
 * cannot currently get to.
 *
 * <p><b>Bidding on a fact, not a feeling.</b> Where other instincts read a gauge and ramp, this one
 * reads {@code confinement().sealed()}, a search's own proof that there is nowhere left to go, so
 * the bid is flat.
 *
 * <p>It also stops a loop: a sealed body used to fail a wander, sit out a hundred ticks, roll
 * another target and fail again forever, each turn costing a fresh terrain capture and a full
 * search.
 */
public final class EscapeInstinct implements Instinct {

    /**
     * How long the drive sits out after a step failed. Longer than the default: the
     * failure that matters is a body saying it cannot get out, and the useful cadence for that is
     * occasionally, not six times a second. A failed CUT shares the timer — something is in the way
     * that the arm would not take.
     */
    public static final int FAIL_COOLDOWN = 200;

    /** How hard being shut in presses — see {@link ProfileAspect#ESCAPE_PRESSURE}. */
    public static double pressure(AgentProfile profile) {
        return profile.d(ProfileAspect.ESCAPE_PRESSURE);
    }

    @Override
    public double pressure(BrainContext ctx) {
        return ctx.percepts().confinement().sealed() ? pressure(ctx.profile()) : 0.0;
    }

    @Override
    public Task root(BrainContext ctx) {
        return new EscapeStep();
    }

    @Override
    public String describe() {
        return "escape";
    }

    @Override
    public int failCooldown() {
        return FAIL_COOLDOWN;
    }
}
