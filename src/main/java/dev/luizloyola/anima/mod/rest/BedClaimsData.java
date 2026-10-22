package dev.luizloyola.anima.mod.rest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.rest.BedClaims;
import dev.luizloyola.anima.mod.store.StoreGuard;
import java.util.List;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The persisted home of {@link BedClaims} ({@code <world>/data/anima/beds.dat}), on the overworld's
 * storage: a claim is an overworld bed, since a bed anywhere else explodes. The live claims are the
 * authority; this marks itself dirty when they change.
 */
public final class BedClaimsData extends SavedData implements StoreGuard.Checked {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("anima", "beds");

    private static final int SCHEMA = 1;

    private static final Codec<BedClaims.Row> ROW = RecordCodecBuilder.create(row -> row.group(
            Codec.INT.fieldOf("x").forGetter(r -> r.bed().x()),
            Codec.INT.fieldOf("y").forGetter(r -> r.bed().y()),
            Codec.INT.fieldOf("z").forGetter(r -> r.bed().z()),
            UUIDUtil.CODEC.fieldOf("sleeper").forGetter(r -> r.sleeper().value())
    ).apply(row, (x, y, z, sleeper) -> new BedClaims.Row(new Pos(x, y, z), AgentId.of(sleeper))));

    private static final Codec<BedClaimsData> CODEC = RecordCodecBuilder.create(data -> data.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(d -> SCHEMA),
            Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED).forGetter(d -> d.claims.rows().size()),
            ROW.listOf().fieldOf("beds").forGetter(d -> d.claims.rows())
    ).apply(data, BedClaimsData::fromRows));

    public static final SavedDataType<BedClaimsData> TYPE =
            SavedDatas.type(ID, BedClaimsData::new, CODEC, DataFixTypes.LEVEL);

    private final BedClaims claims;
    private final int loadedVersion;
    private final int declaredRows;

    public BedClaimsData() {
        this(new BedClaims(), StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED);
    }

    private BedClaimsData(BedClaims claims, int loadedVersion, int declaredRows) {
        this.claims = claims;
        this.loadedVersion = loadedVersion;
        this.declaredRows = declaredRows;
        claims.onChange(this::setDirty);
    }

    public static BedClaimsData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public BedClaims claims() {
        return this.claims;
    }

    @Override
    public int loadedVersion() {
        return this.loadedVersion;
    }

    @Override
    public int declaredRows() {
        return this.declaredRows;
    }

    @Override
    public int actualRows() {
        return this.claims.rows().size();
    }

    private static BedClaimsData fromRows(int version, int declaredRows, List<BedClaims.Row> rows) {
        BedClaims claims = new BedClaims();
        for (BedClaims.Row row : rows) {
            claims.claim(row.sleeper(), row.bed());
        }
        return new BedClaimsData(claims, version, declaredRows);
    }
}
