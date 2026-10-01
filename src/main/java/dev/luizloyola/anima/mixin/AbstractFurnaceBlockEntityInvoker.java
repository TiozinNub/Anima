package dev.luizloyola.anima.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** How long a furnace burns a fuel, by the furnace's own rule. Connector-safe. */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceBlockEntityInvoker {

    //? if >=26.3 {
    /*@Invoker("getBurnDuration")
    int anima$burnDuration(net.minecraft.server.level.ServerLevel level, ItemStack fuel);
    *///?} else {
    @Invoker("getBurnDuration")
    int anima$burnDuration(net.minecraft.world.level.block.entity.FuelValues fuels, ItemStack fuel);
    //?}
}
