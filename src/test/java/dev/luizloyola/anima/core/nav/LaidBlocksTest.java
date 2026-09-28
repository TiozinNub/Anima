package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The record of laid blocks: runs, a pillar moved a column over, a crossing walked. */
class LaidBlocksTest {

    @Test
    void aRunLastsAsLongAsOneOfItsBlocks() {
        LaidBlocks laid = new LaidBlocks();
        int run = laid.open(LaidBlocks.Kind.DECK);
        laid.lay(new Pos(1, 0, 0), "minecraft:dirt", LaidBlocks.Kind.DECK, null, null, 5L, run);
        laid.lay(new Pos(2, 0, 0), "minecraft:dirt", LaidBlocks.Kind.DECK, null, null, 6L, run);
        assertTrue(laid.remove(new Pos(1, 0, 0)));
        assertEquals(1, laid.runs().size());
        assertTrue(laid.remove(new Pos(2, 0, 0)));
        assertTrue(laid.runs().isEmpty(), "a run with nothing left in it is gone");
        assertFalse(laid.remove(new Pos(2, 0, 0)));
    }

    @Test
    void aPillarBlockMovedByAClimberStaysInItsRun() {
        LaidBlocks laid = new LaidBlocks();
        int run = laid.open(LaidBlocks.Kind.PILLAR);
        laid.lay(new Pos(1, 3, 1), "minecraft:dirt", LaidBlocks.Kind.PILLAR, null, null, 5L, run);
        laid.move(new Pos(1, 3, 1), new Pos(2, 2, 1), 9L);
        assertTrue(laid.at(new Pos(1, 3, 1)).isEmpty());
        assertEquals(run, laid.at(new Pos(2, 2, 1)).orElseThrow().run());
        assertEquals(9L, laid.at(new Pos(2, 2, 1)).orElseThrow().tick());
    }

    @Test
    void aCrossingCountsItsWalks() {
        LaidBlocks laid = new LaidBlocks();
        int run = laid.open(LaidBlocks.Kind.DECK);
        laid.lay(new Pos(1, 0, 0), "minecraft:dirt", LaidBlocks.Kind.DECK, null, null, 5L, run);
        laid.walked(run, 100L);
        laid.walked(run, 200L);
        assertEquals(2, laid.run(run).walked());
        assertEquals(200L, laid.run(run).lastWalked());
    }

    @Test
    void onlyPillarsInsideTheBoxAreHandedToASearch() {
        LaidBlocks laid = new LaidBlocks();
        int pillar = laid.open(LaidBlocks.Kind.PILLAR);
        int deck = laid.open(LaidBlocks.Kind.DECK);
        laid.lay(new Pos(1, 1, 1), "minecraft:dirt", LaidBlocks.Kind.PILLAR, null, null, 0L, pillar);
        laid.lay(new Pos(50, 1, 1), "minecraft:dirt", LaidBlocks.Kind.PILLAR, null, null, 0L, pillar);
        laid.lay(new Pos(2, 1, 1), "minecraft:dirt", LaidBlocks.Kind.DECK, null, null, 0L, deck);
        assertEquals(Set.of(LaidBlocks.cell(1, 1, 1)), laid.pillarsWithin(0, 0, 0, 10, 10, 10));
    }

    @Test
    void everyChangeIsHeard() {
        LaidBlocks laid = new LaidBlocks();
        int[] changes = {0};
        laid.onChange(() -> changes[0]++);
        int run = laid.open(LaidBlocks.Kind.DECK);
        laid.lay(new Pos(1, 0, 0), "minecraft:dirt", LaidBlocks.Kind.DECK, null, null, 5L, run);
        laid.walked(run, 7L);
        laid.remove(new Pos(1, 0, 0));
        assertEquals(4, changes[0]);
    }
}
