package dev.luizloyola.anima.mod.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.need.Company;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.social.ContactBook;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.store.StoreGuard;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The world-scoped, persisted home of every contact book — the {@code PersonDirectory} pattern
 * applied to acquaintance ({@code <world>/data/anima_contacts.dat}).
 *
 * <p>It persists for PLAYERS too: a name you were told is not something a relog may take back.
 * Their books live here rather than in player data because a player's {@link AgentId} is minted
 * from their account UUID, so gossip about a logged-out player needs no special case.
 *
 * <p>{@link ContactBook} holds the logic; this owns persistence and the dirty flag, so every
 * mutation goes through here rather than handing the book out.
 */
public final class ContactData extends SavedData implements StoreGuard.Checked {
    /** This store's file key — public so the boot guard can find it on disk. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("anima", "contacts");

    /** One knower's row: {@code {who, knows:[…], heard:[…]}}; {@code heard} is names without faces. */
    private record Row(UUID who, List<UUID> knows, List<UUID> heard) {
    }

    private static final Codec<Row> ROW_CODEC = RecordCodecBuilder.create(row -> row.group(
            UUIDUtil.CODEC.fieldOf("who").forGetter(Row::who),
            UUIDUtil.CODEC.listOf().fieldOf("knows").forGetter(Row::knows),
            UUIDUtil.CODEC.listOf().optionalFieldOf("heard", List.of()).forGetter(Row::heard)
    ).apply(row, Row::new));

    /** This store's schema. Bump when the shape below changes incompatibly. */
    private static final int SCHEMA = 1;

