package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * Write down a place you just made — the actor-notes-what-it-did rule (the chop forgets its tree; a
 * builder remembers its table). One tick, one memory, always succeeds: the sensor would notice the
 * block eventually, but the next subtask may already need the memory.
 */
public final class NotePlace implements PrimitiveTask {

    private final PoiKind kind;
    private final Pos anchor;

    public NotePlace(PoiKind kind, int x, int y, int z) {
        this.kind = kind;
        this.anchor = new Pos(x, y, z);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        ctx.knowledge().note(
                new PoiMemory(kind, anchor, Region.of(anchor), 1, false, ctx.percepts().time()),
                AgentKnowledge.maxPerKind(ctx.profile()));
        return TaskStatus.SUCCESS;
    }

    @Override
    public void cancel(BrainContext ctx) {
    }

    @Override
    public String describe() {
        return "remember " + kind.key() + " at (" + anchor.x() + ", " + anchor.y() + ", "
                + anchor.z() + ")";
    }

    public PoiKind kind() {
        return kind;
    }

    public Pos anchor() {
        return anchor;
    }
}
