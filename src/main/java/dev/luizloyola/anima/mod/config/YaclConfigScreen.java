package dev.luizloyola.anima.mod.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.luizloyola.anima.core.brain.instinct.Danger;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.ConfigStore;
import dev.luizloyola.anima.core.config.ConfigValues;
import dev.luizloyola.anima.core.config.KnobSet;
import dev.luizloyola.anima.core.config.KnobSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The optional YACL config screen — one category per {@link KnobSpec#section()}, one option per
 * knob, built from a {@link KnobSet} so it cannot fall out of step with the file or the command.
 *
 * <p><b>YACL is the GUI only.</b> {@link ConfigFile} keeps {@code config/<mod>.json} for an atomic
 * write, unknown-key reporting and regenerated {@code "// name"} doc lines; values stay in the
 * immutable {@link ConfigValues} behind {@link Config}, whose whole-object swap keeps a reload safe
 * for the off-thread pathfinder.
 *
 * <p><b>Do not load this class unless YACL is installed:</b> it names {@code dev.isxander.*} types,
 * so touching it without the library is a {@link NoClassDefFoundError}. {@link AnimaModMenu} checks
 * first; the library is {@code modCompileOnly}.
 *
 * <p>Controllers get the ranges, but the guarantee is {@link ConfigValues#with}, which clamps again
 * on the way in.
 */
public final class YaclConfigScreen {

    private YaclConfigScreen() {
    }

    /**
     * Builds the screen for one mod's knob set. Called only with YACL present — see
     * {@link AnimaModMenu}, which is the guard.
     */
    public static Screen create(Screen parent, ConfigStore store, ConfigFile file) {
        KnobSet set = store.set();
        ConfigValues live = store.get();

        // Staged rather than applied per-option: YACL calls the setters as the user edits, and one
        // atomic install on Save keeps a half-applied config from being observed mid-tick.
        Map<KnobSpec, Double> staged = new LinkedHashMap<>();

        // Sections in declaration order, each becoming one category tab.
        Map<String, ConfigCategory.Builder> categories = new LinkedHashMap<>();
        for (KnobSpec knob : set.knobs()) {
            categories.computeIfAbsent(knob.section(), section -> ConfigCategory.createBuilder()
                    .name(Component.translatableWithFallback(
                            set.langRoot() + ".category." + section, prettify(section))))
                    .option(option(set, knob, live, staged));
        }
        // The per-species flee weights ride the danger tab beside the modifier knobs — not knobs
        // (open key set), each label borrowing the mob's own lang entry (decision: Luiz). Guarded
        // on Anima's own set, so a consuming mod naming a section "danger" gets its own knobs.
        Map<String, Double> stagedDanger = new LinkedHashMap<>();
        ConfigCategory.Builder dangerTab = set == Config.SET ? categories.get("danger") : null;
        if (dangerTab != null) {
            dangerTab.option(dangerOption(Danger.DEFAULT_KEY, stagedDanger));
            Danger.table().keySet().stream()
                    .filter(species -> !species.equals(Danger.DEFAULT_KEY))
                    .sorted()
                    .forEach(species -> dangerTab.option(dangerOption(species, stagedDanger)));
        }

        YetAnotherConfigLib.Builder builder = YetAnotherConfigLib.createBuilder()
                .title(Component.literal(set.title()));
        for (ConfigCategory.Builder category : categories.values()) {
            builder.category(category.build());
        }
        return builder.save(() -> apply(store, file, staged, stagedDanger)).build().generateScreen(parent);
    }

    private static Option<?> option(KnobSet set, KnobSpec knob, ConfigValues live,
            Map<KnobSpec, Double> staged) {
        // Translation keys with the knob as fallback, so a knob with no lang entry reads sensibly
        // and any label is overridable in any language without touching Java. The last tooltip
        // line stays literal: the dotted key and range are typed into the config command.
        Component name = Component.translatableWithFallback(
                nameKey(set, knob), prettify(knob.leaf()));
        OptionDescription description = OptionDescription.of(
                Component.translatableWithFallback(nameKey(set, knob) + ".desc", knob.doc()),
                Component.literal(""),
                Component.literal(knob.key() + " — accepts " + knob.expects()));
        switch (knob.kind()) {
            case BOOL:
                return Option.<Boolean>createBuilder()
                        .name(name)
                        .description(description)
                        .binding(knob.def() != 0.0, () -> live.b(knob),
                                value -> staged.put(knob, value ? 1.0 : 0.0))
                        .controller(TickBoxControllerBuilder::create)
                        .build();
            case INT:
                return Option.<Integer>createBuilder()
                        .name(name)
                        .description(description)
                        .binding((int) knob.def(), () -> live.i(knob),
                                value -> staged.put(knob, (double) value))
                        .controller(opt -> IntegerFieldControllerBuilder.create(opt)
                                .range((int) knob.min(), (int) knob.max()))
                        .build();
            case DOUBLE:
            default:
                return Option.<Double>createBuilder()
                        .name(name)
                        .description(description)
                        .binding(knob.def(), () -> live.d(knob),
                                value -> staged.put(knob, value))
                        .controller(opt -> DoubleFieldControllerBuilder.create(opt)
                                .range(knob.min(), knob.max()))
                        .build();
        }
    }

    /** {@code <mod>.config.option.<section>.<leaf>}; append {@code .desc} for the tooltip. */
    private static String nameKey(KnobSet set, KnobSpec knob) {
        return set.langRoot() + ".option." + knob.key();
    }

    /** {@code sense_radius} -> {@code Sense radius} — the label an untranslated knob falls back to. */
    private static String prettify(String snakeCase) {
        String spaced = snakeCase.replace('_', ' ');
        return spaced.isEmpty() ? spaced
                : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** One species weight row — named by the mob's own lang entry, never hand-written. */
    private static Option<Double> dangerOption(String species, Map<String, Double> staged) {
        Component name = species.equals(Danger.DEFAULT_KEY)
                ? Component.translatableWithFallback(Config.SET.langRoot() + ".option.danger.default_weight",
                        "Default (unlisted mobs)")
                : speciesName(species);
        return Option.<Double>createBuilder()
                .name(name)
                .description(OptionDescription.of(Component.literal(
                        "danger." + species + " — flee weight, accepts 0.0 to 8.0")))
                .binding(1.0, () -> Danger.weight(species), value -> staged.put(species, value))
                .controller(opt -> DoubleFieldControllerBuilder.create(opt).range(0.0, 8.0))
                .build();
    }

    /** The mob's own display name, or the raw species string for ids not in this game's registry. */
    private static Component speciesName(String species) {
        String id = species.contains(":") ? species : "minecraft:" + species;
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getOptional(net.minecraft.resources.Identifier.parse(id))
                .<Component>map(type -> Component.translatable(type.getDescriptionId()))
                .orElse(Component.literal(prettify(species)));
    }

    /** Install the edited values as one config, then persist — the same path {@code config set} takes. */
    private static void apply(ConfigStore store, ConfigFile file, Map<KnobSpec, Double> staged,
            Map<String, Double> stagedDanger) {
        ConfigValues updated = store.get();
        for (Map.Entry<KnobSpec, Double> change : staged.entrySet()) {
            updated = updated.with(change.getKey(), change.getValue());
        }
        store.install(updated);
        if (!stagedDanger.isEmpty()) {
            Map<String, Double> merged = new LinkedHashMap<>(Danger.table());
            merged.putAll(stagedDanger);
            Danger.install(merged);
        }
        file.save(updated);
    }
}
