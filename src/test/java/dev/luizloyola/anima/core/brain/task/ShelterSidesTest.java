package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.MoveFailure;
import dev.luizloyola.anima.core.brain.instinct.FightOrFlightInstinct;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Combatant;
import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Enclosure.Holes;
import dev.luizloyola.anima.core.brain.sense.Enclosure.Openness;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.Sides;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which side of the walls a threat is on, and what fight or flight, flight and a walk make of it
 * (spec: {@code 2026-09-28-shelter-design.md}, rung 3).
 *
 * <p>Every world is a room seven by seven at y = 64, x and z 0 to 6, its door in the north wall at
 * (3, 64, -1); the body stands at (3, 64, 3). Outside is anywhere else.
 */
class ShelterSidesTest {

    private static final Pos HERE = new Pos(3, 64, 3);
    private static final Pos DOOR = new Pos(3, 64, -1);
    /** Just past the north wall, where the reported zombie stood. */
    private static final Pos AT_THE_WALL = new Pos(3, 64, -2);
    private static final Pos INSIDE = new Pos(5, 64, 5);

    private static final Combatant ZOMBIE = new Combatant(20, 20, 2, 0, 3, 1.0, 0.117, 0, 0);

    private final FakeContext ctx = new FakeContext();
    private final FightOrFlightInstinct mind = new FightOrFlightInstinct();

    // ── which side ───────────────────────────────────────────────────────────────────────────

    @Test
    void aWalkerOutsideAShutRoofedRoomIsShutOut() {
        shelter(Openness.OPENABLE, Holes.NONE, true);
        Being out = add(AT_THE_WALL, 2, ZOMBIE);
        Being in = add(INSIDE, 3, ZOMBIE);
        assertTrue(Sides.shutOut(ctx.percepts, out));
        assertFalse(Sides.shutOut(ctx.percepts, in), "one inside is on this side");
    }

    @Test
    void withoutAShelterNothingIsShutOut() {
        Being out = add(AT_THE_WALL, 2, ZOMBIE);
        shelter(Openness.CLOSEABLE, Holes.NONE, true);
        assertFalse(Sides.shutOut(ctx.percepts, out), "the door stands open");
        shelter(Openness.OPENABLE, Holes.NONE, false);
        assertFalse(Sides.shutOut(ctx.percepts, out), "no roof: it falls or climbs in");
        ctx.percepts.enclosure = Enclosure.UNKNOWN;
        assertFalse(Sides.shutOut(ctx.percepts, out), "no answer at all");
    }

    @Test
    void howItGetsInDecides() {
        shelter(Openness.OPENABLE, Holes.NONE, true);
        assertFalse(Sides.shutOut(ctx.percepts, add(AT_THE_WALL, 2,
                entering(Combatant.Entry.PASSES_WALLS, false, false))), "a vex, a player");
        Being piglin = add(AT_THE_WALL, 2, entering(Combatant.Entry.OPENS_DOORS, false, false));
        assertFalse(Sides.shutOut(ctx.percepts, piglin), "a shut wooden door opens for it");
        shelter(Openness.CLOSED, Holes.NONE, true);
        assertTrue(Sides.shutOut(ctx.percepts, piglin), "no door at all keeps it out");
    }

    @Test
    void aSmallBodyGetsInThroughHoles() {
        shelter(Openness.OPENABLE, Holes.SMALL, true);
        assertFalse(Sides.shutOut(ctx.percepts, add(AT_THE_WALL, 2,
                entering(Combatant.Entry.WALKS, true, false))), "a baby zombie fits the hole");
        assertTrue(Sides.shutOut(ctx.percepts, add(AT_THE_WALL, 2, ZOMBIE)), "a grown one does not");
    }

    @Test
    void aZombieHeardBatteringTheDoorIsOnThisSide() {
        shelter(Openness.OPENABLE, Holes.NONE, true);
        Being breaker = add(AT_THE_WALL, 2, ZOMBIE);
        ctx.percepts.battering.add(breaker.id());
        assertFalse(Sides.shutOut(ctx.percepts, breaker));
    }

    @Test
    void somethingThatShootsIsShutOutOnlyWithNoLine() {
        shelter(Openness.OPENABLE, Holes.NONE, true);
        Being skeleton = add(new Pos(3, 64, -8), 11, entering(Combatant.Entry.WALKS, false, true));
        assertFalse(Sides.shutOut(ctx.percepts, skeleton), "through an open window");
        ctx.percepts.blind.add(skeleton.id());
        assertTrue(Sides.shutOut(ctx.percepts, skeleton), "behind glass");
    }

