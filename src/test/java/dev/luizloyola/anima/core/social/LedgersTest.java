package dev.luizloyola.anima.core.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.TestSpecies;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/** The ledgers' arithmetic: standing lines read fresh, fading lines weighed by the reader. */
class LedgersTest {

    private static final long DAY = Ledgers.DAY_TICKS;
    private static final String NEWCOMER = "test.newcomer";

    private final Ledgers ledgers = new Ledgers();
    private final PartyId party = PartyId.random();
    private final AgentProfile reader = TestSpecies.PROFILE; // newcomer −10 over 3 days

    LedgersTest() {
        ledgers.fades(new Ledgers.Fading(Ledger.OPENNESS, NEWCOMER,
                ProfileAspect.SOCIAL_OPENNESS_NEWCOMER, ProfileAspect.SOCIAL_OPENNESS_NEWCOMER_DAYS));
    }

    private double newcomers(long now) {
        return ledgers.read(Ledger.OPENNESS, party, reader, now).total();
    }

    @Test
    void aMarkFadesLinearlyOverItsDays() {
        ledgers.mark(party, Ledger.OPENNESS, NEWCOMER, 0);
        assertEquals(-10.0, newcomers(0), 1e-9);
        assertEquals(-5.0, newcomers(DAY * 3 / 2), 1e-9);
        assertTrue(ledgers.read(Ledger.OPENNESS, party, reader, 3 * DAY).lines().isEmpty(),
                "a spent mark says nothing at all");
    }

    @Test
    void marksUnderOneKeySumIntoOneLine() {
        ledgers.mark(party, Ledger.OPENNESS, NEWCOMER, 0);
        ledgers.mark(party, Ledger.OPENNESS, NEWCOMER, 0);
        Ledgers.Line line = ledgers.read(Ledger.OPENNESS, party, reader, 0).lines().get(0);
        assertEquals(2, line.count());
        assertEquals(-20.0, line.points(), 1e-9);
    }

    @Test
    void theReaderWeighsTheMark() {
        ledgers.mark(party, Ledger.OPENNESS, NEWCOMER, 0);
        AgentProfile wary = TestSpecies.with(ProfileAspect.SOCIAL_OPENNESS_NEWCOMER, -30.0);
        assertEquals(-30.0, ledgers.read(Ledger.OPENNESS, party, wary, 0).total(), 1e-9);
    }

    @Test
    void aLedgerReadsOnlyItsOwnLines() {
        ledgers.mark(party, Ledger.OPENNESS, NEWCOMER, 0);
        assertTrue(ledgers.read(Ledger.JOINING, party, reader, 0).lines().isEmpty());
    }

    @Test
    void standingLinesComeFirstAndTheWorstIsNamed() {
        ledgers.stands(standing(Ledger.OPENNESS, "test.food", 6.0));
        ledgers.stands(standing(Ledger.OPENNESS, "test.silent", Double.NaN));
        ledgers.mark(party, Ledger.OPENNESS, NEWCOMER, 0);

        Ledgers.Reading reading = ledgers.read(Ledger.OPENNESS, party, reader, 0);

        assertEquals(List.of("test.food", NEWCOMER), reading.lines().stream().map(Ledgers.Line::key).toList());
        assertEquals(-4.0, reading.total(), 1e-9);
        assertEquals(NEWCOMER, reading.strongestAgainst().orElseThrow().key());
        assertFalse(reading.lines().get(0).fading());
    }

    @Test
    void spentMarksAreDroppedWhenTheNextIsLeft() {
        ledgers.mark(party, Ledger.OPENNESS, NEWCOMER, 0);
        ledgers.mark(party, Ledger.OPENNESS, "test.unknown", 0);
        long past = (long) ProfileAspect.SOCIAL_OPENNESS_NEWCOMER_DAYS.max() * DAY + 1;
        ledgers.mark(party, Ledger.OPENNESS, NEWCOMER, past);
        assertEquals(List.of("test.unknown", NEWCOMER),
                ledgers.marks(party).stream().map(Ledgers.Mark::key).toList(),
                "past what any species could weigh it, a mark goes; one with no rule yet stays");
    }

    @Test
    void aPartyThatEndsTakesItsMarks() {
        ledgers.mark(party, Ledger.OPENNESS, NEWCOMER, 0);
        assertTrue(ledgers.forget(party));
        assertTrue(ledgers.marks(party).isEmpty());
        assertFalse(ledgers.forget(party));
    }

    @Test
    void companyIsTheMeanOfLonelyAgainstCrowded() {
        assertEquals(0.25, Ledgers.companyScore(List.of("lonely", "lonely", "content", "crowded")).getAsDouble(), 1e-9);
        assertEquals(0.0, Ledgers.companyScore(List.of("alone", "content")).getAsDouble(), 1e-9);
        assertTrue(Ledgers.companyScore(List.of()).isEmpty(), "nobody loaded to ask");
    }

    private static Ledgers.Standing standing(Ledger ledger, String key, double points) {
        return new Ledgers.Standing() {
            @Override
            public Ledger ledger() {
                return ledger;
            }

            @Override
            public String key() {
                return key;
            }

            @Override
            public OptionalDouble points(PartyId party, AgentProfile reader) {
                return Double.isNaN(points) ? OptionalDouble.empty() : OptionalDouble.of(points);
            }
        };
    }
}
