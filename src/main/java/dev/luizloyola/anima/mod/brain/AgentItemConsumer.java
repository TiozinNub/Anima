package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.inv.FoodValues;
import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.brain.act.ConsumeState;
import dev.luizloyola.anima.core.brain.act.ItemConsumer;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.mod.body.AgentBody;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import org.jspecify.annotations.Nullable;

/**
 * The {@link ItemConsumer} actuator <em>adapter</em>: the mouth. Core tasks say "consume slot N";
 * this class drives VANILLA's real item-use pipeline to do it, so the animation, sounds, particles,
 * duration, consume effects and stack shrink are all vanilla's. On the 26.1.2 bytecode
 * {@code LivingEntity.tick()} runs {@code updatingUsingItem()} for any living entity, so
 * {@code startUsingItem} is all it takes — no player anywhere.
 *
 * <p><b>Except nutrition</b>, which vanilla applies behind an {@code instanceof Player} gate in
 * {@code FoodProperties.onConsume}; on completion this actuator applies the item's
 * {@link FoodValue} to {@link AgentBody#metabolism()} itself.
 *
 * <p><b>The handshake with the equipment mirror.</b> Only the hand item can be used:
 * {@link #begin} arranges the CORE inventory and waits, the mirror pushes it into the hand at the
 * START of the next {@code serverAiStep}, and the brain polls {@link #state()} after the mirror that
 * tick, so PREPARING resolves on the next poll. The mirror's pull carries the in-place shrink back,
 * so this class never edits the eaten slot.
 *
 * <p><b>SETTLING burns one more tick, for the watching clients.</b> {@code detectEquipmentUpdates()}
 * runs before {@code aiStep()} (26.1.2 bytecode, offsets 125 and 178), so a hand set during
 * {@code serverAiStep} is not broadcast until the FOLLOWING tick, while the using-item flag ships
 * that same tick. Started together, the flag lands first, the client leaves {@code useItem} EMPTY
 * and stops the use without ever running a consume tick — the eating sound survived, the crumbs did
 * not.
 *
 * <p>Phases: {@code IDLE → PREPARING → SETTLING → CONSUMING → FINISHED | FAILED}; externally both
 * PREPARING and SETTLING read as {@link ConsumeState#CONSUMING}.
 */
public final class AgentItemConsumer implements ItemConsumer {
    /**
     * How many polls PREPARING may wait for the mirror before giving up. The handshake takes
     * exactly one tick (see class doc), so needing more means the hand was rearranged out from
     * under us (an external core write between begin and the push) — fail outright, never hang.
     */
    private static final int PREPARING_GRACE_TICKS = 5;

    private enum Phase { IDLE, PREPARING, SETTLING, CONSUMING, FINISHED, FAILED }

    private final AgentBody person;
    private Phase phase = Phase.IDLE;
    /** The vanilla image of the stack {@link #begin} committed to; PREPARING waits for the hand to match it. */
    private ItemStack intended = ItemStack.EMPTY;
    /** The hand stack as it looked the instant the bite started — the falling-edge comparison baseline. */
    private ItemStack baseline = ItemStack.EMPTY;
    /** The food payload to feed the body on completion; null for a food-less consumable (a potion). */
    private @Nullable FoodValue foodValue;
    private int preparingTicks;

    public AgentItemConsumer(AgentBody person) {
        this.person = person;
    }

    /**
     * Commits to consuming the stack in core slot {@code slot} (hotbar or main; the mouth does not
     * reach equipment slots). Refuses an empty slot, a non-consumable (no {@code CONSUMABLE}
     * component, or a zero duration — {@code startUsingItem} keys the countdown off
     * {@code Consumable.consumeTicks()}), and plain food on a full bar ({@link #canEat}). Accepting
     * makes the stack the hand item, selecting or swapping into the selected hotbar slot. Any
     * consumption in flight is aborted first — one mouth.
     */
    @Override
    public boolean begin(int slot) {
        abort();
        if (slot < 0 || slot >= Inventory.ARMOR_START) return false;
        Inventory inventory = this.person.inventory();
        dev.luizloyola.anima.core.inv.ItemStack stack = inventory.get(slot);
        if (stack.isEmpty()) return false;
        ItemStack vanilla = ItemStacks.toVanilla(stack, this.person.entity().registryAccess());
        Consumable consumable = vanilla.get(DataComponents.CONSUMABLE);
        if (consumable == null || consumable.consumeTicks() <= 0) return false;
        FoodValue food = FoodValues.of(stack, this.person.entity().registryAccess()).orElse(null);
        // Mirror of vanilla's can-eat gate (Consumable.startConsuming -> canConsume): a FOOD item
        // is refused when the eater couldn't benefit; a food-less consumable always may.
        if (food != null && !canEat(food.canAlwaysEat())) return false;
        if (slot >= Inventory.MAIN_START) {
            int hand = Inventory.HOTBAR_START + inventory.selectedSlot();
            dev.luizloyola.anima.core.inv.ItemStack displaced = inventory.get(hand);
            inventory.set(hand, stack);
            inventory.set(slot, displaced);
        } else {
            inventory.setSelectedSlot(slot - Inventory.HOTBAR_START);
        }
        this.intended = vanilla;
        this.foodValue = food;
        this.phase = Phase.PREPARING;
        return true;
    }

