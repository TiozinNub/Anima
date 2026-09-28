package dev.luizloyola.anima.mod.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.LaidBlocks;
import dev.luizloyola.anima.mod.nav.LaidBlocksData;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code /anima laid} — the record of blocks routes laid and left: what is near, and, for building a
 * test course, marking blocks as laid or forgetting them. The record is the world's, not an
 * agent's, so it takes no subject.
 */
public final class LaidCommands {
    /** How far round the caller a bare {@code laid} looks. */
    private static final int NEAR = 32;

    private LaidCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> laid() {
        return Commands.literal("laid")
                .executes(LaidCommands::near)
                .then(Commands.literal("add")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.literal("pillar")
                                                .executes(ctx -> add(ctx, LaidBlocks.Kind.PILLAR)))
                                        .then(Commands.literal("deck")
                                                .executes(ctx -> add(ctx, LaidBlocks.Kind.DECK))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(LaidCommands::clear))));
    }

    private static LaidBlocks ledger(CommandSourceStack source) {
        return LaidBlocksData.get(source.getServer()).laid();
    }

    private static int near(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        BlockPos here = BlockPos.containing(source.getPosition());
        Map<Integer, int[]> runs = new LinkedHashMap<>();
        Map<Integer, LaidBlocks.Row> firsts = new LinkedHashMap<>();
        for (LaidBlocks.Row row : ledger(source).rows()) {
            Pos at = row.at();
            if (Math.abs(at.x() - here.getX()) > NEAR || Math.abs(at.z() - here.getZ()) > NEAR) {
                continue;
            }
            runs.computeIfAbsent(row.run(), r -> new int[1])[0]++;
            firsts.putIfAbsent(row.run(), row);
        }
        if (runs.isEmpty()) {
            Replies.send(source, () -> Component.translatable("anima.command.laid.none", NEAR)
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        for (Map.Entry<Integer, int[]> run : runs.entrySet()) {
            LaidBlocks.Row first = firsts.get(run.getKey());
            LaidBlocks.Run counts = ledger(source).run(run.getKey());
            int walked = counts == null ? 0 : counts.walked();
            String kind = first.kind().name().toLowerCase(java.util.Locale.ROOT);
            String at = first.at().x() + ", " + first.at().y() + ", " + first.at().z();
            Replies.send(source, () -> Component.translatable("anima.command.laid.run", kind,
                    run.getKey(), run.getValue()[0], at, walked).withStyle(ChatFormatting.AQUA));
        }
        return runs.size();
    }

    private static int add(CommandContext<CommandSourceStack> ctx, LaidBlocks.Kind kind)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos from = BlockPosArgument.getLoadedBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getLoadedBlockPos(ctx, "to");
        LaidBlocks laid = ledger(source);
        int run = laid.open(kind);
        int added = 0;
        for (BlockPos cell : BlockPos.betweenClosed(from, to)) {
            BlockState state = level.getBlockState(cell);
            if (state.canBeReplaced()) {
                continue;
            }
            String block = BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()).toString();
            laid.lay(new Pos(cell.getX(), cell.getY(), cell.getZ()), block, kind, null, null,
                    level.getGameTime(), run);
            added++;
        }
        int count = added;
        Replies.send(source, () -> Component.translatable("anima.command.laid.added", count,
                kind.name().toLowerCase(java.util.Locale.ROOT)).withStyle(ChatFormatting.AQUA));
        return count;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        // Not the loaded accessor: a row is forgotten wherever it is, loaded or not.
        BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");
        LaidBlocks laid = ledger(source);
        int cleared = 0;
        for (BlockPos cell : BlockPos.betweenClosed(from, to)) {
            if (laid.remove(new Pos(cell.getX(), cell.getY(), cell.getZ()))) {
                cleared++;
            }
        }
        int count = cleared;
        Replies.send(source, () -> Component.translatable("anima.command.laid.cleared", count)
                .withStyle(ChatFormatting.AQUA));
        return count;
    }
}
