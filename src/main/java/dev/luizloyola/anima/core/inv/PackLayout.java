package dev.luizloyola.anima.core.inv;

/**
 * Where things sit in a pack: how well a stack fits each storage slot, and how many hotbar slots
 * stay empty. Anima places pickups by it and plans tidying moves by it; what goes where is the
 * consumer's.
 *
 * <p>Weights, not a fixed table, so "tools toward the hotbar, the sword first" is a slope a pack
 * settles down rather than slots it must hit.
 */
public interface PackLayout {

    /** Every slot alike and no slot kept free: the first empty slot, as a player's pickup goes. */
    PackLayout NONE = new PackLayout() {
        @Override
        public double[] weights(ItemStack stack, Inventory pack) {
            return new double[Inventory.ARMOR_START];
        }

        @Override
        public int freeHotbar() {
            return 0;
        }
    };

    /**
     * How well {@code stack} sits in each storage slot of {@code pack}, indexed by slot and
     * {@link Inventory#ARMOR_START} long. Higher is better; only differences matter. The pack is
     * there so a stack can be weighed against its fellows — the newest of a kind before the old.
     */
    double[] weights(ItemStack stack, Inventory pack);

    /**
     * Hotbar slots left empty, so a backpack stack drawn into the hand has somewhere to go without
     * pushing what is held into the backpack.
     */
    int freeHotbar();

    /** The least a move must gain to be worth its handling time. */
    default double minGain() {
        return 1.0;
    }
}
