package dev.luizloyola.anima.mod.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.social.Places;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.UUIDUtil;
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

    @Test
    void mergeHandsTheMovingPartysWorkshopOverAndDisbandsOnce() {
        AgentId hazel = AgentId.random();
        AgentId rowan = AgentId.random();
        AgentId ivy = AgentId.random();
        PartyId moving = parties.partyOf(hazel);
        parties.join(rowan, moving);
        PartyId staying = parties.partyOf(ivy);
        store.places().found(BENCH, new Pos(3, 64, 3), null, moving, 5L);
        List<PartyId> gone = new ArrayList<>();
        parties.onDisband(gone::add);

        assertTrue(parties.merge(moving, staying));

        assertEquals(List.of(moving), gone, "two members moved, one party ended");
        assertTrue(store.places().viewFor(ivy).nearest(BENCH, new Pos(0, 0, 0)).isPresent(),
                "ivy's party now holds the bench the moving party built");
        assertEquals(List.of(ivy, hazel, rowan), parties.members(staying));
    }

    @Test
    void aPlayerInCompanyLeadsUnlessSomeoneAlreadyDoes() {
        AgentId player = AgentId.random();
        AgentId eda = AgentId.random();
        AgentId cai = AgentId.random();
        parties.knowsPlayers(player::equals);

        PartyId edas = parties.partyOf(eda);
        assertTrue(parties.leader(parties.partyOf(player)).isEmpty(), "alone, nobody needs leading");
        parties.join(player, edas);
        assertEquals(player, parties.leader(edas).orElseThrow());

        PartyId cais = parties.partyOf(cai);
        parties.join(AgentId.random(), cais);
        parties.lead(cai);
        parties.merge(edas, cais);
        assertEquals(cai, parties.leader(cais).orElseThrow(), "a party that has a leader keeps it");
    }

    @Test
    void theLeaderSurvivesASaveAndAMisfiledOneDoesNot() {
        AgentId hazel = AgentId.random();
        AgentId rowan = AgentId.random();
        AgentId ivy = AgentId.random();
        PartyId party = parties.partyOf(hazel);
        parties.join(rowan, party);
        parties.lead(rowan);
        PartyId ivys = parties.partyOf(ivy);

        var encoded = PartyData.CODEC.encodeStart(JsonOps.INSTANCE, parties).getOrThrow();
        PartyData decoded = PartyData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(rowan, decoded.leader(party).orElseThrow());

        // Ivy written as the leader of a party she is not in: on load she leads neither that one
        // nor, by way of the roster's lead(), her own.
        for (var element : encoded.getAsJsonObject().getAsJsonArray("parties")) {
            JsonObject row = element.getAsJsonObject();
            if (row.has("leader")) {
                row.add("leader", UUIDUtil.CODEC.encodeStart(JsonOps.INSTANCE, ivy.value()).getOrThrow());
            }
        }
        PartyData misfiled = PartyData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertTrue(misfiled.leader(party).isEmpty());
        assertTrue(misfiled.leader(ivys).isEmpty());
    }

    @Test
    void aSaveFromBeforeLeadersLoadsUnled() {
        AgentId hazel = AgentId.random();
        PartyId party = parties.partyOf(hazel);
        var encoded = PartyData.CODEC.encodeStart(JsonOps.INSTANCE, parties).getOrThrow();
        assertFalse(encoded.toString().contains("leader"), "no leader, no field");
        PartyData decoded = PartyData.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(List.of(hazel), decoded.members(party));
        assertTrue(decoded.leader(party).isEmpty());
    }

    @Test
    void arrivalIsHeardOnlyIntoCompany() {
        List<String> heard = new ArrayList<>();
        parties.onArrival((into, who) -> heard.add(who.size() + " into " + parties.members(into).size()));
        AgentId hazel = AgentId.random();
        AgentId rowan = AgentId.random();
        AgentId ivy = AgentId.random();
        AgentId oak = AgentId.random();

        parties.partyOf(hazel);
        parties.join(rowan, PartyId.random());
        assertEquals(List.of(), heard, "minting a party of one, or moving into an empty one, is no arrival");

        parties.join(ivy, parties.partyOf(hazel));
        parties.join(oak, parties.partyOf(rowan));
        parties.merge(parties.partyOf(rowan), parties.partyOf(hazel));
        assertEquals(List.of("1 into 2", "1 into 2", "2 into 4"), heard);
    }

    @Test
    void aMergeTellsWhichPlacesCameWithTheParty() {
        AgentId hazel = AgentId.random();
        AgentId ivy = AgentId.random();
        PartyId moving = parties.partyOf(hazel);
        PartyId staying = parties.partyOf(ivy);
        store.places().found(BENCH, new Pos(300, 64, 300), null, moving, 5L);
        store.places().found(BENCH, new Pos(1, 64, 1), null, staying, 5L);
        List<PartyData.Merged> heard = new ArrayList<>();
        parties.onMerge(heard::add);

        parties.merge(moving, staying);

        assertEquals(1, heard.size());
        assertEquals(List.of(hazel), heard.get(0).members());
        assertEquals(List.of(new Pos(300, 64, 300)), heard.get(0).places().stream().map(PlaceRow::at).toList(),
                "only what the moving party had, as it was before the merge took it in");
    }
}
