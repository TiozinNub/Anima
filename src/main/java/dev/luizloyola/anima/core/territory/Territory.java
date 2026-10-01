package dev.luizloyola.anima.core.territory;

import dev.luizloyola.anima.core.social.PartyId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Which party holds which chunk (docs/superpowers/specs/2026-10-01-home-area-design.md). A chunk is
 * one party's or nobody's, and a party's chunks are one piece in each dimension — joined by edges,
 * never by a corner alone.
 *
 * <p>Every change is a {@link Claimed}, kept in the party's history and handed to every listener,
 * and a refusal is one too. A call is granted whole or refused whole; only {@link #grow}'s margin
 * is taken where it can be.
 *
 * <p>Pure core and single-threaded by contract (the server thread); persistence is the mod layer's.
 */
public final class Territory {

    /** Whether something outside Anima holds a chunk — a player's OPAC claim. Never asked about a
     *  chunk the party already has. */
    public interface Taken {
        boolean taken(ChunkKey chunk);
    }

    private static final Taken NOTHING_TAKEN = chunk -> false;

    private final Map<ChunkKey, PartyId> owners = new HashMap<>();
    private final Map<PartyId, SortedSet<ChunkKey>> areas = new LinkedHashMap<>();
    private final Map<PartyId, Deque<Claimed>> histories = new LinkedHashMap<>();
    private final List<Consumer<Claimed>> listeners = new ArrayList<>();
    private Taken taken = NOTHING_TAKEN;
    private IntSupplier kept = () -> 64;
    private Function<PartyId, Optional<String>> names = party -> Optional.empty();

    public void takenBy(Taken taken) {
        this.taken = taken == null ? NOTHING_TAKEN : taken;
    }

    /** How many events a party's history keeps, read on every write so a config reload applies. */
    public void keeps(IntSupplier kept) {
        this.kept = kept;
    }

    /** Hears every granted change and every refusal, after the territory has applied it. */
    public void onEvent(Consumer<Claimed> listener) {
        listeners.add(listener);
    }

    /** How a consumer names a party; Anima has no name for one. */
    public void namedBy(Function<PartyId, Optional<String>> names) {
        this.names = names;
    }

    // --- reads -----------------------------------------------------------------------------------

    public Optional<PartyId> owner(ChunkKey chunk) {
        return Optional.ofNullable(owners.get(chunk));
    }

    public SortedSet<ChunkKey> area(PartyId party) {
        SortedSet<ChunkKey> area = areas.get(party);
        return area == null ? Collections.emptySortedSet() : Collections.unmodifiableSortedSet(area);
    }

    /** Every party holding at least one chunk. */
    public Set<PartyId> parties() {
        return Collections.unmodifiableSet(areas.keySet());
    }

    /** The party's kept events, oldest first. */
    public List<Claimed> history(PartyId party) {
        Deque<Claimed> history = histories.get(party);
        return history == null ? List.of() : List.copyOf(history);
    }

    /** Parties with a history, including those that hold nothing any more. */
    public Set<PartyId> historied() {
        return Collections.unmodifiableSet(histories.keySet());
    }

    public String name(PartyId party) {
        return names.apply(party).orElseGet(() -> "Party " + party.value().toString().substring(0, 8));
    }

    /** A colour of its own, stable across restarts since it comes from the id: 0xRRGGBB. */
    public static int colour(PartyId party) {
        float hue = (party.value().hashCode() & 0xFFFF) / 65536F;
        return hsv(hue, 0.75F, 0.95F);
    }

    // --- changes ---------------------------------------------------------------------------------

    /** Exactly these chunks; refused whole if any is not free or the area would be in pieces. */
    public Claimed claim(PartyId party, Collection<ChunkKey> chunks, Reason why, long tick) {
        return grow(party, chunks, 0, why, tick);
    }

    /**
     * The footprint plus a ring {@code margin} chunks wide. The footprint is refused whole if any
     * chunk of it is not free; the ring takes what is free and still joins on. The result must be
     * one piece, so a footprint one chunk off the area is joined to it by its margin.
     */
    public Claimed grow(PartyId party, Collection<ChunkKey> footprint, int margin, Reason why, long tick) {
        return commit(planGrow(party, footprint, margin, why, tick));
    }

    /** What {@link #grow} would do, changing nothing and telling nobody — for pricing a site. */
    public Claimed planGrow(PartyId party, Collection<ChunkKey> footprint, int margin, Reason why,
                            long tick) {
        Set<ChunkKey> mine = areas.getOrDefault(party, Collections.emptySortedSet());
        Claimed blocked = blocked(party, mine, footprint, why, tick);
        if (blocked != null) {
            return blocked;
        }

        Set<ChunkKey> core = new HashSet<>(mine);
        core.addAll(footprint);
        Set<ChunkKey> ring = new HashSet<>();
        Set<ChunkKey> skipped = new TreeSet<>();
        for (ChunkKey chunk : footprint) {
            for (int dx = -margin; dx <= margin; dx++) {
                for (int dz = -margin; dz <= margin; dz++) {
                    ChunkKey near = chunk.offset(dx, dz);
                    if (core.contains(near) || ring.contains(near) || skipped.contains(near)) {
                        continue;
                    }
                    if (owners.containsKey(near) || taken.taken(near)) {
                        skipped.add(near);
                    } else {
                        ring.add(near);
                    }
                }
            }
        }
        // A ring chunk whose way in was skipped would hang on by a corner: keep only what the
        // footprint or the area reaches by edges.
        Set<ChunkKey> result = reachable(core, ring);
        if (!onePiece(result)) {
            return Claimed.refused(tick, party, why, Claimed.Refusal.DETACHED,
                    skipped.isEmpty() ? new TreeSet<>(footprint) : skipped);
        }
        result.removeAll(mine);
        return Claimed.granted(tick, party, result, Set.of(), why);
    }

    /**
     * The area becomes exactly these chunks, as one event — a party that moves. Refused whole, so a
     * new site that cannot be had never costs the old one.
     */
    public Claimed move(PartyId party, Collection<ChunkKey> chunks, Reason why, long tick) {
        Set<ChunkKey> mine = areas.getOrDefault(party, Collections.emptySortedSet());
        Claimed blocked = blocked(party, mine, chunks, why, tick);
        if (blocked != null) {
            return commit(blocked);
        }
        Set<ChunkKey> target = new HashSet<>(chunks);
        if (!onePiece(target)) {
            return commit(Claimed.refused(tick, party, why, Claimed.Refusal.DETACHED, target));
        }
        Set<ChunkKey> added = new HashSet<>(target);
        added.removeAll(mine);
        Set<ChunkKey> removed = new HashSet<>(mine);
        removed.removeAll(target);
        return commit(Claimed.granted(tick, party, added, removed, why));
    }

    /** Refused whole if any chunk is not the party's, or what is left would be in pieces. */
    public Claimed release(PartyId party, Collection<ChunkKey> chunks, Reason why, long tick) {
        Set<ChunkKey> mine = areas.getOrDefault(party, Collections.emptySortedSet());
        Set<ChunkKey> notOurs = new TreeSet<>();
        for (ChunkKey chunk : chunks) {
            if (!mine.contains(chunk)) {
                notOurs.add(chunk);
            }
        }
        if (!notOurs.isEmpty()) {
            return commit(Claimed.refused(tick, party, why, Claimed.Refusal.NOT_OURS, notOurs));
        }
        Set<ChunkKey> rest = new HashSet<>(mine);
        rest.removeAll(chunks);
        if (!onePiece(rest)) {
            return commit(Claimed.refused(tick, party, why, Claimed.Refusal.DETACHED,
                    new TreeSet<>(chunks)));
        }
        return commit(Claimed.granted(tick, party, Set.of(), new HashSet<>(chunks), why));
    }

    /** Everything the party holds — a move, or the party's end. */
    public Claimed releaseAll(PartyId party, Reason why, long tick) {
        return release(party, List.copyOf(area(party)), why, tick);
    }

    /** Drops a party's kept log once it holds nothing — a party that ended leaves no row behind. */
    public void forget(PartyId party) {
        if (!areas.containsKey(party)) {
            histories.remove(party);
        }
    }

    /** Rebuilds a party from a save: no checks, no listeners. */
    public void restore(PartyId party, Collection<ChunkKey> chunks, List<Claimed> history) {
        for (ChunkKey chunk : chunks) {
            owners.put(chunk, party);
            areas.computeIfAbsent(party, key -> new TreeSet<>()).add(chunk);
        }
        if (!history.isEmpty()) {
            histories.computeIfAbsent(party, key -> new ArrayDeque<>()).addAll(history);
        }
    }

    /** The refusal for the first chunk not ours that is another party's or taken, else null. */
    private @Nullable Claimed blocked(PartyId party, Set<ChunkKey> mine, Collection<ChunkKey> chunks, Reason why,
                            long tick) {
        Set<ChunkKey> theirs = new TreeSet<>();
        Set<ChunkKey> held = new TreeSet<>();
        for (ChunkKey chunk : chunks) {
            if (mine.contains(chunk)) {
                continue;
            }
            if (owners.containsKey(chunk)) {
                theirs.add(chunk);
            } else if (taken.taken(chunk)) {
                held.add(chunk);
            }
        }
        if (!theirs.isEmpty()) {
            return Claimed.refused(tick, party, why, Claimed.Refusal.OTHER_PARTY, theirs);
        }
        if (!held.isEmpty()) {
            return Claimed.refused(tick, party, why, Claimed.Refusal.TAKEN, held);
        }
        return null;
    }

    private Claimed commit(Claimed event) {
        if (event.granted()) {
            if (!event.changed()) {
                return event;
            }
            for (ChunkKey chunk : event.removed()) {
                owners.remove(chunk);
            }
            for (ChunkKey chunk : event.added()) {
                owners.put(chunk, event.party());
            }
            SortedSet<ChunkKey> area = areas.computeIfAbsent(event.party(), key -> new TreeSet<>());
            area.removeAll(event.removed());
            area.addAll(event.added());
            if (area.isEmpty()) {
                areas.remove(event.party());
            }
        }
        Deque<Claimed> history = histories.computeIfAbsent(event.party(), key -> new ArrayDeque<>());
        history.addLast(event);
        int keep = Math.max(1, kept.getAsInt());
        while (history.size() > keep) {
            history.removeFirst();
        }
        for (Consumer<Claimed> listener : listeners) {
            listener.accept(event);
        }
        return event;
    }

    // --- shape -----------------------------------------------------------------------------------

    /** Whether the chunks are one piece in each dimension, joined by edges. Empty counts. */
    public static boolean onePiece(Set<ChunkKey> chunks) {
        Map<String, Set<ChunkKey>> byDimension = new HashMap<>();
        for (ChunkKey chunk : chunks) {
            byDimension.computeIfAbsent(chunk.dimension(), key -> new HashSet<>()).add(chunk);
        }
        for (Set<ChunkKey> part : byDimension.values()) {
            ChunkKey first = part.iterator().next();
            if (reachable(Set.of(first), part).size() != part.size()) {
                return false;
            }
        }
        return true;
    }

    /** {@code seeds}, plus every chunk of {@code within} reached from them by edges. */
    private static Set<ChunkKey> reachable(Set<ChunkKey> seeds, Set<ChunkKey> within) {
        Set<ChunkKey> seen = new HashSet<>(seeds);
        Deque<ChunkKey> open = new ArrayDeque<>(seeds);
        while (!open.isEmpty()) {
            for (ChunkKey next : open.removeFirst().edgeNeighbours()) {
                if (within.contains(next) && seen.add(next)) {
                    open.addLast(next);
                }
            }
        }
        return seen;
    }

    private static int hsv(float hue, float saturation, float value) {
        int sector = (int) (hue * 6) % 6;
        float f = hue * 6 - (int) (hue * 6);
        float p = value * (1 - saturation);
        float q = value * (1 - f * saturation);
        float t = value * (1 - (1 - f) * saturation);
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> { r = value; g = t; b = p; }
            case 1 -> { r = q; g = value; b = p; }
            case 2 -> { r = p; g = value; b = t; }
            case 3 -> { r = p; g = q; b = value; }
            case 4 -> { r = t; g = p; b = value; }
            default -> { r = value; g = p; b = q; }
        }
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }
}
