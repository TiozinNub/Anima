package dev.luizloyola.anima.core.brain.instinct;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.DangerTable;
import dev.luizloyola.anima.core.brain.task.FleeStep;
import dev.luizloyola.anima.core.brain.task.Task;
import java.util.random.RandomGenerator;

/**
 * The emergency drive, reading the PERCEIVED world rather than an omniscient scan: pressure comes
 * from the AGGRESSIVE beings this body has made out. A creeper behind a wall does not press, one
 * that spawned behind goes unnoticed until it sounds or crosses the cone, and a hit does not reveal
 * the shooter.
 *
 * <p><b>Per aggressive being:</b> a linear ramp from its REACH (extended for a shooter — seen bow,
 * drawn aim, or {@link Danger#rangedSpecies}) to contact over the last {@link #ramp()} blocks,
 * times {@link Danger#weight} and the visible-gear modifiers, times {@link #approachBonus()} when
 * it is CLOSING IN, capped at 1.0. Overall pressure is the MAX across beings — danger does not
 * stack; nothing aggressive perceived → 0.0.
 *
 * <p>{@link #failCooldown()} is ten ticks, not the {@link Instinct#DEFAULT_FAIL_COOLDOWN 100}: a
 * cornered Person must retry immediately with a freshly-rolled direction.
 */
public final class FleeInstinct implements Instinct {

    /** Beyond this straight-line distance a melee threat exerts no pressure at all. */
    public static double range(AgentProfile profile) {
        return profile.d(ProfileAspect.FLEE_RANGE);
    }

    /** Pressure ramps linearly to full over this many blocks, ending at reach. */
    public static double ramp(AgentProfile profile) {
        return profile.d(ProfileAspect.FLEE_RAMP);
    }

    /** How much harder a threat that is measurably closing in presses on this body. */
    public static double approachBonus(AgentProfile profile) {
        return profile.d(ProfileAspect.FLEE_APPROACH_BONUS);
    }

    /** The emergency override of {@link Instinct#failCooldown()} — retry almost immediately. */
    public static final int FAIL_COOLDOWN = 10;

    private final RandomGenerator random;

    public FleeInstinct(RandomGenerator random) {
        this.random = random;
    }

    @Override
    public double pressure(BrainContext ctx) {
        double max = 0.0;
        for (Being being : ctx.percepts().beings()) {
            double pressure = pressureOf(ctx.profile(), ctx.danger(), being);
            if (pressure > max) {
                max = pressure;
            }
        }
        return max;
    }

    /** One being's contribution — public so tests and debug readouts price fear the same way. */
    public static double pressureOf(AgentProfile profile, DangerTable danger, Being being) {
        if (!being.aggressive()) {
            return 0.0; // masked tiers read non-aggressive: unmade-out things exert nothing
        }
        // Reach is the weapon's, not a multiple of ours. Something that shoots is feared from as
        // far as this body can perceive it at all; something that has to reach you is feared from
        // its own flee range.
        double reach = ranged(danger, being)
                ? profile.i(ProfileAspect.SENSES_RADIUS)
                : range(profile);
        double ramped = clamp01((reach - being.distance()) / ramp(profile));
        if (ramped == 0.0) {
            return 0.0;
        }
        // An aggressive thing with no species was masked by the ladder, and the default weight is
        // the wrong price for something currently shooting at us.
        String species = being.species().isEmpty() ? DangerTable.HOSTILE_KEY : being.species();
        double weight = danger.weight(species) * gearMult(profile, being.gear());
        double pressure = ramped * weight;
        if (being.approaching()) {
            pressure *= approachBonus(profile);
        }
        return Math.min(1.0, pressure);
    }

    /** Whether this threat's reach is a projectile's: seen ranged gear, a drawn aim, or a
     *  species that shoots bare-handed (blaze, ghast — no held item to see). */
    private static boolean ranged(DangerTable danger, Being being) {
        return being.gear().ranged() || being.activity() == Being.Activity.AIMING
                || danger.ranged(being.species());
    }

    /** The visible-equipment story, multiplied — armored < with sword < …. */
    private static double gearMult(AgentProfile profile, Being.Gear gear) {
        double mult = 1.0;
        if (gear.melee()) {
            mult *= profile.d(ProfileAspect.DANGER_MELEE_MULT);
        }
        if (gear.ranged()) {
            mult *= profile.d(ProfileAspect.DANGER_RANGED_MULT);
        }
        if (gear.armored()) {
            mult *= profile.d(ProfileAspect.DANGER_ARMORED_MULT);
        }
        if (gear.mounted()) {
            mult *= profile.d(ProfileAspect.DANGER_MOUNTED_MULT);
        }
        if (gear.baby()) {
            mult *= profile.d(ProfileAspect.DANGER_BABY_MULT);
        }
        return mult;
    }

    @Override
    public Task root(BrainContext ctx) {
        return new FleeStep(random);
    }

    @Override
    public int failCooldown() {
        return FAIL_COOLDOWN;
    }

    @Override
    public String describe() {
        return "flee";
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
