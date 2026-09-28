package dev.luizloyola.anima.core.brain.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.task.FakeDoings;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What a body did lately: distinct deeds, newest first, bounded in count and in age. */
class HistoryTest {

    private static final Deed FLED_ZOMBIE = Deed.of(Doings.FLEEING, Slot.entity("zombie"));
    private static final Deed FLED_SPIDER = Deed.of(Doings.FLEEING, Slot.entity("spider"));
    private static final Deed ATE = Deed.of(Doings.EATING);

    private final History history = new History();

    @Test
    void aRepeatMergesIntoOneEntryAtTheFront() {
        history.record(FLED_ZOMBIE, 100);
        history.record(ATE, 200);
        history.record(FLED_ZOMBIE, 300);

        List<History.Entry> recent = history.recent(300);
        assertEquals(2, recent.size(), "twenty log trips for one yard are one thing to talk about");
        assertEquals(FLED_ZOMBIE, recent.get(0).deed(), "and the repeat is the newest");
        assertEquals(300, recent.get(0).lastTick());
        assertEquals(2, recent.get(0).times());
        assertEquals(ATE, recent.get(1).deed());
    }

    @Test
    void theSameDoingWithDifferentSlotsIsADifferentDeed() {
        history.record(FLED_ZOMBIE, 100);
        history.record(FLED_SPIDER, 200);

        assertEquals(2, history.recent(200).size(),
                "running from a zombie and from a spider are two stories");
    }

    @Test
    void aDoingThatIsNotRememberedIsDropped() {
        history.record(Deed.of(Doings.WANDERING), 100);
        history.record(Deed.of(Doings.TALKING), 100);
        history.record(Deed.of(FakeDoings.IDLED), 100);

        assertTrue(history.recent(100).isEmpty(), "idling, and a name nobody was told, are not news");
    }

    @Test
    void theOldestFallsOffPastTheCap() {
        for (int i = 0; i < History.CAPACITY + 2; i++) {
            history.record(Deed.of(Doings.FLEEING, Slot.entity("mob" + i)), i);
        }
        List<History.Entry> recent = history.recent(History.CAPACITY + 2);
        assertEquals(History.CAPACITY, recent.size());
        assertEquals(Slot.entity("mob" + (History.CAPACITY + 1)), recent.get(0).deed().slots().get(0));
    }

    @Test
    void anythingOlderThanLatelyIsNotRecent() {
        history.record(ATE, 0);
        history.record(FLED_ZOMBIE, 10);

        List<History.Entry> recent = history.recent(History.MAX_AGE_TICKS + 5);
        assertEquals(List.of(FLED_ZOMBIE), recent.stream().map(History.Entry::deed).toList(),
                "three days on, the meal is forgotten and the zombie is not yet");
        assertEquals(2, history.snapshot().size(),
                "reading ages the answer, not the store — a save still carries both until a write");
    }

    @Test
    void restoreTrustsTheOrderAndReappliesTheCap() {
        List<History.Entry> saved = new java.util.ArrayList<>();
        for (int i = 0; i < History.CAPACITY + 3; i++) {
            saved.add(new History.Entry(ATE, i, 1));
        }
        history.restore(saved);
        assertEquals(History.CAPACITY, history.snapshot().size());
        assertEquals(0, history.snapshot().get(0).lastTick(), "newest first, as written");
    }

    @Test
    void whenBucketsTheAge() {
        assertEquals(When.JUST_NOW, When.of(0));
        assertEquals(When.JUST_NOW, When.of(2_399));
        assertEquals(When.EARLIER, When.of(2_400));
        assertEquals(When.EARLIER, When.of(23_999));
        assertEquals(When.YESTERDAY, When.of(24_000));
        assertEquals(When.DAYS_AGO, When.of(48_000));
        assertEquals(When.DAYS_AGO, When.of(Long.MAX_VALUE));
    }

    @Test
    void aDeedTakesExactlyItsDoingsSlots() {
        assertThrows(IllegalArgumentException.class, () -> Deed.of(Doings.FLEEING),
                "a flee with nothing to have fled from cannot be said");
        assertThrows(IllegalArgumentException.class,
                () -> Deed.of(Doings.EATING, Slot.lang("anima.doing.something")));
    }

    @Test
    void aSlotRoundTripsThroughItsString() {
        for (Slot slot : List.of(Slot.lang("autarkia.purpose.yard"), Slot.item("minecraft:oak_log"),
                Slot.entity("zombie"), Slot.entity("somemod:thing"), Slot.name("Luiz"))) {
            assertEquals(Optional.of(slot), Slot.decode(slot.encode()), slot.encode());
        }
        assertEquals(Optional.empty(), Slot.decode("no-prefix"));
        assertEquals(Optional.empty(), Slot.decode("colour:red"), "an unknown type, not a guess");
    }
}
