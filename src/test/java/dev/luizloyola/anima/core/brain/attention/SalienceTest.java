package dev.luizloyola.anima.core.brain.attention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.DangerTable;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakePercepts;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a body chooses to look at: somebody beats idly looking around, a noise it cannot see beats
 * somebody, a look wears off rather than becoming a stare, and the eyes stay on a body that walks
 * rather than on the spot it was standing in.
 *
 * <p>The picker, not the geometry — {@link AimTest} owns where the head ends up. A failure here
 * reads as a mood rather than a bug from inside the game.
 */
class SalienceTest {

    private static final Pos FEET = new Pos(0, 64, 0);
    private static final double EYE_Y = 65.6;

    private final FakePercepts percepts = new FakePercepts();
    private final AgentKnowledge knowledge = new AgentKnowledge();
    private final Attention attention = new Attention();
    private final Random random = new Random(4);

    SalienceTest() {
        percepts.position = FEET;
    }

    private Attention.Focus tick(long now) {
        return attention.tick(0.5, EYE_Y, 0.5, 0.0, now, percepts, knowledge, DangerTable.NEUTRAL,
                TestSpecies.PROFILE, random);
    }

    /** Somebody standing {@code z} blocks away, in view. */
    private static Being person(BeingId id, int z, Being.Awareness awareness,
            Being.Locomotion legs) {
        return new Being(id, Being.Kind.AGENT, "person", "Alice", null, new Pos(0, 64, z), z, 1, 0,
                false, List.of(), Being.Activity.IDLE, legs, false, false, false, false, false,
                Being.Gear.NONE, Being.Identified.INDIVIDUAL, awareness);
    }

    @Test
    @DisplayName("somebody nearby beats idly looking around")
    void aPersonBeatsTheScan() {
        assertEquals(Attention.SCAN_KEY, tick(0).key(), "nobody about — a scan is the right answer");
        BeingId alice = BeingId.of(UUID.randomUUID());
        percepts.beings = List.of(person(alice, 4, Being.Awareness.SEEN, Being.Locomotion.STILL));
        Attention.Focus focus = tick(Attention.DECIDE_INTERVAL);
        assertEquals(BeingSource.key(percepts.beings.get(0)), focus.key());
        assertEquals("Alice", focus.reason(), "the body names who it knows, and only who it knows");
        assertEquals(4.0, focus.z(), 0.51, "aimed at her, not at her feet's cell corner");
        assertTrue(focus.y() > EYE_Y - 1.0, "aimed at a face, not at the ground she stands on");
    }

    @Test
    @DisplayName("a noise it cannot see outranks anything it can, and snaps")
    void anUnseenNoiseStartles() {
        BeingId seen = BeingId.of(UUID.randomUUID());
        BeingId heard = BeingId.of(UUID.randomUUID());
        percepts.beings = List.of(person(seen, 3, Being.Awareness.SEEN, Being.Locomotion.STILL));
        Attention.Focus onPerson = tick(0);
        assertFalse(onPerson.snap(), "a person in plain sight is a look, not a fright");

        percepts.beings = List.of(
                person(seen, 3, Being.Awareness.SEEN, Being.Locomotion.STILL),
                person(heard, 6, Being.Awareness.HEARD, Being.Locomotion.STILL));
        Attention.Focus onNoise = tick(Attention.DECIDE_INTERVAL);
        assertEquals(BeingSource.key(percepts.beings.get(1)), onNoise.key(),
                "something it cannot see is the one thing in the scene it does not know about");
        assertTrue(onNoise.snap(), "and the head whips round rather than easing over");
        // The startle preempts mid-dwell, which nothing else may do: the ordinary rule is that a
        // rival has to beat what you are looking at by a quarter, once the dwell is up.
        assertNotEquals(onPerson.key(), onNoise.key());
    }

    @Test
    @DisplayName("a look wears off, so watching somebody does not become staring at them")
    void aLookWearsOff() {
        BeingId alice = BeingId.of(UUID.randomUUID());
        Being being = person(alice, 4, Being.Awareness.SEEN, Being.Locomotion.STILL);
        percepts.beings = List.of(being);
        long now = 0;
        Attention.Focus first = tick(now);
        assertEquals(BeingSource.key(being), first.key());
        // She is still standing right there and is still the only thing in sight, so the only
        // reason to look away is that a look wears off.
        now = first.until();
        Attention.Focus after = tick(now);
        assertEquals(Attention.SCAN_KEY, after.key(),
                "a body that has just looked at somebody looks at something else for a while");
        // ...and she becomes worth a look again once the refractory has run.
        boolean lookedAgain = false;
        for (long t = now; t <= now + Attention.REFRACTORY_TICKS * 2L; t += Attention.DECIDE_INTERVAL) {
            if (BeingSource.key(being).equals(tick(t).key())) {
                lookedAgain = true;
                break;
            }
        }
        assertTrue(lookedAgain, "she never became interesting again — the refractory never lifts");
    }

    @Test
    @DisplayName("the eyes follow somebody who walks, rather than holding where they were")
    void theEyesFollowAWalker() {
        BeingId alice = BeingId.of(UUID.randomUUID());
        percepts.beings = List.of(person(alice, 3, Being.Awareness.SEEN, Being.Locomotion.WALKING));
        Attention.Focus caught = tick(0);
        assertEquals(BeingSource.key(percepts.beings.get(0)), caught.key());
        // She walks, and this is the tick after a decision, so nothing is re-scored: only the
        // per-tick tracking can move the aim.
        percepts.beings = List.of(person(alice, 8, Being.Awareness.SEEN, Being.Locomotion.WALKING));
        Attention.Focus followed = tick(1);
        assertEquals(caught.key(), followed.key(), "same look");
        assertEquals(8.0, followed.z(), 0.51, "aimed where she is now");
        assertEquals(caught.until(), followed.until(), "and it is the same look, not a fresh one");
    }

