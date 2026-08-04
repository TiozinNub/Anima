package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * The whole "notice as you go" pipeline, assembled — one per person, pure core; the mod-layer
 * {@code PoiSensor} owns one and hands it the person's feet, the game time and a live
 * {@link BlockProbe} each tick. Crescent → pending queue → probe → (confirm-ray → growth) →
 * store + claims, inside one per-tick read wallet, so the cost ceiling is constant.
 *
 * <p>Per probed column the claims answer first (the O(1) fast path):
 * <ul>
 *   <li>a claim <em>above</em> the surface — that block is gone, which the heightmap cannot say
 *       any other way: the region's belief is invalidated;</li>
 *   <li>the surface cell claimed and matching — refreshed; mismatching — invalidated (a negative
 *       claim just cleared);</li>
 *   <li>claims only <em>below</em> — something new stands on an investigated footprint, so a fresh
 *       hypothesis. A live region's interior sits under a claimed surface, so this never fires for
 *       interiors; skipping it made any taller rebuild invisible forever;</li>
 *   <li>no claims — hypothesis: the seen block grows whatever rule a consumer registered
 *       ({@link GrowthRules}), gated by the confirm-ray. One growth runs at a time.</li>
 * </ul>
 *
 * <p>That last arm asks the level's {@link RegionCache} first: a mass somebody else already walked
 * is handed over whole and the scan — the most expensive thing here by an order of magnitude —
 * never runs. The shape is shared; the belief is not.
 */
public final class PoiSensorCore {
    /**
     * The per-tick read wallet — the sensor's whole cost ceiling. Walking feeds ~5 columns/tick
     * (2 reads each), so probing normally keeps up in real time and the rest of the wallet
     * drains growths. Tuning knob.
     */
    public static int readsPerTick() {
        return Config.get().i(Knob.READS_PER_TICK);
    }

    /**
     * Pending-column capacity. Sized to hold a FULL view (~870 columns for a Person), not just a
     * crescent: a spawn/teleport glance that drops columns biases the initial sweep toward
     * whichever side enumerates last. Overflow drops the oldest. Tuning knob.
     *
     * <p>Raise it with {@code places.radius} or that bias returns: the view's area is
     * {@code π·r₀² + (cone/360)·π·(R² − r₀²)} — 871 for a Person's 24-block reach, 8-block halo
     * and 150° aperture.
     */
    public static int queueCap() {
        return Config.get().i(Knob.QUEUE_CAP);
    }
    /** Flat wallet charge for one confirm-ray (a ~R-block voxel walk). Tuning knob. */
    public static final int RAY_COST = 8;
    /**
     * A ray-blocked hypothesis retries after this many ticks — by then they or the occluder have
     * moved, so the geometry differs. Without it a column consumed by one failed ray stays
     * invisible while they remain in sense range: tree-behind-tree blindness. Tuning knob.
     */
    public static final int RAY_RETRY_DELAY_TICKS = 60;
    /** Retries per stay-in-range; leaving and re-entering starts a fresh cycle. Sized so the
     *  window (MAX × DELAY = ~30s) outlives the commonest occluder — the front tree they are
     *  busy felling. Tuning knob. */
    public static final int RAY_RETRY_MAX = 10;

    private final AgentKnowledge knowledge;
    private final AgentProfile profile;
    private final ClaimIndex claims = new ClaimIndex();
    private final CrescentSampler sampler;
    private final HorizonScanner horizon;
    private final RegionCache regions;
    private final ReadBudget reads;
    private final PlaceIndex places;
    private final Deque<Column> pending = new ArrayDeque<>();
    private RegionGrowth active;
    /** The surface cell that seeded {@link #active} — reported on a DISMISSED outcome. */
    private Pos activeSeed;
    /** What {@link #active} will be filed under once it finishes. */
    private RegionCache.Key activeKey;
    /** Ray-blocked columns awaiting another look: when each is due, and its attempt count. */
    private final java.util.Map<Column, long[]> rayRetries = new java.util.HashMap<>();

    /** A sensor that shares nothing — its own scans, its own shapes. Tests, and any lone body. */
    public PoiSensorCore(AgentKnowledge knowledge, AgentProfile profile) {
        this(knowledge, profile, new RegionCache(), new PlaceIndex());
    }

