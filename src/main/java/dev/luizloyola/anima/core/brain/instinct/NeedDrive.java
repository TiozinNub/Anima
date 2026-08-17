package dev.luizloyola.anima.core.brain.instinct;

import dev.luizloyola.anima.core.agent.need.Binding;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.agent.need.NeedLevel;
import dev.luizloyola.anima.core.agent.need.Needs;
import dev.luizloyola.anima.core.agent.need.Ramp;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.task.Task;
import java.util.Objects;
import java.util.function.Function;

/**
 * A drive a need declared, wired to the task it proposes — the one shape every hand-written
 * "pressure is {@code 1 - food/20}" instinct collapses into.
 *
 * <p><b>It reads the need and nothing else.</b> The bid is the gauge's own pressure, so where the
 * knees of that ramp sit is a config file's business and not this class's; the cost ceiling is the
 * level the body is currently at, so a starving body pays anything and a peckish one pays for a
 * short errand. Both used to be constants in two places that were reconciled by a comment.
 *
 * <p><b>It is stateless, and one instance serves every body</b> — everything it needs arrives in
 * the context. See {@code Drives} for the declared ones.
 *
 * <p><b>A drive whose need this body does not have never fires</b>, and need not ask: the roster
 * answers 0 pressure for a gauge that is not there, which is what lets one drive be portable across
 * bodies that do not agree about what they feel.
 */
public final class NeedDrive implements Instinct {

    private final Binding binding;
    private final Function<BrainContext, Task> root;

    /**
     * @param binding what the need declared — which need, which end of it, and under what name
     * @param root a FRESH task tree per grant; see {@link Instinct#root}
     * @throws IllegalArgumentException if the binding is a modulator, which by definition proposes
     *     nothing and can never be an instinct
     */
    public NeedDrive(Binding binding, Function<BrainContext, Task> root) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.root = Objects.requireNonNull(root, "root");
        if (binding.verb() != Binding.Verb.DRIVE) {
            throw new IllegalArgumentException(
                    binding.key() + " is a modulator — it weighs a decision, it does not make one");
        }
    }

    /** What this drive is for. */
    public Binding binding() {
        return binding;
    }

    /**
     * The need's own pressure, or 0 when the body is on the other side of comfortable. A two-sided
     * need presses at both ends with the same number and opposite errands, so the side gate is what
     * stops a crowded body bidding to go and find someone.
     */
    @Override
    public double pressure(BrainContext ctx) {
        NeedKind need = binding.need();
        Needs needs = ctx.percepts().needs();
        if (!needs.has(need)) {
            return 0.0;
        }
        Ramp ramp = need.ramp();
        if (ramp != null
                && !binding.pressing(ramp.side(ctx.profile(), needs.value(need)))) {
            return 0.0;
        }
        return needs.pressure(need);
    }

    /**
     * What a body at its current level will spend, from that level's {@code tolerance} aspect —
     * {@code -1} there means unbounded, since the config kinds are numbers and infinity is not
     * typeable.
     *
     * <p>Nothing to read means nothing to spend: a body without this need, or a need declared
     * without levels, buys only free methods rather than inheriting a budget from a gauge it does
     * not have.
     */
    @Override
    public double costTolerance(BrainContext ctx) {
        NeedLevel level = ctx.percepts().needs().level(binding.need()).orElse(null);
        if (level == null) {
            return 0.0;
        }
        double budget = level.tolerance(ctx.profile());
        return budget < 0.0 ? Double.POSITIVE_INFINITY : budget;
    }

    @Override
    public Task root(BrainContext ctx) {
        return root.apply(ctx);
    }

    /**
     * The binding's key, not this class's name — every need drive is the same class, so the default
     * would file every drive's fail-cooldown under one shared entry.
     */
    @Override
    public String key() {
        return binding.key();
    }

    @Override
    public String describe() {
        return binding.key();
    }
}
