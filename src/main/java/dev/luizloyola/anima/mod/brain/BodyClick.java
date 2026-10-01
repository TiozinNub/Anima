package dev.luizloyola.anima.mod.brain;

import com.mojang.authlib.GameProfile;
import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A body's right-click on a block, run through vanilla's own click by a Fabric {@link FakePlayer}
 * standing at the body's eyes with the body's item in hand (Luiz, 2026-09-30: a fake player is a
 * known mechanic, and mods expect it). Whatever the click leaves in the fake's hands — the tool
 * worn, the bucket filled, the poppy taken out of its pot — goes back into the body's pack.
 *
 * <p>The fake is only ever borrowed for the length of one click: never in the player list, so none
 * of what made a Person-as-player unworkable (docs/why-not-fake-players.md) applies.
 */
final class BodyClick {

    private BodyClick() {
    }

    /**
     * Click the block at {@code pos} holding a stack of {@code itemId} from the body's pack, or with
     * the empty hand when it is null. True when vanilla says the click did something.
     *
     * <p>Unlike a player's click, a block item never places a block beside the one clicked: placing
     * is the placer's, and a click is about the block clicked.
     */
    static boolean click(AgentBody person, ServerLevel level, BlockPos pos, String itemId) {
        int slot = -1;
        ItemStack held = ItemStack.EMPTY;
        if (itemId != null) {
            slot = slotOf(person.inventory(), itemId);
            if (slot < 0) {
                return false;
            }
            held = ItemStacks.toVanilla(person.inventory().get(slot), level.registryAccess());
            person.inventory().set(slot, dev.luizloyola.anima.core.inv.ItemStack.EMPTY);
        }
        FakePlayer hand = FakePlayer.get(level, profileOf(person));
        hand.getInventory().clearContent();
        Vec3 eye = person.entity().getEyePosition();
        Vec3 aim = Vec3.atCenterOf(pos);
        Vec3 look = aim.subtract(eye);
        double flat = Math.sqrt(look.x * look.x + look.z * look.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, flat));
        hand.setPos(eye.x, eye.y - hand.getEyeHeight(), eye.z);
        hand.setYRot(yaw);
        hand.setXRot(pitch);
        hand.setYHeadRot(yaw);
        hand.setItemInHand(InteractionHand.MAIN_HAND, held);
        try {
            return run(hand, level, pos, held, hit(level, hand, eye, aim, pos));
        } finally {
            giveBack(person, level, hand, slot);
        }
    }

    private static boolean run(Player hand, ServerLevel level, BlockPos pos, ItemStack held, BlockHitResult hit) {
        BlockState state = level.getBlockState(pos);
        // ServerPlayerGameMode.useItemOn, as far as a block goes: the block's own use of the item,
        // then, if it only knows the empty hand, that.
        InteractionResult result = state.useItemOn(held, level, hand, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            return true;
        }
        if (result instanceof InteractionResult.TryEmptyHandInteraction) {
            if (state.useWithoutItem(level, hand, hit).consumesAction()) {
                return true;
            }
        }
        if (held.isEmpty() || held.getItem() instanceof BlockItem) {
            return false;
        }
        // The item's own use: a shovel on grass, a hoe on dirt, flint and steel on a candle.
        if (held.useOn(new UseOnContext(hand, InteractionHand.MAIN_HAND, hit)).consumesAction()) {
            return true;
        }
        // A bucket is not used on a block but along a look, which is why the fake looks at it.
        InteractionResult used = held.use(level, hand, InteractionHand.MAIN_HAND);
        if (used instanceof InteractionResult.Success success && success.heldItemTransformedTo() != null) {
            hand.setItemInHand(InteractionHand.MAIN_HAND, success.heldItemTransformedTo());
        }
        return used.consumesAction();
    }

    /** Where the eye's line to the block's middle first meets it; the facing face if something is in the way. */
    private static BlockHitResult hit(ServerLevel level, Player hand, Vec3 eye, Vec3 aim, BlockPos pos) {
        BlockHitResult clip = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, hand));
        if (clip.getType() == HitResult.Type.BLOCK && clip.getBlockPos().equals(pos)) {
            return clip;
        }
        Vec3 out = eye.subtract(aim);
        Direction face = Math.abs(out.y) >= Math.max(Math.abs(out.x), Math.abs(out.z))
                ? (out.y > 0 ? Direction.UP : Direction.DOWN)
                : Math.abs(out.x) >= Math.abs(out.z)
                        ? (out.x > 0 ? Direction.EAST : Direction.WEST)
                        : (out.z > 0 ? Direction.SOUTH : Direction.NORTH);
        return new BlockHitResult(aim.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5),
                face, pos, false);
    }

    /** The held stack back where it came from, and anything else the click handed over after it. */
    private static void giveBack(AgentBody person, ServerLevel level, Player hand, int slot) {
        ItemStack inHand = hand.getItemInHand(InteractionHand.MAIN_HAND);
        if (slot >= 0 && !inHand.isEmpty() && person.inventory().get(slot).isEmpty()) {
            person.inventory().set(slot, ItemStacks.toCore(inHand, level.registryAccess()));
            hand.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        for (int i = 0; i < hand.getInventory().getContainerSize(); i++) {
            ItemStack left = hand.getInventory().removeItemNoUpdate(i);
            if (left.isEmpty()) {
                continue;
            }
            dev.luizloyola.anima.core.inv.ItemStack over =
                    person.inventory().add(ItemStacks.toCore(left, level.registryAccess()));
            if (!over.isEmpty()) {
                Block.popResource(level, person.entity().blockPosition(),
                        ItemStacks.toVanilla(over, level.registryAccess()));
            }
        }
    }

    private static int slotOf(Inventory inventory, String itemId) {
        for (int slot = 0; slot < Inventory.SIZE; slot++) {
            dev.luizloyola.anima.core.inv.ItemStack stack = inventory.get(slot);
            if (!stack.isEmpty() && stack.id().equals(itemId)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * A fake of the body's own, so a protection mod reads the click as this body's and Fabric
     * keeps one per body. Not the body's own UUID: that one names an entity in the world.
     */
    private static GameProfile profileOf(AgentBody person) {
        UUID id = UUID.nameUUIDFromBytes(("anima:hand:" + person.entity().getUUID())
                .getBytes(StandardCharsets.UTF_8));
        String name = person.entity().getName().getString();
        return new GameProfile(id, name.length() > 16 ? name.substring(0, 16) : name);
    }
}
