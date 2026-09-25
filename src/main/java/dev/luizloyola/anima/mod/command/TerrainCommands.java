package dev.luizloyola.anima.mod.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.luizloyola.anima.core.terrain.TerrainRules;
import dev.luizloyola.anima.mod.debug.TerrainViewer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** {@code /anima terrain [radius]}: the ground around you as a site chooser would judge it. */
public final class TerrainCommands {

    private TerrainCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> terrain() {
        return Commands.literal("terrain")
                .executes(ctx -> toggle(ctx.getSource(), 0))
                .then(Commands.argument("radius", IntegerArgumentType.integer(
                                TerrainViewer.MIN_RADIUS, TerrainViewer.MAX_RADIUS))
                        .executes(ctx -> toggle(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "radius"))));
    }

    private static int toggle(CommandSourceStack source, int radius) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        int active = TerrainViewer.toggle(source.getServer(), player, radius);
        Replies.send(source, () -> (active > 0
                ? Component.translatable("anima.command.terrain.on", active,
                        TerrainRules.DEFAULTS.footprint())
                : Component.translatable("anima.command.terrain.off"))
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }
}
