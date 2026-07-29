package dev.luizloyola.anima.mod.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.luizloyola.anima.core.brain.instinct.Danger;
import dev.luizloyola.anima.core.config.ConfigStore;
import dev.luizloyola.anima.core.config.ConfigValues;
import dev.luizloyola.anima.core.config.KnobSet;
import dev.luizloyola.anima.core.config.KnobSpec;
import dev.luizloyola.anima.mod.config.ConfigFile;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * The {@code config} subcommand for any {@link KnobSet}: {@code show}, {@code reload},
 * {@code get}, {@code set}, {@code reset}. Each mod mounts it under its own root, so an operator
 * can always tell whose tunable they are looking at.
 *
 * <p><b>Setting a knob writes the file</b> — every mutation installs and saves, so a reload
 * restores what is in force.
 */
public final class ConfigCommands {

    private ConfigCommands() {
    }

    /** The {@code config} literal, wired to one mod's store and file. */
    public static LiteralArgumentBuilder<CommandSourceStack> tree(ConfigStore store, ConfigFile file) {
        SuggestionProvider<CommandSourceStack> keys = (ctx, builder) ->
                SharedSuggestionProvider.suggest(
                        store.set().knobs().stream().map(KnobSpec::key).toList(), builder);
        return Commands.literal("config")
                .executes(ctx -> show(ctx.getSource(), store, file))
                .then(Commands.literal("show")
                        .executes(ctx -> show(ctx.getSource(), store, file)))
                .then(Commands.literal("reload")
                        .executes(ctx -> reload(ctx.getSource(), store, file)))
                .then(Commands.literal("get")
                        .then(Commands.argument("key", StringArgumentType.string())
                                .suggests(keys)
                                .executes(ctx -> get(ctx.getSource(), store,
                                        StringArgumentType.getString(ctx, "key")))))
                .then(Commands.literal("set")
                        .then(Commands.argument("key", StringArgumentType.string())
                                .suggests(keys)
                                .then(Commands.argument("value", StringArgumentType.string())
                                        .executes(ctx -> set(ctx.getSource(), store, file,
                                                StringArgumentType.getString(ctx, "key"),
                                                StringArgumentType.getString(ctx, "value"))))))
                .then(Commands.literal("reset")
                        .then(Commands.literal("all")
                                .executes(ctx -> resetAll(ctx.getSource(), store, file)))
                        .then(Commands.argument("key", StringArgumentType.string())
                                .suggests(keys)
                                .executes(ctx -> reset(ctx.getSource(), store, file,
                                        StringArgumentType.getString(ctx, "key")))));
    }

    private static int show(CommandSourceStack source, ConfigStore store, ConfigFile file) {
        KnobSet set = store.set();
        ConfigValues config = store.get();
        List<String> overrides = config.describeOverrides();
        Replies.send(source, () -> Component.literal(set.title() + " config — " + file.path())
                .withStyle(ChatFormatting.AQUA));
        if (overrides.isEmpty()) {
            Replies.send(source, () -> Component.literal("  all " + set.size()
                    + " knobs at their defaults").withStyle(ChatFormatting.GRAY));
            return 1;
        }
        for (String line : overrides) {
            Replies.send(source, () -> Component.literal("  " + line)
                    .withStyle(ChatFormatting.YELLOW));
        }
        return overrides.size();
    }

