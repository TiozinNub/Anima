package dev.luizloyola.anima.compat.agent;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * The arm swing, done and seen. 26.3 made the caller name the animation (an item now carries one
 * for hitting and one for using, where before it carried one) and hid the {@code swinging} field
 * behind a getter; older nodes have the one-argument {@code swing} and the public field.
 *
 * <p>The two swings are vanilla's own split: {@link #swingToAttack} is what a left click does,
 * {@link #swingToInteract} a right click. Neither echoes the swing back to the body itself,
 * which is what the old one-argument {@code swing} did too.
 */
public final class Arms {

    private Arms() {
    }

    /** The swing of a hit or a mined block. */
    public static void swingToAttack(LivingEntity body, InteractionHand hand) {
        //? if >=26.3 {
        /*body.swing(hand, body.getItemInHand(hand).getAttackAnimation(), false);
        *///?} else {
        body.swing(hand);
        //?}
    }

    /** The swing of a placed block or a used container. */
    public static void swingToInteract(LivingEntity body, InteractionHand hand) {
        //? if >=26.3 {
        /*body.swing(hand, body.getItemInHand(hand).getInteractAnimation(), false);
        *///?} else {
        body.swing(hand);
        //?}
    }

    /** Whether the arm is mid-swing right now. */
    public static boolean swinging(LivingEntity body) {
        //? if >=26.3 {
        /*return body.isSwinging();
        *///?} else {
        return body.swinging;
        //?}
    }

    /** Whether {@code hand} is mid-swing with a thrust rather than a whack: a spear's swing. */
    public static boolean stabbing(LivingEntity body, InteractionHand hand) {
        //? if >=26.3 {
        /*LivingEntity.SwingDescription swing = body.getCurrentSwing();
        return body.isSwinging() && swing != null && swing.hand() == hand
                && swing.animation().type() == SwingAnimationType.STAB;
        *///?} else {
        SwingAnimation swing = body.getItemInHand(hand).get(DataComponents.SWING_ANIMATION);
        return body.swinging && body.swingingArm == hand && swing != null
                && swing.type() == SwingAnimationType.STAB;
        //?}
    }
}
