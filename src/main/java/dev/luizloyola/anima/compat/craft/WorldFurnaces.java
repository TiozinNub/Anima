package dev.luizloyola.anima.compat.craft;

import dev.luizloyola.anima.compat.agent.Arms;
import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.brain.act.FurnaceAccess;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Furnace;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** {@link FurnaceAccess} over a furnace block entity, reached from {@code eyes}. */
public final class WorldFurnaces implements FurnaceAccess {

    // The furnace's own slot numbers; vanilla keeps the constants protected.
    private static final int INPUT = 0;
    private static final int FUEL = 1;
    private static final int RESULT = 2;

    private final LivingEntity eyes;

    public WorldFurnaces(LivingEntity eyes) {
        this.eyes = eyes;
    }

    @Override
    public Optional<View> read(Pos at) {
        AbstractFurnaceBlockEntity furnace = furnaceAt(at);
        if (furnace == null) {
            return Optional.empty();
        }
        HolderLookup.Provider registries = eyes.level().registryAccess();
        boolean lit = furnace.getBlockState().hasProperty(AbstractFurnaceBlock.LIT)
                && furnace.getBlockState().getValue(AbstractFurnaceBlock.LIT);
        return Optional.of(new View(ItemStacks.toCore(furnace.getItem(INPUT), registries),
                ItemStacks.toCore(furnace.getItem(FUEL), registries),
                ItemStacks.toCore(furnace.getItem(RESULT), registries), lit));
    }

    @Override
    public int load(Pos at, Slot slot, ItemStack stack) {
        AbstractFurnaceBlockEntity furnace = furnaceAt(at);
        if (furnace == null || stack.isEmpty()) {
            return 0;
        }
        HolderLookup.Provider registries = eyes.level().registryAccess();
        net.minecraft.world.item.ItemStack template = ItemStacks.toVanilla(stack, registries);
        int index = slot == Slot.INPUT ? INPUT : FUEL;
        if (template.isEmpty() || !furnace.canPlaceItem(index, template)) {
            return 0;
        }
        ItemStack there = ItemStacks.toCore(furnace.getItem(index), registries);
        int moved;
        if (there.isEmpty()) {
            moved = Math.min(stack.count(), Math.min(stack.maxStackSize(), furnace.getMaxStackSize(template)));
            furnace.setItem(index, ItemStacks.toVanilla(stack.withCount(moved), registries));
        } else if (there.canStackWith(stack)) {
            moved = Math.max(0, Math.min(stack.count(), there.maxStackSize() - there.count()));
            furnace.setItem(index, ItemStacks.toVanilla(there.withCount(there.count() + moved), registries));
        } else {
            return 0;
        }
        if (moved > 0) {
            furnace.setChanged();
            Arms.swingToInteract(eyes, InteractionHand.MAIN_HAND);
        }
        return moved;
    }

    @Override
    public ItemStack takeOutput(Pos at, ItemSpec spec, int max) {
        AbstractFurnaceBlockEntity furnace = furnaceAt(at);
        if (furnace == null || max <= 0) {
            return ItemStack.EMPTY;
        }
        HolderLookup.Provider registries = eyes.level().registryAccess();
        ItemStack done = ItemStacks.toCore(furnace.getItem(RESULT), registries);
        if (done.isEmpty() || !spec.matches(done.id())) {
            return ItemStack.EMPTY;
        }
        int taken = Math.min(max, done.count());
        furnace.setItem(RESULT, done.count() > taken
                ? ItemStacks.toVanilla(done.withCount(done.count() - taken), registries)
                : net.minecraft.world.item.ItemStack.EMPTY);
        furnace.setChanged();
        Arms.swingToInteract(eyes, InteractionHand.MAIN_HAND);
        return done.withCount(taken);
    }

    private @Nullable AbstractFurnaceBlockEntity furnaceAt(Pos at) {
        BlockPos pos = new BlockPos(at.x(), at.y(), at.z());
        if (eyes.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > Furnace.REACH * Furnace.REACH) {
            return null;
        }
        return eyes.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace ? furnace : null;
    }
}
