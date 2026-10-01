package dev.luizloyola.anima.mod.territory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.core.territory.Claimed;
import dev.luizloyola.anima.core.territory.Reason;
import dev.luizloyola.anima.core.territory.Territory;
import dev.luizloyola.anima.mod.store.StoreGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Every party's chunks and its kept claim log ({@code <world>/data/anima/territory.dat}). The live
 * {@link Territory} is the authority; this marks itself dirty on every event it hears.
 */
public final class TerritoryData extends SavedData implements StoreGuard.Checked {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("anima", "territory");

    private static final int SCHEMA = 1;

    /** Chunks of one dimension, x and z interleaved: a party's area is tens of chunks, not rows. */
    private record Group(String dimension, List<Integer> xz) {
    }

    private static final Codec<Group> GROUP_CODEC = RecordCodecBuilder.create(group -> group.group(
            Codec.STRING.fieldOf("dimension").forGetter(Group::dimension),
            Codec.INT.listOf().fieldOf("xz").forGetter(Group::xz)
    ).apply(group, Group::new));

    /** A set of chunks as the save writes it — public for a consumer that keeps chunks of its own. */
    public static final Codec<Set<ChunkKey>> CHUNKS = GROUP_CODEC.listOf().comapFlatMap(groups -> {
        Set<ChunkKey> chunks = new TreeSet<>();
        for (Group group : groups) {
            if (group.xz().size() % 2 != 0) {
                return DataResult.error(() -> "an odd number of coordinates in " + group.dimension());
            }
            for (int i = 0; i < group.xz().size(); i += 2) {
                chunks.add(new ChunkKey(group.dimension(), group.xz().get(i), group.xz().get(i + 1)));
            }
        }
        return DataResult.success(chunks);
    }, chunks -> {
        Map<String, List<Integer>> byDimension = new LinkedHashMap<>();
        for (ChunkKey chunk : new TreeSet<>(chunks)) {
            List<Integer> xz = byDimension.computeIfAbsent(chunk.dimension(), key -> new ArrayList<>());
            xz.add(chunk.x());
            xz.add(chunk.z());
        }
        return byDimension.entrySet().stream().map(e -> new Group(e.getKey(), e.getValue())).toList();
    });

    private static <E extends Enum<E>> Codec<E> named(Class<E> type) {
        return Codec.STRING.comapFlatMap(name -> {
            try {
                return DataResult.success(Enum.valueOf(type, name));
            } catch (IllegalArgumentException e) {
                return DataResult.error(() -> "no " + type.getSimpleName() + " " + name);
            }
        }, Enum::name);
    }

    /** One event, without its party: the row it is kept under names that. */
    private record Event(long tick, Reason.Kind kind, String detail, Claimed.Refusal refusal,
                         Set<ChunkKey> added, Set<ChunkKey> removed, Set<ChunkKey> blocking) {

        static Event of(Claimed claimed) {
            return new Event(claimed.tick(), claimed.why().kind(), claimed.why().detail(),
                    claimed.refusal(), claimed.added(), claimed.removed(), claimed.blocking());
        }

        Claimed in(PartyId party) {
            return new Claimed(tick, party, new TreeSet<>(added), new TreeSet<>(removed),
                    Reason.of(kind, detail), refusal, new TreeSet<>(blocking));
        }
    }

    private static final Codec<Event> EVENT_CODEC = RecordCodecBuilder.create(event -> event.group(
            Codec.LONG.fieldOf("tick").forGetter(Event::tick),
            named(Reason.Kind.class).fieldOf("kind").forGetter(Event::kind),
            Codec.STRING.optionalFieldOf("detail", "").forGetter(Event::detail),
            named(Claimed.Refusal.class).optionalFieldOf("refusal", Claimed.Refusal.NONE)
                    .forGetter(Event::refusal),
            CHUNKS.optionalFieldOf("added", Set.of()).forGetter(Event::added),
            CHUNKS.optionalFieldOf("removed", Set.of()).forGetter(Event::removed),
            CHUNKS.optionalFieldOf("blocking", Set.of()).forGetter(Event::blocking)
    ).apply(event, Event::new));

    /** One party: what it holds and what it was last told. Package-private for the round trip. */
    record Row(PartyId party, Set<ChunkKey> area, List<Claimed> history) {
    }

    static final Codec<Row> ROW_CODEC = RecordCodecBuilder.create(row -> row.group(
            UUIDUtil.CODEC.fieldOf("party").forGetter(r -> r.party().value()),
            CHUNKS.optionalFieldOf("area", Set.of()).forGetter(Row::area),
            EVENT_CODEC.listOf().optionalFieldOf("history", List.of())
                    .forGetter(r -> r.history().stream().map(Event::of).toList())
    ).apply(row, (party, area, history) -> {
        PartyId id = PartyId.of(party);
        return new Row(id, area, history.stream().map(event -> event.in(id)).toList());
    }));

    private static final Codec<TerritoryData> CODEC = RecordCodecBuilder.create(data -> data.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(d -> SCHEMA),
            Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED).forGetter(TerritoryData::actualRows),
            ROW_CODEC.listOf().fieldOf("parties").forGetter(TerritoryData::rows)
    ).apply(data, TerritoryData::fromRows));

    public static final SavedDataType<TerritoryData> TYPE =
            SavedDatas.type(ID, TerritoryData::new, CODEC, DataFixTypes.LEVEL);

    private final Territory territory;
    private final int loadedVersion;
    private final int declaredRows;

    public TerritoryData() {
        this(new Territory(), StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED);
    }

    private TerritoryData(Territory territory, int loadedVersion, int declaredRows) {
        this.territory = territory;
        this.loadedVersion = loadedVersion;
        this.declaredRows = declaredRows;
        // A refusal changes no chunk but joins the kept log, so it is saved too.
        territory.onEvent(event -> setDirty());
    }

    public static TerritoryData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public Territory territory() {
        return territory;
    }

    @Override
    public int loadedVersion() {
        return loadedVersion;
    }

    @Override
    public int declaredRows() {
        return declaredRows;
    }

    /** One row per party with chunks or a history. */
    @Override
    public int actualRows() {
        return parties().size();
    }

    private Set<PartyId> parties() {
        Set<PartyId> parties = new LinkedHashSet<>(territory.parties());
        parties.addAll(territory.historied());
        return parties;
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (PartyId party : parties()) {
            rows.add(new Row(party, territory.area(party), territory.history(party)));
        }
        return rows;
    }

    private static TerritoryData fromRows(int version, int declaredRows, List<Row> rows) {
        Territory territory = new Territory();
        for (Row row : rows) {
            territory.restore(row.party(), row.area(), row.history());
        }
        return new TerritoryData(territory, version, declaredRows);
    }
}
