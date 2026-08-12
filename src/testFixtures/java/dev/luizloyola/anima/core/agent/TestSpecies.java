package dev.luizloyola.anima.core.agent;

/**
 * A complete species declaration for headless tests, carrying the numbers Anima's own defaults used
 * to hold — so old expectations still mean what they meant, and none of them is production code any
 * more. (Anima ships no values: an undeclared species is a programming error.)
 *
 * <p>When Anima grows an aspect, {@link SpeciesProfile.Builder#build()} throws here, naming it —
 * the same failure a consuming mod gets, one test run earlier.
 */
public final class TestSpecies {

    /** The body the core suites are written against — a settler-shaped biped. */
    public static final SpeciesProfile BIPED = SpeciesProfile.of("test_biped")
            .set(ProfileAspect.MIND_STICKINESS, 0.1)
            .set(ProfileAspect.MIND_PREEMPT, 0.6)
            .set(ProfileAspect.FLEE_RANGE, 16.0)
            .set(ProfileAspect.FLEE_RAMP, 12.0)
            .set(ProfileAspect.FLEE_APPROACH_BONUS, 1.3)
            .set(ProfileAspect.WANDER_IDLE_PRESSURE, 0.15)
            .set(ProfileAspect.WANDER_RADIUS, 8)
            .set(ProfileAspect.SENSES_RADIUS, 24)
            .set(ProfileAspect.SENSES_SNEAK_RANGE_MULT, 0.75)
            .set(ProfileAspect.SENSES_CONE_DEGREES, 150)
            .set(ProfileAspect.SENSES_VERTICAL_DEGREES, 60)
            .set(ProfileAspect.SENSES_HEARING_RADIUS, 12)
            .set(ProfileAspect.SENSES_LINGER_TICKS, 300)
            .set(ProfileAspect.SENSES_HEARD_DECAY_TICKS, 60)
            .set(ProfileAspect.SENSES_NEAR_INTERVAL, 1)
            .set(ProfileAspect.SENSES_FAR_INTERVAL, 20)
            .set(ProfileAspect.SENSES_ATTACK_DECAY_TICKS, 600)
            .set(ProfileAspect.SENSES_HERD_LINK_RADIUS, 12)
            // Omnidirectional: the pipeline suites place things all around a fixed
            // body and test noticing, not aperture, and a 360° cone is the old disc. Cone
            // geometry belongs to CrescentSamplerTest.
            .set(ProfileAspect.PLACES_RADIUS, 12)
            .set(ProfileAspect.PLACES_CONE_DEGREES, 360)
            .set(ProfileAspect.PLACES_NEAR_RADIUS, 0)
            // Off, for the same reason the cone is 360: the pipeline suites assert exact event
            // counts and read budgets for the NEAR field, and a skyline sweep spending the
            // leftovers would silently rewrite every one of them. HorizonScannerTest declares
            // its own body with eyes.
            .set(ProfileAspect.PLACES_HORIZON_RADIUS, 0)
            // A settler's 8, unlike the two above: HorizonScannerTest borrows this body and turns
            // its skyline back on, and the thing that suite is a test OF is what a ray does when
            // it meets a canopy. Neutering the reach here would make it assert nothing.
            .set(ProfileAspect.PLACES_SEE_THROUGH_RADIUS, 8)
            .set(ProfileAspect.PLACES_REGION_MAX_SPREAD, 24)
            .set(ProfileAspect.PLACES_MAX_PER_KIND, 160)
            .set(ProfileAspect.DANGER_MELEE_MULT, 1.15)
            .set(ProfileAspect.DANGER_RANGED_MULT, 1.25)
            .set(ProfileAspect.DANGER_ARMORED_MULT, 1.2)
            .set(ProfileAspect.DANGER_MOUNTED_MULT, 1.15)
            .set(ProfileAspect.DANGER_BABY_MULT, 1.2)
            .set(ProfileAspect.SOCIAL_HAIL_RADIUS, 48)
            // A settler's company band and rates. Round numbers on purpose: the band is exactly
            // [0.35, 0.85] and one neighbour is exactly twice solitude, so a suite can assert what
            // a tick did without carrying a tolerance for it.
            .set(ProfileAspect.SOCIAL_COMPANY_SOLITUDE_TICKS, 48_000)
            .set(ProfileAspect.SOCIAL_COMPANY_PROXIMITY_TICKS, 24_000)
            .set(ProfileAspect.SOCIAL_COMPANY_UTTERANCES, 30)
            .set(ProfileAspect.BODY_HEIGHT, 1.8)
            .set(ProfileAspect.BODY_JUMP_HEIGHT, 1)
            .set(ProfileAspect.BODY_MAX_DROP, 3)
            .set(ProfileAspect.BODY_MAX_LEAP, 3)
            .set(ProfileAspect.BODY_CAN_SWIM, true)
            .set(ProfileAspect.BODY_CAN_DIG, true)
            .set(ProfileAspect.ESCAPE_PRESSURE, 0.9)
            // A settler's neck, and round dwell numbers: 40 and 120 make a scan's roll land on
            // exact ticks, so a suite can assert when the next look is due without a tolerance.
            .set(ProfileAspect.GAZE_TURN_DEGREES, 12.0)
            .set(ProfileAspect.GAZE_MAX_TWIST_DEGREES, 60)
            .set(ProfileAspect.GAZE_SCAN_MIN_TICKS, 40)
            .set(ProfileAspect.GAZE_SCAN_MAX_TICKS, 120)
            .build();

    /** {@link #BIPED} as a profile — fixed, with no config file behind it. */
    public static final AgentProfile PROFILE = BIPED.fixed();

    /** {@link #BIPED} with one aspect moved, for a test that needs a body unlike the default one. */
    public static AgentProfile with(ProfileAspect aspect, double value) {
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_variant");
        for (ProfileAspect each : ProfileAspect.all()) {
            builder.set(each, each == aspect ? value : BIPED.get(each));
        }
        return builder.build().fixed();
    }

    private TestSpecies() {
    }
}
