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

/**
 * Put up to {@code count} of {@code spec} from the pack into the container at {@code at} — the same
 * open, beat and one-pause-per-stack shape as {@link TakeItems}, moving the other way.
 *
 * <p><b>A full container is a real outcome, not an error.</b> The belief that it holds real things
 * is not wrong, so nothing corrects it; {@code finish} marks the spot avoided for a while instead,
 * the way a body remembers a shop was closed rather than concluding it burned down.
 */
public final class PutItems implements PrimitiveTask {

    private enum Phase { OPEN, SETTLE, MOVE }

    private final Pos at;
    private final ItemSpec spec;
    private final int count;

    private Phase phase = Phase.OPEN;
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
                        return TaskStatus.FAILED; // nothing there, or out of reach
                    }
                    pause.start(ctx.profile().i(ProfileAspect.HANDLING_OPEN_TICKS));
                    return TaskStatus.RUNNING;
                }
                if (!pause.elapsed()) {
                    return TaskStatus.RUNNING;
                }
                // The belief is written the moment it is open, so an interruption one tick later
                // costs nothing and cancel never has to write anything.
                List<ItemStack> seen = ctx.actuators().containers().contents(at).orElse(List.of());
                ctx.knowledge().sawInside(at, seen, ctx.percepts().time(),
                        AgentKnowledge.maxPerKind(ctx.profile()));
                phase = Phase.SETTLE;
                return TaskStatus.RUNNING;
            }
            case SETTLE -> {
                if (pause.idle()) {
                    pause.start(ctx.profile().i(ProfileAspect.HANDLING_SETTLE_TICKS));
                    return TaskStatus.RUNNING;
                }
                if (!pause.elapsed()) {
                    return TaskStatus.RUNNING;
                }
                phase = Phase.MOVE;
                return TaskStatus.RUNNING;
            }
            case MOVE -> {
                if (pause.idle()) {
                    if (remaining(ctx) <= 0) {
                        return finish(ctx);
                    }
                    pause.start(ctx.profile().i(ProfileAspect.HANDLING_STACK_TICKS));
                    return TaskStatus.RUNNING;
                }
                if (!pause.elapsed()) {
                    return TaskStatus.RUNNING;
                }
                // Re-verified here, because the pause is real time and another body can fill a
                // chest while this one stands over it.
                int want = Math.min(remaining(ctx), oneStackOf(ctx));
                ItemStack pulled = want <= 0 ? ItemStack.EMPTY : pullFromPack(ctx, want);
                if (pulled.isEmpty()) {
                    return finish(ctx);
                }
                int accepted = ctx.actuators().containers().insert(at, pulled);
                if (accepted < pulled.count()) {
                    // A partial accept must not vanish the remainder: what the container refused
                    // is still real items, so it goes straight back into the pack.
                    ctx.percepts().inventory().add(pulled.withCount(pulled.count() - accepted));
                }
                moved += accepted;
                ctx.knowledge().sawInside(at,
                        ctx.actuators().containers().contents(at).orElse(List.of()),
                        ctx.percepts().time(), AgentKnowledge.maxPerKind(ctx.profile()));
                // Nothing accepted means the container is full: trying again would just pull the
                // same stack back out of the pack and refuse it forever.
                if (accepted == 0) {
                    return finish(ctx);
                }
                return TaskStatus.RUNNING;
            }
            default -> {
                return TaskStatus.FAILED;
            }
        }
    }

    private int remaining(BrainContext ctx) {
        return Math.min(count - moved, ctx.percepts().inventory().count(spec.matcher()));
    }

    /** One slot's worth from the pack, so 129 sticks is three pauses and one sword is one. */
    private int oneStackOf(BrainContext ctx) {
        int max = 0;
        for (Inventory.Entry entry : ctx.percepts().inventory().occupied()) {
            if (spec.matches(entry.stack().id())) {
                max = Math.max(max, entry.stack().maxStackSize());
            }
        }
        return max;
    }

    /** Pulls up to {@code want} of one matching stack out of the pack, or {@code EMPTY}. */
    private ItemStack pullFromPack(BrainContext ctx, int want) {
        Inventory pack = ctx.percepts().inventory();
        for (Inventory.Entry entry : pack.occupied()) {
            ItemStack held = entry.stack();
            if (!spec.matches(held.id())) {
                continue;
            }
            int taken = Math.min(want, held.count());
            pack.remove(held.id(), taken);
            return held.withCount(taken);
        }
        return ItemStack.EMPTY;
    }

    private TaskStatus finish(BrainContext ctx) {
        if (moved > 0) {
            ctx.journal().record(Category.BRAIN, "put",
                    "put " + moved + "×" + spec.name() + " in a store");
            return TaskStatus.SUCCESS;
        }
        // The belief is not wrong — it is full of real things — so nothing corrects it but a timer.
        ctx.knowledge().avoid(Store.POI, at,
                ctx.percepts().time() + ctx.profile().i(ProfileAspect.STORES_FULL_AVOID_TICKS));
        ctx.journal().record(Category.BRAIN, "put", "no room in the store");
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

    public String phaseName() {
        return phase.name();
    }

    public int pauseTicks() {
        return pause.remaining();
    }

    public int moved() {
        return moved;
    }

    /** Puts a reload back mid-transfer; the stacks already moved are in the world, not in here. */
    public PutItems resume(String phase, int pauseTicks, int moved) {
        this.phase = Phase.valueOf(phase);
        this.pause.restore(pauseTicks);
        this.moved = moved;
        return this;
    }
}
