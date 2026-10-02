package dev.luizloyola.anima.core.brain.sense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The body's memory of trouble: what it records, how it fades, and what it refuses to grow into. */
class SetbacksTest {

    private final Setbacks setbacks = new Setbacks();

    @Test
    void abodyThatHasNotHadTroubleCostsNothingToAsk() {
        assertSame(SetbackField.NONE, setbacks.field(0));
        assertTrue(setbacks.field(0).isEmpty());
        assertEquals(0.0, setbacks.field(0).at(0, 0, 0));
    }

    @Test
    void aRecordedSetbackWeighsMostWhereItHappened() {
        setbacks.record(new Pos(10, 64, 10), Setbacks.Kind.WEDGED, 0);
        SetbackField field = setbacks.field(0);
        double onIt = field.at(10, 64, 10);
        double nearby = field.at(12, 64, 10);
        double away = field.at(10, 64, 30);
        assertTrue(onIt > nearby, "it falls off with distance");
        assertTrue(nearby > 0.0);
        assertEquals(0.0, away, "and stops mattering entirely past its reach");
    }

    /** Wedged is the strongest thing a leg can report; strayed is the weakest. */
    @Test
    void theKindsAreOrderedByHowMuchTheyProve() {
        assertTrue(Setbacks.Kind.WEDGED.weight() > Setbacks.Kind.STALLED.weight());
        assertTrue(Setbacks.Kind.STALLED.weight() > Setbacks.Kind.STRAYED.weight());
    }

    /**
     * The same cell beating a body twice is worth more than two cells beating it once, and must
     * not cost a second slot to say so.
     */
    @Test
    void aRepeatAtTheSameCellStrengthensRatherThanCrowding() {
        setbacks.record(new Pos(1, 1, 1), Setbacks.Kind.WEDGED, 0);
        double once = setbacks.field(0).at(1, 1, 1);
        setbacks.record(new Pos(1, 1, 1), Setbacks.Kind.WEDGED, 1);
        assertEquals(1, setbacks.snapshot().entries().size(), "one place, not two");
        assertTrue(setbacks.field(1).at(1, 1, 1) > once, "and it counts for more");
    }

    @Test
    void strengthStopsClimbingSoOneUnluckyCornerCannotOutweighEverythingLater() {
        for (int i = 0; i < 50; i++) {
            setbacks.record(new Pos(1, 1, 1), Setbacks.Kind.WEDGED, i);
        }
        assertEquals(4, setbacks.snapshot().entries().get(0).strength());
    }

    /** A place is spent once a repeat there can tell a search nothing new: four of the one kind. */
    @Test
    void aPlaceIsSpentAtFullStrengthOfItsOwnKind() {
        Pos here = new Pos(1, 1, 1);
        for (int i = 0; i < 3; i++) {
            setbacks.record(here, Setbacks.Kind.STALLED, i);
            assertFalse(setbacks.spent(here, Setbacks.Kind.STALLED, i), "a repeat still counts for more");
        }
        setbacks.record(here, Setbacks.Kind.STALLED, 3);
        assertTrue(setbacks.spent(here, Setbacks.Kind.STALLED, 3));
        assertFalse(setbacks.spent(here, Setbacks.Kind.WEDGED, 3), "a wedge here would be news");
        assertFalse(setbacks.spent(new Pos(1, 2, 1), Setbacks.Kind.STALLED, 3), "a cell up is another place");
        assertFalse(setbacks.spent(here, Setbacks.Kind.STALLED, 3 + Setbacks.LIFETIME_TICKS), "forgotten");
    }

    /** The newest news about a place is the truest: a different kind takes the cell over. */
    @Test
    void aDifferentKindAtTheSameCellReplacesTheOldReading() {
        setbacks.record(new Pos(1, 1, 1), Setbacks.Kind.STRAYED, 0);
        setbacks.record(new Pos(1, 1, 1), Setbacks.Kind.WEDGED, 1);
        assertEquals(1, setbacks.snapshot().entries().size());
        assertEquals(Setbacks.Kind.WEDGED, setbacks.snapshot().entries().get(0).kind());
    }

    /**
     * It forgets: trouble that never fades is a body convincing itself the world is impassable,
     * and a doorway somebody opened becomes ordinary on its own — nothing will tell it.
     */
    @Test
    void aSetbackFadesToNothingAndIsThenDropped() {
        setbacks.record(new Pos(1, 1, 1), Setbacks.Kind.WEDGED, 0);
        double fresh = setbacks.field(0).at(1, 1, 1);
        double half = setbacks.field(Setbacks.LIFETIME_TICKS / 2).at(1, 1, 1);
        assertTrue(half > 0.0 && half < fresh, "it thins out on the way");

        assertTrue(setbacks.field(Setbacks.LIFETIME_TICKS).isEmpty(), "and then it is gone");
        assertTrue(setbacks.snapshot().entries().isEmpty(), "not merely weightless — actually dropped");
    }

