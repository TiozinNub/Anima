package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.inv.Surplus;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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

    /** Where to put things, or null when the store is whatever the body is standing at. */
    private final @Nullable Pos fixedAt;
    /** What to move, or null for "everything nobody has spoken for". */
    private final @Nullable ItemSpec spec;
    private final int count;

    /** The store this run settled on. Equal to {@link #fixedAt} unless this is a stow. */
    private @Nullable Pos at;

    private HandlingPhase phase = HandlingPhase.OPEN;
    private final Pause pause = new Pause();
    private int moved;
    private boolean opened;

    private PutItems(@Nullable Pos fixedAt, @Nullable ItemSpec spec, int count) {
        this.fixedAt = fixedAt;
        this.spec = spec;
        this.count = Math.max(1, count);
        this.at = fixedAt;
    }

    /** Put up to {@code count} of {@code spec} from the pack into the container at {@code at}. */
    public static PutItems of(Pos at, ItemSpec spec, int count) {
        return new PutItems(Objects.requireNonNull(at, "at"),
                Objects.requireNonNull(spec, "spec"), count);
    }

    /**
     * Put away everything in storage that nothing has spoken for — the errand both halves of the
     * stow machinery run.
     *
     * <p><b>It carries neither a position nor a keep-list.</b> The store is whatever the body is
     * standing at, which {@code EnsureStore} has just guaranteed; what to keep is read from
     * {@link BrainContext#reserved()} on the tick each stack moves. Both omissions are what keep
     * this out of the codec — a keep-list held as a field would be persisted and would then be a
     * snapshot, so a settler who claimed a mining errand mid-stow would go on stowing against the
     * reservations of an hour ago.
     */
    public static PutItems stow() {
        return new PutItems(null, null, Integer.MAX_VALUE);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        // Re-asked every tick rather than claimed once, for the reason TakeItems gives: the open
        // and settle beats together outlast a WORK hold, so one claim would lapse mid-errand.
        // Skipped on a stow's very first tick, which has not yet worked out which chest it is
        // standing at — there is nothing to look at until OPEN resolves one.
        if (at != null) {
            ctx.actuators().gazer().lookAt(at.x() + 0.5, at.y() + 0.5, at.z() + 0.5,
                    Gazer.Priority.WORK);
        }
        switch (phase) {
            case OPEN -> {
                if (at == null) {
                    // EnsureStore put the body here; nothing else may assume it. A stow that finds
                    // no store in reach is a plan that went stale between decompose and arrival,
                    // and the goal above re-derives rather than guessing at another chest.
                    at = Store.nearestKnown(ctx)
                            .map(PoiMemory::anchor)
                            .filter(anchor -> Store.distance(anchor, ctx.percepts().position())
                                    <= Store.REACH)
                            .orElse(null);
                    if (at == null) {
                        return finish(ctx, "no store in reach");
                    }
                }
                if (pause.idle()) {
                    if (spec == null && cargoSlots(ctx).isEmpty()) {
                        // Checked before the lid, not after: a stow whose cargo went somewhere
                        // else between decompose and arrival should cost nothing, and the goal
                        // above is already satisfied so nothing will re-derive this.
                        return finish(ctx, "nothing to put away");
                    }
                    if (ctx.actuators().containers().contents(at).isEmpty()) {
                        // Nothing there, shut to us, or out of reach — one probe read tells them
                        // apart, and each earns a different correction. See Store.wouldNotOpen.
                        Store.wouldNotOpen(ctx, at);
                        return finish(ctx, "nothing to open");
                    }
                    // Falls through to the elapsed check below rather than returning here: a
                    // pause of N ticks must cost exactly N, not N+1 for a tick spent starting it.
                    pause.start(ctx.profile().i(ProfileAspect.HANDLING_OPEN_TICKS));
                }
                lift(ctx);
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
                lift(ctx);
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
                lift(ctx);
                if (!pause.elapsed()) {
                    return TaskStatus.RUNNING;
                }
                // Re-verified here, because the pause is real time and another body can fill a
                // chest while this one stands over it.
                Inventory pack = ctx.percepts().inventory();
                int slot = firstStored(ctx);
                int want = slot < 0 ? 0
                        : spec == null
                                // The whole slot goes: a stow is not counting items, it is
                                // emptying the pack one slot at a time.
                                ? pack.get(slot).count()
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
                                    (refused.count() - recovered) + "×" + label(pulled)
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
                shut(ctx);
                return TaskStatus.FAILED;
            }
        }
    }

    private int remaining(BrainContext ctx) {
        return spec == null
                ? cargoSlots(ctx).size()
                : Math.min(count - moved, stored(ctx.percepts().inventory()));
    }

    /** The storage slots this run is entitled to move — re-asked per stack, never cached. */
    private List<Integer> cargoSlots(BrainContext ctx) {
        return Surplus.slots(ctx.percepts().inventory(), ctx.reserved(),
                stack -> ctx.percepts().foods().of(stack).isPresent());
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
    private int firstStored(BrainContext ctx) {
        if (spec == null) {
            List<Integer> cargo = cargoSlots(ctx);
            return cargo.isEmpty() ? -1 : cargo.get(0);
        }
        Inventory pack = ctx.percepts().inventory();
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

    /**
     * The lid goes up, once. Idempotent because SETTLE and MOVE ask again on every tick they own:
     * {@link #resume} restores the phase but cannot restore a signal, so a deposit reloaded
     * mid-stride has to raise a lid the world is drawing shut.
     */
    private void lift(BrainContext ctx) {
        // Only what the actuator says it got: a refused open paired with a close would drop
        // another body's lid, so a refusal is retried next tick rather than assumed.
        if (!opened && at != null) {
            opened = ctx.actuators().containers().open(at);
        }
    }

    /** And back down, once, on every way out of this task — including a cancel. */
    private void shut(BrainContext ctx) {
        if (opened && at != null) {
            opened = false;
            ctx.actuators().containers().close(at);
        }
    }

    /** {@code failureReason} is only used, and only journaled, when nothing ever moved. */
    private TaskStatus finish(BrainContext ctx, String failureReason) {
        shut(ctx);
        if (moved > 0) {
            ctx.journal().record(Category.BRAIN, "put",
                    "put " + moved + "×" + (spec == null ? "things" : spec.name()) + " in a store");
            return TaskStatus.SUCCESS;
        }
        ctx.journal().record(Category.BRAIN, "put", failureReason);
        return TaskStatus.FAILED;
    }

    @Override
    public void cancel(BrainContext ctx) {
        // A pause is only a countdown, and every stack that already landed is real items in the
        // container — neither is rolled back. The LID is the one thing here that is a hold: left
        // up, it outlives the task that raised it, so cancel really does have work to do.
        shut(ctx);
    }

    /** What a moved stack is called in a journal line — a stow has no spec to name. */
    private String label(ItemStack pulled) {
        return spec == null ? pulled.id() : spec.name();
    }

    @Override
    public String describe() {
        if (spec == null) {
            return "put away what nobody wants";
        }
        return "put " + spec.name() + " x" + count + " into (" + at.x() + ", " + at.y() + ", "
                + at.z() + ")";
    }

    /** Null for a stow, which resolves its store at OPEN and persists none. */
    public @Nullable Pos at() {
        return fixedAt;
    }

    /** Null for a stow, whose selection is "whatever nobody spoke for". */
    public @Nullable ItemSpec spec() {
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
