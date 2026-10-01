package dev.luizloyola.anima.mod.brain;

import java.util.function.Predicate;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * How a block that no item places comes to be: another block placed, or found already standing,
 * then changed by a click — a pot and then its plant, a cauldron and then a bucket of water, the
 * ground and then a shovel (builder spec, rulings 17 and 18).
 *
 * @param base     what is placed when nothing it is made from stands there yet
 * @param standing what it is made from, already there to click on
 * @param click    what the click is made holding
 */
public record Making(Block base, Predicate<BlockState> standing, Predicate<ItemStack> click) {

    /** Null for a block an item places, or one nothing here knows how to make. */
    public static @Nullable Making of(Block wanted) {
        if (wanted instanceof FlowerPotBlock pot && pot.getPotted() != Blocks.AIR) {
            Block plant = pot.getPotted();
            return new Making(Blocks.FLOWER_POT, s -> s.is(Blocks.FLOWER_POT), stack -> stack.is(plant.asItem()));
        }
        if (wanted == Blocks.WATER_CAULDRON) {
            return cauldron(stack -> stack.is(Items.WATER_BUCKET));
        }
        if (wanted == Blocks.LAVA_CAULDRON) {
            return cauldron(stack -> stack.is(Items.LAVA_BUCKET));
        }
        if (wanted == Blocks.POWDER_SNOW_CAULDRON) {
            return cauldron(stack -> stack.is(Items.POWDER_SNOW_BUCKET));
        }
        // A path is dug on the ground as it is, and on dirt placed first where there is none (18).
        // Grass is not in #dirt (26.1), nor is mycelium or podzol everywhere, so they are named.
        if (wanted == Blocks.DIRT_PATH) {
            return new Making(Blocks.DIRT, s -> s.is(BlockTags.DIRT) || s.is(Blocks.GRASS_BLOCK)
                    || s.is(Blocks.MYCELIUM) || s.is(Blocks.PODZOL), stack -> stack.is(ItemTags.SHOVELS));
        }
        if (wanted == Blocks.FARMLAND) {
            return new Making(Blocks.DIRT,
                    s -> s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.DIRT_PATH),
                    stack -> stack.is(ItemTags.HOES));
        }
        return null;
    }

    private static Making cauldron(Predicate<ItemStack> bucket) {
        return new Making(Blocks.CAULDRON, s -> s.is(Blocks.CAULDRON), bucket);
    }
}
