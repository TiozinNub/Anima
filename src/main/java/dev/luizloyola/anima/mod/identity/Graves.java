package dev.luizloyola.anima.mod.identity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.log.Entry;
import dev.luizloyola.anima.mod.AnimaMod;
import dev.luizloyola.anima.mod.brain.BrainState;
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

    /**
     * This store's schema. Bump when the shape below changes incompatibly.
     *
     * <p>2 — the grave became a black box: damage type, killer, mind at the end, last words. All
     * optional fields, so schema-1 graves still load, as bare tombstones.
     */
    private static final int SCHEMA = 2;

    /**
     * What is kept about one death: when, where, how, and the state of the mind that ended.
     *
     * <p><b>Not the inventory</b> (decision: Luiz): those items lie where they fell, and a second
     * copy would be a place for the two to drift apart. Everything else is kept because nothing
     * else can be recovered after this moment.
     *
     * <p>Prose where something is read, data where something is asked: {@code mind} and
     * {@code lastWords} are the sentences the live readouts already print, one vocabulary rather
     * than two; {@code damageType} and {@code killer} are structured because a later feature
     * queries them.
     *
     * @param diedAtTick the game time it happened, on the same clock every journal line uses
     * @param dimension  the level it happened in
     * @param cause      the death message, e.g. {@code "Alice starved to death"}; may be blank
     * @param damageType the damage type's message id ({@code mob}, {@code starve}, {@code fall}),
     *                   or blank when nothing was handed one
     * @param killer     what dealt it, by display name; blank when nothing did (a fall, a drowning)
     * @param killerId   the killer's own handle, when the killer was an agent or a player — the
     *                   seam a social feature reads, and the reason this is not merely a name
     * @param mind       the state of the mind at the end, one {@code label: sentence} per line
     * @param lastWords  the tail of the journal, oldest first — the fall, the fight or the slow
     *                   starve that led here, kept because the ring itself is swept ten game-minutes
     *                   later and does not survive a restart at all
     */
    public record Death(long diedAtTick, String dimension, int x, int y, int z, String cause,
                        String damageType, String killer, Optional<AgentId> killerId,
                        List<String> mind, List<Entry> lastWords) {

        public Death {
            mind = List.copyOf(mind);
            lastWords = List.copyOf(lastWords);
        }

        /** The bare facts, with nothing written on the rest of the stone. */
        public Death(long diedAtTick, String dimension, int x, int y, int z, String cause) {
            this(diedAtTick, dimension, x, y, z, cause, "", "", Optional.empty(),
                    List.of(), List.of());
        }

        public String where() {
            return x + ", " + y + ", " + z;
        }
    }

    /**
     * The archive format of one death. Package-private rather than private so its own package's
     * test can pin it: graves outlive the code that wrote them, and a codec that silently stopped
     * round-tripping would surface only as a death with nothing recorded under it.
     */
    static final Codec<Death> DEATH_CODEC = RecordCodecBuilder.create(d -> d.group(
            Codec.LONG.fieldOf("tick").forGetter(Death::diedAtTick),
            Codec.STRING.optionalFieldOf("dim", "").forGetter(Death::dimension),
            Codec.INT.fieldOf("x").forGetter(Death::x),
            Codec.INT.fieldOf("y").forGetter(Death::y),
            Codec.INT.fieldOf("z").forGetter(Death::z),
            Codec.STRING.optionalFieldOf("cause", "").forGetter(Death::cause),
            Codec.STRING.optionalFieldOf("damage", "").forGetter(Death::damageType),
            Codec.STRING.optionalFieldOf("killer", "").forGetter(Death::killer),
            UUIDUtil.CODEC.xmap(AgentId::of, AgentId::value)
                    .optionalFieldOf("killerId").forGetter(Death::killerId),
            Codec.STRING.listOf().optionalFieldOf("mind", List.of()).forGetter(Death::mind),
            // The journal codec the entity's own saved ring uses, so a grave's lines and a living
            // body's lines are one shape on disk. Categories round-trip by NAME, and these rows are
            // archives: removing a Category is a schema migration, not a rename.
            BrainState.JOURNAL.optionalFieldOf("words", List.of()).forGetter(Death::lastWords)
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
