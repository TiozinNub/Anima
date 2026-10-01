package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * After a block goes in, the clicks that bring it to the rest of the plan's state, the way a player
 * gets there (builder spec, rulings 15-18): by hand, with a tool, with an item, or the block placed
 * again. Each click is vanilla's own, so whatever a click really does is what the block becomes;
 * this only chooses the next one, and stops when a click changes nothing.
 */
final class Reaching {

    /** A note block's whole scale and then some; nothing a player does takes more clicks. */
    private static final int MAX_CLICKS = 32;

    /** Changed by an empty hand, one step a click. A lever's {@code powered} too, and only a lever's. */
    private static final Set<String> BY_HAND = Set.of("open", "delay", "mode", "note", "inverted");

    /** Counts, which grow by placing the block again into its cell. */
    private static final Set<String> COUNTS = Set.of("candles", "layers", "pickles", "eggs", "flower_amount",
            "segment_amount");

    private Reaching() {
    }

    /** One click, holding {@code itemId} or the empty hand, or the block placed again. */
    private record Step(@Nullable String itemId, boolean again) {
    }

    /** True when any click changed the block. */
    static boolean reach(AgentBody person, ServerLevel level, BlockPos pos, Block wanted, Placing placing,
                         AgentBlockPlacer placer) {
        boolean any = false;
        for (int i = 0; i < MAX_CLICKS; i++) {
            BlockState now = level.getBlockState(pos);
            Step step = next(person, now, wanted, placing.state(), placing.itemId());
            if (step == null) {
                return any;
            }
            boolean done = step.again()
                    ? placer.placeAgain(placing.itemId(), pos)
                    : BodyClick.click(person, level, pos, step.itemId());
            if (!done || level.getBlockState(pos) == now) {
                return any;
            }
            any = true;
        }
        return any;
    }

    private static @Nullable Step next(AgentBody person, BlockState now, Block wanted, Map<String, String> state,
                                       String placedItem) {
        if (!now.is(wanted)) {
            Making making = Making.of(wanted);
            if (making == null || !making.standing().test(now)) {
                return null;
            }
            String tool = carried(person, making.click());
            return tool == null ? null : new Step(tool, false);
        }
        for (Map.Entry<String, String> entry : state.entrySet()) {
            String key = entry.getKey();
            boolean doubled = key.equals("type") && entry.getValue().equals("double");
            if (Placing.ORIENTATION.contains(key) && !doubled) {
                continue;
            }
            Property<?> property = now.getBlock().getStateDefinition().getProperty(key);
            if (property == null || entry.getValue().equals(valueName(now, property))) {
                continue;
            }
            Step step = stepFor(person, now, key, entry.getValue(), valueName(now, property), placedItem, doubled);
            if (step != null) {
                return step;
            }
        }
        return null;
    }

    private static @Nullable Step stepFor(AgentBody person, BlockState now, String key, String want, String is,
                                          String placedItem, boolean doubled) {
        if (doubled || (COUNTS.contains(key) && Integer.parseInt(want) > Integer.parseInt(is))) {
            return person.inventory().count(placedItem) > 0 ? new Step(null, true) : null;
        }
        if (BY_HAND.contains(key) || (key.equals("powered") && now.getBlock() instanceof LeverBlock)) {
            return new Step(null, false);
        }
        switch (key) {
            case "lit" -> {
                if (want.equals("true")) {
                    return held(person, stack -> stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE));
                }
                // A campfire is put out with a shovel; a candle by hand.
                return now.getBlock() instanceof CampfireBlock
                        ? held(person, stack -> stack.is(ItemTags.SHOVELS)) : new Step(null, false);
            }
            case "has_book" -> {
                return want.equals("true") ? held(person, stack -> stack.is(ItemTags.LECTERN_BOOKS)) : null;
            }
            case "has_record" -> {
                return want.equals("true") ? held(person, stack -> stack.has(DataComponents.JUKEBOX_PLAYABLE)) : null;
            }
            case "charges" -> {
                return Integer.parseInt(want) > Integer.parseInt(is)
                        ? held(person, stack -> stack.is(Items.GLOWSTONE)) : null;
            }
            default -> {
                return null;
            }
        }
    }

    private static @Nullable Step held(AgentBody person, Predicate<ItemStack> wanted) {
        String id = carried(person, wanted);
        return id == null ? null : new Step(id, false);
    }

    /** The id of a carried stack the predicate takes, or null. */
    private static @Nullable String carried(AgentBody person, Predicate<ItemStack> wanted) {
        for (int slot = 0; slot < Inventory.SIZE; slot++) {
            dev.luizloyola.anima.core.inv.ItemStack stack = person.inventory().get(slot);
            if (!stack.isEmpty() && wanted.test(ItemStacks.toVanilla(stack, person.level().registryAccess()))) {
                return stack.id();
            }
        }
        return null;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
