package dev.luizloyola.anima.mod.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.nav.PathfinderService;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * {@code /anima enclosure}: how the space the subject stands in opens, as its own senses last
 * answered; {@code enclosure check [<pos>]} asks again now, where it stands or anywhere (spec:
 * {@code 2026-09-28-shelter-design.md}).
 */
public final class EnclosureCommands {

    private EnclosureCommands() {
    }

    /** A factory, not a cached node: each root that mounts it parents its own. */
    public static LiteralArgumentBuilder<CommandSourceStack> enclosure() {
        return Commands.literal("enclosure")
                .executes(EnclosureCommands::read)
                .then(Commands.literal("check")
                        .executes(ctx -> check(ctx, null))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> check(ctx,
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))));
    }

    private static int read(CommandContext<CommandSourceStack> ctx) {
        AgentBody person = Subject.body(ctx);
        if (person == null) return 0;
        String name = person.entity().getName().getString();
        Enclosure answer = person.brain().percepts().enclosure();
        if (!answer.known()) {
            Replies.send(ctx.getSource(), () -> Component.translatable(
                    "anima.command.enclosure.unknown", name).withStyle(ChatFormatting.GRAY));
            return 0;
        }
        say(ctx.getSource(), name, answer);
        return 1;
    }

    /**
     * Runs the check now, on the server thread — a few milliseconds, fine because an operator asked
     * and is waiting. What the body itself believes is untouched.
     */
    private static int check(CommandContext<CommandSourceStack> ctx, @Nullable BlockPos at) {
        AgentBody person = Subject.body(ctx);
        if (person == null) return 0;
        if (!(person.level() instanceof ServerLevel level)) return 0;
        BlockPos where = at != null ? at : person.blockPosition();
        Enclosure answer = PathfinderService.enclosure(level, where,
                MoveCapabilities.of(person.profile()), null, true).result().join();
        say(ctx.getSource(), person.entity().getName().getString(), answer);
        return 1;
    }

    private static void say(CommandSourceStack source, String name, Enclosure answer) {
        Pos from = answer.from();
        int x = from == null ? 0 : from.x();
        int y = from == null ? 0 : from.y();
        int z = from == null ? 0 : from.z();
        String openness = answer.openness().name().toLowerCase(Locale.ROOT);
        if (answer.openness() == Enclosure.Openness.OPEN) {
            Replies.send(source, () -> Component.translatable("anima.command.enclosure.open",
                    name, x, y, z).withStyle(ChatFormatting.GRAY));
            return;
        }
        Component roof = Component.translatable(answer.roofed()
                ? "anima.command.enclosure.roofed" : "anima.command.enclosure.unroofed");
        String holes = answer.holes().name().toLowerCase(Locale.ROOT);
        Replies.send(source, () -> Component.translatable("anima.command.enclosure.shut", name,
                        x, y, z, openness, answer.space().size(), roof, holes)
                .withStyle(answer.shelter() ? ChatFormatting.AQUA : ChatFormatting.YELLOW));
        if (!answer.doorsToShut().isEmpty()) {
            StringBuilder doors = new StringBuilder();
            for (Pos door : answer.doorsToShut()) {
                if (!doors.isEmpty()) doors.append(", ");
                doors.append(door.x()).append(' ').append(door.y()).append(' ').append(door.z());
            }
            Replies.send(source, () -> Component.translatable("anima.command.enclosure.to_shut",
                    doors.toString()).withStyle(ChatFormatting.GRAY));
        }
    }
}
