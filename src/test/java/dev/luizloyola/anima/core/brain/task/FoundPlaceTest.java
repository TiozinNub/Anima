package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.social.Places;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Placing a thing with nothing to hide claims it for the party — no owner, immediate, and known to
 * every member without anyone having seen it.
 */
class FoundPlaceTest {

    private static final PoiKind BENCH = PoiKind.register("test_found_bench", 1, "");

    @Test
    void placingATableClaimsItForThePartyWithNoOwner() {
        PartyId theirs = PartyId.random();
        Places places = new Places();
        FakeContext ctx = new FakeContext();
        places.asks(everyoneIn(theirs));
        ctx.percepts.time = 500L;
        ctx.knowledge.sees(places.viewFor(ctx.self), () -> ctx.percepts.time);

        assertEquals(TaskStatus.SUCCESS, new FoundPlace(BENCH, 88, 64, -12).tick(ctx));

        PlaceRow row = places.rows().iterator().next();
        assertNull(row.owner(), "a table has no inventory, so it belongs to nobody in particular");
        assertEquals(theirs, row.party());
        assertEquals(new Pos(88, 64, -12), row.at());
        assertEquals(500L, row.since());
    }

    @Test
    void anotherMemberKnowsItWithoutEverHavingSeenIt() {
        AgentId rowan = AgentId.random();
        PartyId theirs = PartyId.random();
        Places places = new Places();
        FakeContext ctx = new FakeContext();
        places.asks(everyoneIn(theirs));
        ctx.percepts.time = 500L;
        ctx.knowledge.sees(places.viewFor(ctx.self), () -> ctx.percepts.time);

        new FoundPlace(BENCH, 88, 64, -12).tick(ctx);

        assertTrue(places.viewFor(rowan).nearest(BENCH, new Pos(0, 0, 0)).isPresent(),
                "membership is how Rowan knows; they have never been within 90 blocks of it");
    }

    /** Everybody is in the one party — the roster stand-in these two cases need. */
    private static Places.Parties everyoneIn(PartyId party) {
        return new Places.Parties() {
            @Override
            public Optional<PartyId> current(AgentId who) {
                return Optional.of(party);
            }

            @Override
            public PartyId of(AgentId who) {
                return party;
            }
        };
    }
}
