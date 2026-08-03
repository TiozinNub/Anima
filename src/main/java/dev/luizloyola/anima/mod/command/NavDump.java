package dev.luizloyola.anima.mod.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.luizloyola.anima.compat.nav.WorldSnapshot;
import dev.luizloyola.anima.core.nav.CellType;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /anima nav dump <from> <to> <name>} — writes a box of the live world out as the
 * navigation vocabulary sees it, so a headless test can replay the same terrain.
 *
 * <p>Captured through {@link WorldSnapshot#classifyAt} rather than hand-drawn as an
 * {@code AsciiWorld}, so the blockstate-to-{@link CellType} step stays inside the fixture and a
 * classification bug shows up headlessly instead of only in-world.
 *
 * <p>Sparse text: one line per cell that is <em>not</em> {@link CellType#PASSABLE}. Unlisted cells
 * inside the box are passable; outside it, {@link CellType#OBSTACLE}, matching the {@code NavGrid}
 * out-of-bounds contract.
 *
 * <p>Pure geometry, no start or goal: one capture serves many path assertions.
 */
public final class NavDump {
    private NavDump() {
    }

    /** Where captures land, relative to the server's working directory. */
    private static final Path DIR = Path.of("nav-dumps");
    /** No path separators, no traversal, no surprises — this string becomes a file name. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /**
     * Every cell is a live block read on the server thread: a mistyped coordinate must fail
     * instantly rather than freeze the game.
     */
    private static final int MAX_CELLS = 8_000_000;

    /**
     * The {@code dump} node, mounted by {@code AgentCommands.nav()}. Resolves no agent, unlike its
     * siblings, so a datapack function can sweep a whole course from the console.
     */
    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("dump")
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> dump(ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "from"),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "to"),
                                                StringArgumentType.getString(ctx, "name"))))));
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

        Map<CellType, Integer> counts = new EnumMap<>(CellType.class);
        int unloaded = 0;
        Path file = DIR.resolve(name + ".txt");
        try {
            Files.createDirectories(DIR);
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write("# nav capture: " + name + "\n");
                out.write("# box " + min.getX() + " " + min.getY() + " " + min.getZ()
                        + " " + max.getX() + " " + max.getY() + " " + max.getZ() + "\n");
                out.write("# codes");
                for (CellType type : CellType.values()) {
                    out.write(" " + type.code() + "=" + type.name());
                }
                out.write("\n# unlisted cells inside the box are "
                        + CellType.PASSABLE.name() + "; outside it, "
                        + CellType.OBSTACLE.name() + "\n");

                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                for (int x = min.getX(); x <= max.getX(); x++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        // Loadedness is a property of the column, so ask once per column rather
                        // than per cell — and never trigger a load, exactly as capture() does.
                        pos.set(x, min.getY(), z);
                        if (!level.isLoaded(pos)) {
                            unloaded++;
                            continue;
                        }
                        for (int y = min.getY(); y <= max.getY(); y++) {
                            pos.setY(y);
                            CellType type = WorldSnapshot.classifyAt(level, pos);
                            counts.merge(type, 1, Integer::sum);
                            if (type == CellType.PASSABLE) {
                                continue;
                            }
                            out.write(type.code() + " " + x + " " + y + " " + z + "\n");
                        }
                    }
                }
            }
        } catch (IOException e) {
            Replies.fail(source, Component.literal("could not write " + file + ": " + e));
            return 0;
        }

        // An unloaded column is not an error, but it silently becomes OBSTACLE on replay — so
        // report how many were skipped.
        int skipped = unloaded;
        StringBuilder tally = new StringBuilder();
        for (CellType type : CellType.values()) {
            tally.append(' ').append(type.name().toLowerCase()).append('=')
                    .append(counts.getOrDefault(type, 0));
        }
        Replies.send(source, () -> Component.literal(
                        file.toAbsolutePath() + ":" + tally
                                + (skipped > 0 ? " (" + skipped + " unloaded columns skipped)" : ""))
                .withStyle(skipped > 0 ? ChatFormatting.YELLOW : ChatFormatting.AQUA));
        return 1;
    }
}