    private static int reload(CommandSourceStack source, ConfigStore store, ConfigFile file) {
        String title = store.set().title();
        List<String> problems = file.reload();
        if (problems.isEmpty()) {
            Replies.send(source, () -> Component.literal(title + " config reloaded — "
                    + store.get().describeOverrides().size() + " override(s) in force")
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        Replies.send(source, () -> Component.literal(title + " config reloaded with "
                + problems.size() + " problem(s):").withStyle(ChatFormatting.YELLOW), true);
        for (String problem : problems) {
            Replies.send(source, () -> Component.literal("  " + problem)
                    .withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static int get(CommandSourceStack source, ConfigStore store, String key) {
        // Knobs win over species. `danger.` is a shared prefix: five real knobs live there
        // (danger.melee_mult and friends) alongside the open-ended species table, so asking the
        // set first is what stops `danger.melee_mult` being read as a mob called "melee_mult".
        if (key.startsWith("danger.") && store.set().byKey(key).isEmpty()) {
            String species = key.substring("danger.".length());
            double weight = Danger.weight(species);
            Replies.send(source, () -> Component.literal(key + " = " + weight
                    + " (unlisted species use danger." + Danger.DEFAULT_KEY + ")")
                    .withStyle(ChatFormatting.AQUA));
            return 1;
        }
        KnobSpec knob = store.set().byKey(key).orElse(null);
        if (knob == null) return unknown(source, store, key);
        ConfigValues config = store.get();
        Replies.send(source, () -> Component.literal(knob.key() + " = " + knob.format(config.get(knob))
                + (config.isDefault(knob) ? " (default)"
                        : " — default is " + knob.format(knob.def())))
                .withStyle(ChatFormatting.AQUA));
        Replies.send(source, () -> Component.literal("  " + knob.doc())
                .withStyle(ChatFormatting.GRAY));
        Replies.send(source, () -> Component.literal("  accepts " + knob.expects())
                .withStyle(ChatFormatting.DARK_GRAY));
        return 1;
    }

    private static int set(CommandSourceStack source, ConfigStore store, ConfigFile file,
            String key, String value) {
        // Knobs win over species — see get(). Without this, `set danger.melee_mult 2` wrote a
        // species weight for a mob named "melee_mult" and silently left the knob alone.
        if (key.startsWith("danger.") && store.set().byKey(key).isEmpty()) {
            String species = key.substring("danger.".length());
            double parsed;
            try {
                parsed = Double.parseDouble(value);
            } catch (NumberFormatException e) {
                Replies.fail(source, Component.literal(key + " accepts a number — \"" + value
                        + "\" is not one"));
                return 0;
            }
            double landed = file.setDanger(species, parsed);
            Replies.send(source, () -> Component.literal(key + " = " + landed
                    + (landed != parsed ? " (clamped)" : "")).withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        KnobSpec knob = store.set().byKey(key).orElse(null);
        if (knob == null) return unknown(source, store, key);
        Double parsed = knob.parse(value).orElse(null);
        if (parsed == null) {
            Replies.fail(source, Component.literal(knob.key() + " accepts " + knob.expects()
                    + " — \"" + value + "\" is not one"));
            return 0;
        }
        double landed = knob.clamp(parsed);
        boolean clamped = landed != parsed;
        store.install(store.get().with(knob, landed));
        file.save(store.get());
        Replies.send(source, () -> Component.literal(knob.key() + " = " + knob.format(landed)
                + (clamped ? " (clamped from " + knob.format(parsed) + ")" : ""))
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int reset(CommandSourceStack source, ConfigStore store, ConfigFile file,
            String key) {
        KnobSpec knob = store.set().byKey(key).orElse(null);
        if (knob == null) return unknown(source, store, key);
        store.install(store.get().with(knob, knob.def()));
        file.save(store.get());
        Replies.send(source, () -> Component.literal(knob.key() + " = " + knob.format(knob.def())
                + " (default)").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int resetAll(CommandSourceStack source, ConfigStore store, ConfigFile file) {
        KnobSet set = store.set();
        store.reset();
        file.save(store.get());
        Replies.send(source, () -> Component.literal(set.title() + " config reset to defaults ("
                + set.size() + " knobs)").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int unknown(CommandSourceStack source, ConfigStore store, String key) {
        Replies.fail(source, Component.literal("No such config key \"" + key
                + "\" — try tab-completion, or /" + store.set().id() + " config show"));
        return 0;
    }
}
