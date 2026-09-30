package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.ShelterNoter;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Combatant;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Enclosure.Holes;
import dev.luizloyola.anima.core.brain.sense.Enclosure.Openness;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.MoveType;
import dev.luizloyola.anima.core.nav.Path;
import dev.luizloyola.anima.core.nav.Waypoint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Running for a remembered shelter (shelter spec, rung 5).
 *
 * <p>The shelter is a room seven by seven at y = 64, x and z 0 to 6, its door in the north wall at
 * (3, 64, -1) and the cell inside it (3, 64, 0). The body stands ten blocks north of the door.
 */
class RunForShelterTest {

    private static final Pos DOOR = new Pos(3, 64, -1);
    private static final Pos IN = new Pos(3, 64, 0);
    private static final Pos OUT_HERE = new Pos(3, 64, -10);
    /** Fourteen blocks further north: close enough to frighten, far enough to lose the race. */
    private static final Pos BEHIND = new Pos(3, 64, -24);

    private static final Combatant ZOMBIE = new Combatant(20, 20, 2, 0, 3, 1.0, 0.117, 0, 0);
    private static final Combatant SPRINTER = new Combatant(20, 20, 0, 0, 1, 4, 0.28, 0, 0);

    private final FakeContext ctx = new FakeContext();

    // ── choosing ─────────────────────────────────────────────────────────────────────────────

    @Test
    void aZombieBehindSendsItToTheShelterItRemembers() {
        outside();
        add(BEHIND, 14, ZOMBIE);
        List<String> ways = new FleeStep().methods().stream().map(Method::describe).toList();
        assertEquals(List.of("shut the door", "run for shelter", "escape"), ways);
        Method run = FleeStepTest.method("run for shelter");
        assertTrue(run.applicable(ctx));
        List<Task> plan = run.decompose(ctx);
        assertEquals(IN, assertInstanceOf(RunToShelter.class, plan.get(0)).in());
        assertInstanceOf(Idle.class, plan.get(1), "then holds while the space is read");
    }

    @Test
    void nothingFrighteningIsNoReasonToRun() {
        outside();
        assertNull(FleeStep.shelterToRunFor(ctx));
    }

    @Test
    void aThreatNearerTheDoorWinsTheRaceEvenOnTheStraightLine() {
        outside();
        add(new Pos(3, 64, -3), 7, ZOMBIE); // 26 ticks to the door, against 36
        assertNull(FleeStep.shelterToRunFor(ctx));
    }

    @Test
    void aShelterFurtherThanItsReachIsNotRunFor() {
        outside();
        ctx.percepts.position = new Pos(3, 64, -70);
        add(new Pos(3, 64, -84), 14, ZOMBIE);
        assertNull(FleeStep.shelterToRunFor(ctx));
    }

    @Test
    void notForWhatAShelterDoesNotKeepOut() {
        outside();
        add(BEHIND, 14, new Combatant(40, 40, 0, 0, 7, 1, 0.3, 0, 0,
                Combatant.Entry.PASSES_WALLS, false, false));
        assertNull(FleeStep.shelterToRunFor(ctx), "an enderman");
        ctx.percepts.beings = List.of();
        add(new Pos(4, 64, 4), 14, ZOMBIE);
        assertNull(FleeStep.shelterToRunFor(ctx), "one waiting inside");
    }

    @Test
    void inAShelterAlreadyThereIsNothingToRunFor() {
        outside();
        add(BEHIND, 14, ZOMBIE);
        ctx.percepts.position = new Pos(3, 64, 3);
        ctx.percepts.enclosure = room(Openness.OPENABLE, new Pos(3, 64, 3), List.of(DOOR));
        assertNull(FleeStep.shelterToRunFor(ctx));
    }

    @Test
    void aShelterLeftAloneIsNotChosen() {
        outside();
        add(BEHIND, 14, ZOMBIE);
        ctx.knowledge.avoid(PoiKind.SHELTER, IN, ctx.percepts.time + 40);
        assertNull(FleeStep.shelterToRunFor(ctx));
    }

    @Test
    void theNearerOfTwo() {
        outside();
        // A second room of the same shape twenty blocks west, its door opening north too.
        Set<Pos> west = new HashSet<>();
        for (int x = -20; x <= -14; x++) {
            for (int z = 0; z <= 6; z++) {
                west.add(new Pos(x, 64, z));
            }
        }
        Pos westDoor = new Pos(-17, 64, -1);
        ShelterNoter.note(new Enclosure(Openness.OPENABLE, Holes.NONE, true, new Pos(-17, 64, 3),
                west, List.of(), List.of(westDoor), 0L), ctx.knowledge, cap());
        add(BEHIND, 14, ZOMBIE);
        assertEquals(IN, FleeStep.shelterToRunFor(ctx).anchor());
        ctx.percepts.position = new Pos(-17, 64, -10);
        assertEquals(new Pos(-17, 64, 0), FleeStep.shelterToRunFor(ctx).anchor());
    }

    // ── the route ────────────────────────────────────────────────────────────────────────────