    /**
     * One poll of the phase machine — called once per task tick, after the equipment mirror ran
     * that tick (see the class doc for why that ordering is the whole trick).
     */
    @Override
    public ConsumeState state() {
        switch (this.phase) {
            case PREPARING -> tickPreparing();
            case SETTLING -> tickSettling();
            case CONSUMING -> tickConsuming();
            default -> { }
        }
        return switch (this.phase) {
            case IDLE -> ConsumeState.IDLE;
            case PREPARING, SETTLING, CONSUMING -> ConsumeState.CONSUMING;
            case FINISHED -> ConsumeState.FINISHED;
            case FAILED -> ConsumeState.FAILED;
        };
    }

    /** Waits for the mirror to put the intended stack in the visible hand. */
    private void tickPreparing() {
        if (!ItemStack.isSameItemSameComponents(
                this.person.entity().getItemInHand(InteractionHand.MAIN_HAND), this.intended)) {
            if (++this.preparingTicks > PREPARING_GRACE_TICKS) {
                this.phase = Phase.FAILED; // the hand never arrived — rearranged under us
            }
            return;
        }
        this.phase = Phase.SETTLING;
    }

    /**
     * One tick later — the tick whose {@code detectEquipmentUpdates} has now broadcast the hand (see
     * the class doc) — starts the bite, so the using-item flag reaches clients behind the item they
     * need it to apply to.
     */
    private void tickSettling() {
        ItemStack hand = this.person.entity().getItemInHand(InteractionHand.MAIN_HAND);
        if (!ItemStack.isSameItemSameComponents(hand, this.intended)) {
            this.phase = Phase.FAILED; // rearranged out from under us while the hand settled
            return;
        }
        if (this.person.entity().isUsingItem()) {
            this.phase = Phase.FAILED; // the mouth is busy with a use this actuator didn't start
            return;
        }
        this.person.entity().startUsingItem(InteractionHand.MAIN_HAND);
        if (!this.person.entity().isUsingItem()) {
            this.phase = Phase.FAILED; // defensive: startUsingItem refused (hand emptied in a race)
            return;
        }
        this.baseline = hand.copy(); // vanilla shrinks the hand stack IN PLACE — compare to a copy
        this.phase = Phase.CONSUMING;
    }

    /**
     * Watches the chew. Vanilla counts down while {@code isUsingItem()}; the falling edge is the
     * verdict tick. A hand stack that shrank by the bite (or turned into its remainder, or emptied
     * on the last item) means {@code completeUsingItem} ran: the body eats the {@link FoodValue},
     * FINISHED. An untouched stack was interrupted; one that vanished outright was taken, not eaten
     * — both FAILED, no nutrition.
     */
    private void tickConsuming() {
        if (this.person.entity().isUsingItem()) {
            return;
        }
        ItemStack hand = this.person.entity().getItemInHand(InteractionHand.MAIN_HAND);
        boolean consumed = !ItemStack.matches(hand, this.baseline)
                && (this.baseline.getCount() == 1 || !hand.isEmpty());
        if (!consumed) {
            this.phase = Phase.FAILED;
            return;
        }
        if (this.foodValue != null) {
            this.person.metabolism().eat(this.foodValue.nutrition(), this.foodValue.saturation());
        }
        this.phase = Phase.FINISHED;
    }

    /**
     * Vanilla {@code Player.canEat} mirrored (26.1.2 bytecode: {@code abilities.invulnerable ||
     * canAlwaysEat || foodData.needsFood()}): a AgentBody has no player abilities, so it reduces to
     * always-edible items, or a food bar with room. Vanilla only enforces this for players
     * ({@code Consumable.canConsume} skips the check for any other {@code LivingEntity}), so the
     * gate must live here or a full AgentBody would happily waste food.
     */
    private boolean canEat(boolean canAlwaysEat) {
        return canAlwaysEat || this.person.metabolism().foodLevel() < Metabolism.MAX_FOOD;
    }

    /** Stops any bite in progress ({@code releaseUsingItem}, vanilla's put-it-down) and resets to IDLE. */
    @Override
    public void abort() {
        // Only release a use this actuator started (CONSUMING); no use exists yet in PREPARING or
        // SETTLING, and a foreign one seen there is someone else's business to cancel, not ours.
        if (this.phase == Phase.CONSUMING && this.person.entity().isUsingItem()) {
            this.person.entity().releaseUsingItem();
        }
        this.phase = Phase.IDLE;
        this.intended = ItemStack.EMPTY;
        this.baseline = ItemStack.EMPTY;
        this.foodValue = null;
        this.preparingTicks = 0;
    }
}
