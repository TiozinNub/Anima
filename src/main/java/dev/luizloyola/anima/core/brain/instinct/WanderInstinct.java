package dev.luizloyola.anima.core.brain.instinct;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.WanderStep;
import java.util.random.RandomGenerator;

/**
 * The idle-default drive — what they do when nothing else is pressing. Its pressure is a constant
 * {@link #idlePressure(AgentProfile)} floor, so an unbothered Person drifts instead of standing
 * frozen; its root is a fresh {@link WanderStep}, re-granted each time the last finishes.
 *
 * <p>The RNG is core-pure ({@link RandomGenerator}, so the mod hands in a seeded
 * {@code java.util.Random}, not an entity {@code RandomSource}) and threads through each
 * {@link WanderStep}, making the wander one reproducible stream per Person. A placeholder gait:
 * routine and purposeful idling arrive with the {@code rest} need.
 */
public final class WanderInstinct implements Instinct {

    /**
     * The ambient pressure every real drive must beat to take over — low enough that once peckish
     * (0.30) sticks past the arbiter's stickiness it loses to Eat, high enough to beat doing
     * nothing. Set it to zero and an unbothered Person stands still.
     */
    public static double idlePressure(AgentProfile profile) {
        return profile.d(ProfileAspect.WANDER_IDLE_PRESSURE);
    }

    /** Default roam radius (whole blocks) when the caller doesn't specify — a modest saunter. */
    public static int defaultRadius(AgentProfile profile) {
        return profile.i(ProfileAspect.WANDER_RADIUS);
    }

    private final RandomGenerator random;
    /** An explicit caller-pinned radius, or {@code null} to follow {@link #defaultRadius(AgentProfile)} live. */
    private final Integer radius;

    public WanderInstinct(RandomGenerator random, int radius) {
        this.random = random;
        this.radius = radius;
    }

    /**
     * Wander with the {@link #defaultRadius(AgentProfile) configured radius} — and keep following
     * it, so a {@code /anima config reload} re-tunes Persons already walking around.
     */
    public WanderInstinct(RandomGenerator random) {
        this.random = random;
        this.radius = null;
    }

    @Override
    public double pressure(BrainContext ctx) {
        return idlePressure(ctx.profile());
    }

    @Override
    public Task root(BrainContext ctx) {
        return new WanderStep(random, radius == null ? defaultRadius(ctx.profile()) : radius);
    }

    @Override
    public String describe() {
        return "wander";
    }
}
