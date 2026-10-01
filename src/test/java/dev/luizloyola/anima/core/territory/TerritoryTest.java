package dev.luizloyola.anima.core.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.social.PartyId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TerritoryTest {

    private static final PartyId ARI = PartyId.random();
    private static final PartyId BIA = PartyId.random();
    private static final Reason WHY = Reason.of(Reason.Kind.OP, "test");

    private static ChunkKey c(int x, int z) {
        return new ChunkKey(ChunkKey.OVERWORLD, x, z);
    }

    private static Set<ChunkKey> square(int x0, int z0, int x1, int z1) {
        Set<ChunkKey> chunks = new TreeSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                chunks.add(c(x, z));
            }
        }
        return chunks;
    }

    @Test
    @DisplayName("a first claim takes exactly what it asks for")
    void firstClaim() {
        Territory territory = new Territory();
        Claimed event = territory.claim(ARI, square(0, 0, 1, 1), WHY, 10);
        assertTrue(event.granted());
        assertEquals(square(0, 0, 1, 1), territory.area(ARI));
        assertEquals(ARI, territory.owner(c(1, 1)).orElseThrow());
    }

    @Test
    @DisplayName("a claim in two pieces, or apart from the area, is refused whole")
    void detached() {
        Territory territory = new Territory();
        assertFalse(territory.claim(ARI, Set.of(c(0, 0), c(2, 0)), WHY, 0).granted());
        assertTrue(territory.area(ARI).isEmpty(), "nothing taken from a refused call");

        territory.claim(ARI, Set.of(c(0, 0)), WHY, 0);
        Claimed apart = territory.claim(ARI, Set.of(c(2, 0)), WHY, 0);
        assertEquals(Claimed.Refusal.DETACHED, apart.refusal());
        Claimed corner = territory.claim(ARI, Set.of(c(1, 1)), WHY, 0);
        assertEquals(Claimed.Refusal.DETACHED, corner.refusal(), "a corner alone does not join");
        assertTrue(territory.claim(ARI, Set.of(c(1, 0)), WHY, 0).granted());
    }

    @Test
    @DisplayName("a chunk is one party's: another's claim on it is refused, naming it")
    void otherParty() {
        Territory territory = new Territory();
        territory.claim(ARI, square(0, 0, 1, 1), WHY, 0);
        Claimed event = territory.claim(BIA, square(1, 0, 2, 0), WHY, 0);
        assertEquals(Claimed.Refusal.OTHER_PARTY, event.refusal());
        assertEquals(Set.of(c(1, 0)), event.blocking());
        assertTrue(territory.area(BIA).isEmpty());
    }

    @Test
    @DisplayName("a taken chunk is refused, and the hook is never asked about our own")
    void taken() {
        Territory territory = new Territory();
        territory.claim(ARI, Set.of(c(0, 0)), WHY, 0);
        territory.takenBy(chunk -> !chunk.equals(c(5, 5)) && chunk.x() <= 1);
        assertEquals(Claimed.Refusal.TAKEN, territory.claim(ARI, Set.of(c(1, 0)), WHY, 0).refusal());
        assertTrue(territory.claim(ARI, Set.of(c(0, 0)), WHY, 0).granted());
    }

    @Test
    @DisplayName("growing takes the footprint and a ring round it")
    void growWithMargin() {
        Territory territory = new Territory();
        territory.claim(ARI, Set.of(c(0, 0)), WHY, 0);
        Claimed event = territory.grow(ARI, Set.of(c(2, 0)), 1, WHY, 0);
        assertTrue(event.granted(), "one chunk off the area, joined by its margin");
        assertEquals(square(1, -1, 3, 1), event.added());
    }

    @Test
    @DisplayName("a footprint two chunks off is refused even with its margin")
    void tooFar() {
        Territory territory = new Territory();
        territory.claim(ARI, Set.of(c(0, 0)), WHY, 0);
        assertEquals(Claimed.Refusal.DETACHED,
                territory.grow(ARI, Set.of(c(3, 0)), 1, WHY, 0).refusal());
    }

    @Test
    @DisplayName("the margin skips another party's chunks, and a corner left hanging by them")
    void marginSkips() {
        Territory fresh = new Territory();
        fresh.claim(BIA, Set.of(c(1, 0), c(1, -1), c(0, -1), c(-1, -1)), WHY, 0);
        fresh.claim(BIA, Set.of(c(-1, 0), c(-1, 1)), WHY, 0);
        Claimed event = fresh.grow(ARI, Set.of(c(0, 0)), 1, WHY, 0);
        assertTrue(event.granted());
        // Left: (0,1) by edge; (1,1) by edge from (0,1). Nothing of Bia's.
        assertEquals(Set.of(c(0, 0), c(0, 1), c(1, 1)), event.added());
    }

    @Test
    @DisplayName("a ring chunk joined only by a corner is dropped")
    void cornerDropped() {
        Territory territory = new Territory();
        // (1, -1) is free, but both its ways in are held, by two parties since one would join them.
        territory.claim(BIA, Set.of(c(1, 0)), WHY, 0);
        territory.claim(PartyId.random(), Set.of(c(0, -1)), WHY, 0);
        Claimed event = territory.grow(ARI, Set.of(c(0, 0)), 1, WHY, 0);
        assertTrue(event.granted());
        assertFalse(event.added().contains(c(1, -1)), "hung on by a corner: " + event.added());
        assertTrue(event.added().contains(c(1, 1)), "reached through (0, 1)");
    }

    @Test
    @DisplayName("a release that would split the area is refused; a release of all is not")
    void release() {
        Territory territory = new Territory();
        territory.claim(ARI, square(0, 0, 2, 0), WHY, 0);
        assertEquals(Claimed.Refusal.DETACHED,
                territory.release(ARI, Set.of(c(1, 0)), WHY, 0).refusal());
        assertEquals(Claimed.Refusal.NOT_OURS,
                territory.release(ARI, Set.of(c(9, 9)), WHY, 0).refusal());
        assertTrue(territory.release(ARI, Set.of(c(2, 0)), WHY, 0).granted());
        assertTrue(territory.releaseAll(ARI, WHY, 0).granted());
        assertTrue(territory.parties().isEmpty());
        assertTrue(territory.owner(c(0, 0)).isEmpty());
    }

    @Test
    @DisplayName("a move swaps the area in one event, and a refused one keeps the old ground")
    void move() {
        Territory territory = new Territory();
        territory.claim(ARI, square(0, 0, 1, 1), WHY, 0);
        territory.claim(BIA, Set.of(c(10, 10)), WHY, 0);
        Claimed refused = territory.move(ARI, square(9, 10, 10, 10), WHY, 1);
        assertEquals(Claimed.Refusal.OTHER_PARTY, refused.refusal());
        assertEquals(square(0, 0, 1, 1), territory.area(ARI));

        Claimed moved = territory.move(ARI, square(1, 1, 2, 2), WHY, 2);
        assertTrue(moved.granted());
        assertEquals(square(1, 1, 2, 2), territory.area(ARI));
        assertEquals(Set.of(c(0, 0), c(0, 1), c(1, 0)), moved.removed());
        assertEquals(Set.of(c(1, 2), c(2, 1), c(2, 2)), moved.added());
        assertTrue(territory.owner(c(0, 0)).isEmpty());
        assertEquals(Claimed.Refusal.DETACHED,
                territory.move(ARI, Set.of(c(5, 5), c(7, 7)), WHY, 3).refusal());
    }

    @Test
    @DisplayName("each dimension is its own piece")
    void dimensions() {
        Territory territory = new Territory();
        ChunkKey nether = new ChunkKey("minecraft:the_nether", 40, 40);
        assertTrue(territory.claim(ARI, Set.of(c(0, 0), nether), WHY, 0).granted());
    }

    @Test
    @DisplayName("listeners hear changes and refusals, not a claim of what is already held")
    void events() {
        Territory territory = new Territory();
        List<Claimed> heard = new ArrayList<>();
        territory.onEvent(heard::add);
        territory.claim(ARI, Set.of(c(0, 0)), WHY, 1);
        territory.claim(ARI, Set.of(c(0, 0)), WHY, 2);
        territory.claim(ARI, Set.of(c(5, 5)), WHY, 3);
        assertEquals(2, heard.size());
        assertEquals(2, territory.history(ARI).size());
        assertFalse(heard.get(1).granted());
    }

    @Test
    @DisplayName("a plan changes nothing and tells nobody")
    void plan() {
        Territory territory = new Territory();
        List<Claimed> heard = new ArrayList<>();
        territory.onEvent(heard::add);
        Claimed plan = territory.planGrow(ARI, Set.of(c(0, 0)), 1, WHY, 0);
        assertEquals(9, plan.added().size());
        assertTrue(territory.area(ARI).isEmpty());
        assertTrue(heard.isEmpty());
    }

    @Test
    @DisplayName("the history keeps the newest events up to its cap")
    void historyCap() {
        Territory territory = new Territory();
        territory.keeps(() -> 3);
        for (int i = 0; i < 5; i++) {
            territory.claim(ARI, Set.of(c(i, 0)), WHY, i);
        }
        List<Claimed> history = territory.history(ARI);
        assertEquals(3, history.size());
        assertEquals(2, history.get(0).tick());
    }
}
