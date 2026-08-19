package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.log.Category;
import java.util.List;

/**
 * Take up to {@code count} of {@code spec} out of the container at {@code at} — an open, a beat, and
 * one pause per stack.
 *
 * <p><b>SUCCEEDS on a partial take.</b> Finding four where sixteen were remembered is four logs, not a
 * failure; the parent's satisfied-check drives another round for the rest. Only an empty container
 * fails, and that failure is what corrects the belief.
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
                        return TaskStatus.FAILED; // nothing there, or out of reach
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
                List<ItemStack> seen = ctx.actuators().containers().contents(at).orElse(List.of());
                ctx.knowledge().sawInside(at, seen, ctx.percepts().time(),
                        AgentKnowledge.maxPerKind(ctx.profile()));
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
                        return finish(ctx);
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
                int want = Math.min(remaining(ctx), oneStackOf(ctx));
                ItemStack got = want <= 0
                        ? ItemStack.EMPTY : ctx.actuators().containers().take(at, spec, want);
                if (got.isEmpty()) {
                    return finish(ctx);
                }
                // A full pack must not vanish what came out of the container: what does not fit
                // goes straight back, and only what actually landed counts as moved — the same
                // hazard CraftStep guards on the other side (roomFor: "consuming first and
                // failing to place would vanish the bill").
                ItemStack unplaced = ctx.percepts().inventory().add(got);
                int landed = got.count() - unplaced.count();
                if (!unplaced.isEmpty()) {
                    ctx.actuators().containers().insert(at, unplaced);
                }
                moved += landed;
                ctx.knowledge().sawInside(at,
                        ctx.actuators().containers().contents(at).orElse(List.of()),
                        ctx.percepts().time(), AgentKnowledge.maxPerKind(ctx.profile()));
                // A full pack: trying again would just pull the same stack back out of the
                // container and fail to carry it again, forever.
                if (landed == 0) {
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
        return count - moved;
    }

    /** One slot's worth, so 129 sticks is three pauses and one sword is one. */
    private int oneStackOf(BrainContext ctx) {
        return ctx.actuators().containers().contents(at).orElse(List.of()).stream()
                .filter(stack -> spec.matches(stack.id()))
                .mapToInt(ItemStack::maxStackSize)
                .max().orElse(0);
    }

    private TaskStatus finish(BrainContext ctx) {
        if (moved > 0) {
            ctx.journal().record(Category.BRAIN, "take",
                    "took " + moved + "×" + spec.name() + " from a store");
            return TaskStatus.SUCCESS;
        }
        ctx.journal().record(Category.BRAIN, "take", "took nothing");
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
