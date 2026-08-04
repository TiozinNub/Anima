package dev.luizloyola.anima.mod.log;

import dev.luizloyola.anima.core.log.Entry;
import dev.luizloyola.anima.core.log.JournalService;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.mod.AnimaMod;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import java.nio.file.StandardCopyOption;
import dev.luizloyola.anima.mod.identity.Graves;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.config.Config;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.Comparator;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/**
 * The durable half of the debug log: a {@link JournalService} subscriber that writes every entry to
 * a per-person file. The service's rings are the ephemeral in-memory recall (for {@code /anima
 * log}); this is the keep-forever archive.
 *
 * <p><b>Off the tick, batched.</b> {@link #onEntry} runs on the server thread and only resolves the
 * name once (a directory read, which must stay there) and queues the entry; a single daemon thread
 * drains on the {@link #FLUSH_SECONDS} cadence, groups by person and appends in one go, so a hot
 * file is touched once per cycle. A crash loses at most the last cadence (the ring had those lines
 * too); a clean {@code SERVER_STOPPING} loses nothing (see {@link #close}).
 *
 * <p><b>Bounded open files.</b> At most {@link #MAX_OPEN_FILES} writers (access-ordered LRU); a
 * colder handle is closed and transparently reopened in append mode, so thousands of Persons cannot
 * exhaust file descriptors. Past the constructor's field setup everything is writer-thread only;
 * the queue and the name cache are the sole cross-thread state, both concurrent.
 *
 * <p><b>One folder per run</b>: {@code logs/anima/<TIMESTAMP>/agent-<uuid>-<name>.log}, stamped
 * with the run's wall-clock start, so retention is one list, one sort, one delete — no filename
 * parsing, no half-deleted run. Name changes mid-run are not tracked; the UUID identifies the file.
 *
 * <p><b>Retention</b> keeps the newest {@code journal.keep_runs} folders at boot; before it, fifty
 * settlers and a fortnight of restarts had put 1.5 GB across 13,779 files here.
 *
 * <p><b>The graveyard.</b> A pruned run's files are deleted, except a dead agent's, which move to
 * {@code graveyard/} — that journal has stopped growing, so it costs nothing to keep. At prune time
 * rather than at the burial: moving a file somebody still has open would leave the writer appending
 * to a path that no longer exists, and by prune time every handle is closed.
 */
public final class JournalFileSink {

    /** How often the writer thread drains the queue and flushes — the batching cadence. */
    private static final long FLUSH_SECONDS = 2;

    /** Most per-person files open at once; the coldest is closed (reopened on demand) past this. */
    private static final int MAX_OPEN_FILES = 32;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final MinecraftServer server;
    private final Path dir;
    private final String runStamp;
    /** id → filesystem-safe name, resolved once on the server thread (a directory read lives there). */
    private final Map<AgentId, String> names = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService writer;
    /** Open writers, access-ordered so the eldest entry is the least-recently-written. Writer-thread only. */
    private final LinkedHashMap<AgentId, BufferedWriter> handles;

    private record Pending(AgentId id, Entry entry) {}

