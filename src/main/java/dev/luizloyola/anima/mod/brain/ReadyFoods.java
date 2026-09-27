package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.inv.CookedForms;
import dev.luizloyola.anima.compat.inv.FoodValues;
import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.brain.sense.FoodLookup;
import dev.luizloyola.anima.core.brain.task.ReadyFood;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Gives {@link ReadyFood}'s spec the running server's food values and recipes. */
public final class ReadyFoods {

    private ReadyFoods() {
    }

    public static void install() {
        // Touched at init, not at start: a saved plan names the spec, and chunks load before a
        // server reports STARTED.
        ReadyFood.SPEC.name();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> ReadyFood.install(new FoodLookup() {
            @Override
            public Optional<FoodValue> of(ItemStack stack) {
                return FoodValues.of(stack, server.registryAccess());
            }

            @Override
            public Optional<FoodValue> cookedForm(ItemStack stack) {
                return CookedForms.of(stack, server);
            }
        }));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ReadyFood.install(null));
    }
}
