package dev.luizloyola.anima.mod.body;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.AgentModifiers;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.agent.Pronouns;
import dev.luizloyola.anima.core.agent.need.Needs;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.sense.DangerTable;
import dev.luizloyola.anima.core.brain.sense.Setbacks;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.log.AgentJournal;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.mod.brain.AgentBlockBreaker;
import dev.luizloyola.anima.mod.brain.AgentRiser;
import dev.luizloyola.anima.mod.brain.BrainDriver;
import dev.luizloyola.anima.mod.brain.PoiSensor;
import dev.luizloyola.anima.mod.brain.BeingSense;
import dev.luizloyola.anima.mod.nav.Navigator;
import dev.luizloyola.anima.mod.nav.Swimmer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * What Anima needs a body to be, so that a mind can drive it — the contract the split rests on.
 * Everything above it is written against <em>this</em>, never against a Person, so a wolf or a
 * golem could implement it too.
 *
 * <p>A living entity plus a mind's worth of extras: vanilla's own accessors are read off
 * {@link #entity()} rather than wrapped, and only what vanilla has no concept of is declared here.
 * The act verbs are intent, not physics ({@link #driveForward} says "walk that way", not "set delta
 * movement"), the seam that lets a quadruped honour the same contract as a biped.
 *
 * <p>Nothing here is about being human: names, skins, genders, models and dialogue are the
 * consuming mod's. {@link #pronouns()} asks for three words rather than a gender so a body with no
 * gender can still be described. See {@link Pronouns}.
 */
public interface AgentBody {

    /**
     * This body as a Minecraft entity — the escape hatch to everything vanilla already answers
     * (position, rotation, hands, bounding box, random, water, ground contact). Implementors return
     * {@code this}.
     */
    LivingEntity entity();

    /**
     * Who this is — stable across unloads, deaths and restarts, and the key everything durable
     * hangs off (knowledge, claims, journal, contacts).
     *
     * <p>Null until identity is resolved, typically on the body's first tick.
     */
    @Nullable
    AgentId agentId();

    /** This body's debug journal view, already pinned to {@link #agentId()}. */
    AgentJournal journal();

    Inventory inventory();

    Metabolism metabolism();

    /**
     * Every gauge this body feels — hunger, company, whatever else its mod declared. The metabolism
     * above is the organ behind {@code need.food}; this roster ticks them all on one beat and prints
     * them without knowing what any of them are.
     */
    Needs needs();

    /**
     * How to refer to this body in narration. Return {@link Pronouns#THEY} if there is nothing
     * more specific to say — that is a real answer, not a fallback.
     */
    Pronouns pronouns();

    /**
     * What this body is like — how far it perceives, how wide it looks. Organs read their aspects
     * from here rather than from a global, which makes a wolf's eyesight a wolf's.
     *
     * <p>No default: inventing one is how every agent in the world came to see exactly
     * 24 blocks. Return the profile generated from a
     * {@link dev.luizloyola.anima.core.agent.SpeciesProfile}, the same object every call, since
     * organs hold onto it (a live view, so that is safe — {@link AgentProfile}).
     */
    AgentProfile profile();

    /**
     * What is currently shifting this body away from its species — a trait, a skill, a job.
     *
     * <p>Defaults to {@link AgentModifiers#NONE}, unlike {@link #profile()}: "exactly my species" is
     * a complete answer where "no species at all" was not. Fold a consumer's own set in with
     * {@link dev.luizloyola.anima.core.agent.ModifiedProfile#of}.
     */
    default AgentModifiers modifiers() {
        return AgentModifiers.NONE;
    }

    /**
     * How frightening this body finds each kind of thing. Defaults to {@link DangerTable#NEUTRAL}:
     * inventing fears for a body that has said nothing would be invention.
     *
     * <p>Read through a {@link dev.luizloyola.anima.core.brain.sense.DangerStore}, not cached, so a
     * world load's regeneration reaches a body already walking around.
     */
    default DangerTable danger() {
        return DangerTable.NEUTRAL;
    }

    /**
     * This body's locomotion state machine — the single owner of pathing, following and per-tick
     * steering. The brain asks for destinations, not for legs.
     */
    Navigator navigator();

    /**
     * What this body currently perceives — eyes, ears, attention and the identification ladder.
     * Owned and ticked by the body: perception is something a body <em>does</em>.
     */
    BeingSense beingSense();

    /**
     * Where this body has lately been beaten — a short, fading memory of trouble, so a retry is a
     * different attempt. Owned by the body rather than by whatever asked for the walk: a cell that
     * keeps defeating these legs has to outlive the order that discovered it.
     */
    Setbacks setbacks();

    /** The body's block-breaking actuator, which it owns and ticks (crack, drops, exhaustion). */
    AgentBlockBreaker blockBreaker();

    /** The body's rise-one actuator, which it owns and ticks (centring, jump, place). */
    AgentRiser riser();

    /**
     * What this body does about being in water — buoyancy, wading, and getting out again. Ticked
     * <em>after</em> the {@link Navigator}, whose {@link Navigator#waterIntent()} it reads and whose
     * vertical input it has the last word on.
     */
    Swimmer swimmer();

    /**
     * Where this body's eyes go — the one owner of its head, and its idle attention. Ticked
     * <em>after</em> the {@link Navigator} and the arm actuators (their claims this tick are its
     * input) but <em>before</em> the {@link Swimmer}, which has the last word on a wet pitch.
     */
    Gaze gaze();

    /**
     * The mind mounted on this body — the arbiter, its running task tree, and the autonomy switch.
     * Owned and ticked by the body, like the senses.
     */
    BrainDriver brain();

    /**
     * The body's point-of-interest sense — what it notices as it goes, feeding the durable
     * knowledge store. Like {@link #beingSense()}, the body runs it.
     */
    PoiSensor poiSensor();

    // ---- act verbs: intent, not physics ------------------------------------------------

    /** Walk toward {@code heading} (degrees) at full throttle. */
    void driveForward(float heading);

    /** Walk toward {@code heading} (degrees) at {@code throttle} of full speed. */
    void driveForward(float heading, float throttle);

    /** Stop walking. Does not clear a running navigation. */
    void stopMoving();

    /** Sprint or stop sprinting, if this body has a notion of sprinting. */
    void driveSprint(boolean sprint);

    /** Jump, if able and on the ground. */
    void driveJump();

    /**
     * Movement control: swim down this tick, at {@code throttle} of full effort (0..1).
     *
     * <p>The one input that only means anything in water: everything else is horizontal or a jump,
     * and buoyancy answers both while wet, so without this a body cannot be asked to go under.
     * Outside water, doing nothing is correct.
     */
    void driveDown(float throttle);

    /**
     * Look at the centre of {@code cell} — the arm's form of a gaze claim, shared by every arm
     * actuator.
     *
     * <p>No longer implemented per body: turning the head itself made every arm actuator a second
     * owner of it and froze a body the moment it stopped. It is a {@link Gazer.Priority#WORK} claim
     * held for {@link Gaze#WORK_HOLD_TICKS}, so a one-shot act gets a glance, not a twitch.
     */
    default void faceBlock(BlockPos cell) {
        gaze().lookAt(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5,
                Gazer.Priority.WORK, Gaze.WORK_HOLD_TICKS);
    }

    /** Path to {@code target} at the body's default gait. */
    void navigateTo(Vec3 target);

    void navigateTo(Vec3 target, Gait gait);

    // ---- convenience -------------------------------------------------------------------
    // The vanilla reads Anima performs constantly, defaulted so an implementor never writes them
    // and call sites need not chain through entity().

    default Level level() {
        return entity().level();
    }

    default BlockPos blockPosition() {
        return entity().blockPosition();
    }

    /** Where this body's eyes are — the origin of every line of sight. */
    default Vec3 eyePosition() {
        return entity().getEyePosition();
    }

    default Vec3 position() {
        return entity().position();
    }

    default boolean onGround() {
        return entity().onGround();
    }
}