    @Test
    void theMemoryIsBoundedAndDropsTheOldestFirst() {
        for (int i = 0; i < Setbacks.CAPACITY + 5; i++) {
            setbacks.record(new Pos(i, 1, 1), Setbacks.Kind.WEDGED, i);
        }
        assertEquals(Setbacks.CAPACITY, setbacks.snapshot().entries().size());
        assertFalse(setbacks.snapshot().entries().stream().anyMatch(entry -> entry.at().x() == 0),
                "the first place it had trouble is the first one forgotten");
    }

    /**
     * Ages are absolute ticks across a save, so a memory goes on fading from when it happened
     * rather than being handed a fresh lease by the reload. A world left overnight comes back with
     * nothing held against it.
     */
    @Test
    void restoredTroublesKeepFadingFromWhenTheyHappened() {
        setbacks.record(new Pos(1, 1, 1), Setbacks.Kind.WEDGED, 100);
        Setbacks reloaded = new Setbacks();
        reloaded.restore(setbacks.snapshot());
        assertEquals(setbacks.field(200).at(1, 1, 1), reloaded.field(200).at(1, 1, 1));
        assertTrue(reloaded.field(100 + Setbacks.LIFETIME_TICKS).isEmpty());
    }

    @Test
    void theReadoutNamesTheWorstOfIt() {
        setbacks.record(new Pos(4, 5, 6), Setbacks.Kind.STRAYED, 0);
        setbacks.record(new Pos(1, 2, 3), Setbacks.Kind.WEDGED, 0);
        setbacks.record(new Pos(1, 2, 3), Setbacks.Kind.WEDGED, 1);
        assertEquals("2 place(s), worst wedged ×2 at (1, 2, 3)", setbacks.describe(1));
    }

    /** A move that hurt stays refused for a day, and a save does not forgive it. */
    @Test
    void aRefusedMoveOutlivesASetbackAndASave() {
        Pos takeoff = new Pos(1, 1, 0);
        Pos landing = new Pos(4, 1, 0);
        setbacks.refuse(takeoff, landing, 0);
        assertTrue(setbacks.field(Setbacks.LIFETIME_TICKS).refuses(1, 1, 0, 4, 1, 0));
        assertFalse(setbacks.field(0).refuses(4, 1, 0, 1, 1, 0), "the other way is a different move");

        Setbacks reloaded = new Setbacks();
        reloaded.restore(setbacks.snapshot());
        assertTrue(reloaded.field(1).refuses(1, 1, 0, 4, 1, 0));
        assertFalse(reloaded.field(Setbacks.REFUSED_LIFETIME_TICKS).refuses(1, 1, 0, 4, 1, 0),
                "a day later the world may have changed under it");
    }

    /**
     * Stranded walks from around one spot mark it shut in, roof or no roof — but only from a small
     * region, and only there, and not for ever (Luiz, 2026-09-26). The same goal retried counts: a
     * body in a crevice retried the one tree above it for good.
     */
    @Test
    void strandedWalksFromAroundOneSpotMarkItShutIn() {
        Pos floor = new Pos(0, 74, 0);
        Pos tree = new Pos(10, 85, 0);
        setbacks.stranded(floor, tree, 97, 0);
        setbacks.stranded(new Pos(1, 74, 2), tree, 97, 40);
        assertFalse(setbacks.enclosed(floor, 60), "two is not yet a pattern");
        setbacks.stranded(floor, tree, 97, 80);
        assertTrue(setbacks.enclosed(floor, 100));
        assertFalse(setbacks.enclosed(new Pos(40, 74, 0), 100), "the evidence is about there, not here");

        Setbacks reloaded = new Setbacks();
        reloaded.restore(setbacks.snapshot());
        assertTrue(reloaded.enclosed(floor, 100), "a save does not forgive it");
        assertFalse(reloaded.enclosed(floor, 80 + Setbacks.STRANDED_WINDOW_TICKS),
                "two minutes on, it is forgotten");

        setbacks.stranded(floor, new Pos(50, 64, 50), Setbacks.POCKET_CELLS + 1, 110);
        assertFalse(setbacks.enclosed(floor, 120),
                "a search that got far is proof of open ground, and clears what was gathered");
    }
}
