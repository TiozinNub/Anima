package dev.luizloyola.anima.mod.body;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.attention.Aim;
import dev.luizloyola.anima.core.brain.attention.Attention;
import dev.luizloyola.anima.mod.nav.Swimmer;
import java.util.Locale;
import java.util.Random;
import java.util.random.RandomGenerator;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Where a body's head points — the one owner of its gaze, and what makes an idle body look alive.
 *
 * <p>An organ, not a task: a mind runs one plan at a time, so looking-as-a-task would mean watching
 * somebody <em>instead of</em> chopping. Ticked by the body, every pause it already has gets living
 * eyes. {@code BeingSense} and {@code PoiSensor} take the head's bearing as their cone axis, so this
 * organ also decides what the body is able to notice.
 *
 * <p>It took the head off three writers that snapped it — the legs, each arm actuator and the
 * swimmer — which left a body that stopped walking frozen, staring at its last waypoint. See
 * {@code docs/superpowers/specs/2026-08-11-attention-and-idle-life-design.md}.
 *
 * <p>The ownership rule that keeps this from becoming a fourth writer:
 *
 * <ul>
 *   <li>{@code yRot}, the STEERING yaw, is the legs' while they drive: vanilla's {@code travel}
 *       rotates the movement input by it, so writing it steers the body.
 *   <li>{@code yHeadRot} and {@code xRot} are this organ's alone. Pitch is render-only for a walking
 *       body, and vanilla never touches an {@code Avatar}'s head (only {@code Mob} overrides
 *       {@code tickHeadTurn}).
 *   <li>{@code yBodyRot} is vanilla's while the body moves; undriven, this organ turns it, since
 *       {@code tickHeadTurn} then eases nowhere and only its 50° clamp acts, which would leave a
 *       body wrenched 50° from whoever it turned to look at.
 * </ul>
 *
 * <p><b>Server-side ownership is only half of it, for a body that is not a {@code Mob}.</b>
 * {@code yBodyRot} is in no packet — a client rebuilds it. {@code Mob} rebuilds it from the synced
 * head ({@code BodyRotationControl}); everything else falls back to {@code LivingEntity}, which
 * eases toward the WALK HEADING and then clamps within 50° of {@code yRot}. A body turning on the
 * spot moves nowhere, so the ease has no target and only the clamp acts — and a clamp does not
 * converge: it parks the shoulders exactly 50° behind and leaves them there until the body walks
 * or swings. Such a body must square its own shoulders to {@code yRot} client-side (override
 * {@code tickHeadTurn}), or every twist this organ limits to {@code GAZE_MAX_TWIST_DEGREES}
 * renders 50° wider than that.
 *
 * <p>The swimmer owns a wet body's pitch by running after this, and this organ stands off the pitch
 * entirely while it works: two easers on one number judder rather than average.
 *
 * <p>Ticked once per tick by the body, after the navigator and the arm actuators (their claims are
 * its input) and before the swimmer. Server side, single-threaded.
 */
public final class Gaze implements Gazer {

    /**
     * How long a work look is held after the act that asked for it — half a second. A one-shot act
     * would otherwise hold its look for one tick, which reads as a twitch rather than a glance.
     */
    public static final int WORK_HOLD_TICKS = 10;

    /**
     * How far out (blocks) a bearing-shaped claim puts its point — far enough that a step does not
     * swing the head. {@link #lookAlong} means "that way", not a spot in front of the face.
     */
    private static final double LOOK_AHEAD = 12.0;

    /** How much faster a startled head turns than a considered one. */
    private static final float SNAP_TURN_MULT = 4.0F;

    private final AgentBody body;

    /** The idle picker — pure, and the whole of what this organ decides for itself. */
    private final Attention attention = new Attention();

    /**
     * This organ's own stream of chance, neither seeded from the agent nor saved: sharing the
     * brain's {@code AgentRandom} would let the number of head turns decide the next wander roll,
     * and that stream is saved precisely so it cannot drift. A gaze is re-rolled from live position
     * within seconds, so there is nothing to restore.
     */
    private final RandomGenerator random = new Random();

    /** One live claim per rank; {@code null} when nothing at that rank has asked. */
    private final Claim[] claims = new Claim[Priority.values().length];

    /** What the head is actually aiming at right now, for readouts. */
    private Vec3 aim;

    /**
     * Whether the shoulders are mid-turn — the one piece of state {@link Aim} cannot re-derive.
     * Without the latch a body would stop the instant its neck was comfortable and stand there
     * wrenched most of the way round.
     */
    private boolean turning;

    /** What last won the head — printed by {@link #describe()}, never read as a decision. */
    private String reason = "nothing";

    public Gaze(AgentBody body) {
        this.body = body;
    }

    @Override
    public void lookAt(double x, double y, double z, Priority priority, int holdTicks) {
        long now = this.body.level().getGameTime();
        this.claims[priority.ordinal()] = new Claim(new Vec3(x, y, z), now + Math.max(1, holdTicks));
    }

