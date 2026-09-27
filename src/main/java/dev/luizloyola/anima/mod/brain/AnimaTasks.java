package dev.luizloyola.anima.mod.brain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.ConsumeItem;
import dev.luizloyola.anima.core.brain.task.CraftStep;
import dev.luizloyola.anima.core.craft.CraftRecipe;
import dev.luizloyola.anima.core.brain.task.FleeStep;
import dev.luizloyola.anima.core.brain.task.GatherNearbyDrops;
import dev.luizloyola.anima.core.brain.task.EscapeStep;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.HandlingPhase;
import dev.luizloyola.anima.core.brain.task.Idle;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.SatisfyHunger;
import dev.luizloyola.anima.core.brain.task.SurveyArea;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.WanderStep;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.social.speech.Speech;
import net.minecraft.core.UUIDUtil;

/**
 * How Anima's own tasks write themselves down, registered with {@link TaskCodecs}.
 *
 * <p>Each codec carries what the task <em>is</em> and how far it has <em>got</em>. A task that
 * comes back at the start of itself runs twice: a body re-orders a walk it had begun, or pauses
 * a second time.
 */
public final class AnimaTasks {

    private AnimaTasks() {
    }

    /** Enums round-trip by name; an unknown one is an error rather than a silent default. */
    private static final Codec<Gait> GAIT = Codec.STRING.comapFlatMap(
            name -> {
                try {
                    return DataResult.success(Gait.valueOf(name));
                } catch (IllegalArgumentException e) {
                    return DataResult.error(() -> "no gait called \"" + name + "\"");
                }
            },
            Gait::name);

    /** Enums round-trip by name; an unknown one is an error rather than a silent default. */
    private static final Codec<HandlingPhase> HANDLING_PHASE =
            Codec.STRING.comapFlatMap(
                    name -> {
                        try {
                            return DataResult.success(
                                    HandlingPhase.valueOf(name));
                        } catch (IllegalArgumentException e) {
                            return DataResult.error(() -> "no handling phase called \"" + name + "\"");
                        }
                    },
                    HandlingPhase::name);

    /** Enums round-trip by name; an unknown one is an error rather than a silent default. */
    private static final Codec<Speech.Opening> OPENING = Codec.STRING.comapFlatMap(
            name -> {
                try {
                    return DataResult.success(Speech.Opening.valueOf(name));
                } catch (IllegalArgumentException e) {
                    return DataResult.error(() -> "no conversation opening called \"" + name + "\"");
                }
            },
            Speech.Opening::name);

    /**
     * A class of items, in the two shapes a spec can have. A mod-declared spec's matcher is a
     * lambda and cannot be written down, so its NAME is the handle ({@link ItemSpec#byName}); an
     * unregistered name errors rather than inventing a spec that matches nothing. A
     * {@link ItemSpec#anyOf literal} spec ("any plank") has no declarer, so its CONTENT is the
     * handle: the id list, re-canonicalised through {@code anyOf} on load. The fork is
     * {@link ItemSpec#literalIds}; old saves, always name-shaped, read unchanged.
     */
    private static final Codec<ItemSpec> ITEM_SPEC = Codec.either(
                    Codec.STRING, Codec.STRING.listOf())
            .comapFlatMap(AnimaTasks::specFromEither, AnimaTasks::specToEither);

    private static DataResult<ItemSpec> specFromEither(
            com.mojang.datafixers.util.Either<String, java.util.List<String>> written) {
        return written.map(
                name -> ItemSpec.byName(name)
                        .map(DataResult::success)
                        .orElseGet(() -> DataResult.error(
                                () -> "no item spec is registered as \"" + name
                                        + "\" — was a mod removed?")),
                ids -> ids.isEmpty()
                        ? DataResult.error(() -> "an item spec with no ids")
                        : DataResult.success(ItemSpec.anyOf(new java.util.HashSet<>(ids))));
    }

