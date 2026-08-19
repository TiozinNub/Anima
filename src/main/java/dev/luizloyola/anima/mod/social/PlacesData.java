package dev.luizloyola.anima.mod.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.social.Places;
import dev.luizloyola.anima.mod.store.StoreGuard;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The persisted home of every claimed place ({@code <world>/data/anima/places.dat}).
 *
 * <p>Its own store rather than a corner of {@link PartyData}: a personal claim has no party, so it
 * has no party row to live in, and a claim outlives the party it was shared with.
 *
 * <p><b>The live {@link Places} is the authority.</b> The codec's getter reads it at the moment
 * vanilla asks, and this object marks itself dirty when a claim is made or dropped.
 */
public final class PlacesData extends SavedData implements StoreGuard.Checked {

    /** This store's file key — public so the boot guard can find it on disk. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("anima", "places");

    /** This store's schema. Bump when the shape below changes incompatibly. */
    private static final int SCHEMA = 1;

    /**
     * Kinds round-trip by their stable key. An unknown one means its mod is gone: the row errors
     * rather than guessing, and {@link StoreGuard}'s row count catches the drop at boot instead of
     * a party silently losing its workshop.
     */
    private static final Codec<PoiKind> KIND_CODEC = Codec.STRING.comapFlatMap(
            key -> PoiKind.byKey(key)
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "no POI kind is registered as \"" + key
                            + "\" — was a mod removed?")),
            PoiKind::key);

    /** Package-private so the round-trip test can reach it. */
    static final Codec<PlaceRow> ROW_CODEC = RecordCodecBuilder.create(row -> row.group(
            KIND_CODEC.fieldOf("kind").forGetter(PlaceRow::kind),
            Codec.INT.fieldOf("x").forGetter(r -> r.at().x()),
            Codec.INT.fieldOf("y").forGetter(r -> r.at().y()),
            Codec.INT.fieldOf("z").forGetter(r -> r.at().z()),
            UUIDUtil.CODEC.optionalFieldOf("owner")
                    .forGetter(r -> Optional.ofNullable(r.owner()).map(AgentId::value)),
            UUIDUtil.CODEC.optionalFieldOf("party")
                    .forGetter(r -> Optional.ofNullable(r.party()).map(PartyId::value)),
            Codec.LONG.fieldOf("since").forGetter(PlaceRow::since)
    ).apply(row, (kind, x, y, z, owner, party, since) -> new PlaceRow(kind, new Pos(x, y, z),
            owner.map(AgentId::of).orElse(null), party.map(PartyId::of).orElse(null), since)));

    private static final Codec<PlacesData> CODEC = RecordCodecBuilder.create(data -> data.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(d -> SCHEMA),
            Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED)
                    .forGetter(d -> d.places.rows().size()),
            ROW_CODEC.listOf().fieldOf("places").forGetter(d -> List.copyOf(d.places.rows()))
    ).apply(data, PlacesData::fromRows));

    public static final SavedDataType<PlacesData> TYPE =
            SavedDatas.type(ID, PlacesData::new, CODEC, DataFixTypes.LEVEL);

    private final Places places;
    private final int loadedVersion;
    private final int declaredRows;

    /** Constructs an empty store (the {@link SavedDataType} supplier for a fresh save). */
    public PlacesData() {
        this(new Places(), StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED);
    }

    private PlacesData(Places places, int loadedVersion, int declaredRows) {
        this.places = places;
        this.loadedVersion = loadedVersion;
        this.declaredRows = declaredRows;
        // Every route into this class goes through here, so nothing can forget to listen. The hook
        // exists because the thing that founds a claim is a core-layer task, which cannot reach a
        // SavedData — and a claim that never marked the store dirty would survive until some other
        // write happened to save the file, or not at all.
        places.onChange(this::setDirty);
    }

    @Override
    public int loadedVersion() {
        return loadedVersion;
    }

    @Override
    public int declaredRows() {
        return declaredRows;
    }

    @Override
    public int actualRows() {
        return places.rows().size();
    }

    /** Resolves the single, server-global store (kept on the overworld's data storage). */
    public static PlacesData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** The live store — mutating it marks this store dirty. */
    public Places places() {
        return places;
    }

    /**
     * Wires the roster this store asks about membership, once the server exists.
     *
     * <p>Reads go through {@code currentPartyOf} and founding through {@code partyOf}: the first
     * must never mint a party, or a {@code nearest} on every tick would create one per agent as a
     * side effect.
     */
    public void attach(MinecraftServer server) {
        PartyData parties = PartyData.get(server);
        places.asks(new Places.Parties() {
            @Override
            public Optional<PartyId> current(AgentId who) {
                return parties.currentPartyOf(who);
            }

            @Override
            public PartyId of(AgentId who) {
                return parties.partyOf(who);
            }
        });
    }

    /**
     * Drops every claim this agent owns — an erasure, not a burial.
     *
     * <p>No {@code setDirty()} here: {@link Places#forgetOwner} already runs the listener the
     * constructor installed, which is the one place this store learns it has something to save. A
     * second dirty-marking convention here would be a trap for the next mutator.
     */
    public boolean forget(AgentId who) {
        return places.forgetOwner(who) > 0;
    }

    private static PlacesData fromRows(int version, int declaredRows, List<PlaceRow> rows) {
        Places places = new Places();
        for (PlaceRow row : rows) {
            places.found(row.kind(), row.at(), row.owner(), row.party(), row.since());
        }
        return new PlacesData(places, version, declaredRows);
    }
}
