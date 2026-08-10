package dev.luizloyola.anima.mod.brain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.ConsumeItem;
import dev.luizloyola.anima.core.brain.task.FleeStep;
import dev.luizloyola.anima.core.brain.task.GatherNearbyDrops;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.Idle;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.SatisfyHunger;
import dev.luizloyola.anima.core.brain.task.SurveyArea;
import dev.luizloyola.anima.core.brain.task.WanderStep;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.nav.Gait;

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

    /**
     * A class of items, by the name it registered under: the matcher is a lambda and cannot be
     * written down, so the name is the handle and {@link ItemSpec#byName} supplies the rest. An
     * unregistered name errors rather than inventing a spec that matches nothing.
     */
    private static final Codec<ItemSpec> ITEM_SPEC = Codec.STRING.comapFlatMap(
            name -> ItemSpec.byName(name)
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(
                            () -> "no item spec is registered as \"" + name + "\" — was a mod removed?")),
            ItemSpec::name);

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
                        Codec.INT.fieldOf("count").forGetter(ObtainItem::count)
                ).apply(t, ObtainItem::new)));

        TaskCodecs.register("anima:wander", WanderStep.class,
                RecordCodecBuilder.mapCodec(t -> t.group(
                        Codec.INT.fieldOf("radius").forGetter(WanderStep::radius)
                ).apply(t, WanderStep::new)));

        // The coverage is the progress: a sweep that came back at its start would re-walk a box
        // somebody had already walked.
        // Named rather than chained: inference follows the target type through an xmap, so
        // building the group inline against SurveyArea makes javac read the task's fields off the
        // task instead of off its state, in four hundred lines of DFU generics.
        MapCodec<SurveyArea.State> sweep = RecordCodecBuilder.mapCodec(t -> t.group(
                REGION.fieldOf("area").forGetter(SurveyArea.State::area),
                POI_KIND.fieldOf("looking").forGetter(SurveyArea.State::looking),
                Codec.FLOAT.listOf().fieldOf("known").forGetter(SurveyArea.State::confidence),
                Codec.INT.listOf().fieldOf("tries").forGetter(SurveyArea.State::tries),
                Codec.INT.fieldOf("target").forGetter(SurveyArea.State::target)
        ).apply(t, SurveyArea.State::new));
        TaskCodecs.register("anima:survey_area", SurveyArea.class, sweep.xmap(
                state -> new SurveyArea(state.area(), state.looking()).restore(state),
                SurveyArea::snapshot));

        // Nothing to carry: both are pure decomposers whose choices come from the context, and the
        // stream those choices draw from belongs to the body and is saved there.
        TaskCodecs.register("anima:flee", FleeStep.class, MapCodec.unit(FleeStep::new));
        TaskCodecs.register("anima:eat", SatisfyHunger.class, MapCodec.unit(SatisfyHunger::new));
    }
}
