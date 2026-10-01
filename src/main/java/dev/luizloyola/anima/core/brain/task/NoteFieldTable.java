package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.log.Category;

/**
 * Write down a table just put down to craft on: remembered, so the craft can find it, and listed
 * as the body's own, so {@link PackUpTable} takes it back afterwards. Not claimed for the party: a
 * claimed one sent the next settler out of reach of it to build another, and a party of twelve
 * ended its first clearing with ten (in-world, 2026-09-27).
 */
public final class NoteFieldTable implements PrimitiveTask {

    private final Pos anchor;

    public NoteFieldTable(int x, int y, int z) {
        this.anchor = new Pos(x, y, z);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        ctx.knowledge().note(Workbench.memoryOf(anchor, ctx.percepts().time()),
                AgentKnowledge.maxPerKind(ctx.profile()));
        ctx.fieldTables().record(anchor);
        ctx.journal().record(Category.BRAIN, "place", "put a workbench down at (" + anchor.x()
                + ", " + anchor.y() + ", " + anchor.z() + ") to craft on");
        return TaskStatus.SUCCESS;
    }

    @Override
    public void cancel(BrainContext ctx) {
    }

    @Override
    public String describe() {
        return "remember my workbench at (" + anchor.x() + ", " + anchor.y() + ", " + anchor.z()
                + ")";
    }

    public Pos anchor() {
        return anchor;
    }
}
