package dev.luizloyola.anima.mod.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.ContactBook;
import dev.luizloyola.anima.mod.store.StoreGuard;
import java.util.ArrayList;
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

    /** One knower's row: {@code {who, knows:[…]}}. */
    private record Row(UUID who, List<UUID> knows) {
    }

    private static final Codec<Row> ROW_CODEC = RecordCodecBuilder.create(row -> row.group(
            UUIDUtil.CODEC.fieldOf("who").forGetter(Row::who),
            UUIDUtil.CODEC.listOf().fieldOf("knows").forGetter(Row::knows)
    ).apply(row, Row::new));

    /** This store's schema. Bump when the shape below changes incompatibly. */
    private static final int SCHEMA = 1;

    private static final Codec<ContactData> CODEC = RecordCodecBuilder.create(data -> data.group(
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
        return book.knowers().size();
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

    /** @see ContactBook#knowers */
    public Set<AgentId> knowers() {
        return book.knowers();
    }

    /** @see ContactBook#learn — marks dirty only when something was genuinely new. */
    public boolean learn(AgentId knower, AgentId whom) {
        return dirtyIf(book.learn(knower, whom));
    }

    /** @see ContactBook#introduce */
    public boolean introduce(AgentId one, AgentId other) {
        return dirtyIf(book.introduce(one, other));
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

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (AgentId knower : book.knowers()) {
            List<UUID> knows = new ArrayList<>();
            for (AgentId known : book.contactsOf(knower)) {
                knows.add(known.value());
            }
            rows.add(new Row(knower.value(), knows));
        }
        return rows;
    }

    private static ContactData fromRows(int version, int declaredRows, List<Row> rows) {
        ContactBook book = new ContactBook();
        for (Row row : rows) {
            for (UUID known : row.knows()) {
                book.learn(AgentId.of(row.who()), AgentId.of(known));
            }
        }
        return new ContactData(book, version, declaredRows);
    }
}
