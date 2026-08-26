package dev.luizloyola.anima.mod.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.luizloyola.anima.core.config.ConfigStore;
import dev.luizloyola.anima.core.config.ConfigValues;
import dev.luizloyola.anima.core.config.KnobSet;
import dev.luizloyola.anima.core.config.KnobSpec;
import dev.luizloyola.anima.mod.config.ConfigFile;
import java.util.List;
import java.util.stream.Stream;
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
        SuggestionProvider<CommandSourceStack> values = (ctx, builder) -> store.set()
                .byKey(StringArgumentType.getString(ctx, "key"))
                .map(knob -> SharedSuggestionProvider.suggest(offered(knob, store.get()), builder))
                .orElseGet(builder::buildFuture);
        return Commands.literal("config")
                .executes(ctx -> show(ctx.getSource(), store, file))
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
                                        .suggests(values)
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
        List<KnobSpec> overrides = config.overridden();
        Replies.send(source, () -> Component.translatable("anima.command.config.header",
                title(set), file.path()).withStyle(ChatFormatting.AQUA));
        if (overrides.isEmpty()) {
            Replies.send(source, () -> indent(Component.translatable(
                    "anima.command.config.all_default", set.size())
                    .withStyle(ChatFormatting.GRAY)));
            return 1;
        }
        for (KnobSpec knob : overrides) {
            Replies.send(source, () -> indent(Component.translatable(
                    "anima.command.config.override",
                    knob.key(), config.text(knob), knob.formatDefault())
                    .withStyle(ChatFormatting.YELLOW)));
        }
        return overrides.size();
    }

    /**
     * A set's own name, translated where the owning mod said so. {@code title()} is the fallback
     * rather than the source: a consumer that never adds the key still reads as it always did.
     */
    private static Component title(KnobSet set) {
        return Component.translatableWithFallback(set.langRoot() + ".title", set.title());
    }

    /**
     * What {@code set <key>} offers for the value: a BOOL's two words, otherwise the value in force
     * and the default when they differ — the two starting points an operator reaching for
     * {@code set} actually wants, and seeing them is half of why they asked.
     *
     * <p><b>The raw stored text, never {@link ConfigValues#text}'s display form.</b> A text knob is
     * set through {@code sanitise}, which takes the operator's token literally, so completing to
     * {@code "quoted"} or to a LIST's {@code ["a", "b"]} would set the punctuation along with the
     * value.
     */
    private static List<String> offered(KnobSpec knob, ConfigValues config) {
        if (knob.kind() == KnobSpec.Kind.BOOL) {
            return List.of("true", "false");
        }
        boolean text = knob.kind().textual();
        String current = text ? config.s(knob) : config.text(knob);
        String fallback = text ? knob.defText() : knob.formatDefault();
        return Stream.of(current, fallback)
                .filter(value -> !value.isBlank())
                .distinct()
                .map(ConfigCommands::oneToken)
                .toList();
    }

    /** Quoted when it would otherwise complete as two arguments — Brigadier hands back the inside. */
    private static String oneToken(String value) {
        return value.contains(" ") ? '"' + value + '"' : value;
    }

    /** What a knob accepts, in the reader's language — {@code expects()} is the file's phrasing. */
    private static Component expects(KnobSpec knob) {
        return Component.translatable(knob.expectsKey(), knob.expectsArgs());
    }

    /**
     * One line nested under the one above it. The gutter stays in Java rather than in every
     * translation of the line, where it is one edit away from being trimmed off.
     */
    private static Component indent(Component line) {
        return Component.literal("  ").append(line);
    }

    /**
     * <p>The problem lines themselves stay untranslated: they quote what the TOML parser choked
     * on, which arrives in one language whatever the reader's is.
     */
    private static int reload(CommandSourceStack source, ConfigStore store, ConfigFile file) {
        KnobSet set = store.set();
        List<String> problems = file.reload();
        if (problems.isEmpty()) {
            Replies.send(source, () -> Component.translatable("anima.command.config.reloaded",
                    title(set), store.get().overridden().size())
                    .withStyle(ChatFormatting.GREEN), true);
            return 1;
        }
        Replies.send(source, () -> Component.translatable("anima.command.config.reloaded_problems",
                title(set), problems.size()).withStyle(ChatFormatting.YELLOW), true);
        for (String problem : problems) {
            Replies.send(source, () -> indent(Component.literal(problem)
                    .withStyle(ChatFormatting.RED)));
        }
        return 1;
    }

    private static int get(CommandSourceStack source, ConfigStore store, String key) {
        KnobSpec knob = store.set().byKey(key).orElse(null);
        if (knob == null) return unknown(source, store, key);
        ConfigValues config = store.get();
        Replies.send(source, () -> (config.isDefault(knob)
                        ? Component.translatable("anima.command.config.value_default",
                                knob.key(), config.text(knob))
                        : Component.translatable("anima.command.config.value",
                                knob.key(), config.text(knob), knob.formatDefault()))
                .withStyle(ChatFormatting.AQUA));
        // The same key the GUI reads, so the two never say different things about one knob.
        Replies.send(source, () -> indent(Component.translatableWithFallback(
                        knob.langKey(store.set()) + ".desc", knob.doc())
                .withStyle(ChatFormatting.GRAY)));
        Replies.send(source, () -> indent(Component.translatable(
                "anima.command.config.accepts", expects(knob))
                .withStyle(ChatFormatting.DARK_GRAY)));
        return 1;
    }

    private static int set(CommandSourceStack source, ConfigStore store, ConfigFile file,
            String key, String value) {
        KnobSpec knob = store.set().byKey(key).orElse(null);
        if (knob == null) return unknown(source, store, key);
        if (knob.kind().textual()) {
            return setText(source, store, file, knob, value);
        }
        Double parsed = knob.parse(value).orElse(null);
        if (parsed == null) {
            Replies.fail(source, Component.translatable("anima.command.config.not_accepted",
                    knob.key(), expects(knob), value));
            return 0;
        }
        double landed = knob.clamp(parsed);
        boolean clamped = landed != parsed;
        store.install(store.get().with(knob, landed));
        file.save(store.get());
        Replies.send(source, () -> (clamped
                        ? Component.translatable("anima.command.config.set_clamped",
                                knob.key(), knob.format(landed), knob.format(parsed))
                        : Component.translatable("anima.command.config.set",
                                knob.key(), knob.format(landed)))
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /**
     * A text knob's half of {@link #set}. Refuses rather than silently substituting the default:
     * {@code sanitise} exists so a hand-edited FILE degrades instead of failing, but an operator
     * who just typed the value is owed the news that it did not take.
     *
     * <p>The test is <b>did it fall back</b>, not <b>did it change</b>: a {@link KnobSpec.Kind#LIST}
     * also normalises spacing, and refusing {@code "a-b, c-d"} for the space would be a puzzle
     * rather than a correction. Falling back always lands on the default, which is what this reads.
     */
    private static int setText(CommandSourceStack source, ConfigStore store, ConfigFile file,
            KnobSpec knob, String value) {
        String landed = knob.sanitise(value);
        if (landed.equals(knob.defText()) && !landed.equals(value.strip())) {
            Replies.fail(source, Component.translatable("anima.command.config.not_accepted",
                    knob.key(), expects(knob), value));
            return 0;
        }
        store.install(store.get().with(knob, landed));
        file.save(store.get());
        Replies.send(source, () -> Component.translatable("anima.command.config.set",
                        knob.key(), knob.formatText(landed))
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int reset(CommandSourceStack source, ConfigStore store, ConfigFile file,
            String key) {
        KnobSpec knob = store.set().byKey(key).orElse(null);
        if (knob == null) return unknown(source, store, key);
        store.install(knob.kind().textual()
                ? store.get().with(knob, knob.defText())
                : store.get().with(knob, knob.def()));
        file.save(store.get());
        Replies.send(source, () -> Component.translatable("anima.command.config.value_default",
                        knob.key(), knob.formatDefault())
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int resetAll(CommandSourceStack source, ConfigStore store, ConfigFile file) {
        KnobSet set = store.set();
        store.reset();
        file.save(store.get());
        Replies.send(source, () -> Component.translatable("anima.command.config.reset_all",
                title(set), set.size()).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int unknown(CommandSourceStack source, ConfigStore store, String key) {
        Replies.fail(source, Component.translatable("anima.command.config.no_such_key",
                key, store.set().id()));
        return 0;
    }
}
