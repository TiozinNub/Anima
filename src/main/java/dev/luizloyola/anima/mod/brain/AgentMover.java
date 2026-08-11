package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.act.Mover;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.nav.Navigator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

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

    /**
     * Begin navigating to the cell at {@code (x, y, z)}, replacing any move in progress. The two
     * branches route DIFFERENTLY on purpose:
     *
     * <ul>
     *   <li><b>{@link Gait#WALK}</b> (where the {@link Mover#moveTo(int, int, int)} default lands)
     *       goes through {@link AgentBody#navigateTo(Vec3)}, which cancels the debug jump-sprinter:
     *       it and the navigator both own the forward input, and a brain-issued move must win that
     *       the way a wand-issued one does.
     *   <li><b>{@link Gait#SPRINT} / {@link Gait#STROLL}</b> go straight to
     *       {@link Navigator#pathTo(BlockPos, Gait)}, so a paced move does <em>not</em> cancel the
     *       debug walker — accepted, since that branch skips navigator ticking anyway.
     * </ul>
     */
    @Override
    public void moveTo(int x, int y, int z, Gait gait) {
        BlockPos cell = new BlockPos(x, y, z);
        if (gait == Gait.WALK) {
            this.person.navigateTo(Vec3.atBottomCenterOf(cell));
        } else {
            this.person.navigator().pathTo(cell, gait);
        }
    }

    /**
     * The Navigator's lifecycle folded onto the port's four states. PATHING reports as MOVING — a
     * move is committed the moment it is requested; the wait on the off-thread route is the
     * Navigator's business, not a task's.
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
     * PATHING, unfolded. {@link #state()} hides it from tasks that only ask whether their order is
     * still being worked on; a task counting TICKS against an off-thread search needs this instead.
     */
    @Override
    public boolean routing() {
        return this.person.navigator().state()
                == dev.luizloyola.anima.mod.nav.Navigator.State.PATHING;
    }

    @Override
    public void stop() {
        this.person.navigator().stop();
    }
}
