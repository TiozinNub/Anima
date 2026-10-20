package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.log.Category;
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
 *
 * <p><b>The needs are got twice and then checked</b>: getting one can spend another — crafting 12
 * fences turned the 9 planks got before them into fences (2026-10-20). The second pass costs
 * nothing for what still holds; what it cannot get back fails the errand loudly ({@link KitHeld})
 * rather than starting work that cannot finish.
 */
public final class KittedErrand implements CompoundTask {

    private final List<ItemCall> calls;
    private final Task work;
    /** Whether the errand's walks may lay blocks — see {@link WorkItem#buildsOnTheWay()}. */
    private final boolean builds;
    private final List<Method> methods;

    public KittedErrand(List<ItemCall> calls, Task work) {
        this(calls, work, false);
    }

    public KittedErrand(List<ItemCall> calls, Task work, boolean builds) {
        this.calls = List.copyOf(calls);
        this.work = work;
        this.builds = builds;
        this.methods = List.of(new KitUpThenWork());
    }

    /**
     * A fresh root for a granted item: its own root, behind its kit when it declares one, and
     * carrying its leave to build on the way when it has that.
     */
    public static Task around(WorkItem item) {
        Task root = item.root();
        if (item.kit().isEmpty() && !item.buildsOnTheWay()) {
            return root;
        }
        return new KittedErrand(item.kit().calls(), root, item.buildsOnTheWay());
    }

    @Override
    public boolean buildsOnTheWay() {
        return builds;
    }

    public boolean builds() {
        return builds;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return calls.isEmpty() ? describeWork() : "kit up, then " + describeWork();
    }

    public List<ItemCall> calls() {
        return calls;
    }

    public Task work() {
        return work;
    }

    /** The work comes last, after the kit is got. */
    @Override
    public void rejoin(List<Task> subtasks) {
        int at = subtasks.size() - 1;
        if (at >= 0 && at < subtasks.size() && subtasks.get(at).getClass() == work.getClass()) {
            subtasks.set(at, work);
        }
    }

    private String describeWork() {
        if (work instanceof PrimitiveTask primitive) {
            return primitive.describe();
        }
        return ((CompoundTask) work).describe();
    }

    /** The one way: needs, then tries at the wants, the needs again and checked, then the errand itself. */
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
            List<ItemCall> needs = calls.stream().filter(c -> c.strength() == ItemCall.Strength.NEED).toList();
            if (!needs.isEmpty()) {
                for (ItemCall need : needs) {
                    plan.add(new ObtainItem(need.spec(), need.count()));
                }
                plan.add(new KitHeld(needs));
            }
            plan.add(work);
            return plan;
        }

        @Override
        public String describe() {
            return "kit up";
        }
    }

    /** Fails, in the journal, unless the pack covers every need. */
    public static final class KitHeld implements PrimitiveTask {
        private final List<ItemCall> needs;
        private String shortOf = "";

        public KitHeld(List<ItemCall> needs) {
            this.needs = List.copyOf(needs);
        }

        public List<ItemCall> needs() {
            return needs;
        }

        @Override
        public TaskStatus tick(BrainContext ctx) {
            Inventory pack = ctx.percepts().inventory();
            List<String> shortItems = new ArrayList<>();
            for (ItemCall need : needs) {
                int held = pack.count(need.spec().matcher());
                if (held < need.count()) {
                    shortItems.add((need.count() - held) + " " + need.spec().name());
                }
            }
            if (shortItems.isEmpty()) {
                return TaskStatus.SUCCESS;
            }
            shortOf = String.join(", ", shortItems);
            ctx.journal().record(Category.BRAIN, "kit up", failureDetail());
            return TaskStatus.FAILED;
        }

        @Override
        public void cancel(BrainContext ctx) {
        }

        @Override
        public String describe() {
            return "check the kit";
        }

        @Override
        public String failureDetail() {
            return shortOf.isEmpty() ? "the kit fell short" : "the kit fell short of " + shortOf;
        }
    }
}
