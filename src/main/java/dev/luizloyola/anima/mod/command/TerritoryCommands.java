package dev.luizloyola.anima.mod.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.core.territory.Claimed;
import dev.luizloyola.anima.core.territory.Reason;
import dev.luizloyola.anima.core.territory.Territory;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.anima.mod.territory.Territories;
import dev.luizloyola.anima.mod.territory.TerritoryViewer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /anima territory}: the chunks the subject's party holds, and what it was last told.
 * {@code claim}, {@code grow} and {@code unclaim} are the operator's, and log like any claim;
 * {@code view} is a per-player switch, so it is mounted at the root alone.
 */
public final class TerritoryCommands {

    private static final int SHOWN = 5;

    private TerritoryCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> territory() {
        return Commands.literal("territory")
                .executes(TerritoryCommands::show)
                .then(Commands.literal("log")
                        .executes(ctx -> log(ctx, 20))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 4096))
                                .executes(ctx -> log(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("claim")
                        .executes(ctx -> claim(ctx, here(ctx), here(ctx), 0))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> claim(ctx, pos(ctx, "pos"), pos(ctx, "pos"), 0))))
                .then(Commands.literal("grow")
                        .executes(ctx -> claim(ctx, here(ctx), here(ctx), Territories.margin()))
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .executes(ctx -> claim(ctx, pos(ctx, "from"), pos(ctx, "from"),
                                        Territories.margin()))
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(ctx -> claim(ctx, pos(ctx, "from"), pos(ctx, "to"),
                                                Territories.margin())))))
                .then(Commands.literal("unclaim")
                        .executes(ctx -> unclaim(ctx, here(ctx)))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> unclaim(ctx, pos(ctx, "pos")))));
    }

    /** The root-only half: whether the caller sees every party's ground. */
    public static LiteralArgumentBuilder<CommandSourceStack> view() {
        return Commands.literal("territory")
                .then(Commands.literal("view")
                        .executes(ctx -> viewShow(ctx.getSource()))
                        .then(Commands.argument("on", BoolArgumentType.bool())
                                .executes(ctx -> view(ctx.getSource(), BoolArgumentType.getBool(ctx, "on")))));
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        Optional<PartyId> party = partyOf(ctx, false);
        if (party == null) {
            return 0;
        }
        Territory territory = Territories.of(server);
        SortedSet<ChunkKey> area = party.map(territory::area).orElse(java.util.Collections.emptySortedSet());
        String name = party.map(territory::name).orElse("—");
        if (area.isEmpty()) {
            Replies.send(source, () -> Component.translatable("anima.command.territory.none", name)
                    .withStyle(ChatFormatting.GRAY));
        } else {
            int[] box = bounds(area);
            Replies.send(source, () -> Component.translatable("anima.command.territory.header", name,
                    area.size(), "[" + box[0] + ", " + box[1] + "]", "[" + box[2] + ", " + box[3] + "]")
                    .withStyle(ChatFormatting.AQUA));
        }
        party.ifPresent(id -> events(source, territory.history(id), SHOWN));
        return area.size();
    }

    private static int log(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack source = ctx.getSource();
        Optional<PartyId> party = partyOf(ctx, false);
        if (party == null) {
            return 0;
        }
        Territory territory = Territories.of(source.getServer());
        List<Claimed> history = party.map(territory::history).orElse(List.of());
        if (history.isEmpty()) {
            String name = party.map(territory::name).orElse("—");
            Replies.send(source, () -> Component.translatable("anima.command.territory.log.none", name)
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        events(source, history, count);
        return history.size();
    }

    private static void events(CommandSourceStack source, List<Claimed> history, int count) {
        for (Claimed event : history.subList(Math.max(0, history.size() - count), history.size())) {
            Replies.send(source, () -> Component.translatable("anima.command.territory.event",
                    event.tick(), event.describe())
                    .withStyle(event.granted() ? ChatFormatting.GRAY : ChatFormatting.GOLD));
        }
    }

    private static int claim(CommandContext<CommandSourceStack> ctx, BlockPos from, BlockPos to, int margin)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        Optional<PartyId> party = partyOf(ctx, true);
        if (party == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        Set<ChunkKey> footprint = ChunkKey.covering(dimension(source), from.getX(), from.getZ(),
                to.getX(), to.getZ());
        Claimed event = Territories.of(server).grow(party.get(), footprint, margin,
                Reason.of(Reason.Kind.OP, "by " + source.getTextName()), Territories.now(server));
        return reply(source, event);
    }

    private static int unclaim(CommandContext<CommandSourceStack> ctx, BlockPos at) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        Optional<PartyId> party = partyOf(ctx, false);
        if (party == null) {
            return 0;
        }
        if (party.isEmpty()) {
            Replies.fail(source, Component.translatable("anima.command.territory.none", "—"));
            return 0;
        }
        MinecraftServer server = source.getServer();
        Claimed event = Territories.of(server).release(party.get(),
                Set.of(ChunkKey.at(dimension(source), at.getX(), at.getZ())),
                Reason.of(Reason.Kind.OP, "by " + source.getTextName()), Territories.now(server));
        return reply(source, event);
    }

    private static int reply(CommandSourceStack source, Claimed event) {
        String name = Territories.of(source.getServer()).name(event.party());
        if (!event.granted()) {
            Replies.fail(source, Component.translatable("anima.command.territory.refused", name,
                    event.describe()));
            return 0;
        }
        // Logged: the journals and territory.log say so too, but an operator watches one console.
        Replies.send(source, () -> Component.translatable("anima.command.territory.changed", name,
                event.describe()).withStyle(ChatFormatting.AQUA), event.changed());
        return Math.max(1, event.added().size() + event.removed().size());
    }

    private static int viewShow(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        boolean on = TerritoryViewer.watching(source.getServer(), player);
        Replies.send(source, () -> Component.translatable(on ? "anima.command.territory.view.on"
                : "anima.command.territory.view.off", TerritoryViewer.RANGE_BLOCKS).withStyle(ChatFormatting.GRAY));
        return on ? 1 : 0;
    }

    private static int view(CommandSourceStack source, boolean on) throws CommandSyntaxException {
        TerritoryViewer.watch(source.getServer(), source.getPlayerOrException(), on);
        return viewShow(source);
    }

    /**
     * The subject's party: empty when it has none, {@code null} when no subject resolved (already
     * reported). {@code mint} makes a party of one for a loner, as founding a place does.
     */
    private static Optional<PartyId> partyOf(CommandContext<CommandSourceStack> ctx, boolean mint) {
        AgentId who = Subject.id(ctx);
        if (who == null) {
            return null;
        }
        PartyData parties = PartyData.get(ctx.getSource().getServer());
        return mint ? Optional.of(parties.partyOf(who)) : parties.currentPartyOf(who);
    }

    private static BlockPos here(CommandContext<CommandSourceStack> ctx) {
        return BlockPos.containing(ctx.getSource().getPosition());
    }

    private static BlockPos pos(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return BlockPosArgument.getBlockPos(ctx, name);
    }

    private static String dimension(CommandSourceStack source) {
        return source.getLevel().dimension().identifier().toString();
    }

    /** min x, min z, max x, max z, in chunks. */
    private static int[] bounds(Set<ChunkKey> area) {
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (ChunkKey chunk : area) {
            box[0] = Math.min(box[0], chunk.x());
            box[1] = Math.min(box[1], chunk.z());
            box[2] = Math.max(box[2], chunk.x());
            box[3] = Math.max(box[3], chunk.z());
        }
        return box;
    }
}
