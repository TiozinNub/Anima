package dev.luizloyola.anima.core.agent;

import dev.luizloyola.anima.core.config.KnobSpec.Kind;
import java.util.Optional;

/**
 * The schema of a mind: one constant per aspect on which one species may differ from another. See
 * {@link SpeciesProfile} for the declaration, {@link AgentProfile} for the resolved read, and
 * {@code docs/superpowers/specs/2026-07-28-per-species-minds-design.md} for the whole design.
 *
 * <p><b>Anima names the aspects and ships no values.</b> An undeclared aspect is a programming
 * error rather than a species quietly inheriting a library's idea of how far a body can see.
 * Bounds travel with the aspect: a declared value still has to be legal, and the operator may
 * hand-edit the species file afterwards.
 *
 * <p><b>What is not here.</b> This enum holds all but nine of Anima's tunables; the nine left in
 * {@code anima.json} failed one of two tests, and {@code ProfileAspectTest} fails the build until a
 * new tunable is listed here or listed there:
 *
 * <ul>
 *   <li><b>A species could spend the server's budget with it.</b> {@code perception.reads_per_tick},
 *       {@code perception.queue_cap}, {@code perception.region_max_blocks} and
 *       {@code peers.ray_budget} bound work per agent per tick — server-wide caps, not defaults.
 *   <li><b>It is not a way one mind differs from another.</b> {@code claims.ttl_ticks} is the
 *       contract of a registry two agents share; the four {@code journal.*} knobs configure a
 *       debugging facility and its disk use.
 * </ul>
 *
 * <p>Keys land inside a consumer's file as {@code <species>.anima_settings.<path>}. The old
 * {@code peers.*} name went with the old shape — that sense outgrew peers when it widened to every
 * living body.
 */
public enum ProfileAspect {

    // --- mind: how this one changes its mind --------------------------------------------------

    MIND_STICKINESS("mind.stickiness", Kind.DOUBLE, 0.0, 1.0,
            "Incumbency bonus added to whatever this body is already doing. High is dogged, low "
                    + "is flighty — the difference between a settler finishing a tree and a cat "
                    + "abandoning it."),
    MIND_PREEMPT("mind.preempt", Kind.DOUBLE, 0.0, 1.0,
            "Minimum raw pressure a new urge needs to cut into what this body is doing "
                    + "mid-flight."),

    // --- instincts: what this body wants, unprompted ------------------------------------------

    FLEE_RANGE("instincts.flee_range", Kind.DOUBLE, 1.0, 64.0,
            "Distance (blocks) at which a threat registers on this body at all. A rabbit's is "
                    + "not a golem's. A threat holding something ranged is feared from as far as "
                    + "this body can perceive it instead — reach is a property of the weapon, not "
                    + "a multiple of this."),
    FLEE_RAMP("instincts.flee_ramp", Kind.DOUBLE, 1.0, 64.0,
            "Distance (blocks) over which this body's fear climbs from nothing to full panic."),
    FLEE_APPROACH_BONUS("instincts.flee_approach_bonus", Kind.DOUBLE, 1.0, 4.0,
            "Pressure multiplier when a threat is measurably closing in — how strongly this body "
                    + "reads being followed as being hunted."),
    WANDER_IDLE_PRESSURE("instincts.wander_idle_pressure", Kind.DOUBLE, 0.0, 1.0,
            "This body's do-something floor. Every real drive must beat it; at 0 an unbothered "
                    + "body stands still, which is a perfectly good way for some things to be."),
    WANDER_RADIUS("instincts.wander_radius", Kind.INT, 1, 64,
            "How far (blocks) an idle saunter may roll its next beat — a tethered pet and a "
                    + "roaming herd differ here and nowhere else."),

    // --- senses: what this body makes of other bodies -----------------------------------------

