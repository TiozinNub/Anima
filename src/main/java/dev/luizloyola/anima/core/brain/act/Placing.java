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
 * the angle. Every other property stays where placement leaves it — a door closed, a campfire lit —
 * and reaching the plan's value is a later act, as it is for a player.
 *
 * @param block       the block to place when the item places more than one — a torch on a wall is
 *                    {@code minecraft:wall_torch}; empty for the item's own
 * @param orientation only {@link #ORIENTATION}'s properties survive construction
 */
public record Placing(String itemId, Pos cell, String block, Map<String, String> orientation) {

    /**
     * What is set directly. {@code half} here is a stair's or a trapdoor's; a door's or a bed's
     * halves are which cell, never set. A slab's {@code type=double} is a count: the slab placed
     * again.
     */
    public static final Set<String> ORIENTATION = Set.of("facing", "axis", "rotation", "half", "hinge",
            "face", "attachment", "hanging", "type", "shape");

    public Placing {
        orientation = Collections.unmodifiableMap(orientation.entrySet().stream()
                .filter(e -> ORIENTATION.contains(e.getKey()))
                .filter(e -> !(e.getKey().equals("type") && e.getValue().equals("double")))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new)));
    }

    public static Placing of(String itemId, Pos cell) {
        return new Placing(itemId, cell, "", Map.of());
    }

    @Override
    public String toString() {
        String what = block.isEmpty() ? itemId : block;
        if (!orientation.isEmpty()) {
            what += orientation.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(",", "[", "]"));
        }
        return what + " at (" + cell.x() + ", " + cell.y() + ", " + cell.z() + ")";
    }
}
