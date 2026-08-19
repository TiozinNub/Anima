package dev.luizloyola.anima.core.brain.sense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.TestSpecies;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two hail marks: what they let the body believe, how long for, and the deliberate decision
 * that neither survives a restart.
 */
class HailedTest {

    private static final int PATIENCE =
            TestSpecies.PROFILE.i(ProfileAspect.SOCIAL_HAIL_PATIENCE_TICKS);

    private final BeingId caller = BeingId.of(UUID.randomUUID());

    private BeingReading at(Pos pos, double distance) {
        return new BeingReading(caller, Being.Kind.AGENT, "person", "", null, false, pos, distance,
                Being.Locomotion.STILL, false, false, false, false, Being.Gear.NONE,
                Being.Activity.IDLE);
    }

    private Being only(BeingSensorCore sense) {
        List<Being> beings = sense.beings();
        assertEquals(1, beings.size(), "expected exactly one perceived thing: " + beings);
        return beings.get(0);
    }

    /** A world with exactly one visible body, seen and in sight — nothing else in it. */
    private BeingWorld world(BeingReading who) {
        return new BeingWorld() {
            @Override
            public List<BeingReading> candidates() {
                return List.of(who);
            }

            @Override
            public BeingReading reading(BeingId id) {
                return id.equals(who.id()) ? who : null;
            }

            @Override
            public boolean inSight(BeingId id) {
                return id.equals(who.id());
            }
        };
    }

    @Test
    void aHailNamesTheSpeciesAndNothingMore() {
        BeingSensorCore sense = new BeingSensorCore(TestSpecies.PROFILE);
        BeingReading caller = at(new Pos(40, 64, 0), 40.0);

        sense.hailedBy(caller, 100L);

        Being heard = only(sense);
        assertTrue(heard.hailing(), "a shout is heard as a shout");
        assertEquals(Being.Identified.SPECIES, heard.identified(),
                "a voice names the species; only sight names the individual");
    }

    @Test
    void theMarkFadesOnItsOwnClock() {
        BeingSensorCore sense = new BeingSensorCore(TestSpecies.PROFILE);
        BeingReading caller = at(new Pos(40, 64, 0), 40.0);

        sense.hailedBy(caller, 0L);
        sense.tick(new Pos(0, 64, 0), 0, 0, PATIENCE, new NoWorld());
        assertTrue(only(sense).hailing(), "still worth answering at the deadline");

        sense.tick(new Pos(0, 64, 0), 0, 0, PATIENCE + 1, new NoWorld());
        assertFalse(only(sense).hailing(), "past it, the call stopped being real");
    }

    @Test
    void callingSomebodySuppressesCallingThemAgain() {
        BeingSensorCore sense = new BeingSensorCore(TestSpecies.PROFILE);
        BeingReading them = at(new Pos(40, 64, 0), 40.0);
        sense.heard(them, 0L, true);

        assertFalse(sense.calledLately(them.id(), 0L), "nobody has been called yet");
        sense.calledOut(them.id(), 0L);
        assertTrue(sense.calledLately(them.id(), PATIENCE), "the reason is still spent");
        assertFalse(sense.calledLately(them.id(), PATIENCE + 1), "and re-arms after it");
    }

    @Test
    void standingInFrontOfSomebodyAnswersTheirCall() {
        BeingSensorCore sense = new BeingSensorCore(TestSpecies.PROFILE);

        sense.hailedBy(at(new Pos(40, 64, 0), 40.0), 0L);
        assertTrue(only(sense).hailing());

        // They walked over: seen, close enough to talk. Yaw 90 faces west, putting the caller —
        // now one block west of the body's feet — dead ahead; the cone would otherwise exclude
        // them and sight would never upgrade the tier to INDIVIDUAL for the answer to fire on.
        sense.tick(new Pos(1, 64, 0), 90, 0, 100L, world(at(new Pos(0, 64, 0), 1.0)));

        assertFalse(only(sense).hailing(),
                "a call is answered once — otherwise Converse re-grants Answer for as long as the "
                        + "mark lives and the pair shuffle in place");
    }

    @Test
    void neitherMarkSurvivesARestart() {
        BeingSensorCore sense = new BeingSensorCore(TestSpecies.PROFILE);
        BeingReading caller = at(new Pos(40, 64, 0), 40.0);
        sense.hailedBy(caller, 0L);
        sense.calledOut(caller.id(), 0L);

        BeingSensorCore reloaded = new BeingSensorCore(TestSpecies.PROFILE);
        reloaded.restore(sense.snapshot());

        assertFalse(only(reloaded).hailing(),
                "a 600-tick call is not worth reviving across a restart — deliberate, not an "
                        + "oversight: see the voice-and-hail design");
        assertFalse(reloaded.calledLately(caller.id(), 0L));
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