    /**
     * A sensor that reads the world's shape from, and returns it to, the pools its level keeps —
     * so a thing one body walked up to is a thing none of the others has to walk. What it makes
     * of that shape is still entirely its own: see {@link PlaceIndex}.
     */
    public PoiSensorCore(AgentKnowledge knowledge, AgentProfile profile, RegionCache regions,
            PlaceIndex places) {
        this(knowledge, profile, regions, places, ReadBudget.UNMETERED);
    }

    /**
     * The full wiring, with the server's read allowance as well. A test or a lone body passes
     * {@link ReadBudget#UNMETERED} — with one body there is nothing to arbitrate.
     */
    public PoiSensorCore(AgentKnowledge knowledge, AgentProfile profile, RegionCache regions,
            PlaceIndex places, ReadBudget reads) {
        this.knowledge = knowledge;
        this.profile = profile;
        this.regions = regions;
        this.places = places;
        this.reads = reads;
        this.sampler = new CrescentSampler(profile);
        this.horizon = new HorizonScanner(profile);
    }

    /** The far sense's readout — for the debug view, and for reasoning about vantages. */
    public HorizonBuffer horizon() {
        return this.horizon.buffer();
    }

    /**
     * One perception tick. Returns what was learned (noted/forgotten beliefs) — empty on the
     * common quiet tick. Queued columns are probed even if the head has turned away, or a body
     * that keeps turning would starve its own queue.
     *
     * @param yawDegrees head bearing — see {@link CrescentSampler#advance}
     */
    public List<SenseEvent> tick(Pos feet, double yawDegrees, long now, BlockProbe probe) {
        // Read once, not once per column: a full view is hundreds of columns and this loop runs
        // several times a second per agent. One sweep also sees one consistent cap.
        int cap = queueCap();
        for (Column column : sampler.advance(feet, yawDegrees)) {
            rayRetries.remove(column); // freshly re-entered range: a fresh retry cycle
            if (pending.size() >= cap) {
                pending.pollFirst();
            }
            pending.addLast(column);
        }
        // Due retries jump the queue — near, and already half-investigated. A spent entry
        // (attempts at cap) stays PARKED in the map, blocking a fresh cycle until they leave and
        // re-enter range.
        for (var entry : rayRetries.entrySet()) {
            if (entry.getValue()[0] <= now) {
                pending.addFirst(entry.getKey());
                entry.getValue()[0] = Long.MAX_VALUE; // rescheduled only if the ray fails again
            }
        }
        List<SenseEvent> events = new ArrayList<>();
        // One wallet for the whole tick, read once — a reload mid-tick must not let a Person
        // spend twice. What it gets is its share of the server's ceiling, not its own. Being cut
        // short costs nothing but time: the queue keeps its columns and a growth resumes.
        int wallet = this.reads.grant(this, readsPerTick(), now);
        int reads = 0;
        while (reads < wallet) {
            if (active != null) {
                reads += active.step(probe, wallet - reads);
                if (!active.isDone()) {
                    break;
                }
                GrownRegion grown = active.result();
                regions.put(activeKey, grown); // what it cost to learn, the next body inherits
                // And what it AMOUNTS to: every thing in there seen whole is now a fact the
                // level holds for everybody.
                places.putAll(grown);
                finish(grown, activeSeed, now, events);
                active = null;
                activeKey = null;
                continue;
            }
            if (pending.isEmpty()) {
                break;
            }
            reads += probeColumn(pending.pollFirst(), now, probe, events);
        }
        // Whatever the near field did not want — the order of these lines is the scheduling
        // policy, and needs no knob: a body crossing new ground has a full queue and scans no
        // skyline, one standing still scans with its entire wallet.
        if (reads < wallet) {
            int firstFar = events.size();
            reads += horizon.step(feet, yawDegrees, now, probe, wallet - reads, events);
            recordGlimpses(events, firstFar, feet, now);
        }
        // Pay for what was actually read, not for what was asked: a body in ground it knows asks
        // for its whole wallet and reads almost none of it, and counting that would let incurious
        // agents hold a busy one out of a budget nobody was using.
        this.reads.refund(this, wallet - reads, now);
        return events;
    }

