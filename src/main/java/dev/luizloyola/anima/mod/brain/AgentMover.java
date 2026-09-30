package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.brain.act.MoveFailure;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.act.Mover;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.nav.Path;
import dev.luizloyola.anima.core.nav.WalkLevel;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.nav.Navigator;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * The {@link Mover} actuator <em>adapter</em>: core tasks see a version-neutral movement port,
 * mapped here onto the {@link Navigator}, which stays the single owner of locomotion — pathing,
 * following, re-pathing, every per-tick steering decision. When a task wants something the port
 * cannot say, the port grows; the adapter never leaks Navigator internals upward.
 */
public final class AgentMover implements Mover {
    private final AgentBody person;

    public AgentMover(AgentBody person) {
        this.person = person;
    }

    /** Begin navigating to the cell at {@code (x, y, z)}, replacing any move in progress. */
    @Override
    public void moveTo(int x, int y, int z, Gait gait, WalkLevel level) {
        this.person.navigator().pathTo(new BlockPos(x, y, z), gait, level, null);
    }

    /**
     * The Navigator's lifecycle folded onto the port's four states. PATHING reports as MOVING: from
     * the brain's side the move is committed the moment it is requested, and where the route is
     * worked out is the Navigator's business.
     */
    @Override
    public MoveState state() {
        return switch (this.person.navigator().state()) {
            case IDLE -> MoveState.IDLE;
            case PATHING, FOLLOWING -> MoveState.MOVING;
            case ARRIVED -> MoveState.ARRIVED;
            case FAILED -> MoveState.FAILED;
        };
    }

    /**
     * The Navigator's own verdict on why the last order died, passed through unchanged. The legs
     * record the cause where it is known (each give-up site names its own); nothing here re-derives
     * it.
     */
    @Override
    public MoveFailure failure() {
        return this.person.navigator().failure();
    }

    /**
     * PATHING, unfolded — the answer for a task counting TICKS against a search whose cost is in
     * milliseconds. Bounded at one tick when the search runs in the tick
     * ({@link dev.luizloyola.anima.mod.nav.PathfinderService#inThread()}), unbounded otherwise.
     */
    @Override
    public boolean routing() {
        return this.person.navigator().state()
                == dev.luizloyola.anima.mod.nav.Navigator.State.PATHING;
    }

    /** The Navigator's plan, once it has one: following it, or arrived at its end. */
    @Override
    public @Nullable Path route() {
        Navigator navigator = this.person.navigator();
        return switch (navigator.state()) {
            case FOLLOWING, ARRIVED -> navigator.path();
            case IDLE, PATHING, FAILED -> null;
        };
    }

    @Override
    public void stop() {
        this.person.navigator().stop();
    }
}
