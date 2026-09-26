package dev.luizloyola.anima.core.brain.sense;

import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.agent.need.Needs;
import dev.luizloyola.anima.core.nav.NavGrid;
import java.util.List;

/**
 * The perception bundle — the sense half of {@link dev.luizloyola.anima.core.brain.BrainContext},
 * mirroring {@link dev.luizloyola.anima.core.brain.act.ActuatorAccess} on the acting half: core
 * names what an NPC can perceive; the mod {@code BrainDriver} assembles it over live entity state.
 *
 * <p>These are LIVE views, not snapshots — a method scoring its applicability and a task acting a
 * tick later both see the current truth.
 */
public interface Percepts {
    /**
     * The carried 41 slots ({@code core/inv}) — live, the same object the equipment mirror
     * maintains. The brain may write here; the mirror pushes changes onto the entity.
     */
    Inventory inventory();

    /**
     * Body vitals ({@code core/person}) — read-only BY CONVENTION: the body owns and ticks its
     * metabolism, and nutrition is applied by the body when the consume actuator finishes, never
     * by a task writing here. The brain only reads pressure ({@code hunger()}, {@code band()}).
     */
    Metabolism metabolism();

    /**
     * Everything this body feels ({@code core/agent/need}) — read-only BY CONVENTION, exactly like
     * the metabolism above and for the same reason: the body owns and ticks its gauges. An
     * instinct bids on {@code needs().pressure(kind)}, which answers 0 for a need this species does
     * not have, so a drive stays portable across bodies without ever asking what body it is on.
     */
    Needs needs();

    /** What is edible and what eating it does — see {@link FoodLookup}. */
    FoodLookup foods();

    /**
     * Where the body stands — the feet cell, in whole blocks (the pathfinder/Navigator grid). Read
     * live, so a target offset from it ({@code WanderStep}) is never offset from a stale spawn
     * point.
     */
    Pos position();

    /**
     * Everything living they currently perceive — one list across every kind (see {@link Being}):
     * persons, monsters, neutrals, herds, villagers, and the yet-unmade-out somethings, each
     * masked to its achieved identification tier. {@code FleeInstinct} prices the AGGRESSIVE
     * entries of this same list. Nearest-first is not guaranteed.
     */
    List<Being> beings();

    /**
     * The world's blocks, seen through the one block vocabulary ({@code BlockKind}) — the same
     * probe perception's sensor reads through, now offered to tasks for their task-time re-walks
     * (a chop re-scanning a remembered grove: memory points, world is truth). Live reads, server
     * side; use them budgeted the way the sensor does — this is the expensive sense class.
     */
    BlockProbe blocks();

    /**
     * The same world in the PATHFINDER's vocabulary ({@code CellType}), where {@link #blocks()} is
     * it in perception's botany. Two instruments, not two names for one: {@code BlockKind} cannot
     * say lava, fire, fence or slab — every one of them is {@code OTHER} — so a decision about
     * whether this body can <em>stand</em> somewhere has to be read through here.
     *
     * <p>Live reads, server side, to be budgeted exactly the way {@link #blocks()} is. The default
     * is "nothing known", so a rig with no terrain sense finds nowhere to stand.
     */
    default NavGrid terrain() {
        return NavGrid.UNKNOWN;
    }

    /**
     * Nearby dropped items, sensed right now — budgeted and briefly cached. Bare sightings (cell
     * + item id); ground-vs-stranded and mine-vs-noise are the consumer's questions.
     */
    List<Drop> drops();

    /**
     * Whether this body called out to {@code whom} recently enough that doing it again would just
     * be shouting twice — the per-target half of the hail guardrail.
     */
    boolean calledLately(BeingId whom);

    /**
     * Nearby people — the {@link Being.Kind#PERSON} view over {@link #beings()}: other Persons
     * And live players, one list, indistinguishable. The substrate every social
     * behavior stands on.
     */
    default List<Being> peers() {
        List<Being> people = new java.util.ArrayList<>();
        for (Being being : beings()) {
            if (being.kind().minded()) {
                people.add(being);
            }
        }
        return List.copyOf(people);
    }

    /**
     * The current game time in ticks — the same clock knowledge timestamps carry, so staleness
     * pricing ({@code memory.age(time())}) compares like with like.
     */
    long time();

    /**
     * The sky, the hour and the light where the body stands, as it can tell them. Empty for a body
     * with no world to read — a rig, most of all.
     */
    default java.util.Optional<Surroundings> surroundings() {
        return java.util.Optional.empty();
    }

    /**
     * Whether this body can get out of where it is — see {@link Confinement}.
     *
     * <p>A percept rather than something a drive works out: the answer comes from a route search's
     * own exhaustion, and only the legs ever run one. What arrives is the most recent search's
     * reading. The default is "nothing known".
     */
    default Confinement confinement() {
        return Confinement.NONE;
    }

    /**
     * Whether this body's own walks keep failing stranded from about here — the suspicion that sends
     * {@link #confinement} looking wider (see {@code Setbacks.enclosed}). Nothing known by default.
     */
    default boolean strandedHere() {
        return false;
    }

    /**
     * How far above its feet this body's eyes are, in blocks — where every reach and every line of
     * sight starts. Read off the live entity, never a species constant: the entity is the one
     * that knows. The default is a humanoid's, for a rig with no body behind it.
     */
    default double eyeHeight() {
        return Being.HUMANOID_EYE_HEIGHT;
    }

    /**
     * The same with the body crouched — what a {@link dev.luizloyola.anima.core.brain.act.Leaner}
     * lowers the eyes to. A body that cannot crouch answers with its standing height.
     */
    default double crouchedEyeHeight() {
        return eyeHeight();
    }

    /**
     * How far from its eyes this body's arm reaches a block, in blocks — the same number the
     * breaking arm refuses by, so a plan that says "reachable from here" is one the arm agrees
     * with. The default is the survival player's.
     */
    default double reach() {
        return 4.5;
    }
}
