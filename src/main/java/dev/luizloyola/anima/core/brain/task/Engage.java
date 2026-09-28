package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.act.Striker;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.Gait;
import org.jspecify.annotations.Nullable;

/**
 * Close on somebody and hit them until they are dead: the loop inside {@link Fight}. It knows whom
 * it fights and nothing about why.
 *
 * <p>Its first tick draws the pack's best weapon, so the warmup runs during the chase, and it asks
 * again before every blow, since the last one may have broken it. Each tick it asks the arm where a
 * blow stands. In reach, it swings a reaction after the target came into it
 * ({@code combat.reaction_ticks}), from up to {@code combat.reach_inset} blocks inside full reach,
 * rolled per blow. The reaction counts while the weapon charges and waits at
 * {@code combat.reaction_hold_ticks} for the charge, so a target that stays close is hit soon
 * after the weapon is ready but never on the tick it is, and one stepping in waits the whole
 * reaction (Luiz, 2026-09-28). A machine that hits the tick you enter its reach, with every
 * charge full, is one nobody can touch. Out of reach, or with something in the way,
 * it chases the target's perceived cell — re-aimed whenever that cell changes, the way
 * {@link Converse} follows a speaker. It watches the target throughout, at the rank a deliberate
 * act looks at what it is doing.
 *
 * <p>It ends SUCCESS when the target is dying, whoever landed the last blow, and FAILED when the
 * target leaves the world, drops out of the percepts, or the chase stops gaining ground.
 */
public final class Engage implements PrimitiveTask {

    /** Beyond this many blocks the chase sprints; nearer, it walks, and arrives able to sweep. */
    static final double SPRINT_BEYOND = 6.0;
    /** Chase legs that may end without gaining ground before the target counts as unreachable. */
    static final int FRUITLESS_LIMIT = 3;

    private final BeingId target;
    private Pos lastKnown;
    private int fruitless;
    private @Nullable GoTo leg;
    private double legFrom;
    private String failure = "";
    /** Not saved: a restored fight asks again, and an arm already holding the best keeps it. */
    private boolean drawn;
    /** How far inside full reach this blow is swung from; rolled per blow, NaN until the first. */
    private double inset = Double.NaN;
    /** Ticks left of this blow's reaction, counted from when the target came into reach; -1 out of it. */
    private int reaction = -1;

    /**
     * @param target whom to fight
     * @param lastKnown where they were when the fight began
     */
    public Engage(BeingId target, Pos lastKnown) {
        this.target = target;
        this.lastKnown = lastKnown;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        Striker arm = ctx.actuators().striker();
        if (Double.isNaN(inset)) {
            inset = ctx.random().nextDouble() * ctx.profile().d(ProfileAspect.COMBAT_REACH_INSET);
        }
        Striker.Reach reach = arm.reach(target, inset);
        if (reach == Striker.Reach.DEAD) {
            dropLeg(ctx);
            return TaskStatus.SUCCESS;
        }
        if (reach == Striker.Reach.GONE) {
            return fail(ctx, "they are gone");
        }
        Being seen = seen(ctx);
        if (seen == null) {
            return fail(ctx, "lost track of them");
        }
        lastKnown = seen.pos();
        boolean asked = false;
        boolean handChanged = false;
        if (!drawn) {
            handChanged = arm.draw();
            asked = true;
            drawn = true;
        }
        TaskStatus status;
        if (reach == Striker.Reach.IN_REACH) {
            dropLeg(ctx);
            fruitless = 0;
            int whole = ctx.profile().i(ProfileAspect.COMBAT_REACTION_TICKS);
            int hold = Math.min(whole, ctx.profile().i(ProfileAspect.COMBAT_REACTION_HOLD_TICKS));
            if (reaction < 0) {
                reaction = whole; // came into reach: the reaction starts now, charged or not
                if (!asked) {
                    handChanged = arm.draw(); // the last blow may have broken the weapon
                }
            }
            // A hand that changed this tick has not charged, whatever the counter said a moment ago.
            boolean charged = arm.charge() >= 1.0 && !handChanged;
            if (charged || reaction > hold) {
                reaction--;
            }
            if (charged && reaction <= 0) {
                arm.strike(target);
                reaction = -1;
                inset = ctx.random().nextDouble() * ctx.profile().d(ProfileAspect.COMBAT_REACH_INSET);
            }
            status = TaskStatus.RUNNING;
        } else {
            reaction = -1; // the opening is gone; the next one is reacted to afresh
            status = chase(ctx, seen);
        }
        // Asked last, after any leg's own glance where it walks — this claim outranks that one.
        ctx.actuators().gazer().lookAt(lastKnown.x() + 0.5, lastKnown.y() + seen.eyeHeight(),
                lastKnown.z() + 0.5, Gazer.Priority.WORK);
        return status;
    }

    /** One tick of closing in on where they are now. */
    private TaskStatus chase(BrainContext ctx, Being seen) {
        Pos at = seen.pos();
        if (leg == null || leg.x() != at.x() || leg.y() != at.y() || leg.z() != at.z()) {
            dropLeg(ctx);
            leg = new GoTo(at.x(), at.y(), at.z(),
                    seen.distance() > SPRINT_BEYOND ? Gait.SPRINT : Gait.WALK);
            legFrom = seen.distance();
        }
        if (leg.tick(ctx) == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        // The leg ended with them still out of reach. Re-aims because they moved are not priced —
        // only a leg that stopped without getting any closer says the ground is against us.
        if (seen.distance() < legFrom) {
            fruitless = 0;
        } else if (++fruitless >= FRUITLESS_LIMIT) {
            return fail(ctx, "could not get to them");
        }
        leg = null;
        return TaskStatus.RUNNING;
    }

    private TaskStatus fail(BrainContext ctx, String why) {
        dropLeg(ctx);
        failure = why;
        return TaskStatus.FAILED;
    }

    private void dropLeg(BrainContext ctx) {
        if (leg != null) {
            leg.cancel(ctx);
            leg = null;
        }
    }

    private @Nullable Being seen(BrainContext ctx) {
        for (Being being : ctx.percepts().beings()) {
            if (being.id().equals(target)) {
                return being;
            }
        }
        return null;
    }

    @Override
    public void cancel(BrainContext ctx) {
        dropLeg(ctx);
    }

    @Override
    public String describe() {
        return "engage " + target;
    }

    @Override
    public String failureDetail() {
        return failure;
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────
    // The leg is not saved: the first tick after a load orders a fresh one at the target's cell,
    // which is where a saved leg would have been going.

    public BeingId target() {
        return target;
    }

    public Pos lastKnown() {
        return lastKnown;
    }

    public int fruitless() {
        return fruitless;
    }

    public Engage resume(int fruitless) {
        this.fruitless = fruitless;
        return this;
    }
}
