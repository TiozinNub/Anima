package dev.luizloyola.anima.mod.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.core.territory.Claimed;
import dev.luizloyola.anima.core.territory.Reason;
import dev.luizloyola.anima.core.territory.Territory;
import dev.luizloyola.anima.mod.social.PartyData;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A party's ground and its kept log come back from a save as they went in. */
class TerritoryDataTest {

    private static ChunkKey c(int x, int z) {
        return new ChunkKey(ChunkKey.OVERWORLD, x, z);
    }

    @Test
    void aPartysGroundAndLogSurviveTheRoundTrip() {
        Territory territory = new Territory();
        PartyId party = PartyId.random();
        territory.claim(party, Set.of(c(0, 0), c(1, 0), new ChunkKey("minecraft:the_nether", -3, 7)),
                Reason.of(Reason.Kind.FOUND, "settled"), 40);
        territory.claim(party, Set.of(c(9, 9)), Reason.of(Reason.Kind.OP, "by Luiz"), 41);
        var before = new TerritoryData.Row(party, territory.area(party), territory.history(party));

        var encoded = TerritoryData.ROW_CODEC.encodeStart(JsonOps.INSTANCE, before).getOrThrow();
        var after = TerritoryData.ROW_CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(before.party(), after.party());
        assertEquals(before.area(), after.area());
        assertEquals(before.history(), after.history(), "a refusal is kept with what blocked it");
        assertEquals(Claimed.Refusal.DETACHED, after.history().get(1).refusal());
    }

    @Test
    void theStoreCountsAPartyThatHoldsNothingButAHistory() {
        TerritoryData data = new TerritoryData();
        PartyId party = PartyId.random();
        data.territory().claim(party, Set.of(c(0, 0)), Reason.of(Reason.Kind.OP, ""), 1);
        data.territory().releaseAll(party, Reason.of(Reason.Kind.MOVE, ""), 2);
        assertEquals(1, data.actualRows());
        assertTrue(data.isDirty());
    }

    @Test
    void aPartysEndLetsItsGroundGo() {
        PartyData parties = new PartyData();
        Territory territory = new Territory();
        AgentId ari = AgentId.random();
        PartyId party = parties.partyOf(ari);
        territory.claim(party, List.of(c(0, 0)), Reason.of(Reason.Kind.FOUND, ""), 1);
        parties.onDisband(gone -> {
            territory.releaseAll(gone, Reason.of(Reason.Kind.DISBAND, ""), 2);
            territory.forget(gone);
        });
        parties.evict(ari);
        assertTrue(territory.owner(c(0, 0)).isEmpty());
        assertTrue(territory.history(party).isEmpty());
    }
}
