package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link FakeContainers} on its own — every later container task tests against it, so a hole here
 * is a hole in four plans at once.
 */
class FakeContainersTest {

    private static final Pos CELL = new Pos(1, 2, 3);
    private static final ItemSpec LOGS = ItemSpec.anyOf(Set.of("minecraft:oak_log"));

    private final FakeContainers containers = new FakeContainers();

    @Test
    void aPresentButEmptyBoxAnswersAnEmptyList() {
        containers.boxes.put(CELL, new ArrayList<>());

        assertTrue(containers.contents(CELL).isPresent(), "the box is there, just empty");
        assertEquals(List.of(), containers.contents(CELL).orElseThrow());
    }

    @Test
    void anAbsentBoxAnswersAnEmptyOptional() {
        assertTrue(containers.contents(CELL).isEmpty(), "no box was ever put at this cell");
    }

    @Test
    void aFullBoxAcceptsNothing() {
        containers.boxes.put(CELL, new ArrayList<>());
        containers.full.add(CELL);

        int accepted = containers.insert(CELL, ItemStack.of("minecraft:oak_log", 4, 64));

        assertEquals(0, accepted);
        assertEquals(List.of(), containers.boxes.get(CELL), "a refused insert must not land in the box");
    }

    @Test
    void anOutOfReachBoxAnswersNothingInEveryDirection() {
        containers.boxes.put(CELL, new ArrayList<>(List.of(ItemStack.of("minecraft:oak_log", 4, 64))));
        containers.outOfReach.add(CELL);

        assertTrue(containers.contents(CELL).isEmpty(), "reading through the wall");
        assertEquals(0, containers.insert(CELL, ItemStack.of("minecraft:oak_log", 1, 64)),
                "putting through the wall");
        assertEquals(ItemStack.EMPTY, containers.take(CELL, LOGS, 4), "taking through the wall");
    }

    @Test
    void aPartialTakeLeavesTheRemainder() {
        containers.boxes.put(CELL, new ArrayList<>(List.of(ItemStack.of("minecraft:oak_log", 5, 64))));

        ItemStack taken = containers.take(CELL, LOGS, 2);

        assertEquals(2, taken.count());
        assertEquals(1, containers.boxes.get(CELL).size(), "the stack stays, just smaller");
        assertEquals(3, containers.boxes.get(CELL).get(0).count());
    }

    @Test
    void takingTheWholeStackRemovesItRatherThanLeavingAnEmptyOne() {
        containers.boxes.put(CELL, new ArrayList<>(List.of(ItemStack.of("minecraft:oak_log", 3, 64))));

        ItemStack taken = containers.take(CELL, LOGS, 5);

        assertEquals(3, taken.count(), "max exceeded what was there; only what was there comes out");
        assertTrue(containers.boxes.get(CELL).isEmpty(), "nothing left to leave a remainder of");
    }
}
