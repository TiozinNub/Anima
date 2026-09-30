package dev.luizloyola.anima.compat.sense;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * What each land creature can drop, read from its loot table: every item entry, through nested
 * tables, whatever its conditions. Walking the table's own codec output rather than its classes
 * keeps this off every private field, and a modded creature with a data-driven table counts too.
 *
 * <p>Only {@link MobCategory#CREATURE}: fish and squid are fishing's, not a hunt's. Memoized per
 * species against the reloadable registries instance, which a {@code /reload} replaces — the same
 * rule as {@code CookedForms}. Server-thread confined.
 */
public final class LootYields {

    /** Nested tables deeper than this are not followed; vanilla nests one level (sheep wool). */
    private static final int MAX_DEPTH = 4;

    private static ReloadableServerRegistries.Holder cachedAgainst;
    private static final Map<String, Set<String>> CACHE = new HashMap<>();

    private LootYields() {
    }

    public static Set<String> of(String species, MinecraftServer server) {
        ReloadableServerRegistries.Holder tables = server.reloadableRegistries();
        if (tables != cachedAgainst) {
            CACHE.clear();
            cachedAgainst = tables;
        }
        return CACHE.computeIfAbsent(species, key -> scan(key, tables, server));
    }

    /** Every land creature's species, named as {@code Being.species} names it. */
    public static Set<String> species() {
        Set<String> out = new LinkedHashSet<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() == MobCategory.CREATURE) {
                Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                out.add(key.getNamespace().equals("minecraft") ? key.getPath() : key.toString());
            }
        }
        return Set.copyOf(out);
    }

    private static Set<String> scan(String species, ReloadableServerRegistries.Holder tables,
                                    MinecraftServer server) {
        Identifier id = Identifier.tryParse(species.contains(":") ? species : "minecraft:" + species);
        if (id == null) {
            return Set.of();
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null || type.getCategory() != MobCategory.CREATURE) {
            return Set.of();
        }
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        Set<String> items = new LinkedHashSet<>();
        type.getDefaultLootTable().ifPresent(key -> collect(key, tables, ops, items, 0));
        return Set.copyOf(items);
    }

    private static void collect(ResourceKey<LootTable> key, ReloadableServerRegistries.Holder tables,
                                RegistryOps<JsonElement> ops, Set<String> items, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        LootTable.DIRECT_CODEC.encodeStart(ops, tables.getLootTable(key)).result()
                .ifPresent(json -> walk(json, tables, ops, items, depth));
    }

    private static void walk(JsonElement json, ReloadableServerRegistries.Holder tables,
                             RegistryOps<JsonElement> ops, Set<String> items, int depth) {
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(child -> walk(child, tables, ops, items, depth));
            return;
        }
        if (!json.isJsonObject()) {
            return;
        }
        JsonObject object = json.getAsJsonObject();
        String type = object.has("type") && object.get("type").isJsonPrimitive()
                ? object.get("type").getAsString() : "";
        if (type.equals("minecraft:item") && object.has("name")) {
            items.add(object.get("name").getAsString());
        } else if (type.equals("minecraft:loot_table") && object.has("value")) {
            JsonElement value = object.get("value");
            if (value.isJsonPrimitive()) {
                Identifier nested = Identifier.tryParse(value.getAsString());
                if (nested != null) {
                    collect(ResourceKey.create(Registries.LOOT_TABLE, nested), tables, ops, items,
                            depth + 1);
                }
                return;
            }
        }
        object.entrySet().forEach(entry -> walk(entry.getValue(), tables, ops, items, depth));
    }
}
