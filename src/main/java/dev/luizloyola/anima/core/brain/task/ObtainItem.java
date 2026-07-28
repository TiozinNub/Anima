package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Have {@code n} of {@code spec} in the pack — the first {@link AchieveTask}. The satisfied-check
 * Is the goal, so no "already stocked" method is needed; each executor round picks the cheapest way
 * to make progress: scavenge matching drops in sight ({@link PickUpNearby}) or fell a remembered
 * producer a consumer registered for that spec (see {@link Producers}). The crafting era adds its
 * {@code CraftFor} method here — with the occurs-check, since THAT recursion is genuine (planks
 * want logs) — and no caller changes.
 *
 * <p>In the finished mod this is always a SUBGOAL: a project computes a bill of materials and posts
 * requirements; nobody wants logs as an end state. The board's placeholder stock project posts the
 * same shape until real projects exist.
 */
public final class ObtainItem implements AchieveTask {
    private final ItemSpec spec;
    private final int count;
    private final List<Method> methods;

    public ObtainItem(ItemSpec spec, int count) {
        this.spec = spec;
        this.count = count;
        List<Method> ways = new ArrayList<>();
        // Picking one up is the way Anima always knows: it needs no knowledge of where the
        // thing came from. Everything else is the consuming mod's to teach.
        ways.add(new PickUpNearby(spec));
        ways.addAll(Producers.forSpec(spec));
        this.methods = List.copyOf(ways);
    }

    @Override
    public boolean satisfied(BrainContext ctx) {
        return ctx.percepts().inventory().count(spec.matcher()) >= count;
    }

    /** The stocked count: rounds that grow the pile never count against the rounds cap. */
    @Override
    public double progress(BrainContext ctx) {
        return ctx.percepts().inventory().count(spec.matcher());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "obtain " + spec.name() + " x" + count;
    }
}
