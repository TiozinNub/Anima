package dev.luizloyola.anima.core.agent;

import dev.luizloyola.anima.core.config.KnobSpec.Kind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The schema of a mind: one aspect per thing on which one species may differ from another. See
 * {@link SpeciesProfile} for the declaration, {@link AgentProfile} for the resolved read, and
 * {@code docs/superpowers/specs/2026-07-28-per-species-minds-design.md} for the whole design.
 *
 * <p><b>Open, not an enum</b>, so a consumer can tune the vocabulary it registers itself — its own
 * {@code NeedKind} or {@code PoiKind} numbers belong to a species. See
 * {@code docs/superpowers/specs/2026-08-06-needs-design.md} §1.
 *
 * <p><b>Registration order is schema order</b> — the generated knob family, the sections of a
 * consumer's config file and every readout follow it, so it must not depend on when somebody
 * touched a class. Anima registers its own in this class's initializer, a consumer at mod init,
 * and the registry {@linkplain #freeze() freezes} the moment the first species is declared, so
 * "too late" throws rather than leaving a declared species silently short an aspect.
 *
 * <p><b>Instances are canonical per key.</b> {@link #register} returns the one instance for a key
 * and refuses to redefine it, so {@code ==} is safe and two mods cannot disagree about what
 * {@code senses.radius} means.
 *
 * <p><b>No defaults.</b> An undeclared aspect is a programming error rather than a species quietly
 * inheriting a library's idea of how far a body can see. Bounds travel with the aspect: a declared
 * value still has to be legal, and the operator may hand-edit the species file afterwards.
 *
 * <p><b>What is not here.</b> This holds all but nine of Anima's tunables; the nine left in
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
public final class ProfileAspect {

    /**
     * Insertion-ordered, because registration order is schema order — see the class note. Keyed by
     * the dotted path. That is what a config file, a saved modifier and a command all speak.
     */
    private static final Map<String, ProfileAspect> REGISTERED = new LinkedHashMap<>();

    /** Closed by the first {@link SpeciesProfile} declaration; see {@link #freeze()}. */
    private static boolean frozen;


    // --- mind: how this one changes its mind --------------------------------------------------

    public static final ProfileAspect MIND_STICKINESS = register("mind.stickiness", Kind.DOUBLE, 0.0, 1.0,
            "Incumbency bonus added to whatever this body is already doing. High is dogged, low "
                    + "is flighty — the difference between a settler finishing a tree and a cat "
                    + "abandoning it.");
    public static final ProfileAspect MIND_PREEMPT = register("mind.preempt", Kind.DOUBLE, 0.0, 1.0,
            "Minimum raw pressure a new urge needs to cut into what this body is doing "
                    + "mid-flight.");

    // --- instincts: what this body wants, unprompted ------------------------------------------

    public static final ProfileAspect FLEE_RANGE = register("instincts.flee_range", Kind.DOUBLE, 1.0, 64.0,
            "Distance (blocks) at which a threat registers on this body at all. A rabbit's is "
                    + "not a golem's. A threat holding something ranged is feared from as far as "
                    + "this body can perceive it instead — reach is a property of the weapon, not "
                    + "a multiple of this.");
    public static final ProfileAspect FLEE_RAMP = register("instincts.flee_ramp", Kind.DOUBLE, 1.0, 64.0,
            "Distance (blocks) over which this body's fear climbs from nothing to full panic.");
    public static final ProfileAspect FLEE_APPROACH_BONUS = register("instincts.flee_approach_bonus", Kind.DOUBLE, 1.0, 4.0,
            "Pressure multiplier when a threat is measurably closing in — how strongly this body "
                    + "reads being followed as being hunted.");
    public static final ProfileAspect WANDER_IDLE_PRESSURE = register("instincts.wander_idle_pressure", Kind.DOUBLE, 0.0, 1.0,
            "This body's do-something floor. Every real drive must beat it; at 0 an unbothered "
                    + "body stands still, which is a perfectly good way for some things to be.");
    public static final ProfileAspect WANDER_RADIUS = register("instincts.wander_radius", Kind.INT, 1, 64,
            "How far (blocks) an idle saunter may roll its next beat — a tethered pet and a "
                    + "roaming herd differ here and nowhere else.");

    // --- senses: what this body makes of other bodies -----------------------------------------

    public static final ProfileAspect SENSES_RADIUS = register("senses.radius", Kind.INT, 4, 64,
            "How far (blocks) this body perceives another at all, by any channel. Also the far "
                    + "end of its attention curve, and how far a threat holding something ranged "
                    + "is feared from.");
    public static final ProfileAspect SENSES_SNEAK_RANGE_MULT = register("senses.sneak_range_mult", Kind.DOUBLE, 0.1, 1.0,
            "Multiplier on that radius against a sneaking body. Sneaking shrinks how far away "
                    + "you are noticed by this one; it never makes you invisible to it.");
    public static final ProfileAspect SENSES_CONE_DEGREES = register("senses.cone_degrees", Kind.INT, 30, 360,
            "Horizontal field of view (degrees). Prey animals have eyes on the sides of their "
                    + "heads and this is where that becomes true; 360 is omniscience.");
    public static final ProfileAspect SENSES_VERTICAL_DEGREES = register("senses.vertical_degrees", Kind.INT, 5, 90,
            "Vertical field HALF-angle (degrees) around gaze pitch. Not simply half the "
                    + "horizontal one — vision is wide across and flat up-down.");
    public static final ProfileAspect SENSES_HEARING_RADIUS = register("senses.hearing_radius", Kind.INT, 0, 32,
            "How far (blocks) this body hears sound-makers regardless of where it is looking. "
                    + "0 is deaf, which is a legitimate thing for a body to be.");
    public static final ProfileAspect SENSES_LINGER_TICKS = register("senses.linger_ticks", Kind.INT, 0, 2400,
            "Object permanence: how long something stays perceived, frozen as remembered, after "
                    + "every channel goes dark. How long this body believes in what it can no "
                    + "longer sense.");
    public static final ProfileAspect SENSES_HEARD_DECAY_TICKS = register("senses.heard_activity_decay_ticks", Kind.INT, 0, 1200,
            "How long a sound-told activity stays believed after the sound stops, before only "
                    + "the bare presence is left.");
    public static final ProfileAspect SENSES_NEAR_INTERVAL = register("senses.near_interval_ticks", Kind.INT, 1, 100,
            "Attention at point-blank: re-check interval (ticks) for a body standing right "
                    + "there. Cheap — the expensive sight rays answer to the server's budget.");
    public static final ProfileAspect SENSES_FAR_INTERVAL = register("senses.far_interval_ticks", Kind.INT, 1, 400,
            "Attention at the edge: re-check interval (ticks) at the limit of perception. "
                    + "Distances between lerp across the two, so the pair is this body's "
                    + "attention span.");
    public static final ProfileAspect SENSES_ATTACK_DECAY_TICKS = register("senses.attack_decay_ticks", Kind.INT, 0, 24_000,
            "How long being attacked keeps something read as hostile, with or without a face on "
                    + "it. Much longer than the other channels on purpose: forgetting an attack "
                    + "fifteen seconds later is not object permanence, it is amnesia. 0 means "
                    + "this body reacts to a blow and then lets it go.");
    public static final ProfileAspect SENSES_HERD_LINK_RADIUS = register("senses.herd_link_radius", Kind.INT, 2, 24,
            "How far apart (blocks per axis) two same-species animals may stand and still read "
                    + "to this body as one herd.");

    // --- places: what this body notices about the world as it goes ----------------------------

    public static final ProfileAspect PLACES_RADIUS = register("places.radius", Kind.INT, 1, 64,
            "Horizontal radius (blocks) this body notices places within, where it is looking. "
                    + "Cost scales with the square, but the work is bounded by the server's read "
                    + "budget rather than by this, so a keen nose is safe.");
    public static final ProfileAspect PLACES_CONE_DEGREES = register("places.cone_degrees", Kind.INT, 30, 360,
            "Horizontal field (degrees) within which this body notices places past the halo "
                    + "below. Narrowing it is what pays for range — a 150° cone samples 42% of "
                    + "its disc — and is why a body no longer notices what stands squarely "
                    + "behind it. 360 makes noticing omnidirectional at every distance.");
    public static final ProfileAspect PLACES_NEAR_RADIUS = register("places.near_radius", Kind.INT, 0, 32,
            "Radius (blocks) within which places are noticed whichever way this body faces — "
                    + "peripheral vision and the sole of a boot rather than eyesight. Clamped to "
                    + "the radius above. 0 means nothing is noticed off-bearing at all, which is "
                    + "a legitimate thing for a body with eyes on stalks to be.");
    public static final ProfileAspect PLACES_HORIZON_RADIUS = register("places.horizon_radius", Kind.INT, 0, 256,
            "How far (blocks) this body makes out a skyline — the gist of what is out there, "
                    + "past any range at which it could inspect a thing. Rays are marched to it, "
                    + "and it sets both how long they are and how many make up a fan, so a longer "
                    + "reach costs more than proportionally; what it does not do is raise the "
                    + "per-tick bill, which the server's read budget caps. At or below the radius "
                    + "above it is off, which is the right answer for anything that lives by its "
                    + "nose.");
    public static final ProfileAspect PLACES_SEE_THROUGH_RADIUS = register("places.see_through_radius", Kind.INT, 0, 64,
            "How far (blocks) this body's eye resolves a see-through thing into its parts. "
                    + "Inside it, a canopy is branches and gaps and the view carries past; beyond "
                    + "it, the same canopy is a wall and the skyline stops there. What makes a "
                    + "wood a blob at range instead of something to see through, and the reason a "
                    + "body standing in one has no far sense at all. 0 is a body that never sees "
                    + "into anything.");
    public static final ProfileAspect PLACES_REGION_MAX_SPREAD = register("places.region_max_spread", Kind.INT, 1, 128,
            "Chebyshev spread from a seed before this mind stops calling it one place — what "
                    + "decides whether a forest is one memory or twenty. A judgment about "
                    + "places, not a cost bound.");
    public static final ProfileAspect PLACES_MAX_PER_KIND = register("places.max_per_kind", Kind.INT, 8, 1024,
            "How many places of a kind this body remembers before the stalest goes. Must exceed "
                    + "what it works among, or the edges churn forget/rediscover forever.");

    // --- danger: what this body finds frightening about a body ---------------------------------

    public static final ProfileAspect DANGER_MELEE_MULT = register("danger.melee_mult", Kind.DOUBLE, 0.0, 4.0,
            "Danger multiplier for a visibly held melee weapon. Whether a sword means anything "
                    + "to you depends entirely on what you are.");
    public static final ProfileAspect DANGER_RANGED_MULT = register("danger.ranged_mult", Kind.DOUBLE, 0.0, 4.0,
            "Danger multiplier for a visibly held ranged weapon (bow, crossbow, trident).");
    public static final ProfileAspect DANGER_ARMORED_MULT = register("danger.armored_mult", Kind.DOUBLE, 0.0, 4.0,
            "Danger multiplier for visible armor on the body.");
    public static final ProfileAspect DANGER_MOUNTED_MULT = register("danger.mounted_mult", Kind.DOUBLE, 0.0, 4.0,
            "Danger multiplier for a mounted body — spider jockeys, skeleton horsemen.");
    public static final ProfileAspect DANGER_BABY_MULT = register("danger.baby_mult", Kind.DOUBLE, 0.0, 4.0,
            "Danger multiplier for a baby variant: smaller, faster, harder to hit.");

    // --- social -------------------------------------------------------------------------------

    public static final ProfileAspect SOCIAL_HAIL_RADIUS = register("social.hail_radius", Kind.INT, 8, 64,
            "How far (blocks) a deliberate shout reaches this body, and how far its own carries. "
                    + "Must exceed this species' senses.radius or hailing adds nothing to simply "
                    + "noticing someone.");
    public static final ProfileAspect SOCIAL_COMPANY_SOLITUDE_TICKS = register("social.company_solitude_ticks", Kind.INT, 20, 480_000,
            "How long it takes complete solitude to drain this body's company from full to empty. "
                    + "24000 ticks is one in-game day.");
    public static final ProfileAspect SOCIAL_COMPANY_PROXIMITY_TICKS = register("social.company_proximity_ticks", Kind.INT, 20, 480_000,
            "How long simply being near ONE person this body has met would take to fill its "
                    + "company from empty. Below social.company_solitude_ticks, company sinks even "
                    + "with a neighbour standing there.");
    public static final ProfileAspect SOCIAL_COMPANY_UTTERANCES = register("social.company_utterances", Kind.INT, 1, 10_000,
            "How many lines of conversation would fill this body's company from empty. Counted "
                    + "per line exchanged, NOT per tick spent talking — otherwise a slow replier "
                    + "would be better company than a brisk one. Time spent together still counts, "
                    + "through social.company_proximity_ticks, which is what it actually is.");

    // --- body: what this one can physically do -------------------------------------------------

    public static final ProfileAspect BODY_HEIGHT = register("body.height", Kind.INT, 1, 8,
            "Body height in whole cells — every column this body walks through needs that much "
                    + "clearance. A Person is 2 (a 1.8 hitbox); a wolf is 1, and squeezes under "
                    + "things a Person must walk around.");
    public static final ProfileAspect BODY_JUMP_HEIGHT = register("body.jump_height", Kind.INT, 0, 1,
            "How many cells this body can jump straight up. Only 0 and 1 are modelled; 0 means "
                    + "every step up is a wall, which is how a path around one gets found.");
    public static final ProfileAspect BODY_MAX_DROP = register("body.max_drop", Kind.INT, 0, 32,
            "How many cells this body will willingly fall. Anything deeper reads as a hole to "
                    + "route around, and the same number decides what counts as a ledge to be "
                    + "careful near — a cat's tolerance is not a settler's.");
    public static final ProfileAspect BODY_MAX_LEAP = register("body.max_leap", Kind.INT, 0, 3,
            "Widest gap (cells) this body can jump across at the same level. 1 is a walking "
                    + "jump; 2 and 3 need a sprint run-up, so the path also demands an aligned "
                    + "approach cell for those. 3 is the vanilla sprint-jump limit.");
    public static final ProfileAspect BODY_CAN_SWIM = register("body.can_swim", Kind.BOOL, 0, 1,
            "Whether this body may enter and cross water. False keeps water impassable, so a "
                    + "land-only body routes around it. Surface crossing only — diving would "
                    + "need a depth and a breath of its own.");

    private final String key;
    private final Kind kind;
    private final double min;
    private final double max;
    private final String doc;
    private final int index;

    private ProfileAspect(String key, Kind kind, double min, double max, String doc, int index) {
        this.key = key;
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.doc = doc;
        this.index = index;
    }

    /**
     * Declares one way a species may differ, or returns the existing aspect when this key is
     * already registered with the same shape.
     *
     * @param key dotted snake_case path — where this lands inside a species' section of a config
     *     file, and the name a saved modifier refers to it by, so changing it orphans both
     * @param kind what the value holds
     * @param min lowest legal value, inclusive
     * @param max highest legal value, inclusive
     * @param doc one sentence for the operator, phrased for whoever is describing a species
     * @throws IllegalStateException when the key is already registered with a different shape (two
     *     mods disagreeing about what it means), or when the registry has already
     *     {@linkplain #freeze() frozen} and this key is new
     */
    public static synchronized ProfileAspect register(String key, Kind kind, double min, double max,
            String doc) {
        if (key == null || !key.matches("[a-z0-9_]+(\\.[a-z0-9_]+)+")) {
            throw new IllegalArgumentException(
                    "an aspect key is a dotted snake_case config path: " + key);
        }
        ProfileAspect existing = REGISTERED.get(key);
        if (existing != null) {
            if (existing.kind != kind || existing.min != min || existing.max != max
                    || !existing.doc.equals(doc)) {
                throw new IllegalStateException("aspect \"" + key + "\" is already registered with "
                        + "a different shape — two mods disagree about what it means");
            }
            return existing;
        }
        if (frozen) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "aspect \"%s\" was registered after the first species was declared. Register "
                            + "aspects at mod init: a species declared before this one existed "
                            + "passed its completeness check without it, and would have no value "
                            + "for it.", key));
        }
        ProfileAspect aspect = new ProfileAspect(key, kind, min, max, doc, REGISTERED.size());
        REGISTERED.put(key, aspect);
        return aspect;
    }

    /**
     * Closes the schema, called by the first {@link SpeciesProfile.Builder#build()}: a later aspect
     * would leave every species already declared silently missing it, and every
     * {@link ModifiedProfile} folded with an array too short to hold it. Idempotent.
     */
    static synchronized void freeze() {
        frozen = true;
    }

    /** Every registered aspect, in registration order — which is schema order. */
    public static synchronized List<ProfileAspect> all() {
        return Collections.unmodifiableList(new ArrayList<>(REGISTERED.values()));
    }

    /** How many aspects a species must answer. */
    public static synchronized int count() {
        return REGISTERED.size();
    }

    public static synchronized Optional<ProfileAspect> byKey(String key) {
        return Optional.ofNullable(REGISTERED.get(key));
    }

    /**
     * This aspect's position in schema order, {@code 0..count()-1} — a dense index for the folded
     * arrays on hot paths. Stable for the life of the process; never written to disk, where
     * {@link #key()} is the name.
     */
    public int index() {
        return index;
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

    @Override
    public String toString() {
        return key;
    }
}