    private JournalFileSink(MinecraftServer server, Path dir, String runStamp) {
        this.server = server;
        this.dir = dir;
        this.runStamp = runStamp;
        this.handles = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<AgentId, BufferedWriter> eldest) {
                if (size() > MAX_OPEN_FILES) {
                    closeQuietly(eldest.getValue()); // reopened in append mode when it next writes
                    return true;
                }
                return false;
            }
        };
        this.writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "anima-journal-writer");
            thread.setDaemon(true);
            return thread;
        });
        this.writer.scheduleWithFixedDelay(this::flush, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS);
    }

    /** Create a sink for {@code server} and subscribe it to {@code journal}. Called once per boot. */
    static JournalFileSink attach(MinecraftServer server, JournalService journal) {
        Path root = FabricLoader.getInstance().getGameDir().resolve("logs").resolve(AnimaMod.MOD_ID);
        String stamp = LocalDateTime.now().format(STAMP); // real wall-clock — mod code, not a workflow
        JournalFileSink sink = new JournalFileSink(server, root.resolve(stamp), stamp);
        // Before subscribing, so this run's own folder is never in the set being counted.
        prune(server, root, stamp);
        journal.subscribe(sink::onEntry);
        return sink;
    }

    /** Server thread: pin the name (once) and queue the entry. Never blocks on I/O. */
    private void onEntry(AgentId id, Entry entry) {
        names.computeIfAbsent(id, this::resolveName);
        queue.offer(new Pending(id, entry));
    }

    private String resolveName(AgentId id) {
        return sanitize(AgentDirectory.of(server).nameOf(id).orElse("unknown"));
    }

    // --- writer thread ---------------------------------------------------------------------------

    /** Drain everything queued since the last cadence, grouped by person, appended and flushed. */
    private void flush() {
        List<Pending> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (batch.isEmpty()) {
            return;
        }
        Map<AgentId, List<String>> byPerson = new LinkedHashMap<>();
        for (Pending pending : batch) {
            byPerson.computeIfAbsent(pending.id(), key -> new ArrayList<>()).add(render(pending.entry()));
        }
        for (Map.Entry<AgentId, List<String>> person : byPerson.entrySet()) {
            try {
                BufferedWriter out = handleFor(person.getKey());
                for (String line : person.getValue()) {
                    out.write(line);
                    out.newLine();
                }
                out.flush();
            } catch (IOException e) {
                AnimaMod.LOGGER.warn("journal: failed writing log for {}", person.getKey(), e);
            }
        }
    }

    /** The open writer for {@code id}, opening (and header-stamping) its file on first use. */
    private BufferedWriter handleFor(AgentId id) throws IOException {
        BufferedWriter existing = handles.get(id);
        if (existing != null) {
            return existing;
        }
        Files.createDirectories(dir);
        String name = names.getOrDefault(id, "unknown");
        Path file = dir.resolve("agent-" + id.value() + "-" + name + ".log");
        boolean fresh = !Files.exists(file);
        BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (fresh) {
            out.write("# " + id.value() + "  " + name + "  — run " + runStamp);
            out.newLine();
            out.flush();
        }
        handles.put(id, out);
        return out;
    }

    /** One file line: {@code [<tick>] <category> - <event> - <detail>} (name/uuid are in the filename). */
    private static String render(Entry entry) {
        String base = "[" + entry.tick() + "] " + entry.category().name().toLowerCase(Locale.ROOT)
                + " - " + entry.event();
        return entry.detail().isEmpty() ? base : base + " - " + entry.detail();
    }

    // --- lifecycle -------------------------------------------------------------------------------

    /**
     * Stop the writer, drain whatever is left on this thread, and close every file — the clean
     * shutdown that loses nothing. Called from {@code SERVER_STOPPING} (server thread); after
     * {@link java.util.concurrent.ExecutorService#awaitTermination awaitTermination} the writer
     * thread is done, so the final {@link #flush()} and the handle close-out race nothing.
     */
    void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush(); // final drain of anything queued after the writer's last cycle
        for (BufferedWriter out : handles.values()) {
            closeQuietly(out);
        }
        handles.clear();
    }

    private static void closeQuietly(BufferedWriter out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // closing on shutdown/eviction — nothing useful to do with a failure here
        }
    }

    /** Make a display name safe as one filename segment. */
    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** The folder a pruned run's dead keep their journals in. Never itself a run. */
    private static final String GRAVEYARD = "graveyard";

    /**
     * Where files written before runs had folders are swept, so retention can reach them.
     *
     * <p>Named to sort before any timestamp — digits precede letters, so a plain "legacy" would
     * sort last and be kept longest, the opposite of the intent.
     */
    private static final String LEGACY = "0000-legacy";

    /**
     * Keeps the newest {@code journal.keep_runs} run folders and removes the rest, rescuing the
     * dead on the way out.
     *
     * <p>Run folders are named by sortable wall-clock stamp, so "newest" is a string sort rather
     * than a filesystem timestamp, which a copied or restored world would have lied about.
     * Non-directories and the graveyard are left alone: this deletes runs, and only runs.
     *
     * <p>Every failure is logged and swallowed — a world that will not boot over a log folder would
     * be worse than the disk use this bounds.
     */
    private static void prune(MinecraftServer server, Path root, String thisRun) {
        int keep = Config.get().i(Knob.JOURNAL_KEEP_RUNS);
        try {
            if (!Files.isDirectory(root)) {
                return;
            }
            sweepLegacyFiles(root);
            List<Path> runs;
            try (Stream<Path> entries = Files.list(root)) {
                runs = entries.filter(Files::isDirectory)
                        .filter(path -> !path.getFileName().toString().equals(GRAVEYARD))
                        .filter(path -> !path.getFileName().toString().equals(thisRun))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }
            // Keep-1: this run's folder counts toward the budget even though it is excluded above,
            // because it is about to exist and be the newest of them.
            int drop = runs.size() - Math.max(0, keep - 1);
            if (drop <= 0) {
                return;
            }
            int removed = 0;
            int entombed = 0;
            for (Path run : runs.subList(0, drop)) {
                entombed += rescueTheDead(server, root, run);
                if (deleteTree(run)) {
                    removed++;
                }
            }
            if (removed > 0) {
                AnimaMod.LOGGER.info("journal: pruned {} old run(s), kept {} grave file(s)",
                        removed, entombed);
            }
        } catch (IOException | RuntimeException e) {
            AnimaMod.LOGGER.warn("journal: could not prune old runs under {}", root, e);
        }
    }

    /**
     * Sweeps loose {@code agent-*.log} files (the flat layout used before runs had folders) into
     * one folder, so ordinary retention can age them out.
     *
     * <p>Without this, upgrading leaves every file ever written at the root where a folder-based
     * prune cannot see it: 1.5 GB across 13,779 files in this repo alone.
     *
     * <p>A move, never a delete: they land in a folder that sorts oldest and go on a later boot by
     * the same rule as everything else, which gives an operator a window to take them.
     */
    private static void sweepLegacyFiles(Path root) throws IOException {
        List<Path> loose;
        try (Stream<Path> entries = Files.list(root)) {
            loose = entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("agent-"))
                    .toList();
        }
        if (loose.isEmpty()) {
            return;
        }
        Path legacy = root.resolve(LEGACY);
        Files.createDirectories(legacy);
        int moved = 0;
        for (Path file : loose) {
            try {
                Files.move(file, legacy.resolve(file.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
                moved++;
            } catch (IOException e) {
                AnimaMod.LOGGER.warn("journal: could not sweep {}", file, e);
            }
        }
        AnimaMod.LOGGER.info("journal: swept {} file(s) from the old flat layout into {}/",
                moved, LEGACY);
    }

    /** Moves the journals of agents who died into {@code graveyard/} before their run is deleted. */
    private static int rescueTheDead(MinecraftServer server, Path root, Path run) throws IOException {
        Graves graves = Graves.get(server);
        if (graves.size() == 0) {
            return 0;
        }
        Path graveyard = root.resolve(GRAVEYARD);
        int moved = 0;
        try (Stream<Path> files = Files.list(run)) {
            for (Path file : files.toList()) {
                AgentId id = idOf(file.getFileName().toString());
                if (id == null || !graves.isDead(id)) {
                    continue;
                }
                Files.createDirectories(graveyard);
                // The run stamp goes back into the name here: inside a run folder it was
                // redundant, and out of one it is the only thing telling two lives apart.
                Path target = graveyard.resolve(run.getFileName() + "-" + file.getFileName());
                try {
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                    moved++;
                } catch (IOException e) {
                    AnimaMod.LOGGER.warn("journal: could not entomb {}", file, e);
                }
            }
        }
        return moved;
    }

    /** The agent a journal file belongs to, or null if the name is not one of ours. */
    private static @Nullable AgentId idOf(String fileName) {
        if (!fileName.startsWith("agent-") || fileName.length() < 6 + 36) {
            return null;
        }
        try {
            return AgentId.of(UUID.fromString(fileName.substring(6, 6 + 36)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Depth-first delete. Returns whether the folder is gone. */
    private static boolean deleteTree(Path folder) {
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException e) {
            AnimaMod.LOGGER.warn("journal: could not remove old run {}", folder, e);
            return false;
        }
    }
}
