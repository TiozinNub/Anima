package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Riser;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.Optional;

/**
 * One block higher on a block of {@code spec} laid underfoot, through the {@link Riser}. With
 * {@code recorded}, the block goes in the level's ledger of laid blocks as a pillar, so a scaffold
 * left standing can be found and taken down.
 */
public final class Rise implements PrimitiveTask {

    private final ItemSpec spec;
    private final boolean recorded;
    private boolean begun;

    public Rise(ItemSpec spec, boolean recorded) {
        this.spec = spec;
        this.recorded = recorded;
    }

    public ItemSpec spec() {
        return spec;
    }

    public boolean recorded() {
        return recorded;
    }

    /** The first carried item of {@code spec}, or empty. */
    static Optional<String> carried(Inventory pack, ItemSpec spec) {
        for (Inventory.Entry entry : pack.occupied()) {
            if (spec.matches(entry.stack().id())) {
                return Optional.of(entry.stack().id());
            }
        }
        return Optional.empty();
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        Riser riser = ctx.actuators().riser();
        if (!begun) {
            begun = true;
            Optional<String> item = carried(ctx.percepts().inventory(), spec);
            return item.isPresent() && riser.up(item.get(), recorded) ? TaskStatus.RUNNING : TaskStatus.FAILED;
        }
        return switch (riser.state()) {
            case RISING -> TaskStatus.RUNNING;
            case RISEN -> TaskStatus.SUCCESS;
            case IDLE, FAILED -> TaskStatus.FAILED;
        };
    }

    @Override
    public void cancel(BrainContext ctx) {
        ctx.actuators().riser().abort();
    }

    @Override
    public String describe() {
        return "rise one on " + spec.name();
    }
}
