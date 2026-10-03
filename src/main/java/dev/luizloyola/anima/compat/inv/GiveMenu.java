package dev.luizloyola.anima.compat.inv;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
//? if >=26.1 {
import net.minecraft.world.inventory.ContainerInput;
//?} else {
/*import net.minecraft.world.inventory.ClickType;
*///?}
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The give screen (2026-10-02-food-and-replies-design.md): a one-row chest the player fills, and
 * closing it is the hand-over. Vanilla's own {@code GENERIC_9x1}, so every client already has the
 * screen — a vanilla one included. The slots past the room the taker has hold a named gray pane
 * nobody can move or take, which is the greyed slot (decision: Luiz, 2026-10-02).
 */
public final class GiveMenu extends ChestMenu {

    public static final int SLOTS = 9;

    /** Told what was put in, on the server thread, as the screen closes. Never the panes. */
    public interface Closed {
        void closed(ServerPlayer player, List<ItemStack> given);
    }

    private final SimpleContainer box;
    private final int room;
    private final Closed onClose;
    private boolean done;

    private GiveMenu(int id, Inventory inventory, SimpleContainer box, int room, Component noRoom,
            Closed onClose) {
        super(MenuType.GENERIC_9x1, id, inventory, box, 1);
        this.box = box;
        this.room = room;
        this.onClose = onClose;
        for (int slot = room; slot < SLOTS; slot++) {
            //? if >=26.2 {
            /*ItemStack pane = new ItemStack(Items.STAINED_GLASS_PANE.gray());
            *///?} else {
            ItemStack pane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            //?}
            // Named, so no pane of the player's own ever stacks onto it.
            pane.set(DataComponents.CUSTOM_NAME, noRoom);
            box.setItem(slot, pane);
        }
    }

    /** Opens the screen on {@code player}: {@code room} usable slots of {@link #SLOTS}. */
    public static void open(ServerPlayer player, Component title, int room, Component noRoom,
            Closed onClose) {
        int usable = Math.max(0, Math.min(SLOTS, room));
        player.openMenu(new SimpleMenuProvider((id, inventory, who) ->
                new GiveMenu(id, inventory, new SimpleContainer(SLOTS), usable, noRoom, onClose), title));
    }

    @Override
    //? if >=26.1 {
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (slot >= room && slot < SLOTS) {
            return; // a pane: there is no room there
        }
        super.clicked(slot, button, input, player);
    }
    //?} else {
    /*public void clicked(int slot, int button, ClickType input, Player player) {
        if (slot >= room && slot < SLOTS) {
            return; // a pane: there is no room there
        }
        super.clicked(slot, button, input, player);
    }
    *///?}

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (done) {
            return;
        }
        done = true;
        List<ItemStack> given = new ArrayList<>();
        for (int slot = 0; slot < room; slot++) {
            ItemStack stack = box.getItem(slot);
            if (!stack.isEmpty()) {
                given.add(stack.copy());
            }
        }
        box.clearContent(); // the panes go nowhere
        if (player instanceof ServerPlayer server) {
            onClose.closed(server, given);
        } else {
            given.forEach(stack -> giveBack(player, stack));
        }
    }

    /** Puts {@code stack} back in {@code player}'s inventory, dropping what does not fit at their feet. */
    public static void giveBack(Player player, ItemStack stack) {
        //? if >=26.3 {
        /*player.getInventory().placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY);
        *///?} else {
        player.getInventory().placeItemBackInInventory(stack);
        //?}
    }
}
