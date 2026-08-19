package dev.luizloyola.anima.core.social;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Every claimed place in the world — what somebody built, placed or claimed, as against the
 * sightings a body accumulates in {@code AgentKnowledge}.
 *
 * <p><b>Claims are not sightings</b>, and the separation is what keeps a party from reading minds:
 * joining a party shares what its members have BUILT and not one tree any of them walked past.
 * Nothing here decays, nothing is evicted by a memory budget, and rows arrive only through
 * {@link #found} and leave only through {@link #drop} or a party ceasing to exist.
 *
 * <p>Pure core and single-threaded by contract (the server thread); persistence is the {@code mod}
 * layer's job.
 */
public final class Places {

    /**
     * How this store asks who is in which party, so it need not own a {@code PartyRoster}.
     *
     * <p>Two methods because the two callers differ: a <b>read</b> must never mint a party (a
     * {@code nearest} on every tick would otherwise create one per agent as a side effect), while
     * <b>founding</b> must, since a communal claim needs a party to belong to.
     */
    public interface Parties {
        /** The party {@code who} is in, or empty. Never creates. */
        Optional<PartyId> current(AgentId who);

        /** The party {@code who} is in, minting a party of one if they have never been asked. */
        PartyId of(AgentId who);
    }

    /** Nobody is in a party and nothing can be founded — what a store holds before it is wired. */
    private static final Parties NO_ROSTER = new Parties() {
        @Override
        public Optional<PartyId> current(AgentId who) {
            return Optional.empty();
        }

        @Override
        public PartyId of(AgentId who) {
            throw new IllegalStateException("no roster wired: nothing can be founded here");
        }
    };

    /** A store nobody supplied — answers nothing, accepts nothing. */
    public static final Places EMPTY = new Places();

    private Parties parties = NO_ROSTER;
    private Runnable listener = () -> { };
    /** Keyed so one place is one row: two claims on one block are the same claim. */
    private final Map<Key, PlaceRow> rows = new LinkedHashMap<>();

    private record Key(PoiKind kind, Pos at) {
    }

    /**
     * Installs the roster this store asks about membership.
     *
     * <p>Separate from construction because the store is built by its {@code SavedDataType} factory,
     * which runs before there is a server to ask — rows come off disk first and the roster is wired
     * when the server has started.
     */
    public void asks(Parties parties) {
        this.parties = parties;
    }

    /**
     * Installs what to call when a claim is made or dropped — how the {@code mod}-layer store learns
     * it has something to save, since the thing that founds a claim is a core task that cannot reach
     * a {@code SavedData}.
     */
    public void onChange(Runnable listener) {
        this.listener = listener;
    }

    /** Records a claim, replacing any earlier claim on the same block. */
    public PlaceRow found(PoiKind kind, Pos at, @Nullable AgentId owner, @Nullable PartyId party,
                          long now) {
        PlaceRow row = new PlaceRow(kind, at, owner, party, now);
        rows.put(new Key(kind, at), row);
        listener.run();
        return row;
    }

    /** Forgets a claim. {@code false} when there was none — an idempotent correction. */
    public boolean drop(PoiKind kind, Pos at) {
        boolean gone = rows.remove(new Key(kind, at)) != null;
        if (gone) {
            listener.run();
        }
        return gone;
    }

    /** Every claim, insertion-ordered — the store's codec and the debug readout. */
    public Collection<PlaceRow> rows() {
        return List.copyOf(rows.values());
    }

    /** This agent's window onto the claims they may see. Cheap: it binds an id, nothing more. */
    public View viewFor(AgentId who) {
        return new View(this, who);
    }

    /**
     * One agent's claims — the {@code Board.viewFor} pattern, so identity never has to widen the
     * seams that read this.
     *
     * <p>The party is resolved on <b>every</b> read rather than bound once: a settler who joins a
     * party must see its workshop on the next tick, not on the next time somebody rebuilt a view.
     */
    public static final class View {

        /** The window of an agent with no store behind them. */
        public static final View EMPTY = new View(Places.EMPTY, AgentId.of(new java.util.UUID(0, 0)));

        private final Places places;
        private final AgentId who;

        private View(Places places, AgentId who) {
            this.places = places;
            this.who = who;
        }

        /** The nearest claim of this kind this agent may see, by squared distance. */
        public Optional<PlaceRow> nearest(PoiKind kind, Pos from) {
            PlaceRow best = null;
            long bestDist = Long.MAX_VALUE;
            for (PlaceRow row : visible(kind)) {
                long dist = distanceSq(row.at(), from);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = row;
                }
            }
            return Optional.ofNullable(best);
        }

        /** Every claim of this kind this agent may see, insertion-ordered. */
        public Collection<PlaceRow> all(PoiKind kind) {
            return visible(kind);
        }

        /**
         * Forgets a claim this agent can see — the probe correction, made by whoever stood there
         * and found nothing. Refuses a claim they cannot see: correcting somebody else's record of
         * a block you happen to be standing on is not a report.
         */
        public boolean drop(PoiKind kind, Pos at) {
            PlaceRow row = places.rows.get(new Key(kind, at));
            if (row == null || !row.visibleTo(who, places.parties.current(who).orElse(null))) {
                return false;
            }
            return places.drop(kind, at);
        }

        /** Claims a block for this agent's party — what placing a thing with nothing to hide does. */
        public PlaceRow foundCommunal(PoiKind kind, Pos at, long now) {
            return places.found(kind, at, null, places.parties.of(who), now);
        }

        private List<PlaceRow> visible(PoiKind kind) {
            PartyId theirs = places.parties.current(who).orElse(null);
            List<PlaceRow> out = new ArrayList<>();
            for (PlaceRow row : places.rows.values()) {
                if (row.kind().equals(kind) && row.visibleTo(who, theirs)) {
                    out.add(row);
                }
            }
            return out;
        }

        private static long distanceSq(Pos a, Pos b) {
            long dx = (long) a.x() - b.x();
            long dy = (long) a.y() - b.y();
            long dz = (long) a.z() - b.z();
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