    // ── fight or flight ──────────────────────────────────────────────────────────────────────

    @Test
    void aZombieAgainstTheWallOfAShutHousePressesNothing() {
        add(AT_THE_WALL, 2, ZOMBIE);
        assertTrue(mind.pressure(ctx) > 0.0, "outdoors, the same zombie must frighten");
        shelter(Openness.OPENABLE, Holes.NONE, true);
        assertEquals(0.0, mind.pressure(ctx), "the reported bug: indoors it must not");
    }

    @Test
    void tenOutsideAndOneInsideIsAFightWithOne() {
        ctx.percepts.self = new Combatant(20, 20, 0, 0, 6, 1.6, 0.28, 0, 0); // an iron sword
        Being in = add(INSIDE, 3, ZOMBIE);
        for (int i = 0; i < 10; i++) {
            add(new Pos(-2 - i % 3, 64, -2 - i / 3), 6 + i * 0.1, ZOMBIE);
        }
        ctx.percepts.time++;
        assertInstanceOf(FleeStep.class, mind.root(ctx), "outdoors, eleven zombies: run");

        shelter(Openness.OPENABLE, Holes.NONE, true);
        ctx.percepts.time++;
        Fight fight = assertInstanceOf(Fight.class, mind.root(ctx));
        assertEquals(in.id(), fight.target());
    }

    @Test
    void oneInsideAndMoreOutsideCornersTheBody() {
        ctx.percepts.self = new Combatant(20, 20, 0, 0, 1, 4, 0.28, 0, 0); // bare-handed: 1.25
        add(INSIDE, 3, ZOMBIE);
        shelter(Openness.OPENABLE, Holes.NONE, true);
        ctx.percepts.time++;
        FightOrFlightInstinct.Stance alone = mind.decide(ctx, null);
        assertFalse(alone.cornered(), "one zombie and an open way: it can run");
        assertFalse(alone.fight());

        add(AT_THE_WALL, 2, ZOMBIE);
        ctx.percepts.time++;
        FightOrFlightInstinct.Stance boxed = mind.decide(ctx, null);
        assertTrue(boxed.cornered(), "out is into the other one");
        assertTrue(boxed.fight(), "so it fights at the cornered line");
    }

    @Test
    void aShooterIsFearedFromRangeBeforeItsBowIsSeen() {
        Being skeleton = add(new Pos(20, 64, 20), 20, entering(Combatant.Entry.WALKS, false, true));
        assertEquals(0.0, FightOrFlightInstinct.pressureOf(ctx.profile, ctx.danger, skeleton),
                "unsized, twenty blocks is past a walker's reach");
        assertTrue(FightOrFlightInstinct.pressureOf(ctx.profile, ctx.danger, skeleton, false,
                entering(Combatant.Entry.WALKS, false, true)) > 0.0, "read as shooting, it is not");
    }

    // ── flight inside, and staying in ────────────────────────────────────────────────────────

    @Test
    void flightInsideAShelterKeepsToTheFarSideOfTheRoom() {
        shelter(Openness.OPENABLE, Holes.NONE, true);
        Being in = add(new Pos(1, 64, 1), 3, ZOMBIE);
        List<Being> all = new ArrayList<>(ctx.percepts.beings);
        for (int i = 0; i < 5; i++) {
            all.add(FakePercepts.monsterAt(new Pos(3 + i - 2, 64, -3), 6, false));
        }
        ctx.percepts.beings = all;
        DangerField field = DangerField.of(ctx.danger, ctx.percepts.beings, ctx.knowledge(), 0,
                DangerField.FADE_TICKS);
        Pos goal = FleeStep.keepAway(ctx.percepts.enclosure, HERE, field);
        assertTrue(ctx.percepts.enclosure.covers(goal), "it stays in: " + goal);
        assertTrue(goal.x() >= 4 && goal.z() >= 4, "the corner away from both: " + goal);
        assertFalse(in.pos().equals(goal));
    }

    @Test
    void inTheBestCornerAlreadyItHoldsRatherThanWalkingToItself() {
        shelter(Openness.OPENABLE, Holes.NONE, true);
        add(new Pos(1, 64, 1), 7, ZOMBIE);
        ctx.percepts.position = new Pos(6, 64, 6);
        List<Task> leg = FleeStepTest.method("escape").decompose(ctx);
        assertInstanceOf(Idle.class, leg.get(0), "the far corner is where it stands");

        ctx.percepts.position = HERE;
        GoTo run = assertInstanceOf(GoTo.class, FleeStepTest.method("escape").decompose(ctx).get(0));
        assertEquals(new Pos(6, 64, 6), new Pos(run.x(), run.y(), run.z()));
        assertTrue(run.leavesShelter(), "flight is never refused");
    }

