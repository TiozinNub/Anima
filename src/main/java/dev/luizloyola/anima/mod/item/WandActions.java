package dev.luizloyola.anima.mod.item;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.network.chat.Component;

/**
 * Every verb the debug wand knows for a clicked block — the registry {@link DebugWandItem}
 * consults before falling back to "walk there".
 *
 * <p>Navigation is all a library that knows nothing about the world can do with a coordinate; this
 * is the seam that lets the mod which does know say something better. Each action recognises its
 * own bodies and passes on everybody else's (see {@link WandAction}).
 *
 * <p><b>First claim wins, in registration order.</b> Not a bid: this is an operator's
 * tool, and a wand whose meaning depends on which verb scored highest this tick is a wand you
 * cannot aim. Contention is settled by the consuming mod's ordering, not by Anima.
 */
public final class WandActions {

    private static final List<WandAction> REGISTERED = new CopyOnWriteArrayList<>();

    private WandActions() {
    }

    /**
     * Teaches the wand one meaning for a block click. Call during mod initialization; actions are
     * offered the click in registration order.
     */
    public static void register(WandAction action) {
        REGISTERED.add(action);
    }

    /**
     * Offers {@code click} to each registered action until one claims it, and returns that action's
     * line for the operator. Empty means nobody claimed it.
     */
    public static Optional<Component> perform(WandAction.Click click) {
        for (WandAction action : REGISTERED) {
            Optional<Component> claimed = action.useOn(click);
            if (claimed.isPresent()) {
                return claimed;
            }
        }
        return Optional.empty();
    }

    /** Forgets every registration — test teardown only. */
    public static void reset() {
        REGISTERED.clear();
    }
}
