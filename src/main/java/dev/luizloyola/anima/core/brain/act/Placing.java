package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One block to place almost as a player places it (Luiz, 2026-09-29): vanilla's placement from
 * where the body stands, then the orientation set directly, so a builder need not walk round for
 * the angle. Everything else in {@link #state} goes in at its default and is reached afterwards as
 * a player reaches it — a door opened by hand, a campfire put out with a shovel, a second candle
 * placed into the first, a poppy put in its pot.
 *
 * @param itemId what is placed: the item's own block, or the block the order is made from (a pot for
 *               a potted poppy, dirt for a path where there is no ground)
 * @param block  the block wanted, when it is not the item's own — {@code minecraft:wall_torch} from
 *               a torch, {@code minecraft:potted_poppy} from a pot; empty for the item's own
 * @param state  every property the plan names
 */
public record Placing(String itemId, Pos cell, String block, Map<String, String> state) {

    /**
     * What is set directly. {@code half} here is a stair's or a trapdoor's; a door's or a bed's
     * halves are which cell, never set. A slab's {@code type=double} is a count: the slab placed
     * again.
     */
    public static final Set<String> ORIENTATION = Set.of("facing", "axis", "rotation", "half", "hinge",
            "face", "attachment", "hanging", "type", "shape");

    public Placing {
        state = Collections.unmodifiableMap(new TreeMap<>(state));
    }

    public static Placing of(String itemId, Pos cell) {
        return new Placing(itemId, cell, "", Map.of());
    }

    /** What of {@link #state} is set as the block goes in. */
    public Map<String, String> orientation() {
        return Collections.unmodifiableMap(state.entrySet().stream()
                .filter(e -> ORIENTATION.contains(e.getKey()))
                .filter(e -> !(e.getKey().equals("type") && e.getValue().equals("double")))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new)));
    }

    @Override
    public String toString() {
        String what = block.isEmpty() ? itemId : block;
        if (!state.isEmpty()) {
            what += state.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(",", "[", "]"));
        }
        return what + " at (" + cell.x() + ", " + cell.y() + ", " + cell.z() + ")";
    }
}
