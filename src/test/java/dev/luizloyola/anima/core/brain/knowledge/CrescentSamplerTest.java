package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The leading-crescent geometry: event-driven, zero at rest, full view on discontinuity — and,
 * since the view became a cone with a halo, a wedge when the head turns.
 *
 * <p>{@link TestSpecies} is omnidirectional on purpose (see its note), so the first four tests are
 * the old disc behaviour; the cone gets its own body, {@link #EYED}.
 */
class CrescentSamplerTest {

    private static final int R = CrescentSampler.radius(TestSpecies.PROFILE);

    /** Bearing conventions, Minecraft's: 0° faces +Z, 90° faces −X. */
    private static final double NORTH = 0.0;

    /** A 12-block reach, a human 150° aperture, a 4-block halo. */
    private static final AgentProfile EYED = eyed(12, 150, 4);

    private static AgentProfile eyed(int radius, int cone, int near) {
        Map<ProfileAspect, Double> overrides = Map.of(
                ProfileAspect.PLACES_RADIUS, (double) radius,
                ProfileAspect.PLACES_CONE_DEGREES, (double) cone,
                ProfileAspect.PLACES_NEAR_RADIUS, (double) near);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_eyed");
        for (ProfileAspect aspect : ProfileAspect.all()) {
            builder.set(aspect, overrides.getOrDefault(aspect, TestSpecies.BIPED.get(aspect)));
        }
        return builder.build().fixed();
    }

    @Test
    void firstSightYieldsTheFullDisc() {
        CrescentSampler sampler = new CrescentSampler(TestSpecies.PROFILE);
        List<Column> disc = sampler.advance(new Pos(0, 64, 0), NORTH);

        assertTrue(disc.contains(new Column(0, 0)), "their own column");
        assertTrue(disc.contains(new Column(R, 0)), "rim");
        assertFalse(disc.contains(new Column(R + 1, 0)), "beyond the rim");
        assertTrue(disc.size() > 400 && disc.size() < 470, "≈ πR² = ~452, got " + disc.size());
    }

    @Test
    void standingStillEmitsNothingEvenWhenYChanges() {
        CrescentSampler sampler = new CrescentSampler(TestSpecies.PROFILE);
        sampler.advance(new Pos(0, 64, 0), NORTH);

        assertTrue(sampler.advance(new Pos(0, 64, 0), NORTH).isEmpty());
        assertTrue(sampler.advance(new Pos(0, 70, 0), NORTH).isEmpty(),
                "jumping in place is not moving");
    }

    @Test
    void oneStepEmitsOnlyTheLeadingCrescent() {
        CrescentSampler sampler = new CrescentSampler(TestSpecies.PROFILE);
        sampler.advance(new Pos(0, 64, 0), NORTH);
        List<Column> crescent = sampler.advance(new Pos(1, 64, 0), NORTH);

        assertTrue(crescent.size() >= 2 * R - 5 && crescent.size() <= 2 * R + 5,
                "≈ 2R per block moved, got " + crescent.size());
        assertTrue(crescent.contains(new Column(R + 1, 0)), "the new rim cell dead ahead");
        for (Column c : crescent) {
            long oldDist = (long) c.x() * c.x() + (long) c.z() * c.z();
            assertTrue(oldDist > R * R, c + " was already in range before the step");
        }
    }

    @Test
    void teleportRefillsTheWholeDiscAtTheNewCenter() {
        CrescentSampler sampler = new CrescentSampler(TestSpecies.PROFILE);
        sampler.advance(new Pos(0, 64, 0), NORTH);
        List<Column> disc = sampler.advance(new Pos(1000, 64, -3), NORTH);

        assertTrue(disc.size() > 400, "a jump beyond R starts a fresh glance");
        assertTrue(disc.contains(new Column(1000, -3)));
        assertFalse(disc.contains(new Column(0, 0)));
    }

    // --- the cone ---------------------------------------------------------------------------

    @Test
    void theViewIsAConeAheadAndAHaloUnderfoot() {
        CrescentSampler sampler = new CrescentSampler(EYED);
        List<Column> view = sampler.advance(new Pos(0, 64, 0), NORTH);

        assertTrue(view.contains(new Column(0, 12)), "dead ahead, at the rim");
        assertTrue(view.contains(new Column(0, -4)), "behind, but underfoot in the halo");
        assertFalse(view.contains(new Column(0, -12)), "squarely behind, past the halo");
        assertFalse(view.contains(new Column(12, 0)), "90° off a 150° cone is outside it");
        // π·4² + (150/360)·π·(12² − 4²) ≈ 218
        assertTrue(view.size() > 190 && view.size() < 250, "≈ 218, got " + view.size());
    }

    @Test
    void aTwitchOfTheHeadEmitsNothing() {
        CrescentSampler sampler = new CrescentSampler(EYED);
        sampler.advance(new Pos(0, 64, 0), NORTH);

        assertTrue(sampler.advance(new Pos(0, 64, 0), NORTH + 5).isEmpty(),
                "under the hysteresis: a turn this small cannot have revealed a whole tree");
    }

    @Test
    void turningPastTheHysteresisEmitsOnlyTheWedgeItOpened() {
        CrescentSampler sampler = new CrescentSampler(EYED);
        List<Column> before = sampler.advance(new Pos(0, 64, 0), NORTH);
        List<Column> wedge = sampler.advance(new Pos(0, 64, 0), NORTH + 20);

        assertFalse(wedge.isEmpty(), "20° is a real turn");
        // (20/360)·π·(12² − 4²) ≈ 22
        assertTrue(wedge.size() < 60, "a wedge, not a re-sweep, got " + wedge.size());
        for (Column c : wedge) {
            assertFalse(before.contains(c), c + " was already in view before the turn");
        }
    }

    @Test
    void turningRightAroundRevealsWhatWasBehind() {
        CrescentSampler sampler = new CrescentSampler(EYED);
        sampler.advance(new Pos(0, 64, 0), NORTH);
        List<Column> about = sampler.advance(new Pos(0, 64, 0), NORTH + 180);

        assertTrue(about.contains(new Column(0, -12)), "what was at their back is now ahead");
        assertFalse(about.contains(new Column(0, 12)), "and what was ahead is not re-emitted");
    }

    @Test
    void aDriftingHeadNeverOpensAGap() {
        // A body that walks and turns at once must not lose the columns that only the
        // combination uncovers: the difference is taken against the last view ENUMERATED, not
        // against the last position and the current bearing.
        CrescentSampler drifting = new CrescentSampler(EYED);
        java.util.Set<Column> seen = new java.util.HashSet<>(
                drifting.advance(new Pos(0, 64, 0), NORTH));
        for (int step = 1; step <= 8; step++) {
            seen.addAll(drifting.advance(new Pos(0, 64, step), NORTH + step * 3.0));
        }

        CrescentSampler fresh = new CrescentSampler(EYED);
        List<Column> truth = fresh.advance(new Pos(0, 64, 8), NORTH + 24.0);

        for (Column c : truth) {
            assertTrue(seen.contains(c), c + " is in view at journey's end but was never emitted");
        }
    }
}
