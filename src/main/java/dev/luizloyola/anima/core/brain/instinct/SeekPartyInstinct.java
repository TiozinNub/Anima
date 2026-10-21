package dev.luizloyola.anima.core.brain.instinct;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.task.SeekCompany;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.social.GroupUps;
import org.jspecify.annotations.Nullable;

/**
 * Looking for somewhere to live is a reason to hail (group-up spec, decision 4): a body whose party
 * wants to join another goes up to somebody of another party it could ask. It is the same hail as
 * company's and hunger's, and what was wanted is the first line after the greeting. How hard it
 * pulls is the consumer's {@link dev.luizloyola.anima.core.social.GroupUp#seeking}.
 */
public final class SeekPartyInstinct implements Instinct {

    @Override
    public double pressure(BrainContext ctx) {
        return pressure(ctx, null);
    }

    /** With the seek already {@code running}, its own target keeps it possible. */
    @Override
    public double pressure(BrainContext ctx, @Nullable Task running) {
        AgentId self = ctx.self();
        double seeking = self == null ? 0 : GroupUps.get().seeking(self);
        return seeking > 0 && SeekCompany.possible(ctx, running, true) ? seeking : 0;
    }

    @Override
    public Task root(BrainContext ctx) {
        return new SeekCompany(true);
    }

    @Override
    public Deed doing(BrainContext ctx) {
        return Deed.of(Doings.LOOKING_FOR_A_PARTY);
    }

    @Override
    public String describe() {
        return "seek a party";
    }
}