    @Test
    void aWalkOutIsRefusedWhileAZombieWaits() {
        shelter(Openness.OPENABLE, Holes.NONE, true);
        add(AT_THE_WALL, 2, ZOMBIE);
        GoTo out = new GoTo(3, 64, -10);
        assertEquals(TaskStatus.FAILED, out.tick(ctx));
        assertEquals(0, ctx.mover.moveToCalls, "the legs are never ordered");
        assertTrue(out.failureDetail().contains(MoveFailure.SHELTERING.describe()));
    }

    @Test
    void aWalkInsideFlightAndNoThreatAllGo() {
        shelter(Openness.OPENABLE, Holes.NONE, true);
        assertEquals(TaskStatus.RUNNING, new GoTo(3, 64, -10).tick(ctx), "nothing waits outside");
        add(AT_THE_WALL, 2, ZOMBIE);
        assertEquals(TaskStatus.RUNNING, new GoTo(5, 64, 5).tick(ctx), "a walk across the room");
        assertEquals(TaskStatus.RUNNING, new GoTo(3, 64, -10).leavingShelter().tick(ctx),
                "flight, escape, a fight and a command go out anyway");
    }

    // ── shutting the door (rung 4) ───────────────────────────────────────────────────────────

    /** A Person's sprint, blocks a tick. */
    private static final Combatant SPRINTER = new Combatant(20, 20, 0, 0, 1, 4, 0.28, 0, 0);
    /** Twelve blocks out from the door, straight north. */
    private static final Pos TWELVE_OUT = new Pos(3, 64, -13);

    @Test
    void withTheDoorOpenAndAZombieComingItShutsTheDoorBeforeRunning() {
        ctx.percepts.self = SPRINTER;
        shelter(Openness.CLOSEABLE, Holes.NONE, true);
        add(TWELVE_OUT, 12, ZOMBIE);
        Method shut = new FleeStep().methods().get(0);
        assertEquals("shut the door", shut.describe(), "tried before escape");
        assertTrue(shut.applicable(ctx));
        List<Task> plan = shut.decompose(ctx);
        GoTo run = assertInstanceOf(GoTo.class, plan.get(0));
        assertEquals(new Pos(3, 64, 0), new Pos(run.x(), run.y(), run.z()), "the cell inside it");
        assertFalse(run.leavesShelter());
        assertEquals(DOOR, assertInstanceOf(ShutDoor.class, plan.get(1)).door());
        assertInstanceOf(Idle.class, plan.get(2), "then holds while the space is read again");
    }

    @Test
    void besideTheDoorAlreadyItShutsItWithoutAWalk() {
        ctx.percepts.self = SPRINTER;
        shelter(Openness.CLOSEABLE, Holes.NONE, true);
        add(TWELVE_OUT, 12, ZOMBIE);
        ctx.percepts.position = new Pos(3, 64, 0);
        assertInstanceOf(ShutDoor.class, FleeStepTest.method("shut the door").decompose(ctx).get(0));
    }

    @Test
    void tooLateToShutItRunsInstead() {
        ctx.percepts.self = SPRINTER;
        shelter(Openness.CLOSEABLE, Holes.NONE, true);
        add(new Pos(3, 64, -3), 6, ZOMBIE); // two blocks from the door: 17 ticks, against 11 + 20
        assertEquals(null, FleeStep.shutPlan(ctx));
        add(TWELVE_OUT, 12, ZOMBIE);
        assertEquals(null, FleeStep.shutPlan(ctx), "one close is enough to lose the race");
    }

    @Test
    void theRaceIsMeasuredAtEachThreatsOwnPace() {
        ctx.percepts.self = SPRINTER;
        shelter(Openness.CLOSEABLE, Holes.NONE, true);
        // Six blocks off: a zombie needs 51 ticks, a spider at 0.3 a tick needs 20.
        add(new Pos(3, 64, -7), 10, ZOMBIE);
        assertTrue(FleeStep.shutPlan(ctx) != null);
        ctx.percepts.beings = List.of();
        add(new Pos(3, 64, -7), 10, new Combatant(16, 16, 0, 0, 2, 1, 0.3, 0, 0));
        assertEquals(null, FleeStep.shutPlan(ctx));
    }

