package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.FurnaceAccess;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.SmeltLookup;
import dev.luizloyola.anima.core.craft.Furnace;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.Process;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Work a furnace from beside it: take out what is done, then load what is to be smelted and what
 * is to burn. A look, then one handling pause a step, as a chest is paced ({@link TakeItems}).
 *
 * <p>SUCCEEDS when anything moved; FAILS with no furnace in reach, or with nothing taken and
 * nothing loaded.
 */
public final class TendFurnace implements PrimitiveTask {

    private final Pos at;
    private final @Nullable ItemSpec output;
    private final @Nullable ItemSpec input;
    private final int inputCount;
    private final @Nullable ItemSpec fuel;
    private final int fuelCount;

    /** 0 looking, 1 taking out, 2 loading input, 3 loading fuel, 4 done. */
    private int step;
    private final Pause pause = new Pause();
    private int moved;
    private @Nullable String failure;

    /**
     * @param output what to take out, or null to leave the output be
     * @param input what to load to be smelted, or null for nothing
     * @param fuel what to load to burn, or null for nothing
     */
    public TendFurnace(Pos at, @Nullable ItemSpec output, @Nullable ItemSpec input, int inputCount,
                       @Nullable ItemSpec fuel, int fuelCount) {
        this.at = at;
        this.output = output;
        this.input = input;
        this.inputCount = inputCount;
        this.fuel = fuel;
        this.fuelCount = fuelCount;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        ctx.actuators().gazer().lookAt(at.x() + 0.5, at.y() + 0.5, at.z() + 0.5, Gazer.Priority.WORK);
        FurnaceAccess furnace = ctx.actuators().furnaces();
        if (pause.idle()) {
            if (furnace.read(at).isEmpty()) {
                // Standing at it and finding none, the body corrects the party's record: kept, a
                // broken furnace was loaded and failed 140 times and never set up again (2026-10-01).
                if (dev.luizloyola.anima.core.craft.Workbench.distance(ctx.percepts().position(), at)
                        <= Furnace.REACH) {
                    ctx.knowledge().disprove(Furnace.POI, at);
                }
                failure = "no furnace to reach";
                return TaskStatus.FAILED;
            }
            pause.start(ctx.profile().i(step == 0
                    ? ProfileAspect.HANDLING_OPEN_TICKS : ProfileAspect.HANDLING_STACK_TICKS));
        }
        if (!pause.elapsed()) {
            return TaskStatus.RUNNING;
        }
        switch (step) {
            case 1 -> takeOut(ctx, furnace);
            case 2 -> load(ctx, furnace, FurnaceAccess.Slot.INPUT, input, inputCount);
            case 3 -> load(ctx, furnace, FurnaceAccess.Slot.FUEL, fuel, fuelCount);
            default -> { }
        }
        step++;
        if (step < 4) {
            return TaskStatus.RUNNING;
        }
        // Kept either way: a furnace found with nothing to take out is still a reading, and a due
        // process left in the past would be posted again and again.
        keepTheLedger(ctx, furnace);
        if (moved == 0) {
            failure = "nothing to take out or put in";
            return TaskStatus.FAILED;
        }
        return TaskStatus.SUCCESS;
    }

    /**
     * After any tending, the party's record says what is left to smelt and when it should be done,
     * from what is in the furnace now — or that nothing is running. Only at a party's own furnace.
     */
    private void keepTheLedger(BrainContext ctx, FurnaceAccess furnace) {
        var places = ctx.knowledge().places();
        Optional<FurnaceAccess.View> view = furnace.read(at);
        ItemStack left = view.map(FurnaceAccess.View::input).orElse(ItemStack.EMPTY);
        Optional<SmeltLookup.Smelt> smelt = left.isEmpty() ? Optional.empty()
                : ctx.percepts().smelting().of(left.id());
        if (smelt.isEmpty()) {
            places.end(Furnace.POI, at);
            return;
        }
        long now = ctx.percepts().time();
        long started = places.process(Furnace.POI, at).map(Process::startedAt).orElse(now);
        places.run(Furnace.POI, at, new Process("smelt", left.id(), left.count(), smelt.get().outputId(),
                places.who(), started, now + (long) left.count() * smelt.get().ticks()));
    }

    private void takeOut(BrainContext ctx, FurnaceAccess furnace) {
        if (output == null) {
            return;
        }
        Optional<FurnaceAccess.View> view = furnace.read(at);
        if (view.isEmpty() || view.get().output().isEmpty()) {
            return;
        }
        ItemStack done = view.get().output();
        int room = TakeItems.roomFor(done, ctx.percepts().inventory());
        ItemStack got = furnace.takeOutput(at, output, Math.min(room, done.count()));
        if (got.isEmpty()) {
            return;
        }
        ItemStack unplaced = ctx.percepts().inventory().add(got);
        moved += got.count() - unplaced.count();
        ctx.journal().record(Category.BRAIN, "furnace",
                "took " + (got.count() - unplaced.count()) + "×" + got.id() + " out of the furnace");
    }

    private void load(BrainContext ctx, FurnaceAccess furnace, FurnaceAccess.Slot slot,
                      @Nullable ItemSpec spec, int count) {
        if (spec == null || count <= 0) {
            return;
        }
        Inventory pack = ctx.percepts().inventory();
        String id = null;
        for (Inventory.Entry entry : pack.occupied()) {
            if (spec.matches(entry.stack().id())) {
                id = entry.stack().id();
                break;
            }
        }
        if (id == null) {
            return;
        }
        ItemStack sample = null;
        for (Inventory.Entry entry : pack.occupied()) {
            if (entry.stack().id().equals(id)) {
                sample = entry.stack();
                break;
            }
        }
        int out = pack.remove(id, Math.min(count, sample.maxStackSize()));
        int took = furnace.load(at, slot, sample.withCount(out));
        if (took < out) {
            pack.add(sample.withCount(out - took));
        }
        moved += took;
        if (took > 0) {
            ctx.journal().record(Category.BRAIN, "furnace", "put " + took + "×" + id + " in the furnace"
                    + (slot == FurnaceAccess.Slot.FUEL ? " to burn" : ""));
        }
    }

    @Override
    public void cancel(BrainContext ctx) {
    }

    @Override
    public String describe() {
        return "tend the furnace";
    }

    @Override
    public String failureDetail() {
        return failure == null ? describe() + " failed" : failure;
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    public Pos at() {
        return at;
    }

    public @Nullable ItemSpec output() {
        return output;
    }

    public @Nullable ItemSpec input() {
        return input;
    }

    public int inputCount() {
        return inputCount;
    }

    public @Nullable ItemSpec fuel() {
        return fuel;
    }

    public int fuelCount() {
        return fuelCount;
    }

    public int step() {
        return step;
    }

    public int pauseTicks() {
        return pause.remaining();
    }

    public int moved() {
        return moved;
    }

    public TendFurnace resume(int step, int pauseTicks, int moved) {
        this.step = step;
        this.pause.restore(pauseTicks);
        this.moved = moved;
        return this;
    }
}
