package dev.luizloyola.anima.mod.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.luizloyola.anima.compat.sense.LevelProbe;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Writes down what perception makes of every cell in a box — the {@link BlockKind} it would grow
 * from and the {@link BlockProbe.Sight} a ray would meet — one line per cell, to a file.
 *
 * <p><b>This exists to be diffed.</b> An optimisation to the probe (a memo per blockstate, a held
 * chunk, a heightmap read through it) changes verdicts when it is subtly wrong, and no behaviour
 * announces that. Dump a scene on the old build, dump it on the new one, diff: the only legitimate
 * difference is the name in the header. {@code NavDump} is the sibling for navigation.
 *
 * <p>Resolves no agent, so a datapack function can sweep a whole scene from the console.
 */
public final class ProbeDump {
    private ProbeDump() {
    }

    /** Where dumps land, relative to the server's working directory. */
    private static final Path DIR = Path.of("probe-dumps");
    /** No path separators, no traversal, no surprises — this string becomes a file name. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /**
     * Every cell is a live block read on the server thread: a mistyped coordinate must fail
     * instantly rather than freeze the game.
     */
    private static final int MAX_CELLS = 2_000_000;

    /** The {@code dump} node, mounted by {@code AgentCommands.probe()}. */
    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("dump")
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .then(Commands.argument("file", StringArgumentType.word())
                                        .executes(ctx -> dump(ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "from"),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "to"),
                                                StringArgumentType.getString(ctx, "file"))))));
    }

    private static int dump(CommandSourceStack source, BlockPos from, BlockPos to, String name) {
        if (!NAME.matcher(name).matches()) {
            Replies.fail(source, Component.literal(
                    "name must match " + NAME.pattern() + ": " + name));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos min = new BlockPos(
                Math.min(from.getX(), to.getX()),
                Math.max(Math.min(from.getY(), to.getY()), level.getMinY()),
                Math.min(from.getZ(), to.getZ()));
        BlockPos max = new BlockPos(
                Math.max(from.getX(), to.getX()),
                Math.min(Math.max(from.getY(), to.getY()), level.getMaxY()),
                Math.max(from.getZ(), to.getZ()));
        long cells = (long) (max.getX() - min.getX() + 1)
                * (max.getY() - min.getY() + 1)
                * (max.getZ() - min.getZ() + 1);
        if (cells > MAX_CELLS) {
            Replies.fail(source, Component.literal(
                    "box is " + cells + " cells, over the " + MAX_CELLS + " limit"));
            return 0;
        }

        LevelProbe probe = new LevelProbe(level);
        Path file = DIR.resolve(name + ".txt");
        int written = 0;
        try {
            Files.createDirectories(DIR);
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write("# probe dump: " + name + "\n");
                out.write("# box " + min.getX() + " " + min.getY() + " " + min.getZ()
                        + " " + max.getX() + " " + max.getY() + " " + max.getZ() + "\n");
                out.write("# x y z | kind | sight | surfaceY of the column\n");
                for (int x = min.getX(); x <= max.getX(); x++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        int surface = probe.surfaceY(x, z);
                        for (int y = min.getY(); y <= max.getY(); y++) {
                            BlockKind kind = probe.at(x, y, z);
                            BlockProbe.Sight sight = probe.sightAt(x, y, z);
                            // Everything, including the empty air: a change that turned a cell
                            // into air is exactly as much of a regression as one that did not.
                            out.write(x + " " + y + " " + z + " " + kind.key() + " "
                                    + sight.name() + " " + surface + "\n");
                            written++;
                        }
                    }
                }
            }
        } catch (Exception failure) {
            Replies.fail(source, Component.literal("could not write " + file + ": " + failure));
            return 0;
        }
        int lines = written;
        Replies.send(source, () -> Component.literal(
                        "probe dump " + name + ": " + lines + " cells -> " + file)
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }
}
