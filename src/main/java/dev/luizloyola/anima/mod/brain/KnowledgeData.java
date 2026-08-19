package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.mod.AnimaMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.compat.SavedDatas;
import dev.luizloyola.anima.core.brain.knowledge.KnowledgeRegistry;
import dev.luizloyola.anima.mod.store.StoreGuard;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.knowledge.Sighting;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The world-scoped, persisted home of every person's knowledge: {@code AgentId}-keyed, attached to
 * the overworld's data storage ({@code <world>/data/anima_knowledge.dat}), surviving chunk
 * unloads, death (the inventory drops, the knowledge doesn't) and restarts.
 *
 * <p>Serialization is codec-based and lives here so {@code core} stays free of DataFixerUpper.
 * Loading replays {@code note()} into a fresh {@link KnowledgeRegistry} — entries were stored
 * post-merge and insertion-ordered, so the replay reproduces the store exactly. Only the durable
 * tier is saved; claim indexes and pending queues rebuild from re-walking.
 */
public final class KnowledgeData extends SavedData implements StoreGuard.Checked {
    /** This store's file key — public so the boot guard can find it on disk. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "knowledge");

    private static final Codec<Pos> POS_CODEC = BlockPos.CODEC.xmap(
            bp -> new Pos(bp.getX(), bp.getY(), bp.getZ()),
            p -> new BlockPos(p.x(), p.y(), p.z()));

    /**
     * Kinds round-trip by their stable key. An unknown one means its mod is gone: the entry errors
     * rather than guessing a merge radius, and the loader below drops it with a warning instead of
     * failing the whole file.
     */
    private static final Codec<PoiKind> KIND_CODEC = Codec.STRING.comapFlatMap(
            key -> PoiKind.byKey(key)
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "no POI kind is registered as \"" + key
                            + "\" — was a mod removed?")),
            PoiKind::key);

    private static final Codec<PoiMemory> MEMORY_CODEC = RecordCodecBuilder.create(m -> m.group(
            KIND_CODEC.fieldOf("kind").forGetter(PoiMemory::kind),
            // Absent in pre-herd saves — the defaults keep them loading unchanged.
            Codec.STRING.optionalFieldOf("detail", "").forGetter(PoiMemory::detail),
            UUIDUtil.CODEC.optionalFieldOf("individual")
                    .forGetter(memory -> java.util.Optional.ofNullable(memory.individual())),
            POS_CODEC.fieldOf("anchor").forGetter(PoiMemory::anchor),
            POS_CODEC.fieldOf("min").forGetter(memory -> memory.bounds().min()),
            POS_CODEC.fieldOf("max").forGetter(memory -> memory.bounds().max()),
            Codec.INT.fieldOf("units").forGetter(PoiMemory::units),
            Codec.BOOL.optionalFieldOf("partial", false).forGetter(PoiMemory::partial),
            Codec.LONG.fieldOf("seen").forGetter(PoiMemory::lastSeenTick)
    ).apply(m, (kind, detail, individual, anchor, min, max, units, partial, seen) ->
            new PoiMemory(kind, detail, individual.orElse(null), anchor, new Region(min, max),
                    units, partial, seen)));

    /**
     * How a sighting came to be believed. Lenient on the way in — an unrecognised label costs less
     * than dropping the sighting, unlike an unknown {@link PoiKind}, which rightly errors.
     */
    private static final Codec<Sighting.Provenance> PROVENANCE_CODEC = Codec.STRING.xmap(
            name -> Sighting.Provenance.byName(name).orElse(Sighting.Provenance.PASSIVE),
            Sighting.Provenance::name);

    private static final Codec<Sighting> SIGHTING_CODEC = RecordCodecBuilder.create(s -> s.group(
            KIND_CODEC.fieldOf("kind").forGetter(Sighting::kind),
            POS_CODEC.fieldOf("at").forGetter(Sighting::at),
            POS_CODEC.fieldOf("from").forGetter(Sighting::seenFrom),
            Codec.LONG.fieldOf("when").forGetter(Sighting::whenTick),
            PROVENANCE_CODEC.optionalFieldOf("via", Sighting.Provenance.PASSIVE)
                    .forGetter(Sighting::provenance)
    ).apply(s, Sighting::new));

    /** A core stack, whole — the shape {@code AnimaTasks.CORE_STACK} also writes. */
    private static final Codec<ItemStack> STACK_CODEC = RecordCodecBuilder.create(s -> s.group(
            Codec.STRING.fieldOf("id").forGetter(ItemStack::id),
            Codec.INT.fieldOf("count").forGetter(ItemStack::count),
            Codec.INT.fieldOf("max").forGetter(ItemStack::maxStackSize),
            Codec.STRING.optionalFieldOf("components", "").forGetter(ItemStack::components)
    ).apply(s, ItemStack::new));

    /** One row of {@link AgentKnowledge#insides()} — what was seen at an anchor, and when. */
    private record InsideRow(Pos at, List<ItemStack> stacks, long seen) {
    }

    private static final Codec<InsideRow> INSIDE_ROW_CODEC = RecordCodecBuilder.create(r -> r.group(
            POS_CODEC.fieldOf("at").forGetter(InsideRow::at),
            STACK_CODEC.listOf().fieldOf("stacks").forGetter(InsideRow::stacks),
            Codec.LONG.fieldOf("seen").forGetter(InsideRow::seen)
    ).apply(r, InsideRow::new));

    /**
     * One person's knowledge, flattened across kinds ({@code kind} rides in each entry). The three
     * tiers are stored apart because they ARE apart — see {@link Sighting} and
     * {@link AgentKnowledge.Seen}.
     */
    private record PersonEntry(AgentId id, List<PoiMemory> pois, List<Sighting> sightings,
            List<InsideRow> insides) {
    }

    private static final Codec<PersonEntry> ENTRY_CODEC = RecordCodecBuilder.create(e -> e.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(entry -> entry.id().value()),
            MEMORY_CODEC.listOf().fieldOf("pois").forGetter(PersonEntry::pois),
            // Absent in every save written before the far sense existed.
            SIGHTING_CODEC.listOf().optionalFieldOf("sightings", List.of())
                    .forGetter(PersonEntry::sightings),
            // Absent in every save written before a body could look inside a container — a store
            // nobody has opened has no rows, which is exactly what an old file also has none of.
            INSIDE_ROW_CODEC.listOf().optionalFieldOf("insides", List.of())
                    .forGetter(PersonEntry::insides)
    ).apply(e, (uuid, pois, sightings, insides) ->
            new PersonEntry(AgentId.of(uuid), pois, sightings, insides)));

    /** This store's schema. Bump when the shape above changes incompatibly. */
    private static final int SCHEMA = 1;

    /**
     * Package-private, not private — same reason as {@link #entries()}: a same-package test round-
     * trips through this directly rather than standing up a whole server.
     */
    static final Codec<KnowledgeData> CODEC = RecordCodecBuilder.create(d -> d.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(d2 -> SCHEMA),
            Codec.INT.optionalFieldOf("rows", StoreGuard.UNCOUNTED)
                    .forGetter(d2 -> d2.entries().size()),
            ENTRY_CODEC.listOf().fieldOf("persons").forGetter(KnowledgeData::entries)
    ).apply(d, KnowledgeData::fromEntries));

    public static final SavedDataType<KnowledgeData> TYPE =
            SavedDatas.type(ID, KnowledgeData::new, CODEC, DataFixTypes.LEVEL);

    private final KnowledgeRegistry registry;
    private final int loadedVersion;
    private final int declaredRows;

    /** An empty store (the {@link SavedDataType} supplier for a fresh save). */
    public KnowledgeData() {
        this(new KnowledgeRegistry(), StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED);
    }

    private KnowledgeData(KnowledgeRegistry registry, int loadedVersion, int declaredRows) {
        this.registry = registry;
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
        return entries().size();
    }

    /** The server's knowledge store, loading or creating the overworld-attached instance. */
    public static KnowledgeData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** The pure registry this SavedData persists — the object the sensors and commands share. */
    public KnowledgeRegistry registry() {
        return registry;
    }

    /**
     * Drops one agent's whole map of the world, marking dirty if there was anything to drop — the
     * wipe a burial performs, reached through {@code AgentRecords}. Safe because every
     * {@code forPerson} call passes the asking agent's own id: nobody else reads this.
     */
    public boolean forget(AgentId who) {
        boolean had = registry.remove(who);
        if (had) {
            setDirty();
        }
        return had;
    }

    /**
     * Package-private, not private, so a same-package test can assert against it directly rather
     * than eyeballing the codec's output.
     *
     * <p>Reads {@link AgentKnowledge#sighted} — the uncomposed tier — not {@code all}: a claim is
     * {@code Places}'s row, and writing it here too would double-persist it AND leak it as a
     * private sighting that outlives the party membership that made it visible in the first place.
     */
    List<PersonEntry> entries() {
        List<PersonEntry> entries = new ArrayList<>();
        for (AgentId id : registry.persons()) {
            AgentKnowledge knowledge = registry.forPerson(id);
            List<PoiMemory> pois = new ArrayList<>();
            List<Sighting> sightings = new ArrayList<>();
            for (PoiKind kind : PoiKind.all()) {
                pois.addAll(knowledge.sighted(kind));
                sightings.addAll(knowledge.glimpses(kind));
            }
            List<InsideRow> insides = new ArrayList<>();
            for (Map.Entry<Pos, AgentKnowledge.Seen> row : knowledge.insides().entrySet()) {
                AgentKnowledge.Seen seen = row.getValue();
                insides.add(new InsideRow(row.getKey(), seen.stacks(), seen.seenTick()));
            }
            if (!pois.isEmpty() || !sightings.isEmpty() || !insides.isEmpty()) {
                entries.add(new PersonEntry(id, pois, sightings, insides));
            }
        }
        return entries;
    }

    private static KnowledgeData fromEntries(int version, int declaredRows,
                                             List<PersonEntry> entries) {
        KnowledgeRegistry registry = new KnowledgeRegistry();
        for (PersonEntry entry : entries) {
            AgentKnowledge knowledge = registry.forPerson(entry.id());
            for (PoiMemory memory : entry.pois()) {
                knowledge.restore(memory);
            }
            for (Sighting sighting : entry.sightings()) {
                knowledge.restoreGlimpse(sighting);
            }
            for (InsideRow row : entry.insides()) {
                knowledge.restoreInside(row.at(), row.stacks(), row.seen());
            }
        }
        return new KnowledgeData(registry, version, declaredRows);
    }
}
