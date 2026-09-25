package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Put a station down near a place and claim it for the party — a base's workbench, its chests,
 * later its furnace and campfire. The block is had however {@link ObtainItem} can have it, so a
 * settler with an empty pack chops a tree, makes planks and crafts the thing on the way.
 *
 * <p><b>The block first, the spot on arrival.</b> A yard's chest was placed the other way round —
 * walk, then craft — and a chest needs a workbench, so the craft walked the body away and the
 * placement fired from the bench, out of reach, every time (in-world, 2026-09-24). {@link PutDown}
 * picks its cell only when it expands, once the block is in the pack and nobody's plan has gone
 * stale.
 */
public final class PlaceStation implements CompoundTask {

    private final PoiKind kind;
    private final String itemId;
    private final Pos near;
    private final List<Method> methods = List.of(new GetThenPutDown());

    public PlaceStation(PoiKind kind, String itemId, Pos near) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.near = Objects.requireNonNull(near, "near");
    }

    public PoiKind kind() {
        return kind;
    }

    public String itemId() {
        return itemId;
    }

    public Pos near() {
        return near;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "put " + itemId + " down near (" + near.x() + ", " + near.y() + ", " + near.z() + ")";
    }

    private final class GetThenPutDown implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new ObtainItem(ItemSpec.anyOf(Set.of(itemId)), 1),
                    new PutDown(kind, itemId, near));
        }

        @Override
        public String describe() {
            return "get it, then put it down";
        }
    }
}
