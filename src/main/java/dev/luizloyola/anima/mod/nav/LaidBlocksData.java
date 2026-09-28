package dev.luizloyola.anima.mod.nav;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.LaidBlocks;
import dev.luizloyola.anima.core.social.PartyId;
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
 * The persisted home of every block a route laid and left ({@code <world>/data/anima/laid.dat}) —
 * see {@link LaidBlocks}. Server-global, on the overworld's storage, like the places.
 *
 * <p><b>The live {@link LaidBlocks} is the authority.</b> The codec reads it when vanilla asks, and
 * this object marks itself dirty whenever it changes.
 */
public final class LaidBlocksData extends SavedData implements StoreGuard.Checked {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("anima", "laid");

    /** This store's schema. Bump when the shape below changes incompatibly. */
    private static final int SCHEMA = 1;

    private static final Codec<LaidBlocks.Row> ROW = RecordCodecBuilder.create(row -> row.group(
            Codec.INT.fieldOf("x").forGetter(r -> r.at().x()),
            Codec.INT.fieldOf("y").forGetter(r -> r.at().y()),
            Codec.INT.fieldOf("z").forGetter(r -> r.at().z()),
            Codec.STRING.fieldOf("block").forGetter(LaidBlocks.Row::block),
            Codec.STRING.fieldOf("kind").forGetter(r -> r.kind().name()),
            UUIDUtil.CODEC.optionalFieldOf("layer")
                    .forGetter(r -> Optional.ofNullable(r.layer()).map(AgentId::value)),
            UUIDUtil.CODEC.optionalFieldOf("party")
                    .forGetter(r -> Optional.ofNullable(r.party()).map(PartyId::value)),
            Codec.LONG.fieldOf("tick").forGetter(LaidBlocks.Row::tick),
            Codec.INT.fieldOf("run").forGetter(LaidBlocks.Row::run)
    ).apply(row, (x, y, z, block, kind, layer, party, tick, run) -> new LaidBlocks.Row(
            new Pos(x, y, z), block, LaidBlocks.Kind.valueOf(kind),
            layer.map(AgentId::of).orElse(null), party.map(PartyId::of).orElse(null), tick, run)));

    private static final Codec<LaidBlocks.Run> RUN = RecordCodecBuilder.create(run -> run.group(
            Codec.INT.fieldOf("id").forGetter(LaidBlocks.Run::id),
            Codec.STRING.fieldOf("kind").forGetter(r -> r.kind().name()),
            Codec.INT.fieldOf("walked").forGetter(LaidBlocks.Run::walked),
            Codec.LONG.fieldOf("lastWalked").forGetter(LaidBlocks.Run::lastWalked)
    ).apply(run, (id, kind, walked, last) -> new LaidBlocks.Run(id, LaidBlocks.Kind.valueOf(kind),
            walked, last)));

    private static final Codec<LaidBlocksData> CODEC = RecordCodecBuilder.create(data -> data.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(d -> SCHEMA),
            Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED)
                    .forGetter(d -> d.laid.rows().size()),
            ROW.listOf().fieldOf("blocks").forGetter(d -> d.laid.rows()),
            RUN.listOf().optionalFieldOf("runs", List.of()).forGetter(d -> d.laid.runs())
    ).apply(data, LaidBlocksData::fromRows));

    public static final SavedDataType<LaidBlocksData> TYPE =
            SavedDatas.type(ID, LaidBlocksData::new, CODEC, DataFixTypes.LEVEL);

    private final LaidBlocks laid;
    private final int loadedVersion;
    private final int declaredRows;

    /** An empty store (the {@link SavedDataType} supplier for a fresh save). */
    public LaidBlocksData() {
        this(new LaidBlocks(), StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED);
    }

    private LaidBlocksData(LaidBlocks laid, int loadedVersion, int declaredRows) {
        this.laid = laid;
        this.loadedVersion = loadedVersion;
        this.declaredRows = declaredRows;
        laid.onChange(this::setDirty);
    }

    public static LaidBlocksData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** The live record — changing it marks this store dirty. */
    public LaidBlocks laid() {
        return this.laid;
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
        return this.laid.rows().size();
    }

    private static LaidBlocksData fromRows(int version, int declaredRows, List<LaidBlocks.Row> rows,
                                           List<LaidBlocks.Run> runs) {
        LaidBlocks laid = new LaidBlocks();
        for (LaidBlocks.Run run : runs) {
            laid.restore(run);
        }
        for (LaidBlocks.Row row : rows) {
            laid.lay(row.at(), row.block(), row.kind(), row.layer(), row.party(), row.tick(), row.run());
        }
        return new LaidBlocksData(laid, version, declaredRows);
    }
}
