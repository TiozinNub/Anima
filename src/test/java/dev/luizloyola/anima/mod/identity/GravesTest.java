package dev.luizloyola.anima.mod.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The grave store's contract — every clause of it fails silently: a double burial moving a
 * timestamp, a filter that lets the dead back into a listing, a burial that erases itself.
 */
class GravesTest {

    private final Graves graves = new Graves();
    private final AgentId alice = AgentId.random();
    private final AgentId bob = AgentId.random();

    private static Graves.Death at(long tick) {
        return new Graves.Death(tick, "minecraft:overworld", 1, 2, 3, "died");
    }

    @Test
    void nobodyIsDeadUntilTheyAre() {
        assertFalse(graves.isDead(alice));
        assertTrue(graves.deathOf(alice).isEmpty());
        assertEquals(0, graves.size());
    }

    @Test
    void buryingRecordsTheDeath() {
        assertTrue(graves.bury(alice, at(100)));
        assertTrue(graves.isDead(alice));
        assertEquals(100, graves.deathOf(alice).orElseThrow().diedAtTick());
    }

    @Test
    void buryingTwiceKeepsTheFirstAccount() {
        // A body lingers through its death animation, so nothing should bury it twice — and the
        // first record is the true one: a later call would quietly move the moment of death.
        graves.bury(alice, at(100));
        assertFalse(graves.bury(alice, at(999)), "the second burial is not news");
        assertEquals(100, graves.deathOf(alice).orElseThrow().diedAtTick());
    }

    @Test
    void theLivingFilterKeepsOrderAndDropsOnlyTheBuried() {
        graves.bury(bob, at(50));
        AgentId carol = AgentId.random();
        assertEquals(List.of(alice, carol), graves.living(List.of(alice, bob, carol)));
    }

    @Test
    void anEmptyGraveyardFiltersNothing() {
        List<AgentId> everyone = List.of(alice, bob);
        assertEquals(everyone, graves.living(everyone));
    }

    @Test
    void forgettingIsErasureAndUndoesTheBurial() {
        // Only erasure reaches this — a Person unmade by command, never dead. A burial dropping
        // the grave would undo itself, so AgentRecords registers it as surviving death.
        graves.bury(alice, at(100));
        assertTrue(graves.forget(alice));
        assertFalse(graves.isDead(alice), "erased, so never died as far as anything can tell");
        assertFalse(graves.forget(alice), "and forgetting a stranger is not news");
    }
}
