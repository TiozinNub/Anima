package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.Surplus;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * BE rid of what nobody spoke for — the one goal both halves of the stow arc run, so the acute
 * instinct and the standing project cannot drift apart in what they actually do. What differs
 * between them is only what makes them bid.
 *
 * <p>Being an achieve-goal is what makes a SECOND chest happen without a rule saying so: a round
 * that fills the first one leaves the pack still holding cargo, the goal is still unsatisfied, and
 * the next round re-scores — finding the full store avoided, walking to another, or building one.
 * Always at the body's depot: a load never goes to whatever store is nearest.
 */
public final class PutAwaySurplus implements AchieveTask {

    /**
     * Cargo, in full stacks, that makes the walk worth taking. Zero for the standing stow and
     * unburden, which haul any cargo at all; a project hauling home sets it higher, so a
     * settler fells several trees between trips instead of commuting after each one.
     */
    private final int haulLine;

    /**
     * Free slots above the unburden line at which a haul goes whatever the load: room for the new
     * kinds one more tree brings in — its logs, sapling, sticks and litter. Below it unburden would
     * take the pack to the depot on its own account, and the project's haul would never go.
     */
    static final int ROOM_MARGIN = 4;

    /**
     * Whether the job this haul breaks off has more to give. The line prices a walk taken mid-job;
     * once there is nothing left, whatever is carried goes now — the end-of-job haul (decision:
     * Luiz, 2026-09-27). Without it a crew of twelve cleared a box and kept every log: nobody's
     * share reached the line.
     */
    private final BooleanSupplier workLeft;

    private final List<Method> methods = List.of(new StowAtAStore());

    /**
     * A haul whose job never ends, as far as it knows — also what a save restores, until the
     * project re-grants the errand with the live answer. Worst case, one load waits for the line.
     */
    public PutAwaySurplus(int haulLine) {
        this(haulLine, () -> true);
    }

    public PutAwaySurplus(int haulLine, BooleanSupplier workLeft) {
        this.haulLine = Math.max(0, haulLine);
        this.workLeft = workLeft;
    }

    public int haulLine() {
        return haulLine;
    }

    /**
     * Below the line there is nothing to do — which is what makes "haul when laden" fall out of the
     * achieve-loop re-asking, rather than anything scheduling it: a settler a stack into a box of
     * trees is already satisfied and simply takes the next one.
     *
     * <p>Laden is a load, a pack running out of room, or a job with nothing left. Counted in slots,
     * a mixed wood's logs, saplings, sticks and litter made three slots of one tree, and settlers
     * walked home with about fifteen items a trip (in-world, 2026-09-27).
     */
    @Override
    public boolean satisfied(BrainContext ctx) {
        Inventory pack = ctx.percepts().inventory();
        List<Integer> cargo = Surplus.slots(pack, ctx.reserved(),
                stack -> ctx.percepts().foods().of(stack).isPresent());
        if (cargo.isEmpty()) {
            return true;
        }
        if (!workLeft.getAsBoolean()) {
            return false;
        }
        int roomLine = ctx.profile().i(ProfileAspect.UNBURDEN_SLACK_SLOTS) + ROOM_MARGIN;
        return Surplus.stacks(pack, cargo) < haulLine && Surplus.emptySlots(pack) > roomLine;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "put away what nobody wants";
    }

    /** Get to a store, then empty the pack into it. */
    private final class StowAtAStore implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            // EnsureStore's own two methods decide whether that means walking or building, and
            // one of them is always available.
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            // Free: a haul home is part of a job already claimed, and the walk is where the job
            // said the load goes. Priced by distance it cleared no box more than 80 blocks from
            // home (a job's budget), and failed the tree it had just felled (in-world, 2026-09-27).
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new EnsureStore(), PutItems.stow());
        }

        @Override
        public String describe() {
            return "stow it at a store";
        }
    }
}
