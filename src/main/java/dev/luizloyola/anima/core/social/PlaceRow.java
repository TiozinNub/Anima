package dev.luizloyola.anima.core.social;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One claimed place, in the three states a claim can be: {@code owner} alone is personal,
 * {@code owner} with {@code party} is shared, {@code party} alone is communal.
 *
 * <p>A claim is not a sighting. It never decays, is never evicted by a memory budget, and is
 * written only by an act — see {@link Places}.
 *
 * @param since game time the claim was made, for the readout only; nothing prices it
 */
public record PlaceRow(PoiKind kind, Pos at, @Nullable AgentId owner, @Nullable PartyId party,
                       long since) {

    public PlaceRow {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(at, "at");
        if (owner == null && party == null) {
            throw new IllegalArgumentException("a claim nobody makes: " + kind.key() + " at " + at);
        }
    }

    /** Whether this claim is {@code who}'s to see, given the party they are in right now. */
    public boolean visibleTo(AgentId who, @Nullable PartyId theirs) {
        return who.equals(owner) || (party != null && party.equals(theirs));
    }

    /**
     * The claim as the knowledge store's currency, so a caller cannot tell a claim from a sighting.
     * Stamped {@code now} on every call — a claim is a fact somebody is responsible for, not an
     * observation that ages, and pricing it by staleness would send people to re-look at their own
     * workshop.
     */
    public PoiMemory toMemory(long now) {
        // units 1, matching NotePlace: a claimed block is one thing, never a mass.
        return new PoiMemory(kind, at, Region.of(at), 1, false, now);
    }
}
