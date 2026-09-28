package dev.luizloyola.anima.core.brain.instinct;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.history.Whom;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Combatant;
import dev.luizloyola.anima.core.brain.sense.DangerTable;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import dev.luizloyola.anima.core.brain.task.Fight;
import dev.luizloyola.anima.core.brain.task.FleeStep;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.log.Category;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Fight or flight: the emergency drive, and the one place a body decides which (combat spec,
 * 2026-09-27). It reads the PERCEIVED world rather than an omniscient scan: a creeper behind a wall
 * does not press, one that spawned behind goes unnoticed until it sounds or crosses the cone.
 *
 * <p><b>Pressure is fear.</b> Per aggressive being: a linear ramp from its REACH (a shooter's is
 * how far this body perceives at all) to contact over the last {@link #ramp} blocks, times its
 * species' danger weight and the visible-gear modifiers, times {@link #approachBonus} when it is
 * closing in, capped at 1. Whatever hit this body is priced at least as something hostile. A lit
 * explosive raises it by its {@link #blast}. The maximum across beings wins; danger does not stack.
 *
 * <p><b>The answer is the balance</b>: how long the threats in range would take to kill this body,
 * over how long it would take to kill its target. It fights at {@code instincts.fight_start_ratio}
 * or better, keeps a fight it is in down to {@code fight_quit_ratio}, and takes the worse
 * {@code fight_cornered_ratio} when nothing can be outrun. A lit explosive past
 * {@code fight_blast_line} is run from whatever the balance says. The answer is weighed at every
 * grant and every tick after ({@link #reconsider}), so three more zombies break off a fight, and a
 * zombie one blow from dead is still finished by a body at four hearts.
 *
 * <p><b>The target</b> is the threat worth most: its fear, more if it hit this body, less the
 * longer it would take to kill, with an edge for the one already being fought. A target a fight
 * could not reach is left out for {@link #UNREACHABLE_TICKS}.
 */
public final class FightOrFlightInstinct implements Instinct {

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

    /** How long a target a fight could not get to is left out of the choosing. */
    static final int UNREACHABLE_TICKS = 200;

    /** The edge the target already being fought has, so two alike zombies do not trade places. */
    static final double TARGET_STICKINESS = 1.25;

    /** How much more a threat that hit this body is worth fighting than one that has not. */
    static final double ATTACKER_BONUS = 1.5;

    /** The fuse at which a lit explosive counts in full. A creeper's runs 28 ticks to 1. */
    static final double URGENT_FUSE = 0.5;

    /**
     * How far past its blast reach a lit explosive still keeps a body running. A creeper's fuse
     * runs back down only once its target is 7 blocks off, one past its reach of 6, so turning
     * back at the edge of the blast walks into a fuse that is still burning.
     */
    static final double FUSE_MARGIN = 2.0;

    /** Targets a fight could not get to, until when. Not saved: a restart tries them once more. */
    private final Map<BeingId, Long> unreachable = new HashMap<>();
    /** The answer last written to the journal, so a steady fight is one line and not one a tick. */
    private @Nullable String lastSaid;
    /** Set when a blast sent this body running; held until no fuse near it is still burning. */
    private boolean waitingOutFuse;
    /** One decision per tick and per fight in hand: {@link #doing} and {@link #root} ask in turn. */
    private @Nullable Stance memo;
    private long memoAt = Long.MIN_VALUE;
    private @Nullable BeingId memoFor;

    /**
     * One answer, and what it rested on.
     *
     * @param target   whom to fight; null when running
     * @param balance  time to be killed over time to kill the target
     * @param blast    how close the nearest lit explosive is to going off, 0 to 1
     * @param because  the answer in words, for the journal
     */
    public record Stance(boolean fight, @Nullable Being target, double balance, boolean cornered,
                         double blast, String because) {
    }

    public FightOrFlightInstinct() {
    }

    @Override
    public double pressure(BrainContext ctx) {
        Percepts percepts = ctx.percepts();
        double max = 0.0;
        for (Being being : percepts.beings()) {
            double pressure = pressureOf(ctx.profile(), ctx.danger(), being,
                    percepts.attackedLately(being.id()));
            if (pressure <= 0.0) {
                continue;
            }
            double blast = percepts.combatant(being.id())
                    .map(them -> blast(being.distance(), them))
                    .orElse(0.0);
            max = Math.max(max, Math.max(pressure, blast));
        }
        return max;
    }

    /** {@link #pressureOf(AgentProfile, DangerTable, Being, boolean)} for a being that has not hit us. */
    public static double pressureOf(AgentProfile profile, DangerTable danger, Being being) {
        return pressureOf(profile, danger, being, false);
    }

    /** One being's contribution — public so tests and debug readouts price fear the same way. */
    public static double pressureOf(AgentProfile profile, DangerTable danger, Being being,
                                    boolean attackedMe) {
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
        // the wrong price for something currently shooting at us. Whatever did hit us is priced
        // at least that high: a player's species weighs nothing until they swing.
        String species = being.species().isEmpty() ? DangerTable.HOSTILE_KEY : being.species();
        double weight = danger.weight(species);
        if (attackedMe) {
            weight = Math.max(weight, danger.weight(DangerTable.HOSTILE_KEY));
        }
        double pressure = ramped * weight * gearMult(profile, being.gear());
        if (being.approaching()) {
            pressure *= approachBonus(profile);
        }
        return Math.min(1.0, pressure);
    }

    /**
     * How close a lit explosive is to hurting this body, 0 to 1: nearness within its blast reach
     * (full at a block, nothing at the edge) times how far its fuse has run, counted in full from
     * {@link #URGENT_FUSE}. It falls off fast — a creeper that has just started hissing ten blocks
     * away is nothing, one a block off and about to go is everything (decision: Luiz).
     */
    public static double blast(double distance, Combatant them) {
        if (them.fuse() <= 0.0 || them.blastReach() <= 1.0) {
            return 0.0;
        }
        double near = clamp01((them.blastReach() - distance) / (them.blastReach() - 1.0));
        return clamp01(near * them.fuse() / URGENT_FUSE);
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

    /**
     * Fight or flight, as of now.
     *
     * @param fighting whom this body is already fighting, which holds the fight to the lower line
     *                 and gives that target its edge; null when it is not fighting
     */
    public Stance decide(BrainContext ctx, @Nullable BeingId fighting) {
        Percepts percepts = ctx.percepts();
        long now = percepts.time();
        if (memo != null && memoAt == now && Objects.equals(memoFor, fighting)) {
            return memo;
        }
        unreachable.values().removeIf(until -> until <= now);
        AgentProfile profile = ctx.profile();
        Combatant me = percepts.selfAsCombatant().orElse(null);
        double theirDamagePerSecond = 0.0;
        double blast = 0.0;
        boolean fuseBurning = false;
        boolean outrunsAll = true;
        Being best = null;
        double bestValue = 0.0;
        double bestKillTime = Double.POSITIVE_INFINITY;
        for (Being being : percepts.beings()) {
            boolean attackedMe = percepts.attackedLately(being.id());
            double fear = pressureOf(profile, ctx.danger(), being, attackedMe);
            if (fear <= 0.0) {
                continue;
            }
            Combatant them = percepts.combatant(being.id()).orElse(null);
            if (them == null) {
                continue; // no body to size up: gone, dying, or a sound with nothing behind it
            }
            blast = Math.max(blast, blast(being.distance(), them));
            fuseBurning |= them.fuse() > 0.0
                    && being.distance() < them.blastReach() + FUSE_MARGIN;
            if (me == null) {
                continue;
            }
            theirDamagePerSecond += them.damagePerSecondAgainst(me.armor(), me.toughness());
            boolean dangerous = them.hitsPerSecond() > 0.0 || them.blastReach() > 0.0;
            // Strictly faster: a chaser only as quick as this body never closes the gap.
            if (dangerous && them.pace() > me.pace()) {
                outrunsAll = false;
            }
            if (unreachable.containsKey(being.id())) {
                continue;
            }
            double killTime = killTime(me, being, them);
            double value = fear * (attackedMe ? ATTACKER_BONUS : 1.0) / (1.0 + killTime)
                    * (being.id().equals(fighting) ? TARGET_STICKINESS : 1.0);
            if (best == null || value > bestValue) {
                best = being;
                bestValue = value;
                bestKillTime = killTime;
            }
        }
        if (!fuseBurning) {
            waitingOutFuse = false;
        }
        Stance stance;
        if (blast >= profile.d(ProfileAspect.FIGHT_BLAST_LINE)) {
            waitingOutFuse = true;
            stance = new Stance(false, null, 0.0, !outrunsAll, blast,
                    String.format(Locale.ROOT, "running from a lit explosive (%.2f)", blast));
        } else if (waitingOutFuse && fuseBurning) {
            // The blast line alone flips a body back and forth at its edge, and walks it back
            // into a fuse still burning — the creeper's only runs down past 7 blocks.
            stance = new Stance(false, null, 0.0, !outrunsAll, blast,
                    "running until the fuse goes out");
        } else if (me == null || best == null) {
            stance = new Stance(false, null, 0.0, !outrunsAll, blast, "running: nothing to fight");
        } else {
            double dieTime = theirDamagePerSecond > 0.0
                    ? me.health() / theirDamagePerSecond : Double.POSITIVE_INFINITY;
            double balance = dieTime / bestKillTime;
            double line = profile.d(best.id().equals(fighting)
                    ? ProfileAspect.FIGHT_QUIT_RATIO : ProfileAspect.FIGHT_START_RATIO);
            if (!outrunsAll) {
                line = Math.min(line, profile.d(ProfileAspect.FIGHT_CORNERED_RATIO));
            }
            boolean fight = balance >= line;
            String odds = String.format(Locale.ROOT, "kills it in %s, would be killed in %s%s",
                    seconds(bestKillTime), seconds(dieTime), outrunsAll ? "" : ", cannot outrun it");
            stance = new Stance(fight, fight ? best : null, balance, !outrunsAll, blast,
                    (fight ? "fighting " : "running from ") + name(best) + ": " + odds);
        }
        memo = stance;
        memoAt = now;
        memoFor = fighting;
        return stance;
    }

    /** Seconds this body would take to kill {@code being}: unhurt as far as it knows unless seen. */
    private static double killTime(Combatant me, Being being, Combatant them) {
        double perSecond = me.damagePerSecondAgainst(them.armor(), them.toughness());
        double health = being.awareness() == Being.Awareness.SEEN ? them.health() : them.maxHealth();
        return perSecond > 0.0 ? health / perSecond : Double.POSITIVE_INFINITY;
    }

    @Override
    public Task root(BrainContext ctx) {
        Stance stance = decide(ctx, null);
        say(ctx, stance);
        return stance.fight()
                ? new Fight(stance.target().id(), stance.target().pos())
                : new FleeStep();
    }

    @Override
    public @Nullable String reconsider(BrainContext ctx, Task root) {
        if (root instanceof Fight fight) {
            Stance stance = decide(ctx, fight.target());
            if (!stance.fight() && waitingOutFuse) {
                return stance.because();
            }
            if (!threatens(ctx, fight.target())) {
                return null; // down, gone or calm: the fight ends itself, and a kill is recorded
            }
            if (!stance.fight()) {
                return stance.because();
            }
            return stance.target().id().equals(fight.target()) ? null
                    : "turned on " + name(stance.target());
        }
        if (root instanceof FleeStep) {
            Stance stance = decide(ctx, null);
            return stance.fight() ? stance.because() : null;
        }
        return null;
    }

    /** Whether {@code who} is still a live threat to this body. */
    private boolean threatens(BrainContext ctx, BeingId who) {
        Percepts percepts = ctx.percepts();
        for (Being being : percepts.beings()) {
            if (being.id().equals(who)) {
                return pressureOf(ctx.profile(), ctx.danger(), being,
                        percepts.attackedLately(who)) > 0.0
                        && percepts.combatant(who).isPresent();
            }
        }
        return false;
    }

    @Override
    public void ended(BrainContext ctx, Task root, TaskStatus status) {
        if (root instanceof Fight fight && status == TaskStatus.FAILED) {
            unreachable.put(fight.target(), ctx.percepts().time() + UNREACHABLE_TICKS);
        }
    }

    @Override
    public int failCooldown() {
        return FAIL_COOLDOWN;
    }

    /** Fighting whom it fights, or running from whatever frightens it most. */
    @Override
    public Deed doing(BrainContext ctx) {
        Stance stance = decide(ctx, null);
        if (stance.fight()) {
            return Deed.of(Doings.FIGHTING, Whom.of(ctx, stance.target()));
        }
        Being scariest = null;
        double max = 0.0;
        for (Being being : ctx.percepts().beings()) {
            double pressure = pressureOf(ctx.profile(), ctx.danger(), being,
                    ctx.percepts().attackedLately(being.id()));
            if (pressure > max) {
                max = pressure;
                scariest = being;
            }
        }
        return Deed.of(Doings.FLEEING, Whom.of(ctx, scariest));
    }

    /** One journal line per change of answer or target. */
    private void say(BrainContext ctx, Stance stance) {
        String said = stance.fight() ? "fight " + stance.target().id() : "flee";
        if (!said.equals(lastSaid)) {
            ctx.journal().record(Category.BRAIN, describe(), stance.because());
            lastSaid = said;
        }
    }

    private static String name(Being being) {
        return being.knownAs();
    }

    private static String seconds(double value) {
        return Double.isInfinite(value) ? "never" : String.format(Locale.ROOT, "%.1f s", value);
    }

    @Override
    public String describe() {
        return "fight or flight";
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
