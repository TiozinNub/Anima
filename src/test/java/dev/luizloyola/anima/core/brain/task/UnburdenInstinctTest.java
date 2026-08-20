package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.instinct.UnburdenInstinct;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The safeguard: silent until a settler genuinely cannot carry on, then a spike — and never a bid
 * at all when the pack is full of things somebody asked them to hold.
 */
class UnburdenInstinctTest {

    private static final ItemSpec LOGS = ItemSpec.anyOf(Set.of("minecraft:oak_log"));

    /** A pack with {@code empty} storage slots free and the rest cargo. */
    private static FakeContext packWith(int empty) {
        FakeContext ctx = new FakeContext();
        Inventory pack = ctx.percepts.inventory();
        for (int slot = 0; slot < Inventory.ARMOR_START - empty; slot++) {
            pack.set(slot, ItemStack.of("minecraft:oak_log", 64, 64));
        }
        return ctx;
    }

    @Test
    void roomyPacksSayNothing() {
        assertEquals(0.0, new UnburdenInstinct().pressure(packWith(4)), 0.0001);
        assertEquals(0.0, new UnburdenInstinct().pressure(packWith(20)), 0.0001);
    }

    @Test
    void theLastThreeSlotsWantItButWaitForATaskBoundary() {
        UnburdenInstinct unburden = new UnburdenInstinct();
        FakeContext three = packWith(3);
        double idleFloor = three.profile.d(ProfileAspect.WANDER_IDLE_PRESSURE);
        double preempt = three.profile.d(ProfileAspect.MIND_PREEMPT);

        double atThree = unburden.pressure(three);
        double atOne = unburden.pressure(packWith(1));

        assertTrue(atThree > idleFloor, "over the wander floor, so an idle body goes and stows");
        assertTrue(atOne < preempt, "under mind.preempt, so a working body finishes its errand");
        assertTrue(atOne > atThree, "and it climbs as the room runs out");
    }

    @Test
    void aFullPackCutsWhateverIsRunning() {
        FakeContext ctx = packWith(0);
        assertTrue(new UnburdenInstinct().pressure(ctx) >= ctx.profile.d(ProfileAspect.MIND_PREEMPT),
                "no room at all is the one state worth dropping a half-felled tree for");
    }

    @Test
    void aFullPackOfSpokenForGoodsBidsNothing() {
        FakeContext ctx = packWith(0);
        ctx.reserved = new ArrayList<>(List.of(
                ItemCall.need(LOGS, 64 * Inventory.ARMOR_START)));

        assertEquals(0.0, new UnburdenInstinct().pressure(ctx), 0.0001,
                "a deliberate load is not a burden — there is nothing to shed, and bidding here "
                        + "would take the wheel to run an errand that is already satisfied");
    }

    @Test
    void itRunsTheSameGoalTheStandingProjectPosts() {
        assertInstanceOf(PutAwaySurplus.class, new UnburdenInstinct().root(packWith(0)),
                "one behaviour, two motivations — if these diverge the split is fake");
    }

    @Test
    void itWillNotPayAnyPriceToGetToAChest() {
        FakeContext ctx = packWith(0);
        assertEquals(ctx.profile.d(ProfileAspect.UNBURDEN_TOLERANCE),
                new UnburdenInstinct().costTolerance(ctx), 0.0001,
                "bounded, so a store past the cap prices itself out and building one wins");
    }
}
