package dev.luizloyola.anima.mod.social;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.Places;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The membership-transition wiring end to end: {@link PartyData} driving a real {@link Places}
 * through a real {@link PlacesData}, wired the way {@code PlacesData.attach} wires it in
 * production. {@code PlacesDataTest}'s two transition tests use a bare {@code Places} whose
 * listener is the no-op default — they can prove a row moved, but not that the store learned to
 * save it. These use {@code PlacesData} throughout so {@code isDirty()} can stand as a witness.
 */
class PartyDataTest {

    private static final PoiKind BENCH = PoiKind.register("test_party_data_bench", 1, "");

    private final PartyData parties = new PartyData();
    private final PlacesData store = new PlacesData();

    @org.junit.jupiter.api.BeforeEach
    void wireThemTogetherLikeAttachDoes() {
        parties.follows(store.places());
        store.places().asks(new Places.Parties() {
            @Override
            public Optional<PartyId> current(AgentId who) {
                return parties.currentPartyOf(who);
            }

            @Override
            public PartyId of(AgentId who) {
                return parties.partyOf(who);
            }
        });
    }

    @Test
    void joiningElsewhereLeavesAStillPopulatedPartysWorkshopBehind() {
        // Both members of `together` start there so it survives Hazel's departure — the asymmetry
        // the spec calls load-bearing: only a party that CEASED to exist hands off its communal
        // claims. Nothing today pins this; PlacesDataTest's `joiningCarries...` only exercises the
        // other half, where the old party WAS emptied.
        AgentId hazel = AgentId.random();
        AgentId rowan = AgentId.random();
        AgentId ivy = AgentId.random();
        PartyId together = parties.partyOf(hazel);
        parties.join(rowan, together);
        store.places().found(BENCH, new Pos(1, 64, 1), null, together, 5L);

        PartyId elsewhere = parties.partyOf(ivy);
        assertTrue(parties.join(hazel, elsewhere));

        assertTrue(store.places().viewFor(rowan).nearest(BENCH, new Pos(0, 0, 0)).isPresent(),
                "rowan is still in `together`, which never ceased to exist");
        assertTrue(store.places().viewFor(ivy).nearest(BENCH, new Pos(0, 0, 0)).isEmpty(),
                "hazel joining does not hand ivy's party a workshop it never built");
    }

    @Test
    void evictingADeadLonersLastMemberDropsTheirCommunalClaim() {
        // No test anywhere calls PartyData.evict — the death path (AnimaRecords.register("parties",
        // false, …)) — so nothing today proves it cleans up the party's own claims rather than
        // leaking a row that names a PartyId no roster tracks any more.
        AgentId hazel = AgentId.random();
        PartyId alone = parties.partyOf(hazel);
        store.places().found(BENCH, new Pos(2, 64, 2), null, alone, 5L);
        store.setDirty(false); // isolate to what evict itself does below

        assertTrue(parties.evict(hazel));

        assertTrue(store.places().rows().isEmpty(),
                "hazel's party had only her; her death takes its communal claim with it");
        assertTrue(store.isDirty(),
                "the drop must reach PlacesData through partyDisbanded's listener, or a restart "
                        + "leaves the row pointing at a party that no longer exists");
    }

    @Test
    void aLonerRejoiningMarksTheStoreDirtyThroughOwnerMovedTo() {
        // Isolates ownerMovedTo's listener from partyDisbanded's: the claim here is OWNED (not
        // communal), so partyDisbanded's owner guard skips it even though hazel's old party of one
        // does cease to exist on this join.
        AgentId hazel = AgentId.random();
        PartyId alone = parties.partyOf(hazel);
        store.places().found(BENCH, new Pos(3, 64, 3), hazel, alone, 5L);
        store.setDirty(false);

        AgentId ivy = AgentId.random();
        PartyId elsewhere = parties.partyOf(ivy);
        assertTrue(parties.join(hazel, elsewhere));

        assertTrue(store.isDirty(),
                "hazel's shared claim re-pointed to her new party; PlacesData must learn that");
    }
}
