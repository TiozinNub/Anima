package dev.luizloyola.anima.mod.identity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.mod.AnimaMod;
import dev.luizloyola.anima.mod.store.StoreGuard;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Who has died, and the little that is worth keeping about how.
 *
 * <p>After the fact, dead and unloaded are indistinguishable — a directory entry with no loaded
 * body is what a settler in a far chunk looks like — so a death is knowable only if it is
 * recorded as it happens. The old {@code purge graveyard} guessed: it called every unloaded
 * identity dead and destroyed it.
 *
 * <p>Small and resident rather than a hot index over a cold store: identity SURVIVES death by
 * decision, so {@code PersonDirectory} still answers for the dead and only the fact of the death
 * was missing. A row is a few dozen bytes; a thousand dead is kilobytes.
 *
 * <p>A grave is not erased by a burial — it is the burial. Registered with {@link AgentRecords} as
 * surviving death, and dropped only by an erasure: a Person unmade by command never died.
 */
public final class Graves extends SavedData implements StoreGuard.Checked {

    /** This store's file key — public so the boot guard can find it on disk. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "graves");

    /** This store's schema. Bump when the shape below changes incompatibly. */
    private static final int SCHEMA = 1;

    /**
     * What is kept about one death: when, where, and the story the combat tracker told. Not the
     * inventory or the mind — those were dropped and wiped, and a second copy would let the rules
     * drift apart.
     *
     * @param diedAtTick when it happened, on the clock every journal line uses
     * @param dimension  the level it happened in
     * @param cause      the death message, e.g. {@code "Alice starved to death"}; may be blank
     */
    public record Death(long diedAtTick, String dimension, int x, int y, int z, String cause) {
    }

    private static final Codec<Death> DEATH_CODEC = RecordCodecBuilder.create(d -> d.group(
            Codec.LONG.fieldOf("tick").forGetter(Death::diedAtTick),
            Codec.STRING.optionalFieldOf("dim", "").forGetter(Death::dimension),
            Codec.INT.fieldOf("x").forGetter(Death::x),
            Codec.INT.fieldOf("y").forGetter(Death::y),
            Codec.INT.fieldOf("z").forGetter(Death::z),
            Codec.STRING.optionalFieldOf("cause", "").forGetter(Death::cause)
    ).apply(d, Death::new));

    private record Row(UUID who, Death death) {
    }

    private static final Codec<Row> ROW_CODEC = RecordCodecBuilder.create(r -> r.group(
            UUIDUtil.CODEC.fieldOf("who").forGetter(Row::who),
            DEATH_CODEC.fieldOf("death").forGetter(Row::death)
    ).apply(r, Row::new));

    private static final Codec<Graves> CODEC = RecordCodecBuilder.create(g -> g.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(x -> SCHEMA),
            Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED).forGetter(x -> x.rows().size()),
            ROW_CODEC.listOf().fieldOf("graves").forGetter(Graves::rows)
    ).apply(g, Graves::fromRows));

    public static final SavedDataType<Graves> TYPE =
            SavedDatas.type(ID, Graves::new, CODEC, DataFixTypes.LEVEL);

    /** Insertion-ordered so a readout lists the dead in the order they died. */
    private final Map<AgentId, Death> dead;
    private final int loadedVersion;
    private final int declaredRows;

    public Graves() {
        this(new LinkedHashMap<>(), StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED);
    }

    private Graves(Map<AgentId, Death> dead, int loadedVersion, int declaredRows) {
        this.dead = dead;
        this.loadedVersion = loadedVersion;
        this.declaredRows = declaredRows;
    }

    public static Graves get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * Records a death, if this agent is not already buried. <b>Idempotent</b>: a body lingers
     * through its death animation, and the first record is the true one — a later call would move
     * the timestamp forward.
     *
     * @return whether this was news
     */
    public boolean bury(AgentId who, Death death) {
        if (dead.containsKey(who)) {
            return false;
        }
        dead.put(who, death);
        setDirty();
        return true;
    }

    /** Whether this agent died. The question `list` and the contact filter ask. */
    public boolean isDead(AgentId who) {
        return dead.containsKey(who);
    }

    public Optional<Death> deathOf(AgentId who) {
        return Optional.ofNullable(dead.get(who));
    }

    /** Everyone buried, oldest death first. */
    public Set<AgentId> all() {
        return java.util.Collections.unmodifiableSet(dead.keySet());
    }

    public int size() {
        return dead.size();
    }

    /** Whatever in {@code ids} is not buried — the default view of any list of agents. */
    public List<AgentId> living(Collection<AgentId> ids) {
        List<AgentId> alive = new ArrayList<>(ids.size());
        for (AgentId id : ids) {
            if (!dead.containsKey(id)) {
                alive.add(id);
            }
        }
        return alive;
    }

    /** Drops a grave — erasure only; a burial must never call this. See the class note. */
    public boolean forget(AgentId who) {
        boolean had = dead.remove(who) != null;
        if (had) {
            setDirty();
        }
        return had;
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
        return dead.size();
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>(dead.size());
        dead.forEach((who, death) -> rows.add(new Row(who.value(), death)));
        return rows;
    }

    private static Graves fromRows(int version, int declaredRows, List<Row> rows) {
        Map<AgentId, Death> dead = new LinkedHashMap<>();
        for (Row row : rows) {
            dead.put(AgentId.of(row.who()), row.death());
        }
        return new Graves(dead, version, declaredRows);
    }
}