    /**
     * What the far sense made out becomes the gist tier of the store. Written here rather than
     * inside the scanner so the scanner stays a sensor and nothing else — the near field's
     * discoveries reach the store the same way, through {@link #finish}.
     */
    private void recordGlimpses(List<SenseEvent> events, int from, Pos feet, long now) {
        for (int i = from; i < events.size(); i++) {
            SenseEvent event = events.get(i);
            if (event.type() == SenseEvent.Type.GLIMPSED) {
                knowledge.glimpse(
                        new Sighting(event.kind(), event.anchor(), feet, now,
                                Sighting.Provenance.PASSIVE),
                        AgentKnowledge.maxPerKind(profile));
            }
        }
    }

    /** The person's transient claims — exposed for the debug command's "how full" line. */
    public int claimCount() {
        return claims.size();
    }

    private int probeColumn(Column column, long now, BlockProbe probe, List<SenseEvent> events) {
        Pos highestClaim = claims.highestIn(column);
        // What STANDS here, not what would hold a boot up: sugar cane and berry bushes are absent
        // from the motion-blocking heightmap, so asking for the surface answered with the sand
        // under a cane brake and a body could walk through one seeing nothing. Same single lookup.
        int top = probe.topY(column.x(), column.z());
        int reads = 1;
        if (top == Integer.MIN_VALUE) {
            return reads;
        }
        Pos surface = new Pos(column.x(), top, column.z());
        if (highestClaim != null) {
            if (highestClaim.y() > top) {
                reads += invalidate(highestClaim, claims.get(highestClaim), probe, events);
                return reads;
            }
            ClaimIndex.Claim exact = claims.get(surface);
            if (exact != null) {
                BlockKind kind = probe.at(surface.x(), surface.y(), surface.z());
                reads++;
                if (kind == exact.expected()) {
                    if (exact.anchor() != null
                            && !knowledge.refresh(exact.kind(), exact.anchor(), now)) {
                        // Orphaned claims: the memory is gone (a task forgot it, or eviction)
                        // but the claim survives and would mask the region forever. Drop them so
                        // a later sweep re-discovers whatever stands here now.
                        claims.dropRegion(exact.kind(), exact.anchor());
                    }
                } else {
                    reads += invalidate(surface, exact, probe, events);
                }
                return reads;
            }
            // Claims below, surface UNclaimed: the world grew TALLER on this investigated
            // footprint — fall through to the hypothesis path (see the class doc's third arm).
        }
        BlockKind kind = probe.at(surface.x(), surface.y(), surface.z());
        reads++;
        GrowthRule rule = ruleFor(kind);
        if (rule == null) {
            // Looked at properly, and it is nothing: settles any rumour on this cell. Free — the
            // column was probed anyway, and the near field is the deliberate look the gist tier
            // waits for.
            knowledge.disprove(column.x(), column.z());
            return reads;
        }
        reads += RAY_COST;
        if (!probe.visibleFromEyes(surface)) {
            events.add(SenseEvent.overlooked(rule.kind(), surface));
            // Book another look: the geometry will differ once they (or the occluder) move.
            long[] retry = rayRetries.computeIfAbsent(column, c -> new long[]{0, 0});
            if (retry[1] < RAY_RETRY_MAX) {
                retry[0] = now + RAY_RETRY_DELAY_TICKS;
                retry[1]++;
            }
            return reads;
        }
        rayRetries.remove(column);
        activeSeed = surface;
        // One hash lookup: if the level already knows what this cell belongs to, believe it — no
        // walking, no flood fill, no re-individuating a canopy somebody has already taken apart.
        // This is the path that carries a crowd; see PlaceIndex.
        PlaceIndex.Place standing = places.at(rule.kind(), surface);
        if (standing != null) {
            notePlace(standing, surface, now, events);
            return reads;
        }
        int spread = RegionGrowth.maxSpread(profile);
        RegionCache.Key key = new RegionCache.Key(rule.kind(), surface, spread);
        // Somebody already walked this mass and nothing in it has moved: recognising it reads
        // nothing. What follows is the ordinary reckoning — this body's own memory, claims and
        // journal line.
        GrownRegion known = regions.get(key);
        if (known == null) {
            // Nobody stood exactly here — but they may have walked this same mass from its other
            // side, which is the common case in a wood a crowd is crossing. The cells keep; the
            // judgment is made again, from where this body is looking.
            Map<Pos, BlockKind> mass = regions.covering(rule.kind(), surface, spread);
            if (mass != null) {
                // A complete mass by construction (covering serves no other kind), so nothing in
                // it stands against a cut, and every thing it holds is worth filing.
                known = RegionGrowth.judge(rule, mass, java.util.Set.of(), false, probe);
                regions.tookCovering();
                places.putAll(known);
            }
        }
        if (known != null) {
            finish(known, activeSeed, now, events);
            return reads;
        }
        active = new RegionGrowth(rule, surface, kind, profile);
        activeKey = key;
        return reads;
    }

