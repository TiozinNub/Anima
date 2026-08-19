package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.social.Places;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * One person's remembered POIs — the pure, unit-testable heart of the knowledge store: per kind, an
 * anchor-keyed map of {@link #note}, {@link #refresh}, {@link #forget} and
 * {@link #disprove(PoiKind, Pos)}. Headless; callers stamp game time into the {@link PoiMemory}
 * they pass.
 *
 * <p>Each kind holds at most {@link #maxPerKind(AgentProfile)} entries and evicts the stalest
 * (oldest {@code lastSeenTick}). A new memory within its kind's {@link PoiKind#mergeRadius()}
 * (Chebyshev) replaces the old one — the fresher scan knows the current shape, and keeping both
 * would count the wood twice. Insertion-ordered, for deterministic iteration.
 *
 * <p>Beside that map sits {@link #sawInside}: what this body last saw INSIDE a container, keyed by
 * the same anchor. A sighting, never a claim — see {@link Seen}.
 */
public final class AgentKnowledge {
    /**
     * Memory capacity per {@link PoiKind} — {@code perception.knowledge_max_per_kind}, read
     * through the config on every use so {@code reload} retunes live Persons. Must sit ABOVE the
     * trees a person works among: at 64, an 81-tree grid churned its far corners between forget
     * and rediscover (2026-07-27: 56 notice events for one tree).
     */
    public static int maxPerKind(AgentProfile profile) {
        return profile.i(ProfileAspect.PLACES_MAX_PER_KIND);
    }

    private final Map<PoiKind, Map<Pos, PoiMemory>> byKind = new java.util.LinkedHashMap<>();
    /**
     * TRANSIENT avoid-marks: anchors that are true but not worth retrying right now (an
     * unworkable tree). Never serialized (a fresh boot retries clean), and consulted only by
     * method selection; the memory itself stays.
     */
    private final Map<PoiKind, Map<Pos, Long>> avoidedUntil = new java.util.LinkedHashMap<>();

    /**
     * What this body may claim, as against what it has seen — installed by the registry, empty for
     * a knowledge built on its own. Named {@code places}, not {@code claims}: {@code BrainContext}
     * already has a {@code claims()} that means work claims, one dot away in the same packages.
     *
     * <p>Claims compose into {@link #nearest} and {@link #all} so every existing caller picks up a
     * party's workshop with no change, and into {@link #disprove(PoiKind, Pos)} because that is
     * the probe correction. They do NOT compose into {@link #note}, {@link #refresh}, or the plain
     * {@link #forget} that {@code DangerNoter} and {@code HerdNoter} re-key with — a memory that
     * moved is not a claim that was disproven, and composing there would destroy a claim with
     * nothing to re-found it.
     */
    private Places.View places = Places.View.EMPTY;

    /**
     * What "now" is, for stamping a claim's read as a {@link PoiMemory} — see {@link #nearest}.
     * Installed alongside the view so a claim never inherits {@link PlaceRow#since()} and reads as
     * ancient the moment it is founded. Defaults to a clock stuck at tick zero — what an unwired
     * knowledge reports, never what a live one should: the registry always installs a real clock
     * alongside a real view, through {@link #sees(Places.View, LongSupplier)}.
     */
    private LongSupplier clock = () -> 0L;

    /**
     * Installs this body's window onto what it owns or shares, and the clock a composed claim is
     * stamped with. No one-argument overload: a view with no clock silently recreates the bug the
     * clock exists to fix, stamping every claim maximally ancient.
     */
    public void sees(Places.View places, LongSupplier clock) {
        this.places = places;
        this.clock = clock;
    }

    /** This body's claims, for the readout that wants them apart from the sightings. */
    public Places.View places() {
        return places;
    }

    /**
     * Records a belief: merges into an existing same-kind entry within merge radius (the new
     * memory wins — see class doc), otherwise inserts, evicting the stalest entry of that kind
     * if at capacity. Returns the stored memory for chaining.
     */
    public PoiMemory note(PoiMemory memory, int maxPerKind) {
        Objects.requireNonNull(memory, "memory");
        Map<Pos, PoiMemory> entries = entriesFor(memory.kind());
        Pos merged = findWithin(entries, memory);
        if (merged != null) {
            entries.remove(merged);
        } else if (entries.size() >= maxPerKind) {
            // A place memory this body no longer holds can never be corrected — there is no way
            // to stand somewhere you have forgotten exists and find it wrong — so a contents
            // belief still keyed to the evicted anchor is dead weight that would otherwise sit in
            // the save file for the life of the world.
            insides.remove(evictStalest(entries));
        }
        entries.put(memory.anchor(), memory);
        return memory;
    }

    /**
     * Puts back a memory that was already this agent's — what loading a saved world does.
     *
     * <p>Uncapped: evicting on the way in would make a restart quietly forget things.
     * A lowered cap comes into force through {@link #note}, one memory at a time.
     */
    public PoiMemory restore(PoiMemory memory) {
        Objects.requireNonNull(memory, "memory");
        entriesFor(memory.kind()).put(memory.anchor(), memory);
        return memory;
    }

    /**
     * Re-confirms the entry anchored exactly at {@code anchor} as seen {@code now}. Returns
     * false when no such entry exists (the sensor may race its own eviction; a miss is not an
     * error).
     */
    public boolean refresh(PoiKind kind, Pos anchor, long now) {
        Map<Pos, PoiMemory> entries = byKind.get(kind);
        if (entries == null) {
            return false;
        }
        PoiMemory memory = entries.get(anchor);
        if (memory == null) {
            return false;
        }
        entries.put(anchor, memory.seenAt(now));
        return true;
    }

    /**
     * Drops the entry anchored exactly at {@code anchor}; false when there was none. Sightings
     * only — never reaches a claim. This is the re-key {@code DangerNoter} and {@code HerdNoter}
     * use ("moved, not duplicated"): a herd that walked off is not a claim disproven, and
     * composing here would let a re-key silently erase somebody's workshop. The probe correction
     * that reaches a claim too is {@link #disprove(PoiKind, Pos)}.
     */
    public boolean forget(PoiKind kind, Pos anchor) {
        Map<Pos, PoiMemory> entries = byKind.get(kind);
        boolean dropped = entries != null && entries.remove(anchor) != null;
        insides.remove(anchor); // a container that is gone has no contents
        return dropped;
    }

    /**
     * The probe correction: drops the sighting anchored exactly at {@code anchor}, AND a claim
     * there this body may see — made by whoever (or whatever) found nothing standing where a
     * memory said something did. {@code Workbench.standingAtOne} and
     * {@code PoiSensorCore.invalidate} are today's two callers, so this is not the only thing that
     * ever tells the party a claim is gone — either can.
     *
     * <p>Same verb, finer grain, than {@link #disprove(int, int)}: that one corrects a whole
     * column of unconfirmed sightings at once; this one corrects one belief, and a claim, by its
     * own anchor.
     */
    public boolean disprove(PoiKind kind, Pos anchor) {
        Map<Pos, PoiMemory> entries = byKind.get(kind);
        boolean sighting = entries != null && entries.remove(anchor) != null;
        boolean claim = places.drop(kind, anchor);
        insides.remove(anchor); // a container that is gone has no contents
        return sighting || claim;
    }

    /**
     * The remembered entry of this kind nearest to {@code from} (squared euclidean over anchors),
     * considering both what this body has seen and what it may claim. Distance only — the method
     * prices staleness, weighing {@code age()} against distance per the brain design's cost model.
     */
    public Optional<PoiMemory> nearest(PoiKind kind, Pos from) {
        PoiMemory best = null;
        long bestDist = Long.MAX_VALUE;
        Map<Pos, PoiMemory> entries = byKind.get(kind);
        if (entries != null) {
            for (PoiMemory memory : entries.values()) {
                long dist = distanceSq(memory.anchor(), from);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = memory;
                }
            }
        }
        for (PlaceRow row : places.all(kind)) {
            long dist = distanceSq(row.at(), from);
            // <= so a claim wins any tie, not just one against a sighting of the same block: the
            // loop only ever compares distance, and an equidistant sighting in another direction
            // loses too. Harmless — a claim never goes stale, so preferring it is never wrong —
            // but it means this can name a different equidistant row than Places.View.nearest,
            // which breaks ties with a strict <. Both are deterministic, just not the same choice.
            if (dist <= bestDist) {
                bestDist = dist;
                best = row.toMemory(clock.getAsLong());
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * All entries of a kind, insertion-ordered, unmodifiable — the debug command's view. Sightings
     * then claims, deduped by anchor: a block both seen and claimed is one entry, and the claim
     * wins (see {@link #places}). For the sightings alone, uncomposed — what this store persists —
     * see {@link #sighted}.
     *
     * <p>Always a snapshot, never a live view: a caller iterating this while a sensor notes or
     * forgets must not see a {@code ConcurrentModificationException} on the no-claims path and not
     * the claims one — the store is small enough that copying costs nothing worth avoiding it for.
     */
    public Collection<PoiMemory> all(PoiKind kind) {
        Collection<PlaceRow> claimed = places.all(kind);
        Map<Pos, PoiMemory> entries = byKind.get(kind);
        if (claimed.isEmpty()) {
            return entries == null ? List.of() : List.copyOf(entries.values());
        }
        Map<Pos, PoiMemory> merged = new LinkedHashMap<>();
        if (entries != null) {
            merged.putAll(entries);
        }
        for (PlaceRow row : claimed) {
            // a claim supersedes a sighting of it, stamped now — see the clock field's doc
            merged.put(row.at(), row.toMemory(clock.getAsLong()));
        }
        return List.copyOf(merged.values());
    }

    /**
     * All SIGHTINGS of a kind, insertion-ordered, unmodifiable — no claim, ever. This is what
     * {@link #all} answered before a claim store existed, and it is what {@code KnowledgeData}
     * persists: a claim is {@code Places}'s row, and writing it here too would double-persist it
     * and leak it as a private sighting that outlives the party membership that made it visible.
     */
    public Collection<PoiMemory> sighted(PoiKind kind) {
        Map<Pos, PoiMemory> entries = byKind.get(kind);
        return entries == null
                ? Collections.emptyList()
                : Collections.unmodifiableCollection(entries.values());
    }

    /** Marks an anchor as not-worth-retrying until the given game time. Transient. */
    public void avoid(PoiKind kind, Pos anchor, long untilTick) {
        avoidedUntil.computeIfAbsent(kind, k -> new HashMap<>()).put(anchor, untilTick);
    }

    /** Whether the anchor is currently avoided — consult with the same clock memories carry. */
    public boolean isAvoided(PoiKind kind, Pos anchor, long now) {
        Map<Pos, Long> marks = avoidedUntil.get(kind);
        Long until = marks == null ? null : marks.get(anchor);
        return until != null && until > now;
    }

    // --- insides: what this body last saw when it opened a container -------------------------

    /**
     * What this body last saw inside a container, and when.
     *
     * <p>A <em>sighting</em>, not a claim: the container may be the party's, but what is in it is
     * yours from the last time you looked. Nobody learns you took the last log until they look.
     */
    public record Seen(java.util.List<ItemStack> stacks, long seenTick) {
        public Seen {
            stacks = java.util.List.copyOf(stacks);
        }

        /** How many matching items were in there when this was written. */
        public int count(ItemSpec spec) {
            int total = 0;
            for (ItemStack stack : stacks) {
                if (spec.matches(stack.id())) {
                    total += stack.count();
                }
            }
            return total;
        }

        public long age(long now) {
            return now - seenTick;
        }
    }

    /** Anchor-keyed, and replaced outright on every look — the newer belief is simply the truth. */
    private final Map<Pos, Seen> insides = new LinkedHashMap<>();

    /**
     * Records what was in a container. Written on interaction, never by walking past.
     *
     * <p>Capped at {@code maxPerKind} and evicts the stalest belief when full, the same shape as
     * {@link #note} — belt and braces beside {@link #note}'s own eviction tie: nothing
     * structurally forces every anchor written here to also be a POI this body still remembers.
     */
    public void sawInside(Pos at, java.util.List<ItemStack> stacks, long now, int maxPerKind) {
        if (!insides.containsKey(at) && insides.size() >= maxPerKind) {
            evictStalestInside();
        }
        insides.put(at, new Seen(stacks, now));
    }

    /**
     * Puts back a contents belief that was already this agent's — uncapped, for the same reason
     * {@link #restore} is.
     */
    public void restoreInside(Pos at, java.util.List<ItemStack> stacks, long seenTick) {
        insides.put(at, new Seen(stacks, seenTick));
    }

    public Optional<Seen> insideOf(Pos at) {
        return Optional.ofNullable(insides.get(at));
    }

    /** Every remembered inside, insertion-ordered — the codec's view and the readout's. */
    public Map<Pos, Seen> insides() {
        return Collections.unmodifiableMap(insides);
    }

    private void evictStalestInside() {
        Pos stalest = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<Pos, Seen> entry : insides.entrySet()) {
            if (entry.getValue().seenTick() < oldest) {
                oldest = entry.getValue().seenTick();
                stalest = entry.getKey();
            }
        }
        insides.remove(stalest);
    }

    // --- the gist tier: what was made out but never examined ---------------------------------

    /**
     * How near two glimpses must be to be one glimpse — Chebyshev, <b>horizontal only</b>, since a
     * sighting names a place, not a height. Far coarser than any belief's
     * {@link PoiKind#mergeRadius()}: a wood arrives as one impression, not two hundred trunks, and
     * the tier does not drown the store beside it. Matches the far sense's emission grid, which
     * likewise spends no second confirm-ray inside one cell.
     */
    public static final int GLIMPSE_MERGE_RADIUS = 8;

    /**
     * Keyed by COLUMN, not by cell: a sighting is a claim about a place rather than a block, and
     * the column makes {@link #disprove(int, int)} a hash lookup instead of a scan. It fires tens
     * of times a tick per agent, so a linear pass would cost more than the sense that produced it.
     */
    private final Map<PoiKind, Map<Column, Sighting>> glimpsedByKind = new LinkedHashMap<>();

    /**
     * Records a sighting, unless this agent already <em>knows</em> something of that kind there —
     * a belief outranks a rumour about the same place. Otherwise merges coarsely (the newer
     * sighting wins) and evicts the stalest of its kind at capacity. Returns what was stored, or
     * null when the sighting was declined.
     */
    public Sighting glimpse(Sighting sighting, int maxPerKind) {
        Objects.requireNonNull(sighting, "sighting");
        if (knows(sighting.kind(), sighting.at())) {
            return null;
        }
        Map<Column, Sighting> entries = glimpsesFor(sighting.kind());
        Column merged = findGlimpseWithin(entries, sighting.at());
        if (merged != null) {
            entries.remove(merged);
        } else if (entries.size() >= maxPerKind) {
            evictStalestGlimpse(entries);
        }
        entries.put(columnOf(sighting.at()), sighting);
        return sighting;
    }

    /** Puts back a sighting that was already this agent's — uncapped, for the same reason
     *  {@link #restore} is. */
    public Sighting restoreGlimpse(Sighting sighting) {
        Objects.requireNonNull(sighting, "sighting");
        glimpsesFor(sighting.kind()).put(columnOf(sighting.at()), sighting);
        return sighting;
    }

    /**
     * The real thing turned out to be here: drops every sighting of that kind near the anchor.
     * Keeping both would leave the body walking to a place it already stands in.
     *
     * @return how many were superseded
     */
    public int supersede(PoiKind kind, Pos anchor) {
        Map<Column, Sighting> entries = glimpsedByKind.get(kind);
        if (entries == null) {
            return 0;
        }
        int before = entries.size();
        entries.keySet().removeIf(
                at -> horizontalChebyshev(at.x(), at.z(), anchor) <= GLIMPSE_MERGE_RADIUS);
        return before - entries.size();
    }

    /**
     * The near field's verdict, and only about what it can see: this column's surface was read
     * properly and holds nothing of the sort, so a sighting standing exactly on it is dropped —
     * for every kind that {@linkplain PoiKind.Settling#SURFACE stands at a surface} and no other,
     * and exactly on it, since one empty column is no evidence about its neighbours.
     *
     * <p>That exclusion is why {@link PoiKind.Settling} exists. A column probe reads one cell, the
     * topmost motion-blocking one; for sugar cane (no collision, so not in the heightmap) it
     * reads the sand underneath and reports "nothing here", deleting a true belief that the far
     * sense would re-make and it would kill again, sweep after sweep.
     *
     * <p>Same verb, coarser grain, than {@link #disprove(PoiKind, Pos)}: this one corrects a whole
     * column of unconfirmed sightings at once; that one corrects one belief, and a claim, by its
     * own anchor.
     *
     * @return how many were disproved
     */
    public int disprove(int x, int z) {
        if (glimpsedByKind.isEmpty()) {
            return 0; // the overwhelmingly common case: nothing has been made out at all
        }
        Column column = new Column(x, z);
        int dropped = 0;
        for (Map.Entry<PoiKind, Map<Column, Sighting>> entry : glimpsedByKind.entrySet()) {
            if (entry.getKey().settling() != PoiKind.Settling.SURFACE) {
                continue; // not this sense's to settle: it never looked where the thing lives
            }
            if (entry.getValue().remove(column) != null) {
                dropped++;
            }
        }
        return dropped;
    }

    /** All sightings of a kind, insertion-ordered, unmodifiable. */
    public Collection<Sighting> glimpses(PoiKind kind) {
        Map<Column, Sighting> entries = glimpsedByKind.get(kind);
        return entries == null
                ? Collections.emptyList()
                : Collections.unmodifiableCollection(entries.values());
    }

    /** The sighting of this kind nearest {@code from}, by the same distance-only rule as
     *  {@link #nearest}. */
    public Optional<Sighting> nearestGlimpse(PoiKind kind, Pos from) {
        Map<Column, Sighting> entries = glimpsedByKind.get(kind);
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        Sighting best = null;
        long bestDist = Long.MAX_VALUE;
        for (Sighting sighting : entries.values()) {
            long dist = distanceSq(sighting.at(), from);
            if (dist < bestDist) {
                bestDist = dist;
                best = sighting;
            }
        }
        return Optional.of(best);
    }

    /** Total sightings across kinds. */
    public int glimpseCount() {
        int size = 0;
        for (Map<Column, Sighting> entries : glimpsedByKind.values()) {
            size += entries.size();
        }
        return size;
    }

    /** Whether a BELIEF of this kind already stands within glimpse range of a place. */
    private boolean knows(PoiKind kind, Pos at) {
        Map<Pos, PoiMemory> entries = byKind.get(kind);
        if (entries == null) {
            return false;
        }
        for (Pos anchor : entries.keySet()) {
            if (horizontalChebyshev(anchor.x(), anchor.z(), at) <= GLIMPSE_MERGE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private Map<Column, Sighting> glimpsesFor(PoiKind kind) {
        return glimpsedByKind.computeIfAbsent(kind, k -> new LinkedHashMap<>());
    }

    private static Column columnOf(Pos at) {
        return new Column(at.x(), at.z());
    }

    private static Column findGlimpseWithin(Map<Column, Sighting> entries, Pos at) {
        for (Column existing : entries.keySet()) {
            if (horizontalChebyshev(existing.x(), existing.z(), at) <= GLIMPSE_MERGE_RADIUS) {
                return existing;
            }
        }
        return null;
    }

    private static void evictStalestGlimpse(Map<Column, Sighting> entries) {
        Column stalest = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<Column, Sighting> entry : entries.entrySet()) {
            if (entry.getValue().whenTick() < oldest) {
                oldest = entry.getValue().whenTick();
                stalest = entry.getKey();
            }
        }
        entries.remove(stalest);
    }

    /** Chebyshev in the horizontal plane only — a glimpse names a place, not a height. */
    private static int horizontalChebyshev(int x, int z, Pos b) {
        return Math.max(Math.abs(x - b.x()), Math.abs(z - b.z()));
    }

    /** Total remembered entries across kinds. */
    public int size() {
        int size = 0;
        for (Map<Pos, PoiMemory> entries : byKind.values()) {
            size += entries.size();
        }
        return size;
    }

    private Map<Pos, PoiMemory> entriesFor(PoiKind kind) {
        return byKind.computeIfAbsent(kind, k -> new LinkedHashMap<>());
    }

    /**
     * The anchor of an existing SAME-IDENTITY entry within the kind's merge radius, or null.
     * Merging never crosses details (a cow flock never merges into the sheep flock beside it) nor
     * individuals (two pigs in one cell are still two pigs); detail-free kinds compare {@code ""}
     * to {@code ""} and null to null.
     */
    private static Pos findWithin(Map<Pos, PoiMemory> entries, PoiMemory memory) {
        int radius = memory.kind().mergeRadius();
        for (Map.Entry<Pos, PoiMemory> entry : entries.entrySet()) {
            if (!entry.getValue().detail().equals(memory.detail())
                    || !Objects.equals(entry.getValue().individual(), memory.individual())) {
                continue;
            }
            Pos existing = entry.getKey();
            int dx = Math.abs(existing.x() - memory.anchor().x());
            int dy = Math.abs(existing.y() - memory.anchor().y());
            int dz = Math.abs(existing.z() - memory.anchor().z());
            if (Math.max(dx, Math.max(dy, dz)) <= radius) {
                return existing;
            }
        }
        return null;
    }

    /** @return the evicted anchor, so {@link #note} can drop its {@link #insides} row too */
    private static Pos evictStalest(Map<Pos, PoiMemory> entries) {
        Pos stalest = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<Pos, PoiMemory> entry : entries.entrySet()) {
            if (entry.getValue().lastSeenTick() < oldest) {
                oldest = entry.getValue().lastSeenTick();
                stalest = entry.getKey();
            }
        }
        entries.remove(stalest);
        return stalest;
    }

    private static long distanceSq(Pos a, Pos b) {
        long dx = a.x() - b.x();
        long dy = a.y() - b.y();
        long dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
