package dev.luizloyola.anima.mod.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PartyRoster;
import dev.luizloyola.anima.core.social.Places;
import dev.luizloyola.anima.mod.store.StoreGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * The world-scoped, persisted home of every party roster — the {@code ContactData} pattern applied
 * to membership ({@code <world>/data/anima_parties.dat}).
 *
 * <p>Everything layer 3 hangs off a party (boards, projects, later sites), so a membership that
 * evaporated on relog would orphan all of it. Persistent where {@code SiteClaims} is deliberately
 * transient (social foundations §6): a claim is a heartbeat, a party is a fact.
 *
 * <p>{@link PartyRoster} holds the logic; this owns persistence and the dirty flag. Note that
 * {@link #partyOf} is a mutation in disguise — the first ask about an agent mints their party of
 * one. That is when the file must learn it.
 */
public final class PartyData extends SavedData implements StoreGuard.Checked {
    /** This store's file key — public so the boot guard can find it on disk. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("anima", "parties");

    /** One party's row: {@code {party, members:[…]}} in join order. */
    private record Row(UUID party, List<UUID> members) {
    }

    private static final Codec<Row> ROW_CODEC = RecordCodecBuilder.create(row -> row.group(
            UUIDUtil.CODEC.fieldOf("party").forGetter(Row::party),
            UUIDUtil.CODEC.listOf().fieldOf("members").forGetter(Row::members)
    ).apply(row, Row::new));

    /** This store's schema. Bump when the shape below changes incompatibly. */
    private static final int SCHEMA = 1;

    private static final Codec<PartyData> CODEC = RecordCodecBuilder.create(data -> data.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(d -> SCHEMA),
            Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED)
                    .forGetter(d -> d.rows().size()),
            ROW_CODEC.listOf().fieldOf("parties").forGetter(PartyData::rows)
    ).apply(data, PartyData::fromRows));

    public static final SavedDataType<PartyData> TYPE =
            SavedDatas.type(ID, PartyData::new, CODEC, DataFixTypes.LEVEL);

    private final PartyRoster roster;
    private final int loadedVersion;
    private final int declaredRows;

    /** Installed at server start; null until then, since rows load before there is a world. */
    private @Nullable Places places;

    /** Constructs an empty store (the {@link SavedDataType} supplier for a fresh save). */
    public PartyData() {
        this(new PartyRoster(), StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED);
    }

    private PartyData(PartyRoster roster, int loadedVersion, int declaredRows) {
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
        return roster.parties().size();
    }

    /** Resolves the single, server-global store (kept on the overworld's data storage). */
    public static PartyData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** @see PartyRoster#partyOf — marks dirty when the ask itself minted the party of one. */
    public PartyId partyOf(AgentId member) {
        boolean fresh = roster.currentPartyOf(member).isEmpty();
        PartyId party = roster.partyOf(member);
        if (fresh) {
            setDirty();
        }
        return party;
    }

    /** @see PartyRoster#currentPartyOf — a pure read; it never mints, so it never dirties. */
    public Optional<PartyId> currentPartyOf(AgentId member) {
        return roster.currentPartyOf(member);
    }

    /** What follows a membership change. See the spec's transition table. */
    public void follows(Places places) {
        this.places = places;
    }

    /** @see PartyRoster#join — marks dirty only when membership genuinely moved. */
    public boolean join(AgentId who, PartyId into) {
        Optional<PartyId> before = roster.currentPartyOf(who);
        if (!dirtyIf(roster.join(who, into))) {
            return false;
        }
        if (places != null) {
            // A vacated party that still has members keeps its own workshop; only one that ceased
            // to exist hands its claims to wherever its last member went.
            before.filter(gone -> roster.members(gone).isEmpty())
                    .ifPresent(gone -> places.partyDisbanded(gone, into));
            places.ownerMovedTo(who, into);
        }
        return true;
    }

    /** @see PartyRoster#leave */
    public boolean leave(AgentId who) {
        if (!dirtyIf(roster.leave(who))) {
            return false;
        }
        if (places != null) {
            // The party still has members — `leave` refuses for a loner — so its communal claims
            // stay with it. Only what this agent OWNED stops being shared.
            places.ownerMovedTo(who, null);
        }
        return true;
    }

    /** @see PartyRoster#evict — for an agent who is gone, party of one included. */
    public boolean evict(AgentId who) {
        Optional<PartyId> before = roster.currentPartyOf(who);
        if (!dirtyIf(roster.evict(who))) {
            return false;
        }
        if (places != null) {
            places.ownerMovedTo(who, null);
            // Nothing owns a communal claim, so an emptied party takes its claims with it. The
            // blocks still stand and are re-perceived by whoever walks past.
            before.filter(gone -> roster.members(gone).isEmpty())
                    .ifPresent(gone -> places.partyDisbanded(gone, null));
        }
        return true;
    }

    /** @see PartyRoster#members */
    public List<AgentId> members(PartyId party) {
        return roster.members(party);
    }

    /** @see PartyRoster#parties */
    public Set<PartyId> parties() {
        return roster.parties();
    }

    private boolean dirtyIf(boolean changed) {
        if (changed) {
            setDirty();
        }
        return changed;
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (PartyId party : roster.parties()) {
            List<UUID> members = new ArrayList<>();
            for (AgentId member : roster.members(party)) {
                members.add(member.value());
            }
            rows.add(new Row(party.value(), members));
        }
        return rows;
    }

    private static PartyData fromRows(int version, int declaredRows, List<Row> rows) {
        PartyRoster roster = new PartyRoster();
        for (Row row : rows) {
            for (UUID member : row.members()) {
                roster.join(AgentId.of(member), PartyId.of(row.party()));
            }
        }
        return new PartyData(roster, version, declaredRows);
    }
}
