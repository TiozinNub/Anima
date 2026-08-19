package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Moving things is work: it takes an open, a beat, and a pause per stack — and every stack that
 * lands is a fact in the world, so an interruption costs only the stack in flight.
 */
class TransferTest {

    private static final ItemSpec LOGS = ItemSpec.anyOf(java.util.Set.of("minecraft:oak_log"));
    private static final Pos AT = new Pos(4, 64, 4);

    /** Runs a task to a terminal status, with a bound so a stuck phase fails loudly. */
    private static TaskStatus run(PrimitiveTask task, FakeContext ctx, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            TaskStatus status = task.tick(ctx);
            if (status != TaskStatus.RUNNING) {
                return status;
            }
        }
        throw new AssertionError("still RUNNING after " + maxTicks + " ticks");
    }

    private FakeContext ctxWithBox(List<ItemStack> contents) {
        FakeContext ctx = new FakeContext();
        ctx.containers.boxes.put(AT, new ArrayList<>(contents));
        return ctx;
    }

    @Test
    void takingReadsTheBeliefTheMomentItOpens() {
        FakeContext ctx = ctxWithBox(List.of(ItemStack.of("minecraft:oak_log", 5, 64)));
        ctx.percepts.time = 500L;
        TakeItems take = new TakeItems(AT, LOGS, 5);

        // The open pause, then one more tick: the belief must exist before anything has moved.
        int open = ctx.profile.i(dev.luizloyola.anima.core.agent.ProfileAspect.HANDLING_OPEN_TICKS);
        for (int i = 0; i <= open + 1; i++) {
            take.tick(ctx);
        }
        assertTrue(ctx.knowledge.insideOf(AT).isPresent(),
                "yanked away one tick later, the settler still knows what was in there");
    }

    @Test
    void aFullPassMovesEveryStackAndSucceeds() {
        FakeContext ctx = ctxWithBox(List.of(ItemStack.of("minecraft:oak_log", 5, 64)));
        assertEquals(TaskStatus.SUCCESS, run(new TakeItems(AT, LOGS, 5), ctx, 2000));
        assertEquals(5, ctx.percepts.inventory().count(LOGS.matcher()));
    }

    @Test
    void takingWhatIsThereRatherThanWhatWasRememberedStillSucceeds() {
        FakeContext ctx = ctxWithBox(List.of(ItemStack.of("minecraft:oak_log", 4, 64)));
        assertEquals(TaskStatus.SUCCESS, run(new TakeItems(AT, LOGS, 16), ctx, 2000),
                "four logs is four logs; the parent re-resolves for the rest");
        assertEquals(4, ctx.percepts.inventory().count(LOGS.matcher()));
    }

    @Test
    void anEmptyContainerFailsAndCorrectsTheBelief() {
        FakeContext ctx = ctxWithBox(List.of());
        ctx.percepts.time = 700L;
        assertEquals(TaskStatus.FAILED, run(new TakeItems(AT, LOGS, 16), ctx, 2000));
        assertEquals(0, ctx.knowledge.insideOf(AT).orElseThrow().count(LOGS),
                "the wasted trip is what teaches it");
    }

    @Test
    void takingWithAFullPackFailsWithoutDeletingTheStack() {
        // ContainerAccess.take has already removed the stack from the world by the time the pack
        // is asked to hold it; a full pack must push what does not fit back into the chest, not
        // let it vanish between the two calls.
        FakeContext ctx = ctxWithBox(List.of(ItemStack.of("minecraft:oak_log", 5, 64)));
        for (int slot = 0; slot < Inventory.MAIN_START + Inventory.MAIN_SIZE; slot++) {
            ctx.percepts.inventory().set(slot, ItemStack.of("minecraft:dirt", 64, 64));
        }

        assertEquals(TaskStatus.FAILED, run(new TakeItems(AT, LOGS, 5), ctx, 2000));
        assertEquals(0, ctx.percepts.inventory().count(LOGS.matcher()),
                "nothing fit, so nothing should have been added");
        assertEquals(5, ctx.containers.boxes.get(AT).stream().mapToInt(ItemStack::count).sum(),
                "what came out with nowhere to go must go back, not vanish");
    }

    @Test
    void aRefusedPutBackIsAccountedForRatherThanCounted() {
        // insert's return on the put-back path is not guaranteed either: a real container can
        // let take() succeed and still refuse the same stack coming back — a furnace's OUTPUT
        // slot is the concrete case (canPlaceItem lets an item out, not in). There is no
        // drop-on-ground actuator today, so this is genuinely lost — it must not be counted as
        // moved, and it must not vanish with no trace either.
        FakeContext ctx = ctxWithBox(List.of(ItemStack.of("minecraft:oak_log", 5, 64)));
        ctx.containers.full.add(AT); // take() ignores full; insert() (the put-back) does not
        for (int slot = 0; slot < Inventory.MAIN_START + Inventory.MAIN_SIZE; slot++) {
            ctx.percepts.inventory().set(slot, ItemStack.of("minecraft:dirt", 64, 64));
        }

        assertEquals(TaskStatus.FAILED, run(new TakeItems(AT, LOGS, 5), ctx, 2000));
        assertEquals(0, ctx.percepts.inventory().count(LOGS.matcher()),
                "nothing fit, so nothing should have been credited as taken");
        boolean journaled = ctx.journalService.recent(ctx.journal().id(), Integer.MAX_VALUE)
                .stream().anyMatch(entry -> entry.detail().contains("lost"));
        assertTrue(journaled, "a stranded stack must say so, not vanish with no trace");
    }

    @Test
    void aMissingContainerFailsWithoutInventingABelief() {
        FakeContext ctx = new FakeContext();
        assertEquals(TaskStatus.FAILED, run(new TakeItems(AT, LOGS, 1), ctx, 2000));
        assertTrue(ctx.knowledge.insideOf(AT).isEmpty());
    }

    @Test
    void interruptingMidMoveKeepsWhatAlreadyLanded() {
        FakeContext ctx = ctxWithBox(List.of(
                ItemStack.of("minecraft:oak_log", 64, 64), ItemStack.of("minecraft:oak_log", 64, 64)));
        int open = ctx.profile.i(ProfileAspect.HANDLING_OPEN_TICKS);
        int settle = ctx.profile.i(ProfileAspect.HANDLING_SETTLE_TICKS);
        int stack = ctx.profile.i(ProfileAspect.HANDLING_STACK_TICKS);
        TakeItems take = new TakeItems(AT, LOGS, 128);
        // The first stack lands at open+settle+stack; stop partway into the second stack's own
        // pause, so one has landed and the other has not — the property atomicity rests on.
        int midSecondStack = open + settle + stack + Math.max(1, stack / 2);
        for (int i = 0; i < midSecondStack; i++) {
            take.tick(ctx);
        }
        assertEquals(64, ctx.percepts.inventory().count(LOGS.matcher()),
                "one stack has landed in the pack");
        assertEquals(64, ctx.containers.boxes.get(AT).stream().mapToInt(ItemStack::count).sum(),
                "the other is still sitting in the chest, not half-taken");

        int carried = ctx.percepts.inventory().count(LOGS.matcher());
        take.cancel(ctx);
        assertEquals(carried, ctx.percepts.inventory().count(LOGS.matcher()),
                "cancel releases; it never claws back a stack that already moved");
    }

    @Test
    void oneMoreUnitCostsOneMorePauseWhateverTheItemIs() {
        // The rule is ceil(count / maxStackSize), so crossing a slot boundary always costs exactly
        // one more grab. Asserted as the DIFFERENCE between runs, which pins the arithmetic without
        // pinning brittle totals — and checked at all three stack sizes the game actually has.
        int perStack = new FakeContext()
                .profile.i(dev.luizloyola.anima.core.agent.ProfileAspect.HANDLING_STACK_TICKS);

        assertEquals(perStack, ticks("minecraft:stick", 128, 64) - ticks("minecraft:stick", 64, 64),
                "the 65th stick is a second grab");
        assertEquals(perStack, ticks("minecraft:stick", 129, 64) - ticks("minecraft:stick", 128, 64),
                "and the 129th starts a third, however little is in it");
        assertEquals(perStack,
                ticks("minecraft:iron_sword", 3, 1) - ticks("minecraft:iron_sword", 2, 1),
                "a sword does not stack, so every sword is its own grab");
        assertEquals(perStack, ticks("minecraft:egg", 17, 16) - ticks("minecraft:egg", 16, 16),
                "eggs stop at sixteen, so the seventeenth starts another");
    }

    @Test
    void oneSlotsWorthCostsTheSameWhateverIsInIt() {
        assertEquals(ticks("minecraft:stick", 64, 64), ticks("minecraft:egg", 16, 16),
                "64 sticks and 16 eggs are each one slot, so each is one grab");
        assertEquals(ticks("minecraft:stick", 64, 64), ticks("minecraft:iron_sword", 1, 1),
                "and so is a single sword");
    }

    /** Ticks a full take costs, from a container holding exactly {@code count} of one thing. */
    private int ticks(String id, int count, int maxStack) {
        FakeContext ctx = new FakeContext();
        ctx.containers.boxes.put(AT, new ArrayList<>(List.of(ItemStack.of(id, count, maxStack))));
        TakeItems take = new TakeItems(AT, ItemSpec.anyOf(java.util.Set.of(id)), count);
        for (int elapsed = 1; elapsed <= 5000; elapsed++) {
            if (take.tick(ctx) != TaskStatus.RUNNING) {
                return elapsed;
            }
        }
        throw new AssertionError("still RUNNING after 5000 ticks");
    }

    @Test
    void puttingIntoAFullContainerFailsAndMarksItAvoided() {
        FakeContext ctx = ctxWithBox(List.of());
        ctx.containers.full.add(AT);
        ctx.percepts.time = 100L;
        ctx.percepts.inventory().add(ItemStack.of("minecraft:oak_log", 8, 64));

        assertEquals(TaskStatus.FAILED, run(new PutItems(AT, LOGS, 8), ctx, 2000));
        assertTrue(ctx.knowledge.isAvoided(dev.luizloyola.anima.core.store.Store.POI, AT, 101L),
                "the belief is right — it is full of real things — so only a timer un-blinds it");
    }

    @Test
    void puttingMovesStacksIntoTheContainer() {
        FakeContext ctx = ctxWithBox(List.of());
        ctx.percepts.inventory().add(ItemStack.of("minecraft:oak_log", 8, 64));
        assertEquals(TaskStatus.SUCCESS, run(new PutItems(AT, LOGS, 8), ctx, 2000));
        assertEquals(0, ctx.percepts.inventory().count(LOGS.matcher()));
        assertEquals(8, ctx.containers.boxes.get(AT).get(0).count());
    }

    @Test
    void puttingPartiallyPutsBackWhatTheBoxCouldNotHold() {
        // The exact case FakeContainers.capacity was added for: a partial accept must not vanish
        // the remainder.
        FakeContext ctx = ctxWithBox(List.of());
        ctx.containers.capacity.put(AT, 3);
        ctx.percepts.inventory().add(ItemStack.of("minecraft:oak_log", 5, 64));

        assertEquals(TaskStatus.SUCCESS, run(new PutItems(AT, LOGS, 5), ctx, 2000));
        assertEquals(3, ctx.containers.boxes.get(AT).stream().mapToInt(ItemStack::count).sum(),
                "the box took what it had room for");
        assertEquals(2, ctx.percepts.inventory().count(LOGS.matcher()),
                "the two it refused came back to the pack rather than vanishing");
    }

    @Test
    void puttingWithAnEmptyPackFailsWithoutBlindingAGoodStore() {
        // Arriving with nothing to put says nothing about the store — only a genuine refusal of
        // a real stack earns the avoidance timer.
        FakeContext ctx = ctxWithBox(List.of());
        ctx.percepts.time = 100L;

        assertEquals(TaskStatus.FAILED, run(new PutItems(AT, LOGS, 8), ctx, 2000));
        assertFalse(ctx.knowledge.isAvoided(dev.luizloyola.anima.core.store.Store.POI, AT, 101L),
                "an empty pack is not a full store");
    }

    @Test
    void puttingWhenShovedOutOfReachFailsWithoutBlindingAGoodStore() {
        // Reachable through OPEN so the errand actually reaches MOVE, then yanked out of reach —
        // the OTHER reason insert can return 0, and the one contents(at).isPresent() must catch.
        FakeContext ctx = ctxWithBox(List.of());
        ctx.percepts.inventory().add(ItemStack.of("minecraft:oak_log", 8, 64));
        ctx.percepts.time = 100L;
        int open = ctx.profile.i(ProfileAspect.HANDLING_OPEN_TICKS);
        int settle = ctx.profile.i(ProfileAspect.HANDLING_SETTLE_TICKS);
        PutItems put = new PutItems(AT, LOGS, 8);
        for (int i = 0; i < open + settle; i++) {
            put.tick(ctx);
        }
        ctx.containers.outOfReach.add(AT);

        assertEquals(TaskStatus.FAILED, run(put, ctx, 2000));
        assertFalse(ctx.knowledge.isAvoided(dev.luizloyola.anima.core.store.Store.POI, AT, 101L),
                "out of reach is a wrong belief about distance, not a full store");
    }
}
