package dev.luizloyola.anima.mod.brain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.BeingReading;
import dev.luizloyola.anima.core.brain.sense.BeingSensorCore;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.UUIDUtil;

/**
 * How a body's memory of other bodies writes itself down.
 *
 * <p>Losing this state makes an agent <b>re-notice</b> everything: a track exists so a body keeps
 * reporting what it can no longer see, and the identification ladder is a rung climbed once and
 * held. Dropped, every settler meets its neighbours again and fires recognition out loud.
 */
public final class SenseState {

    private SenseState() {
    }

    /** Enums round-trip by name, and an unknown one errors — a silent default here would quietly
     *  change what a body thinks it is looking at. */
    private static <E extends Enum<E>> Codec<E> byName(Class<E> type, Function<String, E> lookup) {
        return Codec.STRING.comapFlatMap(
                name -> {
                    try {
                        return DataResult.success(lookup.apply(name));
                    } catch (IllegalArgumentException e) {
                        return DataResult.error(
                                () -> "no " + type.getSimpleName() + " called \"" + name + "\"");
                    }
                },
                Enum<E>::name);
    }

    /**
     * A kind is a REGISTRATION, not an enum, so it round-trips by its key — and a key nobody
     * declares any more errors rather than guessing.
     */
    private static final Codec<Being.Kind> KIND = Codec.STRING.comapFlatMap(
            key -> Being.Kind.byKey(key)
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(
                            () -> "no being kind is registered as \"" + key + "\" — was a mod removed?")),
            Being.Kind::key);
    private static final Codec<Being.Locomotion> LOCOMOTION =
            byName(Being.Locomotion.class, Being.Locomotion::valueOf);
    private static final Codec<Being.Activity> ACTIVITY =
            byName(Being.Activity.class, Being.Activity::valueOf);
    private static final Codec<Being.Awareness> AWARENESS =
            byName(Being.Awareness.class, Being.Awareness::valueOf);
    private static final Codec<Being.Identified> IDENTIFIED =
            byName(Being.Identified.class, Being.Identified::valueOf);

    private static final Codec<BeingId> BEING_ID = UUIDUtil.CODEC.xmap(BeingId::new, BeingId::value);

    private static final Codec<Pos> POS = RecordCodecBuilder.create(p -> p.group(
            Codec.INT.fieldOf("x").forGetter(Pos::x),
            Codec.INT.fieldOf("y").forGetter(Pos::y),
            Codec.INT.fieldOf("z").forGetter(Pos::z)
    ).apply(p, Pos::new));

    private static final Codec<Being.Gear> GEAR = RecordCodecBuilder.create(g -> g.group(
            Codec.BOOL.fieldOf("melee").forGetter(Being.Gear::melee),
            Codec.BOOL.fieldOf("ranged").forGetter(Being.Gear::ranged),
            Codec.BOOL.fieldOf("armored").forGetter(Being.Gear::armored),
            Codec.BOOL.fieldOf("mounted").forGetter(Being.Gear::mounted),
            Codec.BOOL.fieldOf("baby").forGetter(Being.Gear::baby)
    ).apply(g, Being.Gear::new));

    private static final Codec<BeingReading> READING = RecordCodecBuilder.create(r -> r.group(
            BEING_ID.fieldOf("id").forGetter(BeingReading::id),
            KIND.fieldOf("kind").forGetter(BeingReading::kind),
            Codec.STRING.fieldOf("species").forGetter(BeingReading::species),
            Codec.STRING.fieldOf("name").forGetter(BeingReading::name),
            Codec.STRING.optionalFieldOf("profession")
                    .forGetter(reading -> Optional.ofNullable(reading.profession())),
            Codec.BOOL.fieldOf("herdAnimal").forGetter(BeingReading::herdAnimal),
            POS.fieldOf("pos").forGetter(BeingReading::pos),
            Codec.DOUBLE.fieldOf("distance").forGetter(BeingReading::distance),
            LOCOMOTION.fieldOf("locomotion").forGetter(BeingReading::locomotion),
            Codec.BOOL.fieldOf("sneaking").forGetter(BeingReading::sneaking),
            Codec.BOOL.fieldOf("watching").forGetter(BeingReading::watching),
            Codec.BOOL.fieldOf("aimedAt").forGetter(BeingReading::aimedAt),
            Codec.BOOL.fieldOf("aggressive").forGetter(BeingReading::aggressive),
            GEAR.fieldOf("gear").forGetter(BeingReading::gear),
            ACTIVITY.fieldOf("activity").forGetter(BeingReading::activity)
    ).apply(r, (id, kind, species, name, profession, herdAnimal, pos, distance, locomotion,
                sneaking, watching, aimedAt, aggressive, gear, activity) ->
            new BeingReading(id, kind, species, name, profession.orElse(null), herdAnimal, pos,
                    distance, locomotion, sneaking, watching, aimedAt, aggressive, gear,
                    activity)));

    private static final Codec<BeingSensorCore.TrackState> TRACK =
            RecordCodecBuilder.create(t -> t.group(
                    READING.fieldOf("last").forGetter(BeingSensorCore.TrackState::last),
                    AWARENESS.fieldOf("awareness").forGetter(BeingSensorCore.TrackState::awareness),
                    IDENTIFIED.fieldOf("tier").forGetter(BeingSensorCore.TrackState::tier),
                    Codec.LONG.fieldOf("nextCheck").forGetter(BeingSensorCore.TrackState::nextCheckAt),
                    Codec.LONG.fieldOf("lastLive").forGetter(BeingSensorCore.TrackState::lastLiveAt),
                    Codec.LONG.fieldOf("heard").forGetter(BeingSensorCore.TrackState::heardAt),
                    Codec.LONG.fieldOf("activityAt").forGetter(BeingSensorCore.TrackState::activityAt),
                    Codec.DOUBLE.fieldOf("trendDistance")
                            .forGetter(BeingSensorCore.TrackState::trendDistance),
                    Codec.LONG.fieldOf("trendAt").forGetter(BeingSensorCore.TrackState::trendAt),
                    Codec.BOOL.fieldOf("approaching")
                            .forGetter(BeingSensorCore.TrackState::approaching),
                    BEING_ID.optionalFieldOf("herd")
                            .forGetter(track -> Optional.ofNullable(track.herd())),
                    Codec.LONG.fieldOf("attackedAt").forGetter(BeingSensorCore.TrackState::attackedAt)
            ).apply(t, (last, awareness, tier, nextCheck, lastLive, heard, activityAt,
                        trendDistance, trendAt, approaching, herd, attackedAt) ->
                    new BeingSensorCore.TrackState(last, awareness, tier, nextCheck, lastLive,
                            heard, activityAt, trendDistance, trendAt, approaching,
                            herd.orElse(null), attackedAt)));

    private static final Codec<BeingSensorCore.HerdState> HERD =
            RecordCodecBuilder.create(h -> h.group(
                    BEING_ID.fieldOf("id").forGetter(BeingSensorCore.HerdState::id),
                    Codec.STRING.fieldOf("species").forGetter(BeingSensorCore.HerdState::species),
                    BEING_ID.listOf().fieldOf("members")
                            .forGetter(BeingSensorCore.HerdState::members)
            ).apply(h, BeingSensorCore.HerdState::new));

    /** Everything a body is holding about other bodies. */
    public static final Codec<BeingSensorCore.State> BEINGS =
            RecordCodecBuilder.create(s -> s.group(
                    TRACK.listOf().fieldOf("tracks").forGetter(BeingSensorCore.State::tracks),
                    HERD.listOf().fieldOf("herds").forGetter(BeingSensorCore.State::herds),
                    Codec.LONG.fieldOf("lastSweep").forGetter(BeingSensorCore.State::lastSweepAt),
                    POS.fieldOf("lastFeet").forGetter(BeingSensorCore.State::lastFeet)
            ).apply(s, BeingSensorCore.State::new));
}
