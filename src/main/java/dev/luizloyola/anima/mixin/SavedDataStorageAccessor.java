package dev.luizloyola.anima.mixin;

import java.nio.file.Path;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches {@code SavedDataStorage}'s own data folder, which it keeps private.
 *
 * <p>Vanilla cannot tell us whether a store's file was there, and that one bit separates "a fresh
 * world" from "your world's memory failed to parse and has been replaced with an empty one":
 * {@code readSavedData} returns {@code null} for both, with only an ERROR line between them. The
 * filesystem is the only way to separate them, and this field is the folder vanilla asked in.
 *
 * <p>An {@code @Accessor} and nothing else — no injection, no overwrite, no behaviour changed —
 * deliberate for Connector.
 */
@Mixin(SavedDataStorage.class)
public interface SavedDataStorageAccessor {

    /** The folder this storage resolves {@code <namespace>/<path>.dat} against. */
    @Accessor("dataFolder")
    Path anima$dataFolder();
}
