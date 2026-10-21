package dev.luizloyola.anima.mod.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.social.Ledger;
import dev.luizloyola.anima.core.social.Ledgers;
import dev.luizloyola.anima.core.social.PartyId;
import org.junit.jupiter.api.Test;

/** An arrival's marks, and that they survive a save — a restart changes nothing. */
class LedgerDataTest {

    @Test
    void anArrivalMarksEachNewcomerAndOneMerge() {
        LedgerData data = new LedgerData();
        PartyId party = PartyId.random();
        data.setDirty(false);

        data.arrived(party, 2, 100L);

        Ledgers.Reading openness = data.ledgers().read(Ledger.OPENNESS, party, TestSpecies.PROFILE, 100L);
        Ledgers.Reading joining = data.ledgers().read(Ledger.JOINING, party, TestSpecies.PROFILE, 100L);
        assertEquals(2, openness.lines().get(0).count());
        assertEquals(-20.0, openness.total(), 1e-9);
        assertEquals(-15.0, joining.total(), 1e-9, "a merge of two is still one merge");
        assertTrue(data.isDirty());
    }

    @Test
    void marksReadBackAsTheyWereLeft() {
        LedgerData data = new LedgerData();
        PartyId party = PartyId.random();
        data.arrived(party, 1, 4_000L);

        var json = LedgerData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        LedgerData back = LedgerData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

        assertEquals(data.ledgers().marks(party), back.ledgers().marks(party));
        assertEquals(-10.0 * (1 - 20_000.0 / (3 * Ledgers.DAY_TICKS)),
                back.ledgers().read(Ledger.OPENNESS, party, TestSpecies.PROFILE, 24_000L).total(), 1e-9,
                "and the fading rules come with the store, not with the save");
    }
}
