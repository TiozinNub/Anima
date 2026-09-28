package dev.luizloyola.anima.mod.nav;

import dev.luizloyola.anima.core.inv.Inventory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import org.jspecify.annotations.Nullable;

/**
 * What a body may lay from what it carries: {@code #anima:bridging_blocks}, and never a block that
 * falls — a deck of sand is a hole with a delay (docs/superpowers/specs/2026-09-28-bridging-design.md).
 * Server thread only; the route search is handed the count.
 */
public final class Laying {
    private static final TagKey<Item> BRIDGING_BLOCKS =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("anima", "bridging_blocks"));
    private static final TagKey<Block> SOFT_GROUND =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("anima", "soft_ground"));

    private Laying() {
    }

    /** How many blocks this pocket could lay. */
    public static int carried(Inventory inventory) {
        int count = 0;
        for (Inventory.Entry entry : inventory.occupied()) {
            if (layable(entry.stack().id())) {
                count += entry.stack().count();
            }
        }
        return count;
    }

    /** The block to lay a deck or a pillar with: the biggest carried stack that may be laid. */
    static @Nullable String pick(Inventory inventory) {
        String best = null;
        int most = 0;
        for (Inventory.Entry entry : inventory.occupied()) {
            String id = entry.stack().id();
            if (entry.stack().count() > most && layable(id)) {
                best = id;
                most = entry.stack().count();
            }
        }
        return best;
    }

    /**
     * The block to put back into a scaled step: the one it held, if carried; else any soft ground
     * the body carries, since a cut grass block drops dirt; else whatever it may lay at all.
     */
    static @Nullable String putBack(Inventory inventory, @Nullable String held) {
        if (held != null && inventory.count(held) > 0) {
            return held;
        }
        for (Inventory.Entry entry : inventory.occupied()) {
            if (soft(entry.stack().id())) {
                return entry.stack().id();
            }
        }
        return pick(inventory);
    }

    private static boolean layable(String itemId) {
        return block(itemId) != null && new ItemStack(item(itemId)).is(BRIDGING_BLOCKS);
    }

    private static boolean soft(String itemId) {
        Block block = block(itemId);
        return block != null && block.defaultBlockState().is(SOFT_GROUND);
    }

    private static @Nullable Block block(String itemId) {
        if (itemId.isEmpty()) {
            return null;
        }
        Item item = item(itemId);
        return item instanceof BlockItem blockItem && !(blockItem.getBlock() instanceof FallingBlock)
                ? blockItem.getBlock() : null;
    }

    private static Item item(String itemId) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
    }
}