    private static com.mojang.datafixers.util.Either<String, java.util.List<String>> specToEither(
            ItemSpec spec) {
        return ItemSpec.literalIds(spec)
                .<com.mojang.datafixers.util.Either<String, java.util.List<String>>>map(
                        ids -> com.mojang.datafixers.util.Either.right(
                                java.util.List.copyOf(new java.util.TreeSet<>(ids))))
                .orElseGet(() -> com.mojang.datafixers.util.Either.left(spec.name()));
    }

    /**
     * A kind of place, by the key it registered under — the same handle the knowledge store and
     * the save file use. An unregistered key errors rather than defaulting: a sweep looking for a
     * kind this build no longer has would walk a whole box and be unable to say what it was for.
     */
    private static final Codec<PoiKind> POI_KIND = Codec.STRING.comapFlatMap(
            key -> PoiKind.byKey(key)
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(
                            () -> "no POI kind is registered as \"" + key + "\" — was a mod removed?")),
            PoiKind::key);

    /** A core stack, whole — what a recipe's output is. */
    private static final Codec<dev.luizloyola.anima.core.inv.ItemStack> CORE_STACK =
            RecordCodecBuilder.create(s -> s.group(
                    Codec.STRING.fieldOf("id")
                            .forGetter(dev.luizloyola.anima.core.inv.ItemStack::id),
                    Codec.INT.fieldOf("count")
                            .forGetter(dev.luizloyola.anima.core.inv.ItemStack::count),
                    Codec.INT.fieldOf("max")
                            .forGetter(dev.luizloyola.anima.core.inv.ItemStack::maxStackSize),
                    Codec.STRING.optionalFieldOf("components", "")
                            .forGetter(dev.luizloyola.anima.core.inv.ItemStack::components)
            ).apply(s, dev.luizloyola.anima.core.inv.ItemStack::new));

    private static final Codec<CraftRecipe.Ingredient> INGREDIENT =
            RecordCodecBuilder.create(i -> i.group(
                    Codec.STRING.listOf().fieldOf("ids").forGetter(line ->
                            java.util.List.copyOf(new java.util.TreeSet<>(line.acceptedIds()))),
                    Codec.INT.fieldOf("count").forGetter(CraftRecipe.Ingredient::count)
            ).apply(i, (ids, count) ->
                    new CraftRecipe.Ingredient(new java.util.HashSet<>(ids), count)));

    private static final Codec<CraftRecipe> CRAFT_RECIPE =
            RecordCodecBuilder.create(r -> r.group(
                    Codec.STRING.fieldOf("id").forGetter(CraftRecipe::id),
                    CORE_STACK.fieldOf("output").forGetter(CraftRecipe::output),
                    INGREDIENT.listOf().fieldOf("bill").forGetter(CraftRecipe::ingredients),
                    Codec.BOOL.fieldOf("table").forGetter(CraftRecipe::needsTable)
            ).apply(r, CraftRecipe::new));

    private static final Codec<dev.luizloyola.anima.core.inv.ItemCall> ITEM_CALL =
            RecordCodecBuilder.create(c -> c.group(
                    ITEM_SPEC.fieldOf("spec")
                            .forGetter(dev.luizloyola.anima.core.inv.ItemCall::spec),
                    Codec.INT.fieldOf("count")
                            .forGetter(dev.luizloyola.anima.core.inv.ItemCall::count),
                    Codec.STRING.comapFlatMap(
                                    name -> {
                                        try {
                                            return DataResult.success(dev.luizloyola.anima.core
                                                    .inv.ItemCall.Strength.valueOf(name));
                                        } catch (IllegalArgumentException e) {
                                            return DataResult.error(
                                                    () -> "no call strength called \"" + name + "\"");
                                        }
                                    },
                                    dev.luizloyola.anima.core.inv.ItemCall.Strength::name)
                            .fieldOf("strength")
                            .forGetter(dev.luizloyola.anima.core.inv.ItemCall::strength)
            ).apply(c, dev.luizloyola.anima.core.inv.ItemCall::new));

    /** One perceived body, by the id its track is kept under — as {@code SenseState} writes it. */
    private static final Codec<BeingId> BEING_ID =
            UUIDUtil.CODEC.xmap(BeingId::new, BeingId::value);

    private static final Codec<Pos> POS = RecordCodecBuilder.create(p -> p.group(
            Codec.INT.fieldOf("x").forGetter(Pos::x),
            Codec.INT.fieldOf("y").forGetter(Pos::y),
            Codec.INT.fieldOf("z").forGetter(Pos::z)
    ).apply(p, Pos::new));

    private static final Codec<Region> REGION = RecordCodecBuilder.create(r -> r.group(
            POS.fieldOf("min").forGetter(Region::min),
            POS.fieldOf("max").forGetter(Region::max)
    ).apply(r, Region::new));

    /** Call once from mod init, before anything can load a plan. */
    public static void install() {
        TaskCodecs.register("anima:goto", GoTo.class, RecordCodecBuilder.mapCodec(t -> t.group(
                Codec.INT.fieldOf("x").forGetter(GoTo::x),
                Codec.INT.fieldOf("y").forGetter(GoTo::y),
                Codec.INT.fieldOf("z").forGetter(GoTo::z),
                GAIT.fieldOf("gait").forGetter(GoTo::gait),
                Codec.BOOL.fieldOf("issued").forGetter(GoTo::issued)
        ).apply(t, (x, y, z, gait, issued) -> new GoTo(x, y, z, gait).resume(issued))));

        // No state of its own: an escape step is re-decided from where the body now stands, which
        // is the same reason it is one step rather than a compiled plan (see EscapeStep).
        TaskCodecs.register("anima:escape", EscapeStep.class,
                com.mojang.serialization.MapCodec.unit(EscapeStep::new));
        TaskCodecs.register("anima:stuck", EscapeStep.Stuck.class,
                com.mojang.serialization.MapCodec.unit(EscapeStep.Stuck::new));

        TaskCodecs.register("anima:idle", Idle.class, RecordCodecBuilder.mapCodec(t -> t.group(
                Codec.INT.fieldOf("ticks").forGetter(Idle::ticks),
                Codec.INT.fieldOf("remaining").forGetter(Idle::remaining)
        ).apply(t, (ticks, remaining) -> new Idle(ticks).resume(remaining))));

        TaskCodecs.register("anima:consume", ConsumeItem.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        Codec.INT.fieldOf("slot").forGetter(ConsumeItem::slot),
                        Codec.BOOL.fieldOf("issued").forGetter(ConsumeItem::issued)
                ).apply(t, (slot, issued) -> new ConsumeItem(slot).resume(issued))));

        TaskCodecs.register("anima:break", BreakBlock.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        Codec.INT.fieldOf("x").forGetter(task -> task.target().x()),
                        Codec.INT.fieldOf("y").forGetter(task -> task.target().y()),
                        Codec.INT.fieldOf("z").forGetter(task -> task.target().z()),
                        Codec.BOOL.fieldOf("begun").forGetter(BreakBlock::begun)
                ).apply(t, (x, y, z, begun) -> new BreakBlock(x, y, z).resume(begun))));

        TaskCodecs.register("anima:gather", GatherNearbyDrops.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        ITEM_SPEC.fieldOf("spec").forGetter(GatherNearbyDrops::spec),
                        Codec.INT.fieldOf("start").forGetter(GatherNearbyDrops::startCount),
                        Codec.INT.fieldOf("laps").forGetter(GatherNearbyDrops::laps),
                        Codec.INT.fieldOf("cap").forGetter(GatherNearbyDrops::lapCap),
                        Codec.BOOL.fieldOf("walking").forGetter(GatherNearbyDrops::walkIssued)
                ).apply(t, (spec, start, laps, cap, walking) ->
                        new GatherNearbyDrops(spec).resume(start, laps, cap, walking))));

        TaskCodecs.register("anima:obtain", ObtainItem.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        ITEM_SPEC.fieldOf("spec").forGetter(ObtainItem::spec),
                        Codec.INT.fieldOf("count").forGetter(ObtainItem::count),
                        // Sorted on the way out so the same plan always writes the same bytes;
                        // optional so every save from before crafting reads as an empty set.
                        Codec.STRING.listOf().optionalFieldOf("pursued", java.util.List.of())
                                .forGetter(task -> java.util.List.copyOf(
                                        new java.util.TreeSet<>(task.pursued()))),
                        // By NAME rather than ordinal — an ordinal is a position in a list
                        // somebody will reorder, and a save file is the one reader that cannot be
                        // recompiled with it. Optional so every save from before today reads ANY.
                        Codec.STRING.optionalFieldOf("sources", ObtainItem.Sources.ANY.name())
                                .forGetter(task -> task.sources().name())
                ).apply(t, (spec, count, pursued, sources) ->
                        new ObtainItem(spec, count, new java.util.HashSet<>(pursued),
                                ObtainItem.Sources.valueOf(sources)))));

        // The recipe rides INLINE, bill and all, rather than as a name to look up: a datapack
        // reload can remove a recipe mid-craft, and finishing a valid plan beats a load error
        // that silently drops the whole mind.
        TaskCodecs.register("anima:craft", CraftStep.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        CRAFT_RECIPE.fieldOf("recipe").forGetter(CraftStep::recipe),
                        Codec.INT.fieldOf("times").forGetter(CraftStep::times),
                        Codec.INT.fieldOf("done").forGetter(CraftStep::done),
                        Codec.INT.fieldOf("work").forGetter(CraftStep::workTicks)
                ).apply(t, (recipe, times, done, work) ->
                        new CraftStep(recipe, times).resume(done, work))));

        TaskCodecs.register("anima:wander", WanderStep.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        Codec.INT.fieldOf("radius").forGetter(WanderStep::radius)
                ).apply(t, WanderStep::new)));

        // The coverage is the progress: a sweep that came back at its start would re-walk a box
        // somebody had already walked.
        // Named rather than chained: inference follows the target type through an xmap, so
        // building the group inline against SurveyArea makes javac read the task's fields off the
        // task instead of off its state, in four hundred lines of DFU generics.
        // "known" keeps the pre-split key: an old save's per-cell look credit restores straight
        // into `looked` and reproduces the old confidence(cell) exactly. "masks" is optional, not
        // required — vanilla parses with resultOrPartial, so a save from before the walked grid
        // existed would otherwise silently drop the whole SurveyArea.State row and "load fine".
        MapCodec<SurveyArea.State> sweep = RecordCodecBuilder.mapCodec(t -> t.group(
                REGION.fieldOf("area").forGetter(SurveyArea.State::area),
                POI_KIND.fieldOf("looking").forGetter(SurveyArea.State::looking),
                Codec.FLOAT.listOf().fieldOf("known").forGetter(SurveyArea.State::looked),
                Codec.INT.listOf().optionalFieldOf("masks", java.util.List.of())
                        .forGetter(SurveyArea.State::masks),
                Codec.INT.listOf().fieldOf("tries").forGetter(SurveyArea.State::tries),
                Codec.INT.fieldOf("target").forGetter(SurveyArea.State::target)
        ).apply(t, SurveyArea.State::new));
        // Restored with Coverage.NONE: a task rebuilt straight from a save reports nothing to the
        // project that owns the pass. Nothing is lost by that — the project persists its own swept
        // set — and the restored task is short-lived anyway, since plans are re-derived on resume.
        TaskCodecs.register("anima:survey_area", SurveyArea.class, sweep.xmap(
                state -> new SurveyArea(state.area(), state.looking()).restore(state),
                SurveyArea::snapshot));

        // Nothing to carry: both are pure decomposers whose choices come from the context, and the
        // stream those choices draw from belongs to the body and is saved there.
        TaskCodecs.register("anima:flee", FleeStep.class, MapCodec.unit(FleeStep::new));
        TaskCodecs.register("anima:eat", SatisfyHunger.class, MapCodec.unit(SatisfyHunger::new));
        TaskCodecs.register("anima:eat_carried", dev.luizloyola.anima.core.brain.task.EatCarried.class,
                MapCodec.unit(dev.luizloyola.anima.core.brain.task.EatCarried::new));

        TaskCodecs.register("anima:place", dev.luizloyola.anima.core.brain.task.PlaceBlock.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        Codec.STRING.fieldOf("item")
                                .forGetter(dev.luizloyola.anima.core.brain.task.PlaceBlock::itemId),
                        POS.fieldOf("at")
                                .forGetter(dev.luizloyola.anima.core.brain.task.PlaceBlock::target)
                ).apply(t, (item, at) -> new dev.luizloyola.anima.core.brain.task.PlaceBlock(
                        item, at.x(), at.y(), at.z()))));

        TaskCodecs.register("anima:note_place", dev.luizloyola.anima.core.brain.task.NotePlace.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        POI_KIND.fieldOf("kind")
                                .forGetter(dev.luizloyola.anima.core.brain.task.NotePlace::kind),
                        POS.fieldOf("at")
                                .forGetter(dev.luizloyola.anima.core.brain.task.NotePlace::anchor)
                ).apply(t, (kind, at) -> new dev.luizloyola.anima.core.brain.task.NotePlace(
                        kind, at.x(), at.y(), at.z()))));

        TaskCodecs.register("anima:found_place", dev.luizloyola.anima.core.brain.task.FoundPlace.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        POI_KIND.fieldOf("kind")
                                .forGetter(dev.luizloyola.anima.core.brain.task.FoundPlace::kind),
                        POS.fieldOf("at")
                                .forGetter(dev.luizloyola.anima.core.brain.task.FoundPlace::anchor)
                ).apply(t, (kind, at) -> new dev.luizloyola.anima.core.brain.task.FoundPlace(
                        kind, at.x(), at.y(), at.z()))));
        TaskCodecs.register("anima:place_station",
                dev.luizloyola.anima.core.brain.task.PlaceStation.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        POI_KIND.fieldOf("kind")
                                .forGetter(dev.luizloyola.anima.core.brain.task.PlaceStation::kind),
                        Codec.STRING.fieldOf("item")
                                .forGetter(dev.luizloyola.anima.core.brain.task.PlaceStation::itemId),
                        POS.fieldOf("near")
                                .forGetter(dev.luizloyola.anima.core.brain.task.PlaceStation::near)
                ).apply(t, dev.luizloyola.anima.core.brain.task.PlaceStation::new)));
        TaskCodecs.register("anima:put_down", dev.luizloyola.anima.core.brain.task.PutDown.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        POI_KIND.fieldOf("kind")
                                .forGetter(dev.luizloyola.anima.core.brain.task.PutDown::kind),
                        Codec.STRING.fieldOf("item")
                                .forGetter(dev.luizloyola.anima.core.brain.task.PutDown::itemId),
                        POS.fieldOf("near")
                                .forGetter(dev.luizloyola.anima.core.brain.task.PutDown::near)
                ).apply(t, dev.luizloyola.anima.core.brain.task.PutDown::new)));

        // Both carry the same shape: where, what, how many, and how far the phase machine got —
        // the pause counter and moved tally are what let a reload resume mid-stack rather than
        // restart the whole errand.
        TaskCodecs.register("anima:take_items", dev.luizloyola.anima.core.brain.task.TakeItems.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        POS.fieldOf("at")
                                .forGetter(dev.luizloyola.anima.core.brain.task.TakeItems::at),
                        ITEM_SPEC.fieldOf("spec")
                                .forGetter(dev.luizloyola.anima.core.brain.task.TakeItems::spec),
                        Codec.INT.fieldOf("count")
                                .forGetter(dev.luizloyola.anima.core.brain.task.TakeItems::count),
                        HANDLING_PHASE.fieldOf("phase")
                                .forGetter(dev.luizloyola.anima.core.brain.task.TakeItems::phase),
                        Codec.INT.fieldOf("pause")
                                .forGetter(dev.luizloyola.anima.core.brain.task.TakeItems::pauseTicks),
                        Codec.INT.fieldOf("moved")
                                .forGetter(dev.luizloyola.anima.core.brain.task.TakeItems::moved),
                        // Optional so every save from before a look was a thing reads as a take.
                        Codec.BOOL.optionalFieldOf("looking", false)
                                .forGetter(dev.luizloyola.anima.core.brain.task.TakeItems::looking)
                ).apply(t, (at, spec, count, phase, pause, moved, looking) ->
                        new dev.luizloyola.anima.core.brain.task.TakeItems(at, spec, count, looking)
                                .resume(phase, pause, moved))));

        TaskCodecs.register("anima:put_items", dev.luizloyola.anima.core.brain.task.PutItems.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        // Optional since 2026-08-20: a stow names neither, resolving its store at
                        // OPEN and its selection from the context. Saves written before that carry
                        // both and take the of(...) branch below, so old worlds load unchanged.
                        // A deposit sits between the two — named spec, no anchor — added
                        // 2026-08-25; it round-trips through the same optional fields untouched.
                        POS.optionalFieldOf("at")
                                .forGetter(task -> java.util.Optional.ofNullable(task.at())),
                        ITEM_SPEC.optionalFieldOf("spec")
                                .forGetter(task -> java.util.Optional.ofNullable(task.spec())),
                        Codec.INT.fieldOf("count")
                                .forGetter(dev.luizloyola.anima.core.brain.task.PutItems::count),
                        HANDLING_PHASE.fieldOf("phase")
                                .forGetter(dev.luizloyola.anima.core.brain.task.PutItems::phase),
                        Codec.INT.fieldOf("pause")
                                .forGetter(dev.luizloyola.anima.core.brain.task.PutItems::pauseTicks),
                        Codec.INT.fieldOf("moved")
                                .forGetter(dev.luizloyola.anima.core.brain.task.PutItems::moved),
                        // The store a stow or a deposit settled on at OPEN. Added 2026-09-26; a
                        // save without it settles again, as it always has.
                        POS.optionalFieldOf("store")
                                .forGetter(task -> java.util.Optional.ofNullable(task.store()))
                ).apply(t, (at, spec, count, phase, pause, moved, store) ->
                        (at.isPresent() && spec.isPresent()
                                ? dev.luizloyola.anima.core.brain.task.PutItems.of(
                                        at.get(), spec.get(), count)
                                : spec.isPresent()
                                        ? dev.luizloyola.anima.core.brain.task.PutItems.deposit(
                                                spec.get(), count)
                                        : dev.luizloyola.anima.core.brain.task.PutItems.stow())
                                .resume(phase, pause, moved)
                                .settledOn(store.orElse(null)))));

        // Both carry a destination since 3a. Optional fields: the 2b flavour writes neither, and a
        // plan saved before the yard existed loads as that flavour without a migration.
        TaskCodecs.register("anima:ensure_store",
                dev.luizloyola.anima.core.brain.task.EnsureStore.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        POS.optionalFieldOf("hint").forGetter(
                                task -> java.util.Optional.ofNullable(task.hint()))
                ).apply(t, hint -> new dev.luizloyola.anima.core.brain.task.EnsureStore(
                        hint.orElse(null)))));
        TaskCodecs.register("anima:put_away_surplus",
                dev.luizloyola.anima.core.brain.task.PutAwaySurplus.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        POS.optionalFieldOf("hint").forGetter(
                                task -> java.util.Optional.ofNullable(task.hint())),
                        Codec.INT.optionalFieldOf("haul_line", 0).forGetter(
                                dev.luizloyola.anima.core.brain.task.PutAwaySurplus::haulLine)
                ).apply(t, (hint, line) ->
                        new dev.luizloyola.anima.core.brain.task.PutAwaySurplus(
                                hint.orElse(null), line))));

        TaskCodecs.register("anima:ensure_table",
                dev.luizloyola.anima.core.brain.task.EnsureTable.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        Codec.STRING.listOf().optionalFieldOf("pursued", java.util.List.of())
                                .forGetter(task -> java.util.List.copyOf(
                                        new java.util.TreeSet<>(task.pursued())))
                ).apply(t, pursued -> new dev.luizloyola.anima.core.brain.task.EnsureTable(
                        new java.util.HashSet<>(pursued)))));

        // The two wrappers carry TASKS, so both lean on the dispatch codec — whose per-key
        // lookups happen at parse time. That is what makes the recursion legal here.
        TaskCodecs.register("anima:try", dev.luizloyola.anima.core.brain.task.Try.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        TaskCodecs.codec().fieldOf("attempt")
                                .forGetter(dev.luizloyola.anima.core.brain.task.Try::attempt)
                ).apply(t, dev.luizloyola.anima.core.brain.task.Try::new)));

        // The two social roots. Both are arbiter-grantable, so a body saved mid-answer or
        // mid-seek writes one of them as its plan's root — unregistered, that save is refused
        // (and today NPEs; see docs/BUGS.md).
        TaskCodecs.register("anima:answer", dev.luizloyola.anima.core.brain.task.Answer.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        BEING_ID.fieldOf("who")
                                .forGetter(dev.luizloyola.anima.core.brain.task.Answer::who),
                        POS.fieldOf("where")
                                .forGetter(dev.luizloyola.anima.core.brain.task.Answer::where)
                ).apply(t, dev.luizloyola.anima.core.brain.task.Answer::new)));

        TaskCodecs.register("anima:face", dev.luizloyola.anima.core.brain.task.Face.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        BEING_ID.fieldOf("who")
                                .forGetter(dev.luizloyola.anima.core.brain.task.Face::who),
                        POS.fieldOf("where")
                                .forGetter(dev.luizloyola.anima.core.brain.task.Face::where),
                        Codec.INT.fieldOf("ticks")
                                .forGetter(dev.luizloyola.anima.core.brain.task.Face::ticks),
                        Codec.INT.fieldOf("remaining")
                                .forGetter(dev.luizloyola.anima.core.brain.task.Face::remaining)
                ).apply(t, (who, where, ticks, remaining) ->
                        new dev.luizloyola.anima.core.brain.task.Face(who, where, ticks)
                                .resume(remaining))));

        // A fight and its loop. The chase leg inside Engage is not written: the first tick after a
        // load orders a fresh one at the target's cell, which is where the saved one was going.
        TaskCodecs.register("anima:fight", dev.luizloyola.anima.core.brain.task.Fight.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        BEING_ID.fieldOf("target")
                                .forGetter(dev.luizloyola.anima.core.brain.task.Fight::target),
                        POS.fieldOf("where")
                                .forGetter(dev.luizloyola.anima.core.brain.task.Fight::where)
                ).apply(t, dev.luizloyola.anima.core.brain.task.Fight::new)));

        TaskCodecs.register("anima:engage", dev.luizloyola.anima.core.brain.task.Engage.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        BEING_ID.fieldOf("target")
                                .forGetter(dev.luizloyola.anima.core.brain.task.Engage::target),
                        POS.fieldOf("lastKnown")
                                .forGetter(dev.luizloyola.anima.core.brain.task.Engage::lastKnown),
                        Codec.INT.fieldOf("fruitless")
                                .forGetter(dev.luizloyola.anima.core.brain.task.Engage::fruitless)
                ).apply(t, (target, lastKnown, fruitless) ->
                        new dev.luizloyola.anima.core.brain.task.Engage(target, lastKnown)
                                .resume(fruitless))));

        // The rung 5 root: what this body would say to OPEN the encounter if speech.current()
        // finds nothing already there. The live Encounter itself is not here — it is world state
        // owned by the shared roster, not this task's — a restored Converse re-resolves it through
        // speech.current() on its own first tick instead of carrying a second copy of the record.
        //
        // Neither is its no-chase latch or its fruitless-leg count: both are readings of what just
        // happened in front of the body, and a reload is exactly when re-reading is right. See
        // Converse's own doc.
        TaskCodecs.register("anima:converse", dev.luizloyola.anima.core.brain.task.Converse.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        BEING_ID.fieldOf("other")
                                .forGetter(dev.luizloyola.anima.core.brain.task.Converse::other),
                        OPENING.fieldOf("opening")
                                .forGetter(dev.luizloyola.anima.core.brain.task.Converse::opening)
                ).apply(t, dev.luizloyola.anima.core.brain.task.Converse::new)));

        // The leg rides through the dispatch codec like the two wrappers below: a seek that came
        // back before its walk would choose a target again — with the mark for the first one
        // already spent, that is a body setting off toward somebody else. "target"/"hailed" ride
        // beside it because the walk alone cannot say who arrival should converse with, or
        // whether that conversation opens I_HAILED or QUIET; a seek already past the walk (no
        // "walk" in the save) carries them into a fresh Converse instead, per Converse's own
        // doc — the live Encounter is world state, not this task's, either way.
        TaskCodecs.register("anima:seek_company",
                dev.luizloyola.anima.core.brain.task.SeekCompany.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        BEING_ID.optionalFieldOf("target")
                                .forGetter(task -> java.util.Optional.ofNullable(task.target())),
                        Codec.BOOL.optionalFieldOf("hailed", false).forGetter(task -> task.hailed()),
                        TaskCodecs.codec().optionalFieldOf("walk").forGetter(task ->
                                java.util.Optional.ofNullable((Task) task.walk()))
                ).apply(t, (target, hailed, walk) -> {
                    dev.luizloyola.anima.core.brain.task.SeekCompany seek =
                            new dev.luizloyola.anima.core.brain.task.SeekCompany();
                    if (target.isEmpty()) {
                        // No target chosen yet — a hand-edited "hailed"/"walk" with no "target" is
                        // read the same way: re-choosing is what this task does whenever its
                        // target is gone, so that is the safe fallback, and also what a save from
                        // before this field existed looks like: walk-without-target, deliberately
                        // re-seeked rather than resumed blind.
                        return seek;
                    }
                    // An absent "walk" means it already succeeded before the save — reconstruct
                    // the conversation it handed off to. Anything present but not a GoTo is a
                    // hand-edited file; that falls back to a fresh seek too, same as no target.
                    if (walk.isEmpty()) {
                        return seek.resumeConverse(target.get(), hailed);
                    }
                    if (walk.get() instanceof GoTo leg) {
                        return seek.resume(target.get(), hailed, leg);
                    }
                    return seek;
                })));

        TaskCodecs.register("anima:kitted", dev.luizloyola.anima.core.brain.task.KittedErrand.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        ITEM_CALL.listOf().fieldOf("calls")
                                .forGetter(dev.luizloyola.anima.core.brain.task.KittedErrand::calls),
                        TaskCodecs.codec().fieldOf("work")
                                .forGetter(dev.luizloyola.anima.core.brain.task.KittedErrand::work)
                ).apply(t, dev.luizloyola.anima.core.brain.task.KittedErrand::new)));

        // Restored with Coverage.NONE: the project that owns the errand re-attaches its live
        // Coverage on the next grant. Every step walked in between IS lost — a SweepingErrand banks
        // nothing until it is re-granted. Pure loss, though, never a false "covered": NONE's methods
        // are no-ops, so the worst case is ground walked twice.
        TaskCodecs.register("anima:sweeping",
                dev.luizloyola.anima.core.brain.task.SweepingErrand.class,
                TaskCodecs.codec().fieldOf("work").xmap(
                        work -> new dev.luizloyola.anima.core.brain.task.SweepingErrand(
                                work, dev.luizloyola.anima.core.brain.knowledge.Coverage.NONE),
                        dev.luizloyola.anima.core.brain.task.SweepingErrand::work));
    }
}
