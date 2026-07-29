package dev.luizloyola.anima.mod.body;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.Needs;
import dev.luizloyola.anima.core.agent.Pronouns;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.log.AgentJournal;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.mod.brain.AgentBlockBreaker;
import dev.luizloyola.anima.mod.brain.BrainDriver;
import dev.luizloyola.anima.mod.brain.PoiSensor;
import dev.luizloyola.anima.mod.brain.AgentScaffolder;
import dev.luizloyola.anima.mod.brain.BeingSense;
import dev.luizloyola.anima.mod.nav.Navigator;
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
     * This body's locomotion state machine — the single owner of pathing, following and per-tick
     * steering. The brain asks for destinations, not for legs.
     */
    Navigator navigator();

    /**
     * What this body currently perceives — eyes, ears, attention and the identification ladder.
     * Owned and ticked by the body: perception is something a body <em>does</em>.
     */
    BeingSense beingSense();

    /** The body's block-breaking actuator, which it owns and ticks (crack, drops, exhaustion). */
    AgentBlockBreaker blockBreaker();

    /** The body's scaffolding actuator — the pillar ledger lives with the body that built it. */
    AgentScaffolder scaffolder();

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

    /** Turn to look at the centre of {@code cell}. */
    void faceBlock(BlockPos cell);

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