    @Test
    void aShelterAcrossARavineIsRefusedAndLeftAloneForAMinute() {
        outside();
        add(BEHIND, 14, ZOMBIE);
        RunToShelter run = new RunToShelter(IN.x(), IN.y(), IN.z());
        assertEquals(TaskStatus.RUNNING, run.tick(ctx), "orders the walk");
        ctx.mover.setState(MoveState.MOVING);
        assertEquals(TaskStatus.RUNNING, run.tick(ctx), "still searching");
        ctx.mover.route = new Path(straight(-9, -4), false); // as near as it gets without a bridge
        assertEquals(TaskStatus.FAILED, run.tick(ctx));
        assertEquals(1, ctx.mover.stopCalls, "the legs are stopped");
        assertTrue(run.failureDetail().contains("no way there"), run.failureDetail());
        assertTrue(ctx.knowledge.isAvoided(PoiKind.SHELTER, IN, ctx.percepts.time + 1_000));
    }

    @Test
    void aThreatBesideTheRouteCutsItOff() {
        outside();
        ctx.percepts.position = new Pos(3, 64, -30);
        // Two blocks off the halfway cell, there in 17 ticks against 54; yet 22 ticks behind at
        // the door, so only a look along the way catches it.
        add(new Pos(5, 64, -15), 15, ZOMBIE);
        RunToShelter run = walking();
        ctx.mover.route = new Path(straight(-29, 0), true);
        assertEquals(TaskStatus.FAILED, run.tick(ctx));
        assertTrue(run.failureDetail().contains("(3, 64, -"), run.failureDetail());
        assertTrue(ctx.knowledge.isAvoided(PoiKind.SHELTER, IN, ctx.percepts.time + 30));
        assertFalse(ctx.knowledge.isAvoided(PoiKind.SHELTER, IN, ctx.percepts.time + 60),
                "only briefly: it moves");
    }

    @Test
    void aRouteLongerThanTheReachIsRefused() {
        outside();
        add(new Pos(3, 64, -200), 14, ZOMBIE);
        List<Waypoint> around = new ArrayList<>();
        for (int x = 4; x <= 35; x++) {
            around.add(new Waypoint(x, 64, -10, MoveType.WALK));
        }
        for (int x = 34; x >= 3; x--) {
            around.add(new Waypoint(x, 64, -9, MoveType.WALK));
        }
        around.addAll(straight(-8, 0));
        RunToShelter run = walking();
        ctx.mover.route = new Path(around, true);
        assertEquals(TaskStatus.FAILED, run.tick(ctx));
        assertTrue(run.failureDetail().contains("too far"), run.failureDetail());
    }

    @Test
    void aClearRouteIsRunToTheEnd() {
        outside();
        add(BEHIND, 14, ZOMBIE);
        RunToShelter run = walking();
        ctx.mover.route = new Path(straight(-9, 0), true);
        assertEquals(TaskStatus.RUNNING, run.tick(ctx));
        assertTrue(run.judged());
        ctx.mover.setState(MoveState.ARRIVED);
        assertEquals(TaskStatus.SUCCESS, run.tick(ctx));
        assertEquals(0, ctx.mover.stopCalls);
    }

    // ── the world ────────────────────────────────────────────────────────────────────────────

    /** The body out north of the room, which it remembers from having stood in it. */
    private void outside() {
        ctx.percepts.self = SPRINTER;
        ShelterNoter.note(room(Openness.OPENABLE, new Pos(3, 64, 3), List.of(DOOR)), ctx.knowledge,
                cap());
        ctx.percepts.position = OUT_HERE;
        ctx.percepts.enclosure = Enclosure.open(OUT_HERE, 0L);
    }

    /** A run whose walk is ordered and under way, its route not yet read. */
    private RunToShelter walking() {
        RunToShelter run = new RunToShelter(IN.x(), IN.y(), IN.z());
        run.tick(ctx);
        ctx.mover.setState(MoveState.MOVING);
        return run;
    }

    /** Waypoints due south along x = 3, from z = {@code from} to z = {@code to}. */
    private static List<Waypoint> straight(int from, int to) {
        List<Waypoint> ways = new ArrayList<>();
        for (int z = from; z <= to; z++) {
            ways.add(new Waypoint(3, 64, z, MoveType.WALK));
        }
        return ways;
    }

    private int cap() {
        return AgentKnowledge.maxPerKind(ctx.profile);
    }

    private static Enclosure room(Openness openness, Pos from, List<Pos> doors) {
        Set<Pos> space = new HashSet<>();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                space.add(new Pos(x, 64, z));
            }
        }
        return new Enclosure(openness, Holes.NONE, true, from, space, List.of(), doors, 0L);
    }

    private void add(Pos at, double distance, Combatant them) {
        Being being = FakePercepts.monsterAt(BeingId.of(UUID.randomUUID()), at, distance,
                Being.Awareness.SEEN);
        List<Being> all = new ArrayList<>(ctx.percepts.beings);
        all.add(being);
        ctx.percepts.beings = all;
        ctx.percepts.combatants.put(being.id(), them);
    }
}
