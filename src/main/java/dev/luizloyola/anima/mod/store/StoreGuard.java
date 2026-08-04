package dev.luizloyola.anima.mod.store;

import dev.luizloyola.anima.compat.SavedDatas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks at boot that every persistent store actually loaded, and refuses to run a world that has
 * silently forgotten itself.
 *
 * <h2>What goes wrong without this</h2>
 *
 * <p>Vanilla's loader ({@code SavedDataStorage.readSavedData}, verified by disassembly on 26.1.2)
 * catches every exception, logs one ERROR line and returns {@code null}; {@code computeIfAbsent}
 * then hands back a <b>fresh, empty store</b>, which the next autosave writes over the file that
 * failed to parse. A codec change that cannot read an old world does not crash: it boots, leaves
 * every agent with no memory of anywhere or anyone, and ninety seconds later destroys the
 * evidence.
 *
 * <h2>Two failures, because there are two</h2>
 *
 * <p><b>Total.</b> The file was there and nothing came back. Detected by a schema {@code version}
 * only a real parse can set: a factory-built store reports {@link #NEVER_LOADED}, and a file that
 * existed beside one is a swallowed failure. A world with no file yet reports the same, so the
 * filesystem has to be asked (see {@link SavedDatas#fileOf}).
 *
 * <p><b>Partial, and this is the likelier one.</b> The loader uses
 * {@code DataResult.resultOrPartial}, and DFU's list codec drops what it cannot decode, so a store
 * shaped {@code {version, rows, [.. entries ..]}} (all of ours) <b>parses successfully with
 * entries missing</b>, at the right version. Each store therefore writes how many rows it saved,
 * and a count that disagrees with what decoded is rows lost.
 *
 * <h2>What it does about it</h2>
 *
 * <p>Copies the file aside as {@code <name>.dat.broken-<stamp>} before anything can overwrite it —
 * the part that makes the failure recoverable rather than merely visible — then throws, because a
 * booted world is a world being saved over.
 *
 * <p>Pre-versioning files are not failures: {@code version} reads {@code 0} and {@code rows} reads
 * {@link #UNCOUNTED} through their {@code optionalFieldOf} defaults, so an older world skips the
 * count check and is upgraded by its first save.
 */
public final class StoreGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("anima/store");

    /** A store built by its factory rather than parsed — no file, or a swallowed failure. */
    public static final int NEVER_LOADED = -1;

    /** A file written before this guard existed, which cannot be asked how many rows it had. */
    public static final int UNCOUNTED = -1;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** What the guard needs of a store, and all it needs. */
    public interface Checked {
        /** The schema version read from the file, or {@link #NEVER_LOADED} if this came from the
         *  factory. Never the version the code writes — that would defeat the whole check. */
        int loadedVersion();

        /** How many rows the file said it held, or {@link #UNCOUNTED} if it predates the count. */
        int declaredRows();

        /** How many rows actually decoded. */
        int actualRows();
    }

    private record Guarded(String label, Identifier id,
                           Function<MinecraftServer, ? extends Checked> load) {
    }

    private static final List<Guarded> STORES = new CopyOnWriteArrayList<>();

    private StoreGuard() {
    }

    /**
     * Registers one store to be checked at boot. Call during mod initialization, beside that
     * store's other wiring — the same reason {@code AgentRecords} takes registrations rather than
     * keeping a list somewhere a new store's author will never look.
     */
    public static void guard(String label, Identifier id,
                             Function<MinecraftServer, ? extends Checked> load) {
        STORES.add(new Guarded(label, id, load));
    }

    /** Call once from mod init: checks every registered store as the server finishes starting. */
    public static void install() {
        // STARTED rather than STARTING: the levels have to exist before a store can be loaded, and
        // no autosave has run yet, so a file present now is a file that was on disk at boot.
        ServerLifecycleEvents.SERVER_STARTED.register(StoreGuard::checkAll);
    }

    /** Loads every registered store and fails the boot if any of them lost data. Visible for the
     *  dev command that re-runs the check by hand. */
    public static void checkAll(MinecraftServer server) {
        StringBuilder summary = new StringBuilder();
        for (Guarded store : STORES) {
            if (!summary.isEmpty()) {
                summary.append(", ");
            }
            summary.append(check(server, store));
        }
        // Each store's file state is named rather than counted: the failure mode of this class
        // is resolving the wrong path, and a lookup that finds nothing calls every store intact.
        // "on disk" here is the standing proof the guard looks where vanilla looked.
        LOGGER.info("stores checked — {}", summary.isEmpty() ? "none registered" : summary);
    }

    /**
     * The whole decision, as a pure function of the two observable things: whether a file was on
     * disk, and what the store says about itself. Returns {@code null} when intact, the specific
     * complaint when not.
     *
     * <p>Package-visible so it can be tested without a server: a fresh world and a swallowed total
     * failure differ only in that boolean, and a partial parse looks exactly like a healthy load
     * until the counts are compared.
     */
    static String verdict(boolean fileExisted, Checked store) {
        if (fileExisted && store.loadedVersion() == NEVER_LOADED) {
            return "its file is on disk but nothing came back from it — the codec could not read "
                    + "it, and vanilla replaced it with an empty store";
        }
        if (store.declaredRows() != UNCOUNTED && store.declaredRows() != store.actualRows()) {
            return "its file says it holds " + store.declaredRows() + " row(s) and only "
                    + store.actualRows() + " decoded — the rest were dropped by a partial parse";
        }
        return null;
    }

    private static String check(MinecraftServer server, Guarded store) {
        Path file = SavedDatas.fileOf(server.overworld(), store.id());
        boolean existed = Files.exists(file);
        Checked loaded = store.load().apply(server);
        String complaint = verdict(existed, loaded);
        if (complaint != null) {
            fail(store, file, complaint);
        }
        return store.label() + " " + (existed ? "on disk" : "new")
                + " v" + loaded.loadedVersion() + " " + loaded.actualRows() + " row(s)";
    }

    private static void fail(Guarded store, Path file, String what) {
        Path backup = preserve(file);
        String where = backup == null
                ? "The file could NOT be copied aside — move it yourself before starting again, or "
                        + "the next save will overwrite it."
                : "The file has been copied aside as " + backup.getFileName() + ".";
        String message = "Anima refused to start: the '" + store.label() + "' store did not load. "
                + "Specifically, " + what + ". Left alone, the next autosave would have written the "
                + "empty store over it and the loss would be permanent and silent. " + where
                + " This is what a codec change that cannot read an older world looks like.";
        LOGGER.error(message);
        throw new IllegalStateException(message);
    }

    /** Copies the unreadable file aside before anything can overwrite it. Never throws — a failed
     *  backup must not replace the real diagnosis with an IO stack trace. */
    private static Path preserve(Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            Path backup = file.resolveSibling(
                    file.getFileName() + ".broken-" + LocalDateTime.now().format(STAMP));
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("could not copy {} aside", file, e);
            return null;
        }
    }
}
