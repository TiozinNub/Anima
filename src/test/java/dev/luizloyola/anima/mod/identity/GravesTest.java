package dev.luizloyola.anima.mod.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.log.Entry;
import java.util.List;
import java.util.Optional;
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
    void theBlackBoxComesBackAsItWentIn() {
        // JsonOps: the codec under test is ops-agnostic, so this needs no Minecraft — and it is
        // the only test that will notice a field that stopped being written.
        Graves.Death before = new Graves.Death(1200, "minecraft:the_nether", -8, 31, 402,
                "Alice was slain by Zombie", "mob", "Zombie", Optional.of(bob),
                List.of("doing: chopping (oak x3)", "food: 3/20 (saturation 0.0)"),
                List.of(new Entry(1180, Category.BRAIN, "task", "chop -> approach"),
                        new Entry(1199, Category.BODY, "health", "took 6 damage (mob) now 0/20")));

        var encoded = Graves.DEATH_CODEC.encodeStart(JsonOps.INSTANCE, before).getOrThrow();
        Graves.Death after = Graves.DEATH_CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(before, after);
        assertEquals(Optional.of(bob), after.killerId(), "the killer's handle is the queryable half");
        assertEquals("took 6 damage (mob) now 0/20", after.lastWords().get(1).detail(),
                "the last words are the whole reason the grave got bigger");
    }

    @Test
    void aGraveFromBeforeTheBlackBoxLoadsAsTheTombstoneItIs() {
        // The schema-1 shape, verbatim. The black box's fields are all optional so old graves load
        // as what they are instead of failing the store — how a codec silently empties one.
        var old = com.google.gson.JsonParser.parseString("""
                {"tick":900,"dim":"minecraft:overworld","x":1,"y":2,"z":3,"cause":"Alice fell"}""");
        Graves.Death death = Graves.DEATH_CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();

        assertEquals(900, death.diedAtTick());
        assertEquals("Alice fell", death.cause());
        assertEquals("", death.damageType());
        assertEquals(Optional.empty(), death.killerId());
        assertTrue(death.mind().isEmpty());
        assertTrue(death.lastWords().isEmpty());
    }

    @Test
    void aBareTombstoneIsStillAValidGrave() {
        // The six-argument constructor, which every test above builds: it must not leave nulls
        // where lists are expected.
        Graves.Death bare = at(10);
        assertTrue(bare.mind().isEmpty());
        assertTrue(bare.lastWords().isEmpty());
        assertEquals("1, 2, 3", bare.where());
        assertEquals(bare, Graves.DEATH_CODEC.parse(JsonOps.INSTANCE,
                Graves.DEATH_CODEC.encodeStart(JsonOps.INSTANCE, bare).getOrThrow()).getOrThrow());
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
