package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.craft.CraftRecipe;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.log.Category;
import java.util.TreeSet;

/**
 * Perform {@code times} crafts of one recipe out of the carried pack — the primitive
 * {@link CraftFor} bottoms out in. Each craft is a short worked pause ({@link #CRAFT_TICKS}) and
 * then an ATOMIC exchange: bill verified, consumed and output added in the same tick, so a death, a
 * suspension or a reload can strand no half-crafted state. Only the pause can be interrupted, and
 * the codec carries it.
 *
 * <p>Materials are re-verified at the START of each craft and again at the exchange, because the
 * pack is live state; a bill that no longer holds is a clean FAILED, so the parent re-rounds and
 * re-obtains rather than waiting for materials nobody is fetching.
 *
 * <p>No table, no position: this is the in-hand 2×2.
 */
public final class CraftStep implements PrimitiveTask {

    /** The worked pause per craft — long enough to read as labour, short enough not to bore. */
    public static final int CRAFT_TICKS = 10;

    private final CraftRecipe recipe;
    private final int times;
    private int done;
    /** Ticks left of the current craft's pause; 0 means the next tick starts a fresh craft. */
    private int workTicks;

    public CraftStep(CraftRecipe recipe, int times) {
        this.recipe = recipe;
        this.times = Math.max(1, times);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        Inventory pack = ctx.percepts().inventory();
        if (workTicks == 0) {
            // Starting a craft: the bill must be there and the output must have somewhere to go.
            if (!billCovered(pack) || !roomFor(recipe.output(), pack)) {
                return TaskStatus.FAILED;
            }
            workTicks = CRAFT_TICKS;
            return TaskStatus.RUNNING;
        }
        if (--workTicks > 0) {
            return TaskStatus.RUNNING;
        }
        // The exchange, atomic within this tick — re-verified, because the pause is real time.
        if (!billCovered(pack) || !roomFor(recipe.output(), pack)) {
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
        workTicks = 0;
    }

    @Override
    public String describe() {
        return "craft " + recipe.outputId() + " x" + times;
    }

    @Override
    public String failureDetail() {
        return "craft lost its materials (" + recipe.outputId() + ", " + done + "/" + times + ")";
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
        return workTicks;
    }

    /** Puts a reload back mid-run: {@code done} crafts finished, {@code workTicks} of pause left. */
    public CraftStep resume(int done, int workTicks) {
        this.done = done;
        this.workTicks = workTicks;
        return this;
    }
}
