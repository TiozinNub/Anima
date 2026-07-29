package dev.luizloyola.anima.core.brain.sense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.instinct.FleeInstinct;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Being attacked reaches through cover, darkness and a missing line of sight, and says nothing
 * about who. Hostility and identity are separable: the ladder still masks the identity, the
 * mark says outright that whatever is over there is dangerous.
 */
class AttackedTest {

    private final BeingSensorCore sensor = new BeingSensorCore(TestSpecies.PROFILE);
    private final BeingId shooter = BeingId.of(UUID.randomUUID());

    private BeingReading at(Pos pos, double distance) {
        return new BeingReading(shooter, Being.Kind.UNKNOWN, "", "", null, false, pos, distance,
                Being.Locomotion.STILL, false, false, false, true, Being.Gear.NONE,
                Being.Activity.IDLE);
    }

    private Being only() {
        List<Being> beings = sensor.beings();
        assertEquals(1, beings.size(), "expected exactly one perceived thing: " + beings);
        return beings.get(0);
    }

    @Test
    @DisplayName("an arrow from nowhere becomes something hostile at a known place")
    void aHitWithNoFaceIsStillWorthRunningFrom() {
        sensor.attacked(at(new Pos(10, 64, 0), 10.0), 100L, false);

        Being being = only();
        assertSame(Being.Kind.HOSTILE, being.kind(),
                "below SPECIES exposes UNKNOWN — or HOSTILE when aggression is known anyway");
        assertTrue(being.aggressive(), "it demonstrably attacked; that is not a guess");
        assertEquals("", being.species(), "hostility is not identity: there is still no who");
        assertEquals(new Pos(10, 64, 0), being.pos(), "the position is exact and never blurred");
    }

    @Test
    @DisplayName("and the flee instinct prices it as a threat rather than as a nobody")
    void anAnonymousAttackerIsPricedAsHostileNotAsDefault() {
        sensor.attacked(at(new Pos(4, 64, 0), 4.0), 100L, false);
        Being being = only();

        double pressure = FleeInstinct.pressureOf(TestSpecies.PROFILE, TestDanger.TABLE, being);
        assertTrue(pressure > 0.0,
                "an agent standing calmly in arrow fire is the failure this key exists to stop");

        // Priced by the hostile key, not by "a mob I have no opinion about".
        DangerTable indifferent = TestDanger.TABLE.withOverrides(
                java.util.Map.of(DangerTable.DEFAULT_KEY, 1.0, DangerTable.HOSTILE_KEY, 0.0));
        assertEquals(0.0, FleeInstinct.pressureOf(TestSpecies.PROFILE, indifferent, being), 1e-9);
    }

    @Test
    @DisplayName("a hit on something already tracked marks THAT, rather than inventing a stranger")
    void ahitOnAKnownTrackResolvesTheThingWeHeard() {
        BeingReading heard = new BeingReading(shooter, Being.Kind.MONSTER, "skeleton", "", null,
                false, new Pos(10, 64, 0), 10.0, Being.Locomotion.STILL, false, false, false,
                true, Being.Gear.NONE, Being.Activity.IDLE);
        sensor.heard(heard, 100L, true); // a voice named the species through a wall

        sensor.attacked(heard, 120L, true);

        Being being = only();
        assertEquals("skeleton", being.species(),
                "the thing I heard over there is what is shooting me — no second track");
        assertSame(Being.Kind.MONSTER, being.kind(), "a face beats the anonymous rung");
    }

    @Test
    @DisplayName("the mark outlives the other channels, then lets go")
    void theGrudgeDecaysOnItsOwnMuchSlowerClock() {
        sensor.attacked(at(new Pos(10, 64, 0), 10.0), 100L, false);
        int grudge = TestSpecies.PROFILE.i(ProfileAspect.SENSES_ATTACK_DECAY_TICKS);

        // Well past the heard-activity decay, and still afraid.
        sensor.tick(new Pos(0, 64, 0), 0, 0, 100L + grudge / 2, new NoWorld());
        assertTrue(only().aggressive(), "forgetting an attack in fifteen seconds would be amnesia");

        // Past its own clock, and it lets go — the track is still there, it is just not a threat.
        sensor.tick(new Pos(0, 64, 0), 0, 0, 100L + grudge + 1, new NoWorld());
        assertFalse(only().aggressive());
        assertSame(Being.Kind.UNKNOWN, only().kind(), "back to something unidentified over there");
    }

    /** A world with nobody in it: the sense still ticks, it just never sees anything. */
    private static final class NoWorld implements BeingWorld {
        @Override
        public List<BeingReading> candidates() {
            return List.of();
        }

        @Override
        public BeingReading reading(BeingId id) {
            return null;
        }

        @Override
        public boolean inSight(BeingId id) {
            return false;
        }
    }
}
