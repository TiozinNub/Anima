package dev.luizloyola.anima.compat;

import com.mojang.serialization.Codec;
import dev.luizloyola.anima.mixin.SavedDataStorageAccessor;
import java.nio.file.Path;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Builds a {@link SavedDataType} across the version divide: 26.1 keys a type by a namespaced
 * {@link Identifier}, older targets by a plain {@code String}. Callers pass the {@code Identifier};
 * where a String is required this derives a filesystem-safe {@code namespace_path} key.
 */
public final class SavedDatas {
    private SavedDatas() {}

    public static <T extends SavedData> SavedDataType<T> type(
            Identifier id, Supplier<T> factory, Codec<T> codec, DataFixTypes fixType) {
        //? if >=26.1 {
        return new SavedDataType<>(id, factory, codec, fixType);
        //?} else {
        /*return new SavedDataType<>(id.getNamespace() + "_" + id.getPath(), factory, codec, fixType);
        *///?}
    }

    /**
     * Where a level keeps one store's file — the path vanilla resolves, derived the same way.
     * Needed because the loader answers "no file" and "unparseable" with the same {@code null};
     * only the filesystem separates them. Folder from the storage's private field via
     * {@link SavedDataStorageAccessor}; leaf mirrors {@link #type} — namespaced subfolder at 26.1,
     * {@code namespace_path} before it.
     */
    public static Path fileOf(ServerLevel level, Identifier id) {
        Path folder = ((SavedDataStorageAccessor) level.getDataStorage()).anima$dataFolder();
        //? if >=26.1 {
        return folder.resolve(id.getNamespace()).resolve(id.getPath() + ".dat");
        //?} else {
        /*return folder.resolve(id.getNamespace() + "_" + id.getPath() + ".dat");
        *///?}
    }
}
