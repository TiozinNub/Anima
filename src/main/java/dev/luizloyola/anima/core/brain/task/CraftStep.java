package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.craft.CraftRecipe;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.log.Category;
import java.util.TreeSet;

/**
 * Perform {@code times} crafts of one recipe from the carried pack — what {@link CraftFor} bottoms
 * out in. Each craft is a worked pause ({@link ProfileAspect#HANDLING_CRAFT_TICKS}) then an ATOMIC
 * exchange in one tick, so nothing strands a half-crafted state; only the pause survives a reload,
 * carried by the codec.
 *
 * <p>The pack is live, so the bill is re-verified at the start of each craft and again at the
 * exchange; one that no longer holds is FAILED and the parent re-obtains. A table recipe also
 * verifies its bench in reach.
 */
public final class CraftStep implements PrimitiveTask {

    private final CraftRecipe recipe;
    private final int times;
    private int done;
    private final Pause pause = new Pause();

    public CraftStep(CraftRecipe recipe, int times) {
        this.recipe = recipe;
        this.times = Math.max(1, times);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        Inventory pack = ctx.percepts().inventory();
        if (pause.idle()) {
            // EnsureTable just ran, but the pause is real time and a body can be shoved.
            if (!billCovered(pack) || !roomFor(recipe.output(), pack) || !sited(ctx)) {
                return TaskStatus.FAILED;
            }
            // Falls through to the elapsed check below rather than returning here: a pause of N
            // ticks must cost exactly N, not N+1 for a tick spent starting it.
            pause.start(ctx.profile().i(ProfileAspect.HANDLING_CRAFT_TICKS));
        }
        if (!pause.elapsed()) {
            return TaskStatus.RUNNING;
        }
        // The exchange, atomic within this tick — re-verified, because the pause is real time.
        if (!billCovered(pack) || !roomFor(recipe.output(), pack) || !sited(ctx)) {
            return TaskStatus.FAILED;
        }
        consumeBill(pack);
        pack.add(recipe.output());
        done++;
        ctx.journal().record(Category.BRAIN, "craft",
                "made " + recipe.outputCount() + "×" + recipe.outputId()
                        + " (" + done + "/" + times + ")");
        return done >= times ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
    }

    @Override
    public void cancel(BrainContext ctx) {
        // Mid-pause work is abandoned, never half-exchanged; finished crafts are real items.
        pause.start(0);
    }

    @Override
    public String describe() {
        return "craft " + recipe.outputId() + " x" + times;
    }

    @Override
    public String failureDetail() {
        return "craft could not go on (" + recipe.outputId() + ", " + done + "/" + times
                + (recipe.needsTable() ? ", needs a bench" : "") + ")";
    }

    /** In-hand recipes craft anywhere; a table recipe needs its bench within reach, verified. */
    private boolean sited(BrainContext ctx) {
        return !recipe.needsTable() || dev.luizloyola.anima.core.craft.Workbench.standingAtOne(ctx);
    }

    /** One craft's bill, present in the pack right now. */
    private boolean billCovered(Inventory pack) {
        for (CraftRecipe.Ingredient line : recipe.ingredients()) {
            if (pack.count(line.acceptedIds()::contains) < line.count()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the output can land: an empty slot, or headroom in a same-kind stack. Checked before
     * consuming — consuming first and failing to place would vanish the bill.
     */
    private static boolean roomFor(ItemStack output, Inventory pack) {
        int headroom = 0;
        for (int slot = 0; slot < Inventory.ARMOR_START; slot++) {
            ItemStack held = pack.get(slot);
            if (held.isEmpty()) {
                return true;
            }
            if (held.canStackWith(output)) {
                headroom += held.remainingSpace();
            }
        }
        return headroom >= output.count();
    }

    /**
     * Consumes one craft's bill, drawing each line across its accepted ids in sorted order — a
     * fixed order, so the same pack always pays with the same items.
     */
    private void consumeBill(Inventory pack) {
        for (CraftRecipe.Ingredient line : recipe.ingredients()) {
            int remaining = line.count();
            for (String id : new TreeSet<>(line.acceptedIds())) {
                if (remaining == 0) {
                    break;
                }
                remaining -= pack.remove(id, remaining);
            }
        }
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    public CraftRecipe recipe() {
        return recipe;
    }

    public int times() {
        return times;
    }

    public int done() {
        return done;
    }

    public int workTicks() {
        return pause.remaining();
    }

    /** Puts a reload back mid-run: {@code done} crafts finished, {@code workTicks} of pause left. */
    public CraftStep resume(int done, int workTicks) {
        this.done = done;
        this.pause.restore(workTicks);
        return this;
    }
}