    SENSES_RADIUS("senses.radius", Kind.INT, 4, 64,
            "How far (blocks) this body perceives another at all, by any channel. Also the far "
                    + "end of its attention curve, and how far a threat holding something ranged "
                    + "is feared from."),
    SENSES_SNEAK_RANGE_MULT("senses.sneak_range_mult", Kind.DOUBLE, 0.1, 1.0,
            "Multiplier on that radius against a sneaking body. Sneaking shrinks how far away "
                    + "you are noticed by this one; it never makes you invisible to it."),
    SENSES_CONE_DEGREES("senses.cone_degrees", Kind.INT, 30, 360,
            "Horizontal field of view (degrees). Prey animals have eyes on the sides of their "
                    + "heads and this is where that becomes true; 360 is omniscience."),
    SENSES_VERTICAL_DEGREES("senses.vertical_degrees", Kind.INT, 5, 90,
            "Vertical field HALF-angle (degrees) around gaze pitch. Not simply half the "
                    + "horizontal one — vision is wide across and flat up-down."),
    SENSES_HEARING_RADIUS("senses.hearing_radius", Kind.INT, 0, 32,
            "How far (blocks) this body hears sound-makers regardless of where it is looking. "
                    + "0 is deaf, which is a legitimate thing for a body to be."),
    SENSES_LINGER_TICKS("senses.linger_ticks", Kind.INT, 0, 2400,
            "Object permanence: how long something stays perceived, frozen as remembered, after "
                    + "every channel goes dark. How long this body believes in what it can no "
                    + "longer sense."),
    SENSES_HEARD_DECAY_TICKS("senses.heard_activity_decay_ticks", Kind.INT, 0, 1200,
            "How long a sound-told activity stays believed after the sound stops, before only "
                    + "the bare presence is left."),
    SENSES_NEAR_INTERVAL("senses.near_interval_ticks", Kind.INT, 1, 100,
            "Attention at point-blank: re-check interval (ticks) for a body standing right "
                    + "there. Cheap — the expensive sight rays answer to the server's budget."),
    SENSES_FAR_INTERVAL("senses.far_interval_ticks", Kind.INT, 1, 400,
            "Attention at the edge: re-check interval (ticks) at the limit of perception. "
                    + "Distances between lerp across the two, so the pair is this body's "
                    + "attention span."),
    SENSES_ATTACK_DECAY_TICKS("senses.attack_decay_ticks", Kind.INT, 0, 24_000,
            "How long being attacked keeps something read as hostile, with or without a face on "
                    + "it. Much longer than the other channels on purpose: forgetting an attack "
                    + "fifteen seconds later is not object permanence, it is amnesia. 0 means "
                    + "this body reacts to a blow and then lets it go."),
    SENSES_HERD_LINK_RADIUS("senses.herd_link_radius", Kind.INT, 2, 24,
            "How far apart (blocks per axis) two same-species animals may stand and still read "
                    + "to this body as one herd."),

    // --- places: what this body notices about the world as it goes ----------------------------

    PLACES_RADIUS("places.radius", Kind.INT, 1, 64,
            "Horizontal radius (blocks) this body notices places within, where it is looking. "
                    + "Cost scales with the square, but the work is bounded by the server's read "
                    + "budget rather than by this, so a keen nose is safe."),
    PLACES_CONE_DEGREES("places.cone_degrees", Kind.INT, 30, 360,
            "Horizontal field (degrees) within which this body notices places past the halo "
                    + "below. Narrowing it is what pays for range — a 150° cone samples 42% of "
                    + "its disc — and is why a body no longer notices what stands squarely "
                    + "behind it. 360 makes noticing omnidirectional at every distance."),
    PLACES_NEAR_RADIUS("places.near_radius", Kind.INT, 0, 32,
            "Radius (blocks) within which places are noticed whichever way this body faces — "
                    + "peripheral vision and the sole of a boot rather than eyesight. Clamped to "
                    + "the radius above. 0 means nothing is noticed off-bearing at all, which is "
                    + "a legitimate thing for a body with eyes on stalks to be."),
    PLACES_REGION_MAX_SPREAD("places.region_max_spread", Kind.INT, 1, 128,
            "Chebyshev spread from a seed before this mind stops calling it one place — what "
                    + "decides whether a forest is one memory or twenty. A judgment about "
                    + "places, not a cost bound."),
    PLACES_MAX_PER_KIND("places.max_per_kind", Kind.INT, 8, 1024,
            "How many places of a kind this body remembers before the stalest goes. Must exceed "
                    + "what it works among, or the edges churn forget/rediscover forever."),