    /** Package-visible for the round-trip tests. */
    static final Codec<ContactData> CODEC = RecordCodecBuilder.create(data -> data.group(
            // Written always, read as 0 from a file that predates it — either way not the factory's
            // NEVER_LOADED. That is the whole signal StoreGuard reads.
            Codec.INT.optionalFieldOf("version", 0).forGetter(d -> SCHEMA),
            // How many rows were saved, so a partial parse that silently drops some is caught.
            Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED)
                    .forGetter(d -> d.rows().size()),
            ROW_CODEC.listOf().fieldOf("books").forGetter(ContactData::rows)
    ).apply(data, ContactData::fromRows));

    public static final SavedDataType<ContactData> TYPE =
            SavedDatas.type(ID, ContactData::new, CODEC, DataFixTypes.LEVEL);

    private final ContactBook book;
    private final int loadedVersion;
    private final int declaredRows;

    /** Constructs an empty store (the {@link SavedDataType} supplier for a fresh save). */
    public ContactData() {
        this(new ContactBook(), StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED);
    }

    private ContactData(ContactBook book, int loadedVersion, int declaredRows) {
        this.book = book;
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
        return rows().size(); // a knower who has only heard of people is a row too
    }

    /** Resolves the single, server-global store (kept on the overworld's data storage). */
    public static ContactData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** @see ContactBook#knows */
    public boolean knows(AgentId knower, AgentId whom) {
        return book.knows(knower, whom);
    }

    /** @see ContactBook#contactsOf */
    public Set<AgentId> contactsOf(AgentId knower) {
        return book.contactsOf(knower);
    }

    /** @see ContactBook#heardOf */
    public boolean heardOf(AgentId knower, AgentId whom) {
        return book.heardOf(knower, whom);
    }

    /** @see ContactBook#heardBy */
    public Set<AgentId> heardBy(AgentId knower) {
        return book.heardBy(knower);
    }

    /** @see ContactBook#hearers */
    public Set<AgentId> hearers() {
        return book.hearers();
    }

    /** @see ContactBook#hear — a name is no meeting, so no gauge moves. */
    public boolean hear(AgentId knower, AgentId whom) {
        return dirtyIf(book.hear(knower, whom));
    }

    /**
     * As {@link #learn}, but no company gauge moves: for a learner the caller counts once however many
     * it learns at a time — joining a party is one meeting (group-up spec, decision 19).
     */
    public boolean learnQuietly(AgentId knower, AgentId whom) {
        return dirtyIf(book.learn(knower, whom));
    }

    /** One meeting's worth of company for {@code who}, if their body is loaded. */
    public static void met(MinecraftServer server, AgentId who) {
        filled(server, who);
    }

    /** @see ContactBook#knowers */
    public Set<AgentId> knowers() {
        return book.knowers();
    }

    /** @see ContactBook#learn — marks dirty only when something was genuinely new. */
    public boolean learn(MinecraftServer server, AgentId knower, AgentId whom) {
        boolean fresh = book.learn(knower, whom);
        if (fresh) {
            filled(server, knower);
        }
        return dirtyIf(fresh);
    }

    /**
     * @see ContactBook#introduce — decided PER SIDE, not on the combined result: the two rows can
     *     desync (a one-sided {@link #forget} followed by a re-introduction relearns only one of
     *     them), and a side that learned nothing must not have its company gauge filled for an
     *     acquaintance it never made.
     */
    public boolean introduce(MinecraftServer server, AgentId one, AgentId other) {
        List<AgentId> fresh = freshSides(book, one, other);
        for (AgentId id : fresh) {
            filled(server, id);
        }
        return dirtyIf(!fresh.isEmpty());
    }

    /**
     * Which side(s) of a mutual introduction genuinely gained a row — {@code one}, {@code other},
     * both, or neither, decided by two independent {@link ContactBook#learn} calls rather than
     * {@link ContactBook#introduce}'s single combined OR, which cannot tell a genuine acquaintance
     * from a side that already knew.
     *
     * <p>Split out from {@link #introduce} so the decision is checkable without a
     * {@link MinecraftServer}: nothing below this line touches Minecraft at all.
     */
    static List<AgentId> freshSides(ContactBook book, AgentId one, AgentId other) {
        List<AgentId> fresh = new ArrayList<>(2);
        if (book.learn(one, other)) {
            fresh.add(one);
        }
        if (book.learn(other, one)) {
            fresh.add(other);
        }
        return fresh;
    }

    /** @see ContactBook#forget */
    public boolean forget(AgentId knower, AgentId whom) {
        return dirtyIf(book.forget(knower, whom));
    }

    /** @see ContactBook#erase — both directions; a burial must not call this. */
    public boolean erase(AgentId who) {
        return dirtyIf(book.erase(who));
    }

    /** @see ContactBook#clear */
    public boolean clear(AgentId knower) {
        return dirtyIf(book.clear(knower));
    }

    private boolean dirtyIf(boolean changed) {
        if (changed) {
            setDirty();
        }
        return changed;
    }

    /**
     * Meeting somebody new is worth something on its own. Resolves the loaded body for {@code id}
     * and lands the boost on its company gauge; a body that is not currently loaded simply misses
     * it, the same rule every other percept follows.
     */
    private static void filled(MinecraftServer server, AgentId id) {
        AgentBody body = AgentBodies.findLoaded(server, id);
        if (body != null) {
            body.needs().gauge(NeedKind.COMPANY, Company.class).ifPresent(Company::met);
        }
    }

    private List<Row> rows() {
        Set<AgentId> knowers = new LinkedHashSet<>(book.knowers());
        knowers.addAll(book.hearers());
        List<Row> rows = new ArrayList<>();
        for (AgentId knower : knowers) {
            rows.add(new Row(knower.value(), uuids(book.contactsOf(knower)), uuids(book.heardBy(knower))));
        }
        return rows;
    }

    private static List<UUID> uuids(Set<AgentId> ids) {
        List<UUID> out = new ArrayList<>(ids.size());
        for (AgentId id : ids) {
            out.add(id.value());
        }
        return out;
    }

    private static ContactData fromRows(int version, int declaredRows, List<Row> rows) {
        ContactBook book = new ContactBook();
        for (Row row : rows) {
            for (UUID known : row.knows()) {
                // Straight to the book, never through this.learn: the wrapper fills the company
                // gauge, and a reload is not a room full of introductions.
                book.learn(AgentId.of(row.who()), AgentId.of(known));
            }
            for (UUID heard : row.heard()) {
                book.hear(AgentId.of(row.who()), AgentId.of(heard));
            }
        }
        return new ContactData(book, version, declaredRows);
    }
}
