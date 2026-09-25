package dev.luizloyola.anima.mod.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
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

/**
 * {@code /anima terrain [radius]}: the ground around you as a site chooser would judge it, and
 * {@code /anima terrain rules} to judge it by other rules than the defaults.
 */
public final class TerrainCommands {

    private TerrainCommands() {
    }

    /**
     * One field of {@link TerrainRules}, by the name the command takes. {@code slot} is its place
     * in {@link #values}; the bounds keep a typo from stalling the server, not taste.
     */
    private enum Rule {
        SMOOTH_RADIUS("smooth_radius", 0, true, 1, 16),
        MAX_SLOPE("max_slope", 1, false, 0, 1),
        MAX_ROUGH("max_rough", 2, false, 0, 16),
        AREA_SIZE("area_size", 3, true, 1, 63),
        USED_MARGIN("used_margin", 4, true, 0, 32),
        SITE_SIZE("site_size", 5, true, 3, 63),
        MAX_TILT("max_tilt", 6, false, 0, 1),
        TREE_COST("tree_cost", 7, false, 0, 1000);

        final String key;
        final int slot;
        final boolean whole;
        final double min;
        final double max;

        Rule(String key, int slot, boolean whole, double min, double max) {
            this.key = key;
            this.slot = slot;
            this.whole = whole;
            this.min = min;
            this.max = max;
        }

        /** A rise per block also read as "1 in N", the way a slope is judged by eye. */
        boolean isSlope() {
            return this == MAX_SLOPE || this == MAX_TILT;
        }
    }

    private static double[] values(TerrainRules rules) {
        return new double[] {rules.smoothRadius(), rules.maxSlope(), rules.maxRough(),
                rules.areaSize(), rules.usedMargin(), rules.footprint(), rules.maxTilt(),
                rules.treeCost()};
    }

    /** Sizes round up to odd, so an even one asked for is widened rather than refused. */
    private static TerrainRules rules(double[] v) {
        return new TerrainRules((int) v[0], v[1], v[2], (int) v[3] | 1, (int) v[4], (int) v[5] | 1,
                v[6], v[7]);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> terrain() {
        LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("rules")
                .executes(ctx -> show(ctx.getSource()))
                .then(Commands.literal("reset").executes(ctx -> reset(ctx.getSource())));
        for (Rule rule : Rule.values()) {
            set.then(Commands.literal(rule.key).then(rule.whole
                    ? Commands.argument("value", IntegerArgumentType.integer((int) rule.min, (int) rule.max))
                            .executes(ctx -> set(ctx.getSource(), rule,
                                    IntegerArgumentType.getInteger(ctx, "value")))
                    : Commands.argument("value", DoubleArgumentType.doubleArg(rule.min, rule.max))
                            .executes(ctx -> set(ctx.getSource(), rule,
                                    DoubleArgumentType.getDouble(ctx, "value")))));
        }
        return Commands.literal("terrain")
                .executes(ctx -> toggle(ctx.getSource(), 0))
                .then(set)
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
                        TerrainViewer.rules(source.getServer(), player).footprint())
                : Component.translatable("anima.command.terrain.off"))
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int show(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        double[] now = values(TerrainViewer.rules(source.getServer(), player));
        double[] defaults = values(TerrainRules.DEFAULTS);
        Replies.send(source, () -> Component.translatable("anima.command.terrain.rules"));
        for (Rule rule : Rule.values()) {
            double value = now[rule.slot];
            Component line = rule.isSlope() && value > 0
                    ? Component.translatable("anima.command.terrain.rule_slope", rule.key,
                            number(value), Math.round(1 / value))
                    : Component.translatable("anima.command.terrain.rule", rule.key, number(value));
            boolean changed = value != defaults[rule.slot];
            Replies.send(source, () -> line.copy().withStyle(
                    changed ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        }
        return 1;
    }

    private static int set(CommandSourceStack source, Rule rule, double value)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        double[] v = values(TerrainViewer.rules(source.getServer(), player));
        v[rule.slot] = value;
        TerrainRules now = rules(v);
        TerrainViewer.rules(source.getServer(), player, now);
        Replies.send(source, () -> Component.translatable("anima.command.terrain.rule_set", rule.key,
                number(values(now)[rule.slot])).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int reset(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        TerrainViewer.rules(source.getServer(), player, TerrainRules.DEFAULTS);
        Replies.send(source, () -> Component.translatable("anima.command.terrain.rules_reset")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
