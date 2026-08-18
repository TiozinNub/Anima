package dev.luizloyola.anima.mod.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
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
 * knob, built from a {@link KnobSet}, so it cannot fall out of step with the file or the command
 * and a consumer gets the same screen from its own store.
 *
 * <p><b>YACL is used here for the GUI only.</b> {@link ConfigFile} keeps {@code config/<mod>.toml}
 * for the atomic tmp-and-rename write, the unknown-key report and the regenerated {@code #} doc
 * comments YACL's serializer has none of; values stay in the immutable {@link ConfigValues} behind
 * {@link Config}, whose volatile whole-object swap makes a reload safe for the off-thread
 * pathfinder.
 *
 * <p><b>Must not be loaded unless YACL is installed</b> — it names {@code dev.isxander.*} types
 * directly, so touching it without the {@code modCompileOnly} library is a
 * {@link NoClassDefFoundError}; {@link AnimaModMenu} is the only caller and checks first.
 *
 * <p>Controller ranges refuse illegal input early; {@link ConfigValues#with} clamps again on the
 * way in.
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
        Map<KnobSpec, String> stagedText = new LinkedHashMap<>();

        // Sections in declaration order, each becoming one category tab.
        Map<String, ConfigCategory.Builder> categories = new LinkedHashMap<>();
        for (KnobSpec knob : set.knobs()) {
            categories.computeIfAbsent(knob.category(), category -> ConfigCategory.createBuilder()
                    .name(Component.translatableWithFallback(
                            set.langRoot() + ".category." + category, prettify(category))))
                    .option(option(set, knob, live, staged, stagedText));
        }

        YetAnotherConfigLib.Builder builder = YetAnotherConfigLib.createBuilder()
                .title(Component.literal(set.title()));
        for (ConfigCategory.Builder category : categories.values()) {
            builder.category(category.build());
        }
        return builder.save(() -> apply(store, file, staged, stagedText)).build()
                .generateScreen(parent);
    }

    private static Option<?> option(KnobSet set, KnobSpec knob, ConfigValues live,
            Map<KnobSpec, Double> staged, Map<KnobSpec, String> stagedText) {
        // Translation keys with the knob as fallback, so a knob with no lang entry reads sensibly
        // and any label is overridable in any language without touching Java. The last tooltip
        // line stays literal: the dotted key and range are typed into the config command.
        Component name = Component.translatableWithFallback(
                knob.langKey(set), prettify(knob.leaf()));
        OptionDescription description = OptionDescription.of(
                Component.translatableWithFallback(knob.langKey(set) + ".desc", knob.doc()),
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
            case STRING:
            case LIST:
                // No range on the controller: YACL's text field has no length bound, so the
                // sanitise in ConfigValues.with is the only gate — and it is the one the file
                // takes too, so the screen cannot admit what a reload would reject. A LIST edits
                // as its comma-joined text, which sanitise normalises on the way back in.
                return Option.<String>createBuilder()
                        .name(name)
                        .description(description)
                        .binding(knob.defText(), () -> live.s(knob),
                                value -> stagedText.put(knob, value))
                        .controller(StringControllerBuilder::create)
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


    /** The mob's own display name, or the raw species string for ids not in this game's registry. */
    private static Component speciesName(String species) {
        String id = species.contains(":") ? species : "minecraft:" + species;
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getOptional(net.minecraft.resources.Identifier.parse(id))
                .<Component>map(type -> Component.translatable(type.getDescriptionId()))
                .orElse(Component.literal(prettify(species)));
    }

    /** Install the edited values as one config, then persist — the same path {@code config set} takes. */
    private static void apply(ConfigStore store, ConfigFile file,
            Map<KnobSpec, Double> staged, Map<KnobSpec, String> stagedText) {
        ConfigValues updated = store.get();
        for (Map.Entry<KnobSpec, Double> change : staged.entrySet()) {
            updated = updated.with(change.getKey(), change.getValue());
        }
        for (Map.Entry<KnobSpec, String> change : stagedText.entrySet()) {
            updated = updated.with(change.getKey(), change.getValue());
        }
        store.install(updated);
        file.save(updated);
    }
}
