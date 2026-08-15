package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.inv.ItemCall;
import java.util.ArrayList;
import java.util.List;

/**
 * A claimed errand plus the kit-up that comes first. The arbiter wraps every granted
 * {@link WorkItem} through {@link #around}: needs become plain {@link ObtainItem} subgoals, wants
 * become {@link Try}-wrapped ones (shrugged past when they cannot be had), then the item's own root.
 * A kit-less item wraps to nothing.
 *
 * <p><b>The kit phase happens INSIDE the claim</b>: the item is claimed before this task first
 * ticks, so the two-shoppers loop cannot form; the TTL lease backstops a kit-up that never returns.
 *
 * <p>Nothing here is fetch state — every obtain is satisfied-check-first, so a resume pays nothing
 * for calls the pack already covers. Persisted whole (calls + work), like the executor's chain.
 */
public final class KittedErrand implements CompoundTask {

    private final List<ItemCall> calls;
    private final Task work;
    private final List<Method> methods;

    public KittedErrand(List<ItemCall> calls, Task work) {
        this.calls = List.copyOf(calls);
        this.work = work;
        this.methods = List.of(new KitUpThenWork());
    }

    /** A fresh root for a granted item: its own root, behind its kit when it declares one. */
    public static Task around(WorkItem item) {
        Task root = item.root();
        return item.kit().isEmpty() ? root : new KittedErrand(item.kit().calls(), root);
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "kit up, then " + describeWork();
    }

    public List<ItemCall> calls() {
        return calls;
    }

    public Task work() {
        return work;
    }

    private String describeWork() {
        if (work instanceof PrimitiveTask primitive) {
            return primitive.describe();
        }
        return ((CompoundTask) work).describe();
    }

    /** The one way: needs, then tries at the wants, then the errand itself. */
    private final class KitUpThenWork implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            List<Task> plan = new ArrayList<>();
            for (ItemCall call : calls) {
                if (call.strength() == ItemCall.Strength.NEED) {
                    plan.add(new ObtainItem(call.spec(), call.count()));
                }
            }
            for (ItemCall call : calls) {
                if (call.strength() == ItemCall.Strength.WANT) {
                    plan.add(new Try(new ObtainItem(call.spec(), call.count())));
                }
            }
            plan.add(work);
            return plan;
        }

        @Override
        public String describe() {
            return "kit up";
        }
    }
}
