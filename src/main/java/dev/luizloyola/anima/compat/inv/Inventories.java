package dev.luizloyola.anima.compat.inv;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.core.inv.HandChange;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for the pure {@link Inventory}: the non-empty slots only (compact, like a vanilla
 * container), plus the selected hotbar slot and any hand change under way. The per-stack cap is <em>re-derived</em> on decode
 * ({@link ItemStacks#maxStackSize}), never written, so it cannot go stale against a changed item.
 *
 * <p>In {@code compat}: codecs are DataFixerUpper, and deriving the cap is version-specific lookup.
 */
public final class Inventories {
    private Inventories() {}

    private record SlotData(int slot, String id, int count, String components) {}

    private static final Codec<SlotData> SLOT_CODEC = RecordCodecBuilder.create(s -> s.group(
            Codec.INT.fieldOf("slot").forGetter(SlotData::slot),
            Codec.STRING.fieldOf("id").forGetter(SlotData::id),
            Codec.INT.fieldOf("count").forGetter(SlotData::count),
            Codec.STRING.optionalFieldOf("components", "").forGetter(SlotData::components)
    ).apply(s, SlotData::new));

    private static final Codec<HandChange.Kind> CHANGE_KIND = Codec.STRING.comapFlatMap(name -> {
        try {
            return DataResult.success(HandChange.Kind.valueOf(name));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "unknown hand change: " + name);
        }
    }, Enum::name);

    private static final Codec<HandChange> CHANGE_CODEC = RecordCodecBuilder.create(c -> c.group(
            CHANGE_KIND.fieldOf("kind").forGetter(HandChange::kind),
            Codec.INT.fieldOf("from").forGetter(HandChange::from),
            Codec.INT.fieldOf("to").forGetter(HandChange::to),
            Codec.LONG.fieldOf("ready_at").forGetter(HandChange::readyAt),
            Codec.LONG.fieldOf("asked_at").forGetter(HandChange::askedAt)
    ).apply(c, HandChange::new));

    /** A swap under way is saved too: a restart mid-draw finishes the same draw. */
    public static final Codec<Inventory> CODEC = RecordCodecBuilder.create(inv -> inv.group(
            SLOT_CODEC.listOf().fieldOf("slots").forGetter(Inventories::toSlots),
            Codec.INT.optionalFieldOf("selected", 0).forGetter(Inventory::selectedSlot),
            CHANGE_CODEC.optionalFieldOf("change").forGetter(i -> Optional.ofNullable(i.change()))
    ).apply(inv, Inventories::fromSlots));

    private static List<SlotData> toSlots(Inventory inv) {
        return inv.occupied().stream()
                .map(e -> new SlotData(e.slot(), e.stack().id(), e.stack().count(), e.stack().components()))
                .toList();
    }

    private static Inventory fromSlots(List<SlotData> slots, int selected, Optional<HandChange> change) {
        Inventory inv = new Inventory();
        for (SlotData s : slots) {
            // Skip what a corrupt or older save could hold.
            if (s.slot() < 0 || s.slot() >= Inventory.SIZE || s.count() <= 0) continue;
            inv.set(s.slot(), ItemStack.of(s.id(), s.count(), ItemStacks.maxStackSize(s.id()), s.components()));
        }
        if (selected >= 0 && selected < Inventory.HOTBAR_SIZE) inv.setSelectedSlot(selected);
        change.filter(Inventories::valid).map(HandChange::asRestored).ifPresent(inv::setChange);
        return inv;
    }

    private static boolean valid(HandChange change) {
        return change.from() >= -1 && change.from() < Inventory.ARMOR_START
                && change.to() >= -1 && change.to() < Inventory.SIZE;
    }
}