    /**
     * One thing the level already knew about, believed afresh by this body — the whole of what a
     * {@link PlaceIndex} hit costs. The anchor is chosen for where THEY stand, so two bodies
     * meeting one tree from opposite sides walk to opposite feet of it.
     */
    private void notePlace(PlaceIndex.Place place, Pos from, long now, List<SenseEvent> events) {
        PoiMemory memory = knowledge.note(place.toMemory(from, now),
                AgentKnowledge.maxPerKind(profile));
        claims.claimRegion(place.kind(), memory.anchor(), place.blocks());
        knowledge.supersede(place.kind(), memory.anchor()); // the gist was right; keep the belief
        events.add(SenseEvent.noted(memory));
    }

    private void finish(GrownRegion region, Pos from, long now, List<SenseEvent> events) {
        if (!region.accepted()) {
            claims.claimNegative(region.kind(), region.blocks());
            // A rumour about this mass has been examined and found wanting — the glimpse this
            // exists to settle is something that reads as a tree from the skyline and is not one.
            for (Pos cell : region.blocks().keySet()) {
                knowledge.disprove(cell.x(), cell.z());
            }
            events.add(SenseEvent.dismissed(region.kind(), activeSeed));
            return;
        }
        java.util.Set<Pos> spoken = new java.util.HashSet<>();
        for (GrownRegion.Part part : region.parts()) {
            PoiMemory memory = knowledge.note(region.toMemory(part, from, now),
                    AgentKnowledge.maxPerKind(profile));
            claims.claimRegion(region.kind(), memory.anchor(), part.blocks());
            spoken.addAll(part.blocks().keySet());
            knowledge.supersede(region.kind(), memory.anchor()); // the gist was right; keep the belief
            events.add(SenseEvent.noted(memory));
        }
        if (spoken.size() < region.blocks().size()) {
            java.util.Map<Pos, BlockKind> leftovers = new java.util.LinkedHashMap<>();
            for (var entry : region.blocks().entrySet()) {
                if (!spoken.contains(entry.getKey())) {
                    leftovers.put(entry.getKey(), entry.getValue());
                }
            }
            claims.claimNegative(region.kind(), leftovers);
        }
    }

    /**
     * A claimed cell stopped matching the world. The claims drop either way, but the MEMORY is
     * only wrong when its anchor is gone: a half-felled tree still stands on its stump, the cell
     * its anchor names, and the chop's partial exit kept that memory to come back for. Forgetting
     * it here was the lone-stump factory — the stump failed its rule's own liveness test, was
     * dismissed and negative-claimed, and nothing could find it again. Costs one probe read.
     */
    private int invalidate(Pos at, ClaimIndex.Claim claim, BlockProbe probe, List<SenseEvent> events) {
        if (claim.anchor() == null) {
            claims.remove(at);
            return 0;
        }
        ClaimIndex.Claim anchorClaim = claims.get(claim.anchor());
        boolean anchorStands = anchorClaim != null
                && probe.at(claim.anchor().x(), claim.anchor().y(), claim.anchor().z())
                        == anchorClaim.expected();
        if (!anchorStands && knowledge.forget(claim.kind(), claim.anchor())) {
            events.add(SenseEvent.forgot(claim.kind(), claim.anchor()));
        }
        claims.dropRegion(claim.kind(), claim.anchor());
        return 1;
    }

    /** Whatever a consuming mod said this seed grows, or null — see {@link GrowthRules}. */
    private static GrowthRule ruleFor(BlockKind kind) {
        return GrowthRules.forSeed(kind).orElse(null);
    }
}
