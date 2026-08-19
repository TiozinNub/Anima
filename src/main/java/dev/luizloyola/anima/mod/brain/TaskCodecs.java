package dev.luizloyola.anima.mod.brain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import dev.luizloyola.anima.core.brain.task.Task;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a task writes itself down — the registry that lets a plan in progress survive a reload.
 *
 * <p>An extension point: Anima owns the machinery of a plan, not what the plan is FOR. A consumer
 * registers its own task types beside the code that creates them, as it registers a
 * {@code PoiKind} or an {@code AgentRecords} eraser.
 *
 * <p><b>Register the config, not the progress — unless the progress is real.</b> A codec must
 * round-trip everything the instance carries: what it was constructed with, plus any mid-flight
 * counter, because a countdown that restarts is one an agent would feel.
 *
 * <p><b>An unregistered task fails loudly on load:</b> refuse the read and name the type with no
 * codec, rather than let a plan come back missing a limb. Encode has no such net today — a class
 * with no codec throws a raw {@code NullPointerException} out of {@code KeyDispatchCodec} instead
 * of naming itself, tracked 2026-08-19 in {@code docs/BUGS.md}. Register before anything can
 * construct one, and this never bites.
 */
public final class TaskCodecs {

    private TaskCodecs() {
    }

    private record Entry<T extends Task>(String key, Class<T> type, MapCodec<T> codec) {
    }

    /** Insertion-ordered, so a dump of the registry reads in registration order. */
    private static final Map<String, Entry<? extends Task>> BY_KEY = new LinkedHashMap<>();
    private static final Map<Class<?>, Entry<? extends Task>> BY_TYPE = new LinkedHashMap<>();

    /**
     * Teaches the registry one task type. Call during mod initialization.
     *
     * @param key   the stable name this type round-trips under — a rename orphans every saved plan
     *              holding one, so choose it once
     * @param type  the concrete class. That is what a live task is matched by
     * @param codec how to write one down and read it back, progress included
     */
    public static <T extends Task> void register(String key, Class<T> type, MapCodec<T> codec) {
        Entry<T> entry = new Entry<>(key, type, codec);
        BY_KEY.put(key, entry);
        BY_TYPE.put(type, entry);
    }

    /** The name {@code task} round-trips under, or null if nobody registered its type. */
    public static String keyOf(Task task) {
        Entry<? extends Task> entry = BY_TYPE.get(task.getClass());
        return entry == null ? null : entry.key();
    }

    /** Every registered key, in registration order — for a readout, and for the guard's message. */
    public static java.util.Set<String> keys() {
        return java.util.Collections.unmodifiableSet(BY_KEY.keySet());
    }

    /**
     * The dispatching codec over every registered type: {@code {"task": "<key>", …}}.
     *
     * <p>Built lazily: registration happens during mod init, so a field initialised at class-load
     * would freeze an empty registry — surfacing later as "every plan is empty".
     */
    public static Codec<Task> codec() {
        return Codec.STRING.dispatch("task", TaskCodecs::keyOf, TaskCodecs::codecFor);
    }

    @SuppressWarnings("unchecked")
    private static MapCodec<? extends Task> codecFor(String key) {
        Entry<? extends Task> entry = BY_KEY.get(key);
        if (entry == null) {
            // A MapCodec that only ever errors, so the failure names the key and the file it was
            // in rather than throwing out of a codec lookup.
            return MapCodec.unit((Task) null).flatXmap(
                    ignored -> com.mojang.serialization.DataResult.error(
                            () -> "no task type is registered as \"" + key + "\" — was a mod removed?"),
                    ignored -> com.mojang.serialization.DataResult.error(
                            () -> "no task type is registered as \"" + key + "\""));
        }
        return (MapCodec<? extends Task>) entry.codec();
    }
}
