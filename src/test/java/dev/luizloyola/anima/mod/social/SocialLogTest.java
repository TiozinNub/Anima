package dev.luizloyola.anima.mod.social;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class SocialLogTest {

    @Test
    void aLineIsTickRecordKindAndText() {
        assertEquals("[53739607] e7f3a1c0 open   Tia + TiozinNub, Tia hailed",
                SocialLog.line(53739607L, "e7f3a1c0", SocialLog.OPEN, "Tia + TiozinNub, Tia hailed"));
    }

    @Test
    void aLineOutsideARecordHasDashesForItsTag() {
        assertEquals("[5] -------- hail   Tia -> Bram, called out, 6.2 blocks",
                SocialLog.line(5L, null, SocialLog.HAIL, "Tia -> Bram, called out, 6.2 blocks"));
    }

    /** The kind column is padded to the longest kind, so a kind that outgrew it would skew every line. */
    @Test
    void everyKindIsDistinctAndFitsItsColumn() {
        assertEquals(SocialLog.KINDS.size(), new HashSet<>(SocialLog.KINDS).size());
        for (String kind : SocialLog.KINDS) {
            assertEquals(20, SocialLog.line(0L, null, kind, "x").indexOf('x'), kind);
        }
    }
}
