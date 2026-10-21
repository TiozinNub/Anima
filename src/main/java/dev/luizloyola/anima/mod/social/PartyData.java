package dev.luizloyola.anima.mod.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.PartyRoster;
import dev.luizloyola.anima.core.social.PlaceRow;
import dev.luizloyola.anima.core.social.Places;
import dev.luizloyola.anima.mod.store.StoreGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
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

    /** One party's row: {@code {party, members:[…], leader?}}, members in join order. */
    private record Row(UUID party, List<UUID> members, Optional<UUID> leader) {
    }

    private static final Codec<Row> ROW_CODEC = RecordCodecBuilder.create(row -> row.group(
            UUIDUtil.CODEC.fieldOf("party").forGetter(Row::party),
            UUIDUtil.CODEC.listOf().fieldOf("members").forGetter(Row::members),
            UUIDUtil.CODEC.optionalFieldOf("leader").forGetter(Row::leader)
    ).apply(row, Row::new));

    /** This store's schema. Bump when the shape below changes incompatibly. */
    private static final int SCHEMA = 1;

    /** Package-visible for the round-trip tests. */
    static final Codec<PartyData> CODEC = RecordCodecBuilder.create(data -> data.group(
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
    private final List<Consumer<PartyId>> disbanded = new ArrayList<>();
    private final List<BiConsumer<PartyId, List<AgentId>>> arrived = new ArrayList<>();
    private final List<Consumer<Merged>> merged = new ArrayList<>();

    /** A whole party moved into another: who came, and the places that came with them. */
    public record Merged(PartyId from, PartyId into, List<AgentId> members, List<PlaceRow> places) {
    }
    private Predicate<AgentId> players = who -> false;

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

    /** Which agents are players, for the default lead. Installed at server start. */
    public void knowsPlayers(Predicate<AgentId> players) {
        this.players = players;
    }

    /** Hears every party that ceased to exist, after its claims have moved. */
    public void onDisband(Consumer<PartyId> listener) {
        disbanded.add(listener);
    }

    /** Hears every merge, after its arrival: what a consumer does with places brought from far off. */
    public void onMerge(Consumer<Merged> listener) {
        merged.add(listener);
    }

    /** Hears every arrival into company: the party joined, and who came, after their claims moved. */
    public void onArrival(BiConsumer<PartyId, List<AgentId>> listener) {
        arrived.add(listener);
    }

    private void arrive(PartyId into, List<AgentId> who) {
        for (BiConsumer<PartyId, List<AgentId>> listener : arrived) {
            listener.accept(into, who);
        }
    }

    private void disband(PartyId gone) {
        for (Consumer<PartyId> listener : disbanded) {
            listener.accept(gone);
        }
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
        before.filter(gone -> roster.members(gone).isEmpty()).ifPresent(this::disband);
        playerLeads(into);
        if (roster.members(into).size() > 1) {
            arrive(into, List.of(who));
        }
        return true;
    }

    /**
     * @see PartyRoster#merge — {@code from}'s communal claims go to {@code into}, each member's own
     * claims follow them, and {@code from} disbands once, however many members it had.
     */
    public boolean merge(PartyId from, PartyId into) {
        List<AgentId> moving = List.copyOf(roster.members(from));
        List<PlaceRow> brought = places == null ? List.of()
                : places.rows().stream().filter(row -> from.equals(row.party())).toList();
        if (!dirtyIf(roster.merge(from, into))) {
            return false;
        }
        if (places != null) {
            places.partyDisbanded(from, into);
            moving.forEach(member -> places.ownerMovedTo(member, into));
        }
        disband(from);
        playerLeads(into);
        arrive(into, moving);
        Merged event = new Merged(from, into, moving, brought);
        merged.forEach(listener -> listener.accept(event));
        return true;
    }

    /** @see PartyRoster#leader */
    public Optional<AgentId> leader(PartyId party) {
        return roster.leader(party);
    }

    /** @see PartyRoster#lead */
    public boolean lead(AgentId who) {
        return dirtyIf(roster.lead(who));
    }

    /**
     * A player in company leads it unless someone already does (decision: Luiz, 2026-10-21). Only
     * on arrival: when a lead passes on it goes by seniority, players or not.
     */
    private void playerLeads(PartyId party) {
        List<AgentId> members = roster.members(party);
        if (members.size() < 2 || roster.leader(party).isPresent()) {
            return;
        }
        members.stream().filter(players).findFirst().ifPresent(this::lead);
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
        before.filter(gone -> roster.members(gone).isEmpty()).ifPresent(this::disband);
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
            rows.add(new Row(party.value(), members, roster.leader(party).map(AgentId::value)));
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
        // After every membership, so a leader filed under the wrong party is refused whatever the
        // row order, and is not made leader of the party they really are in.
        for (Row row : rows) {
            PartyId party = PartyId.of(row.party());
            row.leader().map(AgentId::of)
                    .filter(leader -> roster.currentPartyOf(leader).filter(party::equals).isPresent())
                    .ifPresent(roster::lead);
        }
        return new PartyData(roster, version, declaredRows);
    }
}
