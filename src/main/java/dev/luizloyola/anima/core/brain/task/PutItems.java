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
 * Put up to {@code count} of {@code spec} from the pack into the container at {@code at} — the same
 * open, beat and one-pause-per-stack shape as {@link TakeItems}, moving the other way.
 *
 * <p><b>Only storage goes in.</b> The hotbar and the backpack are the pack; a worn helmet and the
 * offhand are not, so {@code put minecraft:iron_helmet} never undresses a settler into a chest.
 *
 * <p><b>A full container is a real outcome, not an error.</b> The belief that it holds real things
 * is not wrong, so nothing corrects it; a refusal of a real, non-empty stack marks the spot avoided
 * for a while instead, the way a body remembers a shop was closed rather than concluding it burned
 * down. That guard lives in the MOVE branch itself, not in {@code finish} — arriving with an empty
 * pack, or being shoved out of reach mid-errand, also ends in FAILED, but neither is a refusal, and
 * neither may blind the store.
 */
public final class PutItems implements PrimitiveTask {

    private final Pos at;
    private final ItemSpec spec;
    private final int count;

    private HandlingPhase phase = HandlingPhase.OPEN;
    private final Pause pause = new Pause();
    private int moved;

    public PutItems(Pos at, ItemSpec spec, int count) {
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
                    if (remaining(ctx) <= 0) {
                        return finish(ctx, "nothing to put");
                    }
                    // One stack costs exactly handling.stack_ticks: the 65th stick must be one
                    // more pause than the 64th, not one more plus a tick spent starting it.
                    pause.start(ctx.profile().i(ProfileAspect.HANDLING_STACK_TICKS));
                }
                if (!pause.elapsed()) {
                    return TaskStatus.RUNNING;
                }
                // Re-verified here, because the pause is real time and another body can fill a
                // chest while this one stands over it.
                Inventory pack = ctx.percepts().inventory();
                int slot = firstStored(pack);
                int want = slot < 0 ? 0
                        : Math.min(remaining(ctx), pack.get(slot).maxStackSize());
                ItemStack pulled = want <= 0 ? ItemStack.EMPTY : pullFromPack(pack, slot, want);
                if (pulled.isEmpty()) {
                    return finish(ctx, "nothing left to put");
                }
                int landed = ctx.actuators().containers().insert(at, pulled);
                if (landed < pulled.count()) {
                    // A partial accept must not vanish the remainder: what the container refused
                    // is still real items, so it goes straight back into the pack. That refund
                    // always fits — it returns to the same slot the stack was just pulled from,
                    // so the space it vacated is still free — but the return is captured rather
                    // than trusted blind, on the same principle TakeItems guards the other side:
                    // what the container then swallows is really moved, and what neither will
                    // hold is journaled rather than lost with no trace.
                    ItemStack refused = pack.add(pulled.withCount(pulled.count() - landed));
                    if (!refused.isEmpty()) {
                        int recovered = ctx.actuators().containers().insert(at, refused);
                        landed += recovered;
                        if (recovered < refused.count()) {
                            ctx.journal().record(Category.BRAIN, "put",
                                    (refused.count() - recovered) + "×" + spec.name()
                                            + " lost — store and pack both refused it");
                        }
                    }
                }
                moved += landed;
                // contents(at) is read once and used twice: as the belief to write, and as the
                // proof that the look happened at all.
                Optional<List<ItemStack>> inside = ctx.actuators().containers().contents(at);
                remember(ctx, inside);
                // Nothing accepted means the container is full: trying again would just pull the
                // same stack back out of the pack and refuse it forever.
                if (landed == 0) {
                    // A real, non-empty stack was offered and refused — pulled was already
                    // confirmed non-empty above, so an empty pack never reaches this branch.
                    // contents(at) still being present rules out the other reason insert can
                    // return 0: shoved out of reach mid-errand. That is a wrong belief, not a
                    // full store, and only a genuine refusal earns the avoidance timer.
                    if (inside.isPresent()) {
                        ctx.knowledge().avoid(Store.POI, at, ctx.percepts().time()
                                + ctx.profile().i(ProfileAspect.STORES_FULL_AVOID_TICKS));
                        return finish(ctx, "no room in the store");
                    }
                    return finish(ctx, "shoved out of reach");
                }
                return TaskStatus.RUNNING;
            }
            default -> {
                return TaskStatus.FAILED;
            }
        }
    }

    private int remaining(BrainContext ctx) {
        return Math.min(count - moved, stored(ctx.percepts().inventory()));
    }

    /**
     * How many matching items are in STORAGE. {@code Inventory.count} spans all 41 slots, which
     * would let a deposit reach into worn armour and the offhand; those are what the settler has
     * on, not what it is carrying to the chest.
     */
    private int stored(Inventory pack) {
        int total = 0;
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            ItemStack held = pack.get(slot);
            if (!held.isEmpty() && spec.matches(held.id())) {
                total += held.count();
            }
        }
        return total;
    }

    /**
     * The first storage slot holding a match, or {@code -1}. Hotbar and backpack only, for the
     * same reason {@link #stored} is — and its stack alone is what a grab costs a pause for, which
     * is what makes one pause per source SLOT: 64/64/1 is three, and so is a messy pack holding a
     * single stick in each of three slots.
     */
    private int firstStored(Inventory pack) {
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            ItemStack held = pack.get(slot);
            if (!held.isEmpty() && spec.matches(held.id())) {
                return slot;
            }
        }
        return -1;
    }

    /** Pulls up to {@code want} out of exactly that one slot — never several, never equipment. */
    private static ItemStack pullFromPack(Inventory pack, int slot, int want) {
        ItemStack held = pack.get(slot);
        int taken = Math.min(want, held.count());
        pack.set(slot, held.withCount(held.count() - taken));
        return held.withCount(taken);
    }

    /**
     * Writes what is inside — <b>only when the look actually happened</b>. An unreadable container
     * (shoved out of reach, chest gone) is "I could not look", and recording that as "I looked and
     * it was empty" would make {@code TakeFromStore} inapplicable and leave nothing in this slice
     * that ever re-opens the chest: a settler knocked back mid-deposit would permanently forget a
     * stocked store.
     */
    private void remember(BrainContext ctx, Optional<List<ItemStack>> inside) {
        inside.ifPresent(seen -> ctx.knowledge().sawInside(at, seen, ctx.percepts().time(),
                AgentKnowledge.maxPerKind(ctx.profile())));
    }

    /** {@code failureReason} is only used, and only journaled, when nothing ever moved. */
    private TaskStatus finish(BrainContext ctx, String failureReason) {
        if (moved > 0) {
            ctx.journal().record(Category.BRAIN, "put",
                    "put " + moved + "×" + spec.name() + " in a store");
            return TaskStatus.SUCCESS;
        }
        ctx.journal().record(Category.BRAIN, "put", failureReason);
        return TaskStatus.FAILED;
    }

    @Override
    public void cancel(BrainContext ctx) {
        // A pause is only a countdown, and every stack that already landed is real items in the
        // container — there is nothing here to release or roll back.
    }

    @Override
    public String describe() {
        return "put " + spec.name() + " x" + count + " into (" + at.x() + ", " + at.y() + ", "
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
    public PutItems resume(HandlingPhase phase, int pauseTicks, int moved) {
        this.phase = phase;
        this.pause.restore(pauseTicks);
        this.moved = moved;
        return this;
    }
}
