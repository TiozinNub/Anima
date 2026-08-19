package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import java.util.Optional;

/**
 * Take up to {@code count} of {@code spec} out of the container at {@code at} — an open, a beat, and
 * one pause per stack.
 *
 * <p><b>SUCCEEDS on a partial take.</b> Finding four where sixteen were remembered is four logs, not a
 * failure; the parent's satisfied-check drives another round for the rest. FAILS for three reasons
 * instead: nothing to open at all (which drops a claim the world no longer backs), a container with
 * nothing left matching {@code spec} (which corrects the belief), and a pack with no room, which is
 * checked BEFORE the world is touched so a take can never over-reach what the pack can carry.
 */
public final class TakeItems implements PrimitiveTask {

    private final Pos at;
    private final ItemSpec spec;
    private final int count;

    private HandlingPhase phase = HandlingPhase.OPEN;
    private final Pause pause = new Pause();
    private int moved;

    public TakeItems(Pos at, ItemSpec spec, int count) {
        this.at = at;
        this.spec = spec;
        this.count = Math.max(1, count);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        switch (phase) {
            case OPEN -> {
                if (pause.idle()) {
                    if (ctx.actuators().containers().contents(at).isEmpty()) {
                        // Nothing there, or out of reach. One probe read tells those apart: a
                        // chest genuinely gone is a claim the whole party must stop planning
                        // against, not just a walk this body wasted.
                        Store.standingAtOne(ctx);
                        return finish(ctx, "nothing to open");
                    }
                    // Falls through to the elapsed check below rather than returning here: a
                    // pause of N ticks must cost exactly N, not N+1 for a tick spent starting it.
                    pause.start(ctx.profile().i(ProfileAspect.HANDLING_OPEN_TICKS));
                }
                if (!pause.elapsed()) {
                    return TaskStatus.RUNNING;
                }
                // The belief is written the moment it is open, so an interruption one tick later
                // costs nothing and cancel never has to write anything.
                remember(ctx, ctx.actuators().containers().contents(at));
                phase = HandlingPhase.SETTLE;
                return TaskStatus.RUNNING;
            }
            case SETTLE -> {
                if (pause.idle()) {
                    pause.start(ctx.profile().i(ProfileAspect.HANDLING_SETTLE_TICKS));
                }
                if (!pause.elapsed()) {
                    return TaskStatus.RUNNING;
                }
                phase = HandlingPhase.MOVE;
                return TaskStatus.RUNNING;
            }
            case MOVE -> {
                if (pause.idle()) {
                    if (remaining() <= 0) {
                        // Only reachable with moved >= count, i.e. the last stack landed on the
                        // previous tick — a take that never moved anything exits below instead.
                        return succeed(ctx);
                    }
                    // One stack costs exactly handling.stack_ticks: the 65th stick must be one
                    // more pause than the 64th, not one more plus a tick spent starting it.
                    pause.start(ctx.profile().i(ProfileAspect.HANDLING_STACK_TICKS));
                }
                if (!pause.elapsed()) {
                    return TaskStatus.RUNNING;
                }
                // Re-verified here, because the pause is real time and another body can empty a
                // chest while this one stands over it.
                Inventory pack = ctx.percepts().inventory();
                ItemStack next = nextStack(ctx.actuators().containers().contents(at));
                if (next.isEmpty()) {
                    return finish(ctx, "nothing left in the store");
                }
                // Never reach for more than the pack can hold. take() removes from the world
                // FIRST, so anything the pack then refuses depends on the container taking it
                // back — and a container that let an item out can still refuse one in (a
                // furnace's OUTPUT slot, canPlaceItem). CraftStep.roomFor clamps the identical
                // hazard one layer down: "consuming first and failing to place would vanish the
                // bill".
                int want = Math.min(Math.min(remaining(), next.maxStackSize()), roomFor(next, pack));
                if (want <= 0) {
                    // Trying again would only re-measure the same full pack, forever.
                    return finish(ctx, "no room in the pack");
                }
                ItemStack got = ctx.actuators().containers().take(at, spec, want);
                if (got.isEmpty()) {
                    return finish(ctx, "nothing left in the store");
                }
                ItemStack unplaced = pack.add(got);
                int landed = got.count() - unplaced.count();
                if (!unplaced.isEmpty()) {
                    // Belt and braces behind the clamp above: roomFor and Inventory.add would have
                    // to disagree for anything to land here. If they ever do, the items are
                    // already out of the world, so push them back — and journal what neither side
                    // will hold rather than let it vanish with no trace.
                    int returned = ctx.actuators().containers().insert(at, unplaced);
                    if (returned < unplaced.count()) {
                        ctx.journal().record(Category.BRAIN, "take",
                                (unplaced.count() - returned) + "×" + spec.name()
                                        + " lost — pack and container both refused it");
                    }
                }
                moved += landed;
                remember(ctx, ctx.actuators().containers().contents(at));
                if (landed == 0) {
                    // Same disagreement, seen from the loop's side: roomFor promised space the
                    // pack did not have, and it would promise it again next tick, forever.
                    return finish(ctx, "no room in the pack");
                }
                return TaskStatus.RUNNING;
            }
            default -> {
                return TaskStatus.FAILED;
            }
        }
    }