    @Test
    void itShutsOnlyWhatAShutDoorKeepsOut() {
        ctx.percepts.self = SPRINTER;
        shelter(Openness.CLOSEABLE, Holes.NONE, true);
        add(INSIDE, 3, ZOMBIE);
        assertEquals(null, FleeStep.shutPlan(ctx), "one already inside");

        ctx.percepts.beings = List.of();
        add(TWELVE_OUT, 12, entering(Combatant.Entry.OPENS_DOORS, false, false));
        assertEquals(null, FleeStep.shutPlan(ctx), "a piglin opens it again");

        ctx.percepts.beings = List.of();
        shelter(Openness.CLOSEABLE, Holes.SMALL, true);
        add(TWELVE_OUT, 12, entering(Combatant.Entry.WALKS, true, false));
        assertEquals(null, FleeStep.shutPlan(ctx), "a baby zombie fits the hole");

        ctx.percepts.beings = List.of();
        add(TWELVE_OUT, 12, new Combatant(20, 20, 0, 0, 0, 0, 0.2, 0.6, 6));
        assertEquals(null, FleeStep.shutPlan(ctx), "a door does not stop a blast");

        ctx.percepts.beings = List.of();
        shelter(Openness.CLOSEABLE, Holes.NONE, false);
        add(TWELVE_OUT, 12, ZOMBIE);
        assertEquals(null, FleeStep.shutPlan(ctx), "no roof: shut, it is still no shelter");
    }

    @Test
    void twoDoorsAreShutNearestFirst() {
        ctx.percepts.self = SPRINTER;
        shelter(Openness.CLOSEABLE, Holes.NONE, true);
        Pos east = new Pos(7, 64, 3);
        Enclosure one = ctx.percepts.enclosure;
        ctx.percepts.enclosure = new Enclosure(one.openness(), one.holes(), true, HERE, one.space(),
                List.of(DOOR, east), List.of(DOOR, east), 0L);
        ctx.percepts.position = new Pos(5, 64, 3);
        add(new Pos(14, 64, 14), 14, ZOMBIE);
        List<Task> plan = FleeStepTest.method("shut the door").decompose(ctx);
        assertEquals(east, assertInstanceOf(ShutDoor.class, plan.get(1)).door(), "a step away");
        assertEquals(DOOR, assertInstanceOf(ShutDoor.class, plan.get(3)).door());
    }

    @Test
    void theShutFailsOnAThreatInTheDoorwayAndOnADoorThatWillNotShut() {
        shelter(Openness.CLOSEABLE, Holes.NONE, true);
        ShutDoor shut = new ShutDoor(DOOR.x(), DOOR.y(), DOOR.z());
        assertEquals(TaskStatus.SUCCESS, shut.tick(ctx));
        assertEquals(List.of(DOOR), ctx.hand.shut);

        ctx.hand.jammed.add(DOOR);
        assertEquals(TaskStatus.FAILED, new ShutDoor(DOOR.x(), DOOR.y(), DOOR.z()).tick(ctx));

        ctx.hand.jammed.clear();
        ctx.hand.shut.clear();
        add(DOOR, 4, ZOMBIE);
        ShutDoor late = new ShutDoor(DOOR.x(), DOOR.y(), DOOR.z());
        assertEquals(TaskStatus.FAILED, late.tick(ctx));
        assertTrue(ctx.hand.shut.isEmpty(), "never shut on it");
        assertTrue(late.failureDetail().contains("got there first"));
    }

    // ── the world ────────────────────────────────────────────────────────────────────────────

    /** The room as the check would hand it back, with this verdict. */
    private void shelter(Openness openness, Holes holes, boolean roofed) {
        Set<Pos> space = new HashSet<>();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                space.add(new Pos(x, 64, z));
            }
        }
        ctx.percepts.position = HERE;
        ctx.percepts.enclosure = new Enclosure(openness, holes, roofed, HERE, space,
                openness == Openness.CLOSEABLE ? List.of(DOOR) : List.of(),
                openness == Openness.CLOSED ? List.of() : List.of(DOOR), 0L);
    }

    private Being add(Pos at, double distance, Combatant them) {
        Being being = FakePercepts.monsterAt(BeingId.of(UUID.randomUUID()), at, distance,
                Being.Awareness.SEEN);
        List<Being> all = new ArrayList<>(ctx.percepts.beings);
        all.add(being);
        ctx.percepts.beings = all;
        ctx.percepts.combatants.put(being.id(), them);
        return being;
    }

    private static Combatant entering(Combatant.Entry entry, boolean small, boolean shoots) {
        return new Combatant(20, 20, 2, 0, 3, 1.0, 0.117, 0, 0, entry, small, shoots);
    }
}
