package dev.luizloyola.anima.mod.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.agent.need.NeedLevel;
import dev.luizloyola.anima.core.social.Ledger;
import dev.luizloyola.anima.core.social.Ledgers;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.store.StoreGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The marks every party's {@link Ledgers} weigh, persisted ({@code anima:ledgers}), and the lines
 * Anima itself contributes: newcomers and a recent merge fade, size and members' company stand.
 * A consumer adds its own lines through {@link #ledgers()} once the server has started.
 */
public final class LedgerData extends SavedData implements StoreGuard.Checked {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("anima", "ledgers");

    public static final String NEWCOMER = "anima.ledger.newcomer";
    public static final String MERGED = "anima.ledger.merged";
    public static final String SIZE = "anima.ledger.size";
    public static final String COMPANY = "anima.ledger.company";

    private record MarkRow(String ledger, String key, long since) {
    }

    private record Row(UUID party, List<MarkRow> marks) {
    }

    private static final Codec<MarkRow> MARK_CODEC = RecordCodecBuilder.create(m -> m.group(
            Codec.STRING.fieldOf("ledger").forGetter(MarkRow::ledger),
            Codec.STRING.fieldOf("key").forGetter(MarkRow::key),
            Codec.LONG.fieldOf("since").forGetter(MarkRow::since)
    ).apply(m, MarkRow::new));

    private static final Codec<Row> ROW_CODEC = RecordCodecBuilder.create(row -> row.group(
            UUIDUtil.CODEC.fieldOf("party").forGetter(Row::party),
            MARK_CODEC.listOf().fieldOf("marks").forGetter(Row::marks)
    ).apply(row, Row::new));

    private static final int SCHEMA = 1;

    /** Package-visible for the round-trip tests. */
    static final Codec<LedgerData> CODEC = RecordCodecBuilder.create(data -> data.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(d -> SCHEMA),
            Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED).forGetter(d -> d.rows().size()),
            ROW_CODEC.listOf().fieldOf("parties").forGetter(LedgerData::rows)
    ).apply(data, LedgerData::fromRows));

    public static final SavedDataType<LedgerData> TYPE =
            SavedDatas.type(ID, LedgerData::new, CODEC, DataFixTypes.LEVEL);

    private final Ledgers ledgers = new Ledgers();
    private final int loadedVersion;
    private final int declaredRows;
    private boolean installed;

    public LedgerData() {
        this(StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED);
    }

    private LedgerData(int loadedVersion, int declaredRows) {
        this.loadedVersion = loadedVersion;
        this.declaredRows = declaredRows;
        ledgers.fades(new Ledgers.Fading(Ledger.OPENNESS, NEWCOMER,
                ProfileAspect.SOCIAL_OPENNESS_NEWCOMER, ProfileAspect.SOCIAL_OPENNESS_NEWCOMER_DAYS));
        ledgers.fades(new Ledgers.Fading(Ledger.JOINING, MERGED,
                ProfileAspect.SOCIAL_JOIN_MERGED, ProfileAspect.SOCIAL_JOIN_MERGED_DAYS));
    }

    public static LedgerData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
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
        return ledgers.all().size();
    }

    /** The ledgers, to read or to add a consumer's lines to. Marks go through {@link #mark}. */
    public Ledgers ledgers() {
        return ledgers;
    }

    public void mark(PartyId party, Ledger ledger, String key, long now) {
        ledgers.mark(party, ledger, key, now);
        setDirty();
    }

    public void forget(PartyId party) {
        if (ledgers.forget(party)) {
            setDirty();
        }
    }

    /**
     * Wires the store to the roster: arrivals leave their marks, a party that ends takes its marks
     * with it, and the standing lines read the live world. Once per server start.
     */
    public void install(MinecraftServer server) {
        if (installed) {
            return;
        }
        installed = true;
        PartyData parties = PartyData.get(server);
        parties.onArrival((into, who) -> arrived(into, who.size(), now(server)));
        parties.onDisband(this::forget);
        ledgers.stands(standing(Ledger.OPENNESS, SIZE, (party, reader) -> {
            int size = parties.members(party).size();
            return size > 1 ? OptionalDouble.of((size - 1) * reader.d(ProfileAspect.SOCIAL_OPENNESS_MEMBER))
                    : OptionalDouble.empty();
        }));
        ledgers.stands(standing(Ledger.OPENNESS, COMPANY, (party, reader) ->
                scaled(companyOf(server, parties.members(party)), reader.d(ProfileAspect.SOCIAL_OPENNESS_COMPANY))));
        ledgers.stands(standing(Ledger.JOINING, COMPANY, (party, reader) ->
                scaled(companyOf(server, parties.members(party)), reader.d(ProfileAspect.SOCIAL_JOIN_COMPANY))));
    }

    /** The clock every mark is stamped and read against. */
    public static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    /** Each newcomer counts against taking in more; the party has merged once more, not once per newcomer. */
    void arrived(PartyId into, int newcomers, long now) {
        for (int i = 0; i < newcomers; i++) {
            ledgers.mark(into, Ledger.OPENNESS, NEWCOMER, now);
        }
        ledgers.refresh(into, Ledger.JOINING, MERGED, now);
        setDirty();
    }

    private static OptionalDouble scaled(OptionalDouble score, double weight) {
        return score.isPresent() ? OptionalDouble.of(score.getAsDouble() * weight) : OptionalDouble.empty();
    }

    /** The company levels of the members whose bodies are loaded; a player has no gauge to ask. */
    private static OptionalDouble companyOf(MinecraftServer server, List<AgentId> members) {
        List<String> levels = new ArrayList<>();
        for (AgentId member : members) {
            AgentBody body = AgentBodies.findLoaded(server, member);
            if (body != null) {
                body.needs().level(NeedKind.COMPANY).map(NeedLevel::key).ifPresent(levels::add);
            }
        }
        return Ledgers.companyScore(levels);
    }

    private interface Points {
        OptionalDouble of(PartyId party, AgentProfile reader);
    }

    private static Ledgers.Standing standing(Ledger ledger, String key, Points points) {
        return new Ledgers.Standing() {
            @Override
            public Ledger ledger() {
                return ledger;
            }

            @Override
            public String key() {
                return key;
            }

            @Override
            public OptionalDouble points(PartyId party, AgentProfile reader) {
                return points.of(party, reader);
            }
        };
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        ledgers.all().forEach((party, marks) -> {
            List<MarkRow> saved = new ArrayList<>();
            for (Ledgers.Mark mark : marks) {
                saved.add(new MarkRow(mark.ledger().key(), mark.key(), mark.since()));
            }
            rows.add(new Row(party.value(), saved));
        });
        return rows;
    }

    private static LedgerData fromRows(int version, int declaredRows, List<Row> rows) {
        LedgerData data = new LedgerData(version, declaredRows);
        for (Row row : rows) {
            for (MarkRow mark : row.marks()) {
                data.ledgers.restore(PartyId.of(row.party()),
                        new Ledgers.Mark(Ledger.of(mark.ledger()), mark.key(), mark.since()));
            }
        }
        return data;
    }
}