    private int remaining() {
        return count - moved;
    }

    /**
     * The stack {@code take} will draw from next: the first match in slot order, which is the order
     * both {@code contents} and {@code take} walk. Its own {@code maxStackSize} is what makes a
     * grab cost one pause per source SLOT — a store packed 64/64/1 is three, and so is a messy one
     * holding a single stick in each of three slots.
     */
    private ItemStack nextStack(Optional<List<ItemStack>> inside) {
        return inside.orElse(List.of()).stream()
                .filter(stack -> spec.matches(stack.id()))
                .findFirst().orElse(ItemStack.EMPTY);
    }

    /**
     * How many more of {@code kind} the pack could hold: headroom in the stacks it already carries
     * plus a full stack per empty slot, counted exactly the way {@link Inventory#add} fills. Stops
     * at {@link Inventory#ARMOR_START} because worn armour and the offhand are not storage.
     */
    private static int roomFor(ItemStack kind, Inventory pack) {
        int room = 0;
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            ItemStack held = pack.get(slot);
            if (held.isEmpty()) {
                room += kind.maxStackSize();
            } else if (held.canStackWith(kind)) {
                room += held.remainingSpace();
            }
        }
        return room;
    }

    /**
     * Writes what is inside — <b>only when the look actually happened</b>. An unreadable container
     * (shoved out of reach, chest gone) is "I could not look", and recording that as "I looked and
     * it was empty" would make {@code TakeFromStore} inapplicable and leave nothing in this slice
     * that ever re-opens the chest: a permanently forgotten store.
     */
    private void remember(BrainContext ctx, Optional<List<ItemStack>> inside) {
        inside.ifPresent(seen -> ctx.knowledge().sawInside(at, seen, ctx.percepts().time(),
                AgentKnowledge.maxPerKind(ctx.profile())));
    }

    /** SUCCESS with the tally: any take that moved anything at all leaves through here. */
    private TaskStatus succeed(BrainContext ctx) {
        ctx.journal().record(Category.BRAIN, "take",
                "took " + moved + "×" + spec.name() + " from a store");
        return TaskStatus.SUCCESS;
    }

    /** {@code failureReason} is only used, and only journaled, when nothing ever moved. */
    private TaskStatus finish(BrainContext ctx, String failureReason) {
        if (moved > 0) {
            return succeed(ctx);
        }
        ctx.journal().record(Category.BRAIN, "take", failureReason);
        return TaskStatus.FAILED;
    }

    @Override
    public void cancel(BrainContext ctx) {
        // A pause is only a countdown, and every stack that already landed is real items in the
        // pack — there is nothing here to release or roll back.
    }

    @Override
    public String describe() {
        return "take " + spec.name() + " x" + count + " from (" + at.x() + ", " + at.y() + ", "
                + at.z() + ")";
    }

    public Pos at() {
        return at;
    }

    public ItemSpec spec() {
        return spec;
    }

    public int count() {
        return count;
    }

    public HandlingPhase phase() {
        return phase;
    }

    public int pauseTicks() {
        return pause.remaining();
    }

    public int moved() {
        return moved;
    }

    /** Puts a reload back mid-transfer; the stacks already moved are in the world, not in here. */
    public TakeItems resume(HandlingPhase phase, int pauseTicks, int moved) {
        this.phase = phase;
        this.pause.restore(pauseTicks);
        this.moved = moved;
        return this;
    }
}