    /**
     * Look along a heading — the legs' form of a claim, and the reason the mover no longer writes
     * the head. A bearing, not a point, because the direction being pressed this tick is not the
     * waypoint it is pressing toward: a deflecting body should look where it is actually walking.
     *
     * @param headingDegrees the direction being walked, Minecraft convention (0° is +Z)
     */
    public void lookAlong(float headingDegrees, Priority priority, int holdTicks) {
        Vec3 eye = this.body.eyePosition();
        double yaw = Math.toRadians(headingDegrees);
        lookAt(eye.x - Math.sin(yaw) * LOOK_AHEAD, eye.y, eye.z + Math.cos(yaw) * LOOK_AHEAD,
                priority, holdTicks);
    }

    /** One tick of looking: resolve the claims, decide the aim, ease the head onto it. */
    public void tick() {
        LivingEntity entity = this.body.entity();
        long now = this.body.level().getGameTime();
        if (this.body.agentId() == null) {
            // A body one tick old: its memory of places is keyed by an identity nobody has decided
            // yet, and one tick of not looking anywhere is not something an observer could catch.
            return;
        }
        Claim winner = null;
        Priority rank = null;
        for (int i = this.claims.length - 1; i >= 0; i--) {
            Claim claim = this.claims[i];
            if (claim != null && claim.until() > now) {
                winner = claim;
                rank = Priority.values()[i];
                break;
            }
        }
        // A live claim from the legs is the question "is this body being driven": while it
        // is, the steering yaw is not ours to move.
        Claim driving = this.claims[Priority.NAV.ordinal()];
        boolean bodyFree = driving == null || driving.until() <= now;
        if (winner != null && rank != Priority.IDLE) {
            // What the body had chosen to look at was chosen from where it used to be standing, so
            // it does not survive the interruption.
            this.attention.clear();
            this.reason = rank == Priority.WORK ? "work" : "walking";
            aimAt(entity, winner.at(), bodyFree, false);
            return;
        }
        Vec3 eye = this.body.eyePosition();
        // Off the SHOULDERS, not the head: a scan measured from where the head already points
        // compounds every roll, and the body revolves on the spot. The brain's own eyes and memory,
        // so the organ never looks at something the mind does not believe is there.
        Attention.Focus focus = this.attention.tick(eye.x, eye.y, eye.z, entity.yBodyRot, now,
                this.body.brain().percepts(), this.body.brain().knowledge(), this.body.danger(),
                this.body.profile(), this.random);
        this.reason = focus.reason();
        if (!aimAt(entity, new Vec3(focus.x(), focus.y(), focus.z()), bodyFree, focus.snap())) {
            // Past the neck's twist and the shoulders are the legs' — holding the focus would mean
            // staring at the twist limit until it expired. Choose again next tick.
            this.attention.clear();
        }
    }

    /**
     * Work out this tick's head with {@link Aim} and put it on the entity — the whole of what this
     * organ writes, in one place, so "who moved the head" has exactly one answer.
     *
     * @return whether the target is one this body can actually look at; {@code false} when it sits
     *     past the neck's twist and the shoulders are not ours to turn after it
     */
    private boolean aimAt(LivingEntity entity, Vec3 target, boolean bodyFree, boolean snap) {
        this.aim = target;
        Vec3 eye = this.body.eyePosition();
        Aim aimed = Aim.of(target.x - eye.x, target.y - eye.y, target.z - eye.z,
                entity.getYHeadRot(),
                // The SHOULDERS, not the steering yaw: what renders as a twisted neck is the head
                // against the body, and while walking those two are not the same number.
                entity.yBodyRot,
                entity.getXRot(),
                // A startle is the one look allowed to be a snap rather than a turn; everything
                // else eases, or every glance reads as a twitch.
                (float) this.body.profile().d(ProfileAspect.GAZE_TURN_DEGREES)
                        * (snap ? SNAP_TURN_MULT : 1.0F),
                this.body.profile().i(ProfileAspect.GAZE_MAX_TWIST_DEGREES),
                bodyFree, this.turning);
        this.turning = aimed.turning();
        entity.setYHeadRot(aimed.headYaw());
        // The pitch only when it is ours: a wet body's belongs to the swimmer, which eases it a
        // moment after this runs, and two easers on one number judder rather than average.
        if (this.body.swimmer().state() == Swimmer.State.DRY) {
            entity.setXRot(aimed.pitch());
        }
        if (aimed.bodyYaw() != entity.yBodyRot) {
            // Whether the shoulders MOVED, not whether they are still turning, so the last step of
            // a turn (squaring them and dropping the latch) is not the one dropped. Both fields,
            // same value: vanilla clamps yBodyRot within 50° of yRot every tick, so leaving them
            // apart puts that clamp in a fight with this organ's easing.
            entity.setYRot(aimed.bodyYaw());
            entity.setYBodyRot(aimed.bodyYaw());
        }
        return aimed.reachable();
    }

    /**
     * What this body is looking at, why, and what it turned down for it — a debug readout, never a
     * decision. The runner-up cannot be seen from outside: scanning past somebody reads the same
     * whether the picker never noticed them or priced them at nothing.
     */
    public String describe() {
        String verdict = this.attention.verdict();
        if (this.aim == null) {
            return "gaze: " + this.reason + " | " + verdict;
        }
        return String.format(Locale.ROOT, "gaze: %s (%.1f, %.1f, %.1f) | %s",
                this.reason, this.aim.x, this.aim.y, this.aim.z, verdict);
    }

    private record Claim(Vec3 at, long until) {
    }
}
