package dev.luizloyola.anima.compat.craft;

import dev.luizloyola.anima.compat.agent.Arms;
import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.brain.act.CampfireAccess;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Campfire;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** {@link CampfireAccess} over a campfire block entity, reached from {@code eyes}. */
public final class WorldCampfires implements CampfireAccess {

    private final LivingEntity eyes;

    public WorldCampfires(LivingEntity eyes) {
        this.eyes = eyes;
    }

    @Override
    public Optional<View> read(Pos at) {
        CampfireBlockEntity fire = fireAt(at);
        if (fire == null) {
            return Optional.empty();
        }
        HolderLookup.Provider registries = eyes.level().registryAccess();
        List<ItemStack> slots = new ArrayList<>(fire.getItems().size());
        for (net.minecraft.world.item.ItemStack slot : fire.getItems()) {
            slots.add(ItemStacks.toCore(slot, registries));
        }
        boolean lit = fire.getBlockState().hasProperty(CampfireBlock.LIT)
                && fire.getBlockState().getValue(CampfireBlock.LIT);
        return Optional.of(new View(slots, lit));
    }

    @Override
    public boolean place(Pos at, ItemStack stack) {
        CampfireBlockEntity fire = fireAt(at);
        if (fire == null || stack.isEmpty() || !(eyes.level() instanceof ServerLevel level)) {
            return false;
        }
        net.minecraft.world.item.ItemStack one = ItemStacks.toVanilla(stack.withCount(1), level.registryAccess());
        // Vanilla's own way in, as a player's click takes: the recipe check, the cook time, the
        // game event and the client update all come with it.
        if (one.isEmpty() || !fire.placeFood(level, eyes, one)) {
            return false;
        }
        Arms.swingToInteract(eyes, InteractionHand.MAIN_HAND);
        return true;
    }

    private @Nullable CampfireBlockEntity fireAt(Pos at) {
        BlockPos pos = new BlockPos(at.x(), at.y(), at.z());
        if (eyes.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > Campfire.REACH * Campfire.REACH) {
            return null;
        }
        return eyes.level().getBlockEntity(pos) instanceof CampfireBlockEntity fire ? fire : null;
    }
}
