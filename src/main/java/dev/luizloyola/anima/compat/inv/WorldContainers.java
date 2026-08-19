package dev.luizloyola.anima.compat.inv;

import dev.luizloyola.anima.core.brain.act.ContainerAccess;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The {@link ContainerAccess} port over a live level, seen from one body's eyes — built on the
 * {@link LivingEntity} itself rather than the mod-level body, the same shape
 * {@link dev.luizloyola.anima.compat.sense.LevelProbe} uses, so this stays an ordinary compat
 * facade with nothing above it to reach into.
 *
 * <p>Resolves the block entity at the cell and refuses beyond {@link Store#REACH} (eye to block
 * centre — the geometry the breaker and placer use over their own, looser number); a cell with no
 * {@link Container}, or one out of reach, answers empty rather than throwing. Every read or write
 * crosses through {@link ItemStacks}, so components round-trip the way they do everywhere else.
 *
 * <p><b>And it is not silent.</b> The lid, the creak and the vibration bus are {@link Lids}; the arm
 * is here, one swing per stack that really moved, because a grab nobody sees is a chest emptying
 * itself. Where the body LOOKS is neither — the transfer tasks hold that claim themselves, since a
 * one-shot glance from an actuator lapses before the open beat is over.
 */
public final class WorldContainers implements ContainerAccess {
    private final LivingEntity eyes;
    private final Level level;

    public WorldContainers(LivingEntity eyes) {
        this.eyes = eyes;
        this.level = eyes.level();
    }

    @Override
    public Optional<List<ItemStack>> contents(Pos at) {
        Container container = containerAt(at);
        if (container == null) {
            return Optional.empty();
        }
        HolderLookup.Provider registries = level.registryAccess();
        List<ItemStack> items = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            net.minecraft.world.item.ItemStack held = container.getItem(slot);
            if (!held.isEmpty()) {
                items.add(ItemStacks.toCore(held, registries));
            }
        }
        return Optional.of(List.copyOf(items));
    }

    @Override
    public void open(Pos at) {
        // Gated on one actually being there, so a body that walked to a chest somebody has since
        // mined creaks at nothing. close() is deliberately NOT gated — see Lids.close.
        if (containerAt(at) == null) {
            return;
        }
        Lids.open(level, new BlockPos(at.x(), at.y(), at.z()), eyes);
    }

    @Override
    public void close(Pos at) {
        Lids.close(level, new BlockPos(at.x(), at.y(), at.z()), eyes);
    }

    @Override
    public int insert(Pos at, ItemStack stack) {
        Container container = containerAt(at);
        if (container == null || stack.isEmpty()) {
            return 0;
        }
        HolderLookup.Provider registries = level.registryAccess();
        net.minecraft.world.item.ItemStack template = ItemStacks.toVanilla(stack, registries);
        if (template.isEmpty()) {
            return 0; // an id the running registry no longer knows
        }
        int remaining = stack.count();
        int size = container.getContainerSize();
        // First pass: top up slots already holding the same kind.
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            net.minecraft.world.item.ItemStack existingVanilla = container.getItem(slot);
            if (existingVanilla.isEmpty()) {
                continue;
            }
            ItemStack existing = ItemStacks.toCore(existingVanilla, registries);
            if (!existing.canStackWith(stack) || !container.canPlaceItem(slot, existingVanilla)) {
                continue;
            }
            int cap = Math.min(existing.maxStackSize(), container.getMaxStackSize(existingVanilla));
            int moved = Math.min(remaining, Math.max(0, cap - existing.count()));
            if (moved <= 0) {
                continue;
            }
            container.setItem(slot,
                    ItemStacks.toVanilla(existing.withCount(existing.count() + moved), registries));
            remaining -= moved;
        }
        // Second pass: whatever is left spills into empty slots.
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            if (!container.getItem(slot).isEmpty() || !container.canPlaceItem(slot, template)) {
                continue;
            }
            int cap = Math.min(stack.maxStackSize(), container.getMaxStackSize(template));
            int moved = Math.min(remaining, cap);
            if (moved <= 0) {
                continue;
            }
            container.setItem(slot, ItemStacks.toVanilla(stack.withCount(moved), registries));
            remaining -= moved;
        }
        int accepted = stack.count() - remaining;
        if (accepted > 0) {
            container.setChanged();
            // One grab, and only when something really moved. Vanilla's own guard means a second
            // call in the same tick — the refund path PutItems runs — costs no extra packet.
            eyes.swing(InteractionHand.MAIN_HAND);
        }
        return accepted;
    }

    @Override
    public ItemStack take(Pos at, ItemSpec spec, int max) {
        // A non-positive max must be a no-op: Math.min(max, count) below would otherwise let a
        // negative max make the rewritten remainder LARGER than the slot's original count — a
        // duplication hiding behind an EMPTY return.
        if (max <= 0) {
            return ItemStack.EMPTY;
        }
        Container container = containerAt(at);
        if (container == null) {
            return ItemStack.EMPTY;
        }
        HolderLookup.Provider registries = level.registryAccess();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            net.minecraft.world.item.ItemStack held = container.getItem(slot);
            if (held.isEmpty()) {
                continue;
            }
            ItemStack core = ItemStacks.toCore(held, registries);
            if (!spec.matches(core.id())) {
                continue;
            }
            int taken = Math.min(max, core.count());
            int remainder = core.count() - taken;
            container.setItem(slot, remainder > 0
                    ? ItemStacks.toVanilla(core.withCount(remainder), registries)
                    : net.minecraft.world.item.ItemStack.EMPTY);
            container.setChanged();
            eyes.swing(InteractionHand.MAIN_HAND);
            return core.withCount(taken);
        }
        return ItemStack.EMPTY;
    }

    /** The container at {@code at}, or {@code null} beyond {@link Store#REACH} or with none there. */
    private @Nullable Container containerAt(Pos at) {
        BlockPos pos = new BlockPos(at.x(), at.y(), at.z());
        if (eyes.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > Store.REACH * Store.REACH) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof Container container ? container : null;
    }
}
