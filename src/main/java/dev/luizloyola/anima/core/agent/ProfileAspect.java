package dev.luizloyola.anima.core.agent;

import dev.luizloyola.anima.core.config.KnobSpec.Kind;
import java.util.Optional;

/**
 * The schema of a mind: one constant per aspect on which one species may differ from another. See
 * {@link AgentProfile} for the resolved read and
 * {@code docs/superpowers/specs/2026-07-28-per-species-minds-design.md} for the whole design.
 *
 * <p><b>Anima names the aspects and ships no values.</b> An undeclared aspect is a programming
 * error rather than a species quietly inheriting a library's idea of how far a body can see.
 * Bounds travel with the aspect: a declared value still has to be legal, and the operator may
 * hand-edit the species file afterwards.
 *
 * <p><b>What is not here.</b> This enum holds 28 of Anima's 37 knobs; the nine left in
 * {@code anima.json} failed one of two tests, and {@code ProfileAspectTest} fails the build until
 * knob #38 is listed here or listed there:
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
 * <p><b>Keys are today's knob keys, unchanged.</b> {@code peers.*} is a stale name — the
 * being sense outgrew peers when it widened to every living body — and the sections get re-parented
 * anyway once these land inside a consumer's file as {@code <species>.<namespace>.<path>}. Renaming
 * them is a config migration and deserves its own decision.
 */
public enum ProfileAspect {

    // --- arbitration: how this mind changes its mind ------------------------------------------

    BRAIN_STICKINESS("brain.stickiness", Kind.DOUBLE, 0.0, 1.0,
            "Incumbency bonus added to whatever this body is already doing. High is dogged, low "
                    + "is flighty — the difference between a settler finishing a tree and a cat "
                    + "abandoning it."),
    BRAIN_PREEMPT("brain.preempt", Kind.DOUBLE, 0.0, 1.0,
            "Minimum raw pressure a new urge needs to cut into what this body is doing "
                    + "mid-flight."),

    // --- instincts: what this body wants, unprompted ------------------------------------------

    FLEE_RANGE("instincts.flee_range", Kind.DOUBLE, 1.0, 64.0,
            "Distance (blocks) at which a threat registers on this body at all. A rabbit's is "
                    + "not a golem's."),
    FLEE_RAMP("instincts.flee_ramp", Kind.DOUBLE, 1.0, 64.0,
            "Distance (blocks) over which this body's fear climbs from nothing to full panic."),
    FLEE_RANGED_RANGE_MULT("instincts.flee_ranged_range_mult", Kind.DOUBLE, 1.0, 4.0,
            "Flee-range multiplier against a source holding something ranged. Slated for removal: "
                    + "the design settled on ranged meaning flee at perception range rather than "
                    + "at a multiple of flee range."),
    FLEE_APPROACH_BONUS("instincts.flee_approach_bonus", Kind.DOUBLE, 1.0, 4.0,
            "Pressure multiplier when a threat is measurably closing in — how strongly this body "
                    + "reads being followed as being hunted."),
    DESCEND_PRESSURE("instincts.descend_pressure", Kind.DOUBLE, 0.0, 1.0,
            "Pressure to climb down off an orphaned pillar. Keep below brain.preempt so a "
                    + "legitimate mid-climb job is never interrupted."),
    WANDER_IDLE_PRESSURE("instincts.wander_idle_pressure", Kind.DOUBLE, 0.0, 1.0,
            "This body's do-something floor. Every real drive must beat it; at 0 an unbothered "
                    + "body stands still, which is a perfectly good way for some things to be."),
    WANDER_RADIUS("instincts.wander_radius", Kind.INT, 1, 64,
            "How far (blocks) an idle saunter may roll its next beat — a tethered pet and a "
                    + "roaming herd differ here and nowhere else."),

    // --- perception: what this body notices as it goes ----------------------------------------

    SENSE_RADIUS("perception.sense_radius", Kind.INT, 1, 32,
            "Horizontal radius (blocks) this body notices places within. Cost scales with the "
                    + "square, but the work is bounded by the server's read budget rather than "
                    + "by this, so a keen nose is safe."),
    REGION_MAX_SPREAD("perception.region_max_spread", Kind.INT, 1, 128,
            "Chebyshev spread from a seed before this mind stops calling it one place — what "
                    + "decides whether a forest is one memory or twenty. A judgment about "
                    + "places, not a cost bound."),
    KNOWLEDGE_MAX_PER_KIND("perception.knowledge_max_per_kind", Kind.INT, 8, 1024,
            "How many places of a kind this body remembers before the stalest goes. Must exceed "
                    + "what it works among, or the edges churn forget/rediscover forever."),

    // --- the being sense: what this body makes of other bodies --------------------------------

    PEERS_RADIUS("peers.radius", Kind.INT, 4, 64,
            "How far (blocks) this body perceives another at all, by any channel. Also the far "
                    + "end of its attention curve."),
    PEERS_SNEAK_RANGE_MULT("peers.sneak_range_mult", Kind.DOUBLE, 0.1, 1.0,
            "Multiplier on that radius against a sneaking body. Sneaking shrinks how far away "
                    + "you are noticed by this one; it never makes you invisible to it."),
    PEERS_CONE_DEGREES("peers.cone_degrees", Kind.INT, 30, 360,
            "Horizontal field of view (degrees). Prey animals have eyes on the sides of their "
                    + "heads and this is where that becomes true; 360 is omniscience."),
    PEERS_VERTICAL_DEGREES("peers.vertical_degrees", Kind.INT, 5, 90,
            "Vertical field HALF-angle (degrees) around gaze pitch. Not simply half the "
                    + "horizontal one — vision is wide across and flat up-down."),
    PEERS_HEARING_RADIUS("peers.hearing_radius", Kind.INT, 0, 32,
            "How far (blocks) this body hears sound-makers regardless of where it is looking. "
                    + "0 is deaf, which is a legitimate thing for a body to be."),
    PEERS_LINGER_TICKS("peers.linger_ticks", Kind.INT, 0, 2400,
            "Object permanence: how long something stays perceived, frozen as remembered, after "
                    + "every channel goes dark. How long this body believes in what it can no "
                    + "longer sense."),
    PEERS_HEARD_DECAY_TICKS("peers.heard_activity_decay_ticks", Kind.INT, 0, 1200,
            "How long a sound-told activity stays believed after the sound stops, before only "
                    + "the bare presence is left."),
    PEERS_NEAR_INTERVAL("peers.near_interval_ticks", Kind.INT, 1, 100,
            "Attention at point-blank: re-check interval (ticks) for a body standing right "
                    + "there. Cheap — the expensive sight rays answer to the server's budget."),
    PEERS_FAR_INTERVAL("peers.far_interval_ticks", Kind.INT, 1, 400,
            "Attention at the edge: re-check interval (ticks) at the limit of perception. "
                    + "Distances between lerp across the two, so the pair is this body's "
                    + "attention span."),
    PEERS_HERD_LINK_RADIUS("peers.herd_link_radius", Kind.INT, 2, 24,
            "How far apart (blocks per axis) two same-species animals may stand and still read "
                    + "to this body as one herd."),

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
                    + "Must exceed this species' peers.radius or hailing adds nothing to simply "
                    + "noticing someone.");

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

    public static Optional<ProfileAspect> byKey(String key) {
        for (ProfileAspect aspect : values()) {
            if (aspect.key.equals(key)) {
                return Optional.of(aspect);
            }
        }
        return Optional.empty();
    }
}