    // --- danger: what this body finds frightening about a body ---------------------------------

    DANGER_MELEE_MULT("danger.melee_mult", Kind.DOUBLE, 0.0, 4.0,
            "Danger multiplier for a visibly held melee weapon. Whether a sword means anything "
                    + "to you depends entirely on what you are."),
    DANGER_RANGED_MULT("danger.ranged_mult", Kind.DOUBLE, 0.0, 4.0,
            "Danger multiplier for a visibly held ranged weapon (bow, crossbow, trident)."),
    DANGER_ARMORED_MULT("danger.armored_mult", Kind.DOUBLE, 0.0, 4.0,
            "Danger multiplier for visible armor on the body."),
    DANGER_MOUNTED_MULT("danger.mounted_mult", Kind.DOUBLE, 0.0, 4.0,
            "Danger multiplier for a mounted body — spider jockeys, skeleton horsemen."),
    DANGER_BABY_MULT("danger.baby_mult", Kind.DOUBLE, 0.0, 4.0,
            "Danger multiplier for a baby variant: smaller, faster, harder to hit."),

    // --- social -------------------------------------------------------------------------------

    SOCIAL_HAIL_RADIUS("social.hail_radius", Kind.INT, 8, 64,
            "How far (blocks) a deliberate shout reaches this body, and how far its own carries. "
                    + "Must exceed this species' senses.radius or hailing adds nothing to simply "
                    + "noticing someone."),

    // --- body: what this one can physically do -------------------------------------------------

    BODY_HEIGHT("body.height", Kind.INT, 1, 8,
            "Body height in whole cells — every column this body walks through needs that much "
                    + "clearance. A Person is 2 (a 1.8 hitbox); a wolf is 1, and squeezes under "
                    + "things a Person must walk around."),
    BODY_JUMP_HEIGHT("body.jump_height", Kind.INT, 0, 1,
            "How many cells this body can jump straight up. Only 0 and 1 are modelled; 0 means "
                    + "every step up is a wall, which is how a path around one gets found."),
    BODY_MAX_DROP("body.max_drop", Kind.INT, 0, 32,
            "How many cells this body will willingly fall. Anything deeper reads as a hole to "
                    + "route around, and the same number decides what counts as a ledge to be "
                    + "careful near — a cat's tolerance is not a settler's."),
    BODY_MAX_LEAP("body.max_leap", Kind.INT, 0, 3,
            "Widest gap (cells) this body can jump across at the same level. 1 is a walking "
                    + "jump; 2 and 3 need a sprint run-up, so the path also demands an aligned "
                    + "approach cell for those. 3 is the vanilla sprint-jump limit."),
    BODY_CAN_SWIM("body.can_swim", Kind.BOOL, 0, 1,
            "Whether this body may enter and cross water. False keeps water impassable, so a "
                    + "land-only body routes around it. Surface crossing only — diving would "
                    + "need a depth and a breath of its own.");

    private final String key;
    private final Kind kind;
    private final double min;
    private final double max;
    private final String doc;

    ProfileAspect(String key, Kind kind, double min, double max, String doc) {
        this.key = key;
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.doc = doc;
    }

    /** The dotted, snake_case path this aspect lands at inside a species' section of a file. */
    public String key() {
        return key;
    }

    public Kind kind() {
        return kind;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    /** One sentence for the operator, phrased for whoever is describing a species. */
    public String doc() {
        return doc;
    }

    /** The JSON object this aspect nests under within a species ({@code "senses"}). */
    public String section() {
        return key.substring(0, key.indexOf('.'));
    }

    /** Whether {@code value} is legal for this aspect — what {@link SpeciesProfile} checks. */
    public boolean accepts(double value) {
        return Double.isFinite(value) && value >= min && value <= max
                && (kind == Kind.DOUBLE || value == Math.rint(value));
    }

    public static Optional<ProfileAspect> byKey(String key) {
        for (ProfileAspect aspect : values()) {
            if (aspect.key.equals(key)) {
                return Optional.of(aspect);
            }
        }
        return Optional.empty();
    }
}
