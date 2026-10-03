package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Handover;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Moves what an accepted offer held out, on the accepting line ({@link Handover}): out of the
 * giver's pack and into the taker's, and straight into a player's inventory — accepting is taking
 * (decision: Luiz, 2026-10-02). Whole stacks move, components and all. What does not fit stays with
 * the giver, and a giver who no longer has it gives what is left.
 */
final class Handovers {

    /** Somebody's storage, as far as handing over asks of it. */
    interface Pack {

        /** Takes up to {@code count} of {@code id} out, as the stacks it came in. */
        List<ItemStack> take(String id, int count);

        /** Puts {@code stack} in; returns what did not fit. */
        ItemStack put(ItemStack stack);
    }

    private Handovers() {
    }

    /** Line {@code index} of {@code e} is an accept: move what the offer it answered held out. */
    static void accepted(MinecraftServer server, Encounter e, int index) {
        Handover.accepted(e, index).ifPresent(offer -> move(server, offer, e.transcript().get(index)));
    }

    /** Line {@code index} is a take-back: what was not wanted goes back to whoever gave it. */
    static void takenBack(MinecraftServer server, Encounter e, int index) {
        Handover.takenBack(e, index).ifPresent(unwanted -> move(server, unwanted, e.transcript().get(index)));
    }

    /** Moves the items {@code from} names out of its author's pack into {@code to}'s author's. */
    private static void move(MinecraftServer server, Utterance from, Utterance to) {
        Pack giver = pack(server, from.author());
        Pack taker = pack(server, to.author());
        if (giver == null || taker == null) {
            return; // one of them is not here to hand over to, and nothing moves
        }
        for (Handover.Item item : Handover.read(from.payload())) {
            for (ItemStack stack : giver.take(item.id(), item.count())) {
                ItemStack left = taker.put(stack);
                if (!left.isEmpty()) {
                    giver.put(left);
                }
            }
        }
    }

    static @Nullable Pack pack(MinecraftServer server, @Nullable AgentId who) {
        if (who == null) {
            return null;
        }
        AgentBody body = AgentBodies.findLoaded(server, who);
        if (body != null) {
            return of(body.inventory());
        }
        ServerPlayer player = server.getPlayerList().getPlayer(who.value());
        return player == null ? null : of(player, server.registryAccess());
    }

    private static Pack of(Inventory inventory) {
        return new Pack() {
            @Override
            public List<ItemStack> take(String id, int count) {
                List<ItemStack> out = new ArrayList<>();
                int left = count;
                for (int slot = Inventory.HOTBAR_START; slot < Inventory.ARMOR_START && left > 0; slot++) {
                    ItemStack held = inventory.get(slot);
                    if (held.isEmpty() || !held.id().equals(id)) {
                        continue;
                    }
                    int n = Math.min(left, held.count());
                    out.add(held.withCount(n));
                    inventory.set(slot, held.count() == n ? ItemStack.EMPTY : held.withCount(held.count() - n));
                    left -= n;
                }
                return out;
            }

            @Override
            public ItemStack put(ItemStack stack) {
                return inventory.add(stack);
            }
        };
    }

    /** The player's hotbar and main pack — never armour or the off hand. */
    private static Pack of(ServerPlayer player, HolderLookup.Provider registries) {
        int storage = Inventory.HOTBAR_SIZE + Inventory.MAIN_SIZE;
        return new Pack() {
            @Override
            public List<ItemStack> take(String id, int count) {
                List<ItemStack> out = new ArrayList<>();
                int left = count;
                for (int slot = 0; slot < storage && left > 0; slot++) {
                    net.minecraft.world.item.ItemStack held = player.getInventory().getItem(slot);
                    ItemStack core = ItemStacks.toCore(held, registries);
                    if (core.isEmpty() || !core.id().equals(id)) {
                        continue;
                    }
                    int n = Math.min(left, held.getCount());
                    out.add(core.withCount(n));
                    held.shrink(n);
                    left -= n;
                }
                player.getInventory().setChanged();
                return out;
            }

            @Override
            public ItemStack put(ItemStack stack) {
                net.minecraft.world.item.ItemStack vanilla = ItemStacks.toVanilla(stack, registries);
                if (vanilla.isEmpty()) {
                    return stack;
                }
                player.getInventory().add(vanilla); // shrinks it by what fit
                return vanilla.isEmpty() ? ItemStack.EMPTY : stack.withCount(vanilla.getCount());
            }
        };
    }
}
