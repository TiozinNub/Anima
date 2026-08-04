package dev.luizloyola.anima.core.brain.board;

import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.agent.AgentId;
import java.util.HashMap;
import java.util.Map;

/**
 * The shared "who is working what" registry, and the first GROUP-scoped piece of layer 3. The
 * failure mode it exists for: two choppers on one tree steal each other's logs, and one can fell
 * the other's scaffolding. One instance per world; slice 3's group claim board will absorb it.
 *
 * <p><b>Claims are heartbeats, not locks.</b> A claim lives {@link #ttlTicks()} from its last
 * {@link #claim} and a working task re-claims every tick, so death, despawn or a crash frees a site
 * within one TTL. A clean task exit still releases immediately.
 *
 * <p>Transient by design: a fresh boot starts unclaimed and the first tick's claims rebuild the
 * truth. Pure core and single-threaded by contract (the server thread), so a plain map suffices.
 */
public final class SiteClaims {
    /**
     * Ticks a claim survives past its last heartbeat — long enough to ride out a suspension
     * (a meal mid-chop), short enough that a dead claimant frees the site within half a minute.
     */
    public static int ttlTicks() {
        return Config.get().i(Knob.CLAIM_TTL_TICKS);
    }

    private record Key(PoiKind kind, Pos anchor) {
    }

    private record Claim(AgentId who, long untilTick, Region area) {
    }

    private final Map<Key, Claim> claims = new HashMap<>();

    /** Claim a site whose extent is not known (or does not matter): just the anchor cell. */
    public boolean claim(PoiKind kind, Pos anchor, AgentId who, long now) {
        return claim(kind, anchor, Region.of(anchor), who, now);
    }

    /** See {@link AgentClaims#claim}: succeeds unless someone ELSE's live claim holds the site. */
    public boolean claim(PoiKind kind, Pos anchor, Region area, AgentId who, long now) {
        Key key = new Key(kind, anchor);
        Claim held = claims.get(key);
        if (held != null && held.untilTick() > now && !held.who().equals(who)) {
            return false;
        }
        claims.put(key, new Claim(who, now + ttlTicks(), area));
        return true;
    }

    /**
     * Whether {@code cell} falls inside a work site somebody OTHER than {@code who} is holding.
     *
     * <p>A worker's own claim wins for them, or two trees whose boxes overlap would lock both
     * owners out of the seam between them.
     *
     * <p>Scans the live claims rather than an index: at most one per working agent, asked per drop
     * rather than per cell, so an index would cost more to keep current than the walk costs.
     */
    public boolean heldByOtherAt(Pos cell, AgentId who, long now) {
        boolean foreign = false;
        for (Claim claim : claims.values()) {
            if (claim.untilTick() <= now || !claim.area().contains(cell)) {
                continue;
            }
            if (claim.who().equals(who)) {
                return false; // inside their own site: theirs, whoever else also reaches here
            }
            foreign = true;
        }
        return foreign;
    }

    /** See {@link AgentClaims#release}: only the holder's own release removes anything. */
    public void release(PoiKind kind, Pos anchor, AgentId who) {
        Key key = new Key(kind, anchor);
        Claim held = claims.get(key);
        if (held != null && held.who().equals(who)) {
            claims.remove(key);
        }
    }

    /** See {@link AgentClaims#availableTo}: unclaimed, expired, or already this person's. */
    public boolean availableTo(PoiKind kind, Pos anchor, AgentId who, long now) {
        Claim held = claims.get(new Key(kind, anchor));
        return held == null || held.untilTick() <= now || held.who().equals(who);
    }

    /** This person's bound view — what a {@code BrainContext} hands the task machinery. */
    public AgentClaims forPerson(AgentId who) {
        return new AgentClaims() {
            @Override
            public boolean claim(PoiKind kind, Pos anchor, Region area, long now) {
                return SiteClaims.this.claim(kind, anchor, area, who, now);
            }

            @Override
            public void release(PoiKind kind, Pos anchor) {
                SiteClaims.this.release(kind, anchor, who);
            }

            @Override
            public boolean availableTo(PoiKind kind, Pos anchor, long now) {
                return SiteClaims.this.availableTo(kind, anchor, who, now);
            }

            @Override
            public boolean claimedByOther(Pos cell, long now) {
                return SiteClaims.this.heldByOtherAt(cell, who, now);
            }
        };
    }

    /** Live + lapsed entries currently stored — the debug commands' number, not a semantic one. */
    public int size() {
        return claims.size();
    }

    /**
     * One live hold, flattened for the claims dump.
     *
     * @param remaining ticks left before it lapses if nobody re-claims it
     */
    public record Held(PoiKind kind, Pos anchor, AgentId who, long remaining) {
    }

    /**
     * Every hold still live at {@code now}, nearest expiry first — what {@code /anima claims}
     * prints. Lapsed entries are omitted, not swept: readers already ignore them, and a dump must
     * not mutate the registry.
     */
    public java.util.List<Held> held(long now) {
        java.util.List<Held> live = new java.util.ArrayList<>();
        for (Map.Entry<Key, Claim> entry : claims.entrySet()) {
            if (entry.getValue().untilTick() > now) {
                live.add(new Held(entry.getKey().kind(), entry.getKey().anchor(),
                        entry.getValue().who(), entry.getValue().untilTick() - now));
            }
        }
        live.sort(java.util.Comparator.comparingLong(Held::remaining));
        return live;
    }
}
