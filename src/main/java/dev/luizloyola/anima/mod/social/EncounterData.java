package dev.luizloyola.anima.mod.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Encounters;
import dev.luizloyola.anima.core.social.speech.Utterance;
import dev.luizloyola.anima.mod.store.StoreGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The world-scoped, persisted home of every conversation — the {@code ContactData} pattern
 * applied to encounters ({@code <world>/data/anima/encounters.dat}).
 *
 * <p>{@link Encounters} holds the logic; this owns persistence and the dirty flag. A reload is
 * not a conversation — {@link #fromRows} seats every record straight into the roster via
 * {@link Encounters#restore}, never through a {@code SpeechEngine}, so nothing re-fires a
 * listener or pays a need gauge for lines that were already spoken.
 */
public final class EncounterData extends SavedData implements StoreGuard.Checked {
    /** This store's file key — public so the boot guard can find it on disk. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("anima", "encounters");

    /** One transcript line: SYSTEM when {@code author} is empty. Package-private — the round-trip
     *  test names this type directly, the ContactDataTest seam pattern. */
    record Line(Optional<UUID> author, String act, Map<String, String> payload, long tick) {
    }

    /** One encounter's row: {@code {id, participants:[…], openedAt, closedAt, lines:[…]}}. */
    record Row(UUID id, List<UUID> participants, long openedAt, long closedAt, List<Line> lines) {
    }

    private static final Codec<Line> LINE_CODEC = RecordCodecBuilder.create(line -> line.group(
            UUIDUtil.CODEC.optionalFieldOf("author").forGetter(Line::author),
            Codec.STRING.fieldOf("act").forGetter(Line::act),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("payload")
                    .forGetter(Line::payload),
            Codec.LONG.fieldOf("tick").forGetter(Line::tick)
    ).apply(line, Line::new));

    private static final Codec<Row> ROW_CODEC = RecordCodecBuilder.create(row -> row.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Row::id),
            UUIDUtil.CODEC.listOf().fieldOf("participants").forGetter(Row::participants),
            Codec.LONG.fieldOf("openedAt").forGetter(Row::openedAt),
            Codec.LONG.fieldOf("closedAt").forGetter(Row::closedAt),
            LINE_CODEC.listOf().fieldOf("lines").forGetter(Row::lines)
    ).apply(row, Row::new));

    /** This store's schema. Bump when the shape below changes incompatibly. */
    private static final int SCHEMA = 1;

    private static final Codec<EncounterData> CODEC = RecordCodecBuilder.create(data -> data.group(
            // Written always, read as 0 from a file that predates it — either way not the factory's
            // NEVER_LOADED. That is the whole signal StoreGuard reads.
            Codec.INT.optionalFieldOf("version", 0).forGetter(d -> SCHEMA),
            // How many rows were saved, so a partial parse that silently drops some is caught.
            Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED)
                    .forGetter(d -> d.rows().size()),
            ROW_CODEC.listOf().fieldOf("encounters").forGetter(EncounterData::rows)
    ).apply(data, EncounterData::fromRows));

    public static final SavedDataType<EncounterData> TYPE =
            SavedDatas.type(ID, EncounterData::new, CODEC, DataFixTypes.LEVEL);

    private final Encounters roster;
    private final int loadedVersion;
    private final int declaredRows;

    /** Constructs an empty store (the {@link SavedDataType} supplier for a fresh save). */
    public EncounterData() {
        this(new Encounters(), StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED);
    }

    private EncounterData(Encounters roster, int loadedVersion, int declaredRows) {
        this.roster = roster;
        this.loadedVersion = loadedVersion;
        this.declaredRows = declaredRows;
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
        return roster.open().size() + roster.closed().size();
    }

    /** Resolves the single, server-global store (kept on the overworld's data storage). */
    public static EncounterData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public Encounters roster() {
        return roster;
    }

    /** Public {@code setDirty} passthrough — the engine's listener marks writes, not this class. */
    public void dirty() {
        setDirty();
    }

    /** @see Encounters#prune — drops closed encounters past the retention window. */
    public void prune(long now) {
        roster.prune(now, Config.get().i(Knob.SOCIAL_ENCOUNTER_RETENTION_TICKS));
    }

    /** Package-private so the round-trip test can reach it without a codec. */
    List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (Encounter e : roster.open()) {
            rows.add(toRow(e));
        }
        for (Encounter e : roster.closed()) {
            rows.add(toRow(e));
        }
        return rows;
    }

    private static Row toRow(Encounter e) {
        List<UUID> participants = new ArrayList<>();
        for (AgentId who : e.participants()) {
            participants.add(who.value());
        }
        List<Line> lines = new ArrayList<>();
        for (Utterance u : e.transcript()) {
            lines.add(new Line(Optional.ofNullable(u.author()).map(AgentId::value), u.act(),
                    u.payload(), u.tick()));
        }
        return new Row(e.id(), participants, e.openedAt(), e.closedAt(), lines);
    }

    /** Package-private so the round-trip test can reach it without a codec. */
    static EncounterData fromRows(int version, int rows, List<Row> raw) {
        Encounters roster = new Encounters();
        for (Row row : raw) {
            List<AgentId> participants = new ArrayList<>();
            for (UUID who : row.participants()) {
                participants.add(AgentId.of(who));
            }
            Encounter e = new Encounter(row.id(), participants, row.openedAt());
            for (Line line : row.lines()) {
                e.append(new Utterance(line.author().map(AgentId::of).orElse(null), line.act(),
                        line.payload(), line.tick()));
            }
            if (row.closedAt() >= 0) {
                e.close(row.closedAt());
            }
            // Never through a SpeechEngine: a reload is not a conversation, and restore seats the
            // record without firing a listener or paying a need gauge for lines already spoken.
            roster.restore(e);
        }
        return new EncounterData(roster, version, rows);
    }
}
