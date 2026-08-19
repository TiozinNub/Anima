package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * Claim a place you just made for your party — the communal half of {@link NotePlace}, for a thing
 * with no inventory and so nothing to hide.
 *
 * <p>One tick, always succeeds. A claim rather than a memory because the next member to want a
 * table should find this one without having to walk past it: that is the whole of what a party
 * place is for.
 */
public final class FoundPlace implements PrimitiveTask {

    private final PoiKind kind;
    private final Pos anchor;

    public FoundPlace(PoiKind kind, int x, int y, int z) {
        this.kind = kind;
        this.anchor = new Pos(x, y, z);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        ctx.knowledge().places().foundCommunal(kind, anchor, ctx.percepts().time());
        return TaskStatus.SUCCESS;
    }

    @Override
    public void cancel(BrainContext ctx) {
    }

    @Override
    public String describe() {
        return "claim " + kind.key() + " at (" + anchor.x() + ", " + anchor.y() + ", "
                + anchor.z() + ") for the party";
    }

    public PoiKind kind() {
        return kind;
    }

    public Pos anchor() {
        return anchor;
    }
}
