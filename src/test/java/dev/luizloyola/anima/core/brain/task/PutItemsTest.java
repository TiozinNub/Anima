package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Store;
import org.junit.jupiter.api.Test;

/**
 * {@code deposit(spec, count)}: the third {@link PutItems} factory, for a gather errand that has
 * reserved its cargo and now has to hand it to whatever store {@code EnsureStore} just grew. It
 * resolves its container the way {@link PutItems#stow()} does and selects stacks the way
 * {@link PutItems#of} does — in particular, ignoring {@link dev.luizloyola.anima.core.brain.BrainContext#reserved()}
 * entirely, since the reservation it would be checking is the load THIS errand is delivering.
 */
class PutItemsTest {

    private static final ItemSpec LOGS = ItemSpec.register(
            new ItemSpec("put-items-test-logs", id -> id.endsWith("_log")));

    @Test
    void aDepositResolvesTheStoreItIsStandingAt() {
        FakeContext ctx = new FakeContext();
        Pos chest = new Pos(2, 64, 0);
        ctx.percepts.position = new Pos(2, 64, 1);
        ctx.containers.boxes.put(chest, new java.util.ArrayList<>());
        ctx.knowledge.note(new PoiMemory(Store.POI, chest, Region.of(chest), 1, false, 0L), 64);
        ctx.percepts.inventory().add(ItemStack.of("minecraft:oak_log", 12, 64));

        PutItems deposit = PutItems.deposit(LOGS, 12);
        for (int tick = 0; tick < 40 && ctx.containers.boxes.get(chest).isEmpty(); tick++) {
            deposit.tick(ctx);
        }

        assertEquals(12, ctx.containers.boxes.get(chest).stream()
                .filter(stack -> LOGS.matches(stack.id())).mapToInt(ItemStack::count).sum(),
                "the load goes into the chest the body is standing at, with no anchor named");
    }

    @Test
    void aDepositIgnoresReservationsBecauseItIsTheErrandDeliveringThem() {
        FakeContext ctx = new FakeContext();
        Pos chest = new Pos(2, 64, 0);
        ctx.percepts.position = new Pos(2, 64, 1);
        ctx.containers.boxes.put(chest, new java.util.ArrayList<>());
        ctx.knowledge.note(new PoiMemory(Store.POI, chest, Region.of(chest), 1, false, 0L), 64);
        ctx.percepts.inventory().add(ItemStack.of("minecraft:oak_log", 12, 64));
        ctx.reserved.add(ItemCall.need(LOGS, 12));

        PutItems deposit = PutItems.deposit(LOGS, 12);
        for (int tick = 0; tick < 40 && ctx.containers.boxes.get(chest).isEmpty(); tick++) {
            deposit.tick(ctx);
        }

        assertEquals(12, ctx.containers.boxes.get(chest).stream()
                .filter(stack -> LOGS.matches(stack.id())).mapToInt(ItemStack::count).sum(),
                "a stow protects reserved cargo from being put away; the errand carrying that "
                        + "cargo to the yard is what the reservation exists to get it there");
    }
}
