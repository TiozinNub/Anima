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
            .set(ProfileAspect.DESCEND_PRESSURE, 0.45)
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
            .set(ProfileAspect.SENSES_HERD_LINK_RADIUS, 12)
            .set(ProfileAspect.PLACES_RADIUS, 12)
            .set(ProfileAspect.PLACES_REGION_MAX_SPREAD, 24)
            .set(ProfileAspect.PLACES_MAX_PER_KIND, 160)
            .set(ProfileAspect.DANGER_MELEE_MULT, 1.15)
            .set(ProfileAspect.DANGER_RANGED_MULT, 1.25)
            .set(ProfileAspect.DANGER_ARMORED_MULT, 1.2)
            .set(ProfileAspect.DANGER_MOUNTED_MULT, 1.15)
            .set(ProfileAspect.DANGER_BABY_MULT, 1.2)
            .set(ProfileAspect.SOCIAL_HAIL_RADIUS, 48)
            .set(ProfileAspect.BODY_HEIGHT, 2)
            .set(ProfileAspect.BODY_JUMP_HEIGHT, 1)
            .set(ProfileAspect.BODY_MAX_DROP, 3)
            .set(ProfileAspect.BODY_MAX_LEAP, 3)
            .set(ProfileAspect.BODY_CAN_SWIM, true)
            .build();

    /** {@link #BIPED} as a profile — fixed, with no config file behind it. */
    public static final AgentProfile PROFILE = BIPED.fixed();

    /** {@link #BIPED} with one aspect moved, for a test that needs a body unlike the default one. */
    public static AgentProfile with(ProfileAspect aspect, double value) {
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_variant");
        for (ProfileAspect each : ProfileAspect.values()) {
            builder.set(each, each == aspect ? value : BIPED.get(each));
        }
        return builder.build().fixed();
    }

    private TestSpecies() {
    }
}
