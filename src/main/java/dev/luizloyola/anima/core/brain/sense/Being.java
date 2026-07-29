package dev.luizloyola.anima.core.brain.sense;

import java.util.Optional;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Collection;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A nearby living body, as one perception. Since the being-sense slice
 * ({@code 2026-07-27-being-sense-design.md}) the organ tracks every living entity, not only
 * people (one pipeline, typed enrichment. A person) another Person or a live PLAYER,
 * indistinguishable — is the only kind with the full classifier reading; a
 * creature's is thin: presence, place, distance, plus its kind's reader.
 *
 * <p><b>Identification is a LADDER, universal across kinds:</b> a step or generic sound gives
 * position only; a VOICE (idle call, hurt sound, a projectile launch) names the SPECIES and
 * nothing visual; sight tells everything knowable by seeing. The sensor MASKS the reading down to
 * {@link #identified} — at {@link Identified#NONE} even {@link #kind} reads {@link Kind#UNKNOWN}.
 * The id stays stable underneath for track continuity, so identity-dependent behaviour must gate
 * on the tier, never on the id existing. {@link BeingEvent.Type#RECOGNIZED} fires on every
 * upgrade, by eye or by ear.
 *
 * <p><b>The reading decomposes along independent axes:</b> {@link #activity} the ARMS/ATTENTION
 * occupation (persons only), {@link #locomotion} the LEGS, {@link #sneaking} posture,
 * {@link #watching} gaze — all OBSERVABLE body signals, never a peek into another brain. Hence
 * {@link #aggressive} reads the game's SYNCED anger on a neutral mob, and {@link #approaching} is
 * a measured distance trend rather than the old {@code getTarget()} peek.
 *
 * <p><b>Herds are one perception:</b> 3+ same-species herd animals collapse into one being with
 * {@link #count} > 1, 1–2 stay individuals; {@link #awareness} then carries the best member
 * channel and {@link #pos} the centroid.
 */
public record Being(BeingId id, Kind kind, String species, String name,
                    @Nullable String profession, Pos pos, double distance, int count,
                    int spread, boolean herdAnimal, java.util.List<BeingId> members,
                    Activity activity, Locomotion locomotion,
                    boolean sneaking, boolean watching, boolean aimedAt, boolean approaching,
                    boolean aggressive, Gear gear, Identified identified, Awareness awareness) {

    public Being {
        members = java.util.List.copyOf(members);
    }

    /** How far up the ladder the observer has made this one out. */
    public enum Identified {
        /** Position only — a step behind the wall. Everything else reads masked. */
        NONE,
        /** A voice named the species — "a zombie", never the individual, never gear. */
        SPECIES,
        /** Seen: everything knowable by seeing. Tiers never go back down. */
        INDIVIDUAL
    }

    /**
     * What an observer takes a perceived body to BE — the coarse bucket deciding whether it is
     * someone to talk to, something to fear, or scenery.
     *
     * <p><b>Open, not an enum</b>, for the same reason {@code PoiKind} is: a consumer may perceive
     * distinctions the library has no word for — a tamed companion, a mount, a construct that is
     * neither creature nor person — so it registers a kind and a classifier ({@code BeingKinds}),
     * and nothing here branches on the identity of a constant.
     *
     * <p>Behaviour comes from the two flags, not {@code ==}: {@link #minded()} makes a peer,
     * {@link #hostile()} a threat before it has done anything.
     */
    public static final class Kind {

        private static final Map<String, Kind> REGISTERED = new LinkedHashMap<>();

        /** Identification below {@code SPECIES} — the observer cannot say yet. */
        public static final Kind UNKNOWN = register("unknown", false, false);
        /**
         * Below {@code SPECIES} too, but demonstrably dangerous: something attacked and there is
         * no face to put to it. Earned by an arrow arriving, not by recognising anybody, so it
         * shares {@link #UNKNOWN}'s rung and upgrades the moment a face does.
         */
        public static final Kind HOSTILE = register("hostile", false, true);
        /** A minded body (any {@code AgentBody}) or a live player — seamlessly. */
        public static final Kind AGENT = register("agent", true, false);
        /** The game's {@code Enemy} — aggressive without provocation. */
        public static final Kind MONSTER = register("monster", false, true);
        /** The game's {@code NeutralMob} — aggressive only while visibly angry, which the sensor
         *  reads off the body rather than off the kind. */
        public static final Kind NEUTRAL = register("neutral", false, false);
        /** Everything else living — herd animals among them collapse into herds. */
        public static final Kind PASSIVE = register("passive", false, false);
        /** An {@code AbstractVillager} — villager or wandering trader; species tells which. */
        public static final Kind VILLAGER = register("villager", false, false);

        private final String key;
        private final boolean minded;
        private final boolean hostile;

        private Kind(String key, boolean minded, boolean hostile) {
            this.key = key;
            this.minded = minded;
            this.hostile = hostile;
        }

        /**
         * Declares a kind of thing an observer can recognise, or returns the existing one when
         * this key is already registered with the same flags.
         *
         * @param key     stable id, also what a saved or transmitted reading carries
         * @param minded  whether this counts as a peer — named, spoken to, listed by {@code peers()}
         * @param hostile whether it is a threat before it has done anything
         */
        public static synchronized Kind register(String key, boolean minded, boolean hostile) {
            Kind existing = REGISTERED.get(key);
            if (existing != null) {
                if (existing.minded != minded || existing.hostile != hostile) {
                    throw new IllegalStateException("being kind \"" + key + "\" is already "
                            + "registered with different behaviour — two mods disagree about it");
                }
                return existing;
            }
            Kind kind = new Kind(key, minded, hostile);
            REGISTERED.put(key, kind);
            return kind;
        }

        /** The kind with this key, or empty. */
        public static synchronized Optional<Kind> byKey(String key) {
            return Optional.ofNullable(REGISTERED.get(key));
        }

        /** Every registered kind, in registration order. */
        public static synchronized Collection<Kind> all() {
            return Collections.unmodifiableCollection(new LinkedHashMap<>(REGISTERED).values());
        }

        /** Stable id. */
        public String key() {
            return key;
        }

        /** Whether this is a peer: someone to name, address and count among {@code peers()}. */
        public boolean minded() {
            return minded;
        }

        /** Whether this is a threat before it has done anything. */
        public boolean hostile() {
            return hostile;
        }

        @Override
        public String toString() {
            return key;
        }
    }

    /** The sight-tier equipment reads — the danger modifiers (decision: Luiz). */
    public record Gear(boolean melee, boolean ranged, boolean armored, boolean mounted,
                       boolean baby) {
        public static final Gear NONE = new Gear(false, false, false, false, false);
    }

    /** Whether this is a herd aggregate rather than one body. {@link #spread} is then the
     *  members' max Chebyshev distance from the centroid, {@link #members} the ids it speaks for
     *  — a SEEN herd is seen cow by cow, which lets knowledge retire exactly its members' loner
     *  memories, never an unrelated stray. */
    public boolean herd() {
        return count > 1;
    }

    /**
     * The name they'd use, by tier: below SPECIES everything is {@code "someone"}; at SPECIES a
     * creature is {@code "a zombie"} while a person is still {@code "someone"}, since voices name
     * species and a person's species names nobody; at INDIVIDUAL a person is their name, a
     * creature its custom name if it wears one.
     *
     * <p>A person seen clearly but never introduced is {@code "a stranger"} — the tier says the
     * observer can tell them apart, the empty name that nobody ever said who they are. Seeing a
     * face never revealed a name: the sensor fills {@link #name} from the observer's own contact
     * book.
     */
    public String knownAs() {
        if (identified == Identified.NONE) {
            return "someone";
        }
        if (kind.minded()) {
            if (identified != Identified.INDIVIDUAL) {
                return "someone";
            }
            return name.isEmpty() ? "a stranger" : name;
        }
        String species = this.species.replace('_', ' ');
        if (herd()) {
            return "a herd of " + species + (species.endsWith("s") ? "" : "s");
        }
        if (identified == Identified.INDIVIDUAL && !name.isEmpty()) {
            return name;
        }
        if (identified == Identified.INDIVIDUAL && profession != null) {
            // Sight tells the profession, so sight SAYS it (decision: Luiz — "see: profession"):
            // a villager you have actually looked at is "a farmer", not "a villager".
            String job = profession.replace('_', ' ');
            return ("aeiou".indexOf(job.charAt(0)) >= 0 ? "an " : "a ") + job;
        }
        return ("aeiou".indexOf(species.charAt(0)) >= 0 ? "an " : "a ") + species;
    }

    /**
     * The one human-readable reading. A person composes their axes as before ({@code "eating,
     * walking, sneaking"}, {@code "watching him/her"} with the OBSERVER's pronoun — callers pass
     * {@code gender.object()}). A creature's thin reading renders {@code "nearby"} — or
     * {@code "closing in on him/her"} when an aggressive one's distance trend says so; a herd
     * tells its head count. Renderers append their own awareness tag.
     */
    public String tell(String observerPronoun) {
        if (!kind.minded()) {
            if (herd()) {
                return count + " head, centered there";
            }
            if (approaching && aggressive) {
                return "closing in on " + observerPronoun;
            }
            return aggressive ? "aggressive, nearby" : "nearby";
        }
        StringBuilder tell = new StringBuilder();
        if (activity == Activity.IDLE) {
            tell.append(locomotion == Locomotion.STILL
                    ? "idle" : locomotion.name().toLowerCase(Locale.ROOT));
        } else {
            tell.append(activity == Activity.AIMING && aimedAt
                    ? "aiming at " + observerPronoun
                    : activity.name().toLowerCase(Locale.ROOT));
            if (locomotion != Locomotion.STILL) {
                tell.append(", ").append(locomotion.name().toLowerCase(Locale.ROOT));
            }
        }
        if (sneaking) {
            tell.append(", sneaking");
        }
        if (watching) {
            tell.append(", watching ").append(observerPronoun);
        }
        return tell.toString();
    }

    /** Which channel produced this perception — the freshness story, live-first. */
    public enum Awareness {
        /** In the vision cone with a clear ray to some body part — the full live reading. */
        SEEN,
        /** Made noise inside the hearing bubble — position live (sound places its source), sight unconfirmed. */
        HEARD,
        /** Every channel dark, linger window still open — the last live reading, frozen. */
        REMEMBERED
    }

    /** The LEGS axis — from measured speed over a real window, sound's steps included. */
    public enum Locomotion {
        STILL,
        WALKING,
        SPRINTING
    }

    /** The ARMS/ATTENTION occupation — persons only; every other kind reads {@link #IDLE}. */
    public enum Activity {
        /** Unoccupied arms — the approachable state the social layer looks for. */
        IDLE,
        /** Swinging at the world — breaking blocks, or any sustained unclassified arm work. */
        MINING,
        /** Swinging and recently dealt damage — the same arm, a different story. */
        FIGHTING,
        /** The eat use-animation. */
        EATING,
        /** The drink use-animation. */
        DRINKING,
        /** Shield raised. */
        BLOCKING,
        /** Bow or crossbow drawn — {@link #aimedAt} says whether it points at the OBSERVER
         *  (a tight cone off the draw, instant: a bow crossing you alarms immediately). */
        AIMING,
        /** Placing blocks — the same swing as mining, told apart by fresh place-marks (seen)
         *  or the block-place sound (heard). */
        BUILDING,
        /** At a visibly open chest — seen near it, CONFIRMED by the container (the lid tells). */
        AT_CHEST,
        /** Facing a crafting table within reach — assumed, and fallible. */
        AT_CRAFTING,
        /** In a bed. */
        SLEEPING
    }
}
