package dev.luizloyola.anima.core.brain.sense;

import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.Optional;

/**
 * What a furnace makes of an item, how long a fuel burns, and what a campfire cooks — the running
 * server's recipes.
 */
public interface SmeltLookup {

    /** One smelt: what comes out, and the ticks it takes. */
    record Smelt(String outputId, int ticks) {
    }

    Optional<Smelt> of(String inputId);

    /** What a campfire makes of an item and in how long; empty when a campfire takes none. */
    default Optional<Smelt> campfire(String inputId) {
        return Optional.empty();
    }

    /** The longest smelt, in ticks, among the items {@code input} takes that smelt at all; 0 if none. */
    int smeltTicks(ItemSpec input);

    /**
     * The shortest burn, in ticks, among the items {@code fuel} takes that burn at all — 0 when
     * none does. The shortest, so fuel counted by it is never too little whichever turns up.
     */
    int burnTicks(ItemSpec fuel);

    SmeltLookup NONE = new SmeltLookup() {
        @Override
        public Optional<Smelt> of(String inputId) {
            return Optional.empty();
        }

        @Override
        public int smeltTicks(ItemSpec input) {
            return 0;
        }

        @Override
        public int burnTicks(ItemSpec fuel) {
            return 0;
        }
    };
}