    @Test
    @DisplayName("somebody who walks out of perception is not stared after")
    void aVanishedBodyIsDropped() {
        BeingId alice = BeingId.of(UUID.randomUUID());
        percepts.beings = List.of(person(alice, 3, Being.Awareness.SEEN, Being.Locomotion.WALKING));
        assertEquals(BeingSource.key(percepts.beings.get(0)), tick(0).key());
        percepts.beings = List.of();
        assertEquals(Attention.SCAN_KEY, tick(1).key(),
                "the thousand-yard stare at where somebody used to be");
    }

    @Test
    @DisplayName("a remembered body is not looked at — a head aimed at a belief is aimed at nothing")
    void rememberedBodiesAreNotLookedAt() {
        BeingId ghost = BeingId.of(UUID.randomUUID());
        percepts.beings = List.of(person(ghost, 3, Being.Awareness.REMEMBERED, Being.Locomotion.STILL));
        assertEquals(Attention.SCAN_KEY, tick(0).key());
    }

    @Test
    @DisplayName("the live scene: a noise wins over somebody already looked at")
    void theStagedSceneFromTheDevServer() {
        // The scene staged in world: a neighbour 4.3 blocks off whose novelty is spent, and a cow
        // heard 9 blocks away behind a wall. In world the body held its idle scan through the
        // whole noise.
        BeingId alice = BeingId.of(UUID.randomUUID());
        BeingId moo = BeingId.of(UUID.randomUUID());
        Being neighbour = person(alice, 4, Being.Awareness.SEEN, Being.Locomotion.STILL);
        percepts.beings = List.of(neighbour);
        long now = 0;
        Attention.Focus look = tick(now);
        assertEquals(BeingSource.key(neighbour), look.key(), "she is looked at first");
        now = look.until();
        assertEquals(Attention.SCAN_KEY, tick(now).key(), "and the look wears off");

        Being cow = new Being(moo, Being.Kind.PASSIVE, "cow", "", null, new Pos(0, 64, 9), 9.0, 1,
                0, false, List.of(), Being.Activity.IDLE, Being.Locomotion.STILL, false, false,
                false, false, false, Being.Gear.NONE, Being.Identified.SPECIES,
                Being.Awareness.HEARD);
        percepts.beings = List.of(neighbour, cow);
        Attention.Focus startled = tick(now + Attention.DECIDE_INTERVAL);
        assertEquals(BeingSource.key(cow), startled.key(),
                "a body that cannot see what just made a noise has one thing worth looking at");
        assertTrue(startled.snap());
    }

    @Test
    @DisplayName("boredom accumulates: the same motionless neighbour is worth less each time")
    void lookingAgainAndAgainGetsHarder() {
        // Reported: a body looks too often at the same entity, worst when it is the only one to
        // look at. A flat refractory guarantees that, because the wait never grows.
        BeingId alice = BeingId.of(UUID.randomUUID());
        Being being = person(alice, 4, Being.Awareness.SEEN, Being.Locomotion.STILL);
        percepts.beings = List.of(being);
        String key = BeingSource.key(being);

        long now = 0;
        long previousGap = 0;
        for (int round = 0; round < 4; round++) {
            long lookedAt = waitUntilLookedAt(key, now);
            long endedAt = tick(lookedAt).until();
            long gap = waitUntilLookedAt(key, endedAt) - endedAt;
            assertTrue(gap > previousGap,
                    "round " + round + " came back after " + gap + " ticks, no later than the "
                            + previousGap + " before it — the body never gets bored");
            previousGap = gap;
            now = endedAt;
        }
        assertTrue(previousGap > Attention.REFRACTORY_TICKS / 2,
                "after four looks at a statue the gap is still under fifteen seconds: " + previousGap);
    }

    @Test
    @DisplayName("doing something else makes somebody worth looking at again")
    void aChangeOfSightRestoresInterest() {
        BeingId alice = BeingId.of(UUID.randomUUID());
        Being standing = person(alice, 4, Being.Awareness.SEEN, Being.Locomotion.STILL);
        percepts.beings = List.of(standing);
        String key = BeingSource.key(standing);

        long now = 0;
        for (int round = 0; round < 4; round++) {           // bore the body thoroughly
            now = tick(waitUntilLookedAt(key, now)).until();
        }
        long boredUntil = waitUntilLookedAt(key, now);

        // Same person, same spot, now walking. The body tires of a SIGHT, not of a thing.
        percepts.beings = List.of(person(alice, 4, Being.Awareness.SEEN, Being.Locomotion.WALKING));
        long freshUntil = waitUntilLookedAt(key, now);
        assertTrue(freshUntil < boredUntil,
                "walking off should be worth a look sooner than standing there: " + freshUntil
                        + " vs " + boredUntil);
    }

    private long waitUntilLookedAt(String key, long from) {
        for (long t = from; t < from + 20_000; t += Attention.DECIDE_INTERVAL) {
            if (key.equals(tick(t).key())) {
                return t;
            }
        }
        throw new AssertionError("never looked at " + key + " again within a thousand seconds");
    }
}
