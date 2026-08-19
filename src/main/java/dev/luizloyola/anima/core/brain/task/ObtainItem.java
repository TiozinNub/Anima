package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Have {@code n} of {@code spec} in the pack — the first {@link AchieveTask}. The satisfied-check is
 * the goal; each executor round picks the cheapest way to make progress: scavenge drops in sight
 * ({@link PickUpNearby}), fell a producer a consumer registered for that spec (see
 * {@link Producers}), make one ({@link CraftFor}, with an occurs-check — planks want logs), or take
 * one out of a store a party already filled ({@link TakeFromStore}).
 *
 * <p>Always a SUBGOAL in the finished mod: a craft or build project computes a bill of materials and
 * posts requirements; nobody wants logs as an end state.
 */
public final class ObtainItem implements AchieveTask {
    private final ItemSpec spec;
    private final int count;
    private final java.util.Set<String> pursued;
    private final List<Method> methods;

    public ObtainItem(ItemSpec spec, int count) {
        this(spec, count, java.util.Set.of());
    }

    /**
     * @param pursued output ids this branch of the goal stack is already obtaining — threaded
     *                into {@link CraftFor} as its ancestor-based occurs-check, and grown by one
     *                (the chosen recipe's output) on each descent. Method ORDER is load-bearing:
     *                a saved plan resumes its method BY INDEX into the roster rebuilt here, so
     *                nothing may ever be inserted BEFORE an existing entry — a new way is always
     *                appended. A reload then finds a fresh trailing slot in {@code tried[]},
     *                which simply restores {@code false} (untried); see
     *                {@link TaskExecutor#restore}.
     */
    public ObtainItem(ItemSpec spec, int count, java.util.Set<String> pursued) {
        this.spec = spec;
        this.count = count;
        this.pursued = java.util.Set.copyOf(pursued);
        List<Method> ways = new ArrayList<>();
        // Picking one up is the way Anima always knows: it needs no knowledge of where the
        // thing came from. Everything else is the consuming mod's to teach.
        ways.add(new PickUpNearby(spec));
        ways.addAll(Producers.forSpec(spec));
        // A literal spec (a crafting ingredient) reaches producers by CONTENT: "any oak log"
        // intersects what a consumer's logs spec means, so its chop is on this menu too — the
        // bridge that lets a craft chain end in a felled tree.
        ItemSpec.literalIds(spec).ifPresent(ids -> ways.addAll(Producers.forItems(ids, spec)));
        ways.add(new CraftFor(spec, count, this.pursued));
        // Appended AFTER CraftFor on purpose: a saved plan resumes its method by index, so nothing
        // may be inserted above it. Selection is by cost, so last in the list still wins when cheap.
        ways.add(new TakeFromStore(spec, count));
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

    public ItemSpec spec() {
        return spec;
    }

    public int count() {
        return count;
    }

    /** The occurs-check's ancestor set — what the codec writes so a reload keeps refusing cycles. */
    public java.util.Set<String> pursued() {
        return pursued;
    }
}
