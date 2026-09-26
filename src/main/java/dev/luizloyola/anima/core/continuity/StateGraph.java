package dev.luizloyola.anima.core.continuity;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Everything an object graph holds, flattened to one value per path, so a graph taken before a save
 * can be compared with the one after the load — the check that holds the continuity contract
 * (docs: {@code 2026-09-25-continuity-enforced-design.md}). A field nobody wrote into a codec fails
 * it the first time it holds anything but its default; nobody has to remember to list it.
 *
 * <p>What is walked: every non-static field of Anima's and its consumers' classes
 * ({@code dev.luizloyola.*}), recursively, except {@link Ephemeral} ones; records by component;
 * lists in order; sets and maps by a canonical key; arrays; {@code Optional}. Values: primitives,
 * strings, enums, {@link UUID}s. An object met twice is recorded once and then as a reference to the
 * path it was first met at, so a restore that copies what was shared shows up as a difference — a
 * record excepted, which is a value and compared as one.
 * Anything else — a JDK or Minecraft object — is recorded by its type only and listed as
 * {@link #opaque}: the check cannot see into it, and says so.
 */
public final class StateGraph {

    /** How many objects one capture walks before it stops. A body's state is thousands, not this. */
    private static final int MAX_OBJECTS = 200_000;
    private static final String OURS = "dev.luizloyola.";

    private final Map<String, String> values = new LinkedHashMap<>();
    private final IdentityHashMap<Object, String> seen = new IdentityHashMap<>();
    private final Set<String> opaque = new LinkedHashSet<>();
    private int objects;

    private StateGraph() {
    }

    public static StateGraph capture(Object root) {
        StateGraph graph = new StateGraph();
        graph.walk("", root);
        return graph;
    }

    /** Path to value, in the order walked. */
    public Map<String, String> values() {
        return this.values;
    }

    /** Paths holding something this walk could only name, not see into. */
    public Set<String> opaque() {
        return this.opaque;
    }

    /**
     * Every path whose value differs in {@code after}, as {@code path: before → after}. A path under
     * one already reported is left out: a subtree that came back different is one finding.
     */
    public List<String> diff(StateGraph after) {
        List<String> out = new ArrayList<>();
        List<String> reported = new ArrayList<>();
        Set<String> paths = new LinkedHashSet<>(this.values.keySet());
        paths.addAll(after.values.keySet());
        for (String path : paths) {
            String before = this.values.getOrDefault(path, "(absent)");
            String now = after.values.getOrDefault(path, "(absent)");
            if (before.equals(now) || under(path, reported)) {
                continue;
            }
            reported.add(path);
            out.add(path + ": " + before + " → " + now);
        }
        return out;
    }

    private static boolean under(String path, List<String> reported) {
        for (String parent : reported) {
            if (path.length() > parent.length() && path.startsWith(parent)
                    && (parent.isEmpty() || ".[{?".indexOf(path.charAt(parent.length())) >= 0)) {
                return true;
            }
        }
        return false;
    }

    private void walk(String path, Object value) {
        if (value == null) {
            this.values.put(path, "null");
            return;
        }
        String plain = plain(value);
        if (plain != null) {
            this.values.put(path, plain);
            return;
        }
        Class<?> type = value.getClass();
        // A record is a value: two equal ones and one shared are the same thing to anybody reading
        // them. Its contents are still walked, and anything mutable inside keeps its identity.
        if (!type.isRecord()) {
            String first = this.seen.get(value);
            if (first != null) {
                this.values.put(path, "@" + (first.isEmpty() ? "root" : first));
                return;
            }
            this.seen.put(value, path);
        }
        if (++this.objects > MAX_OBJECTS) {
            this.values.put(path, "(past " + MAX_OBJECTS + " objects)");
            return;
        }
        if (value instanceof Optional<?> optional) {
            this.values.put(path, "optional");
            walk(path + "?", optional.orElse(null));
        } else if (type.isArray()) {
            int length = Array.getLength(value);
            this.values.put(path, "array of " + length);
            for (int i = 0; i < length; i++) {
                walk(path + "[" + i + "]", Array.get(value, i));
            }
        } else if (value instanceof Map<?, ?> map) {
            this.values.put(path, "map of " + map.size());
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(keyOf(entry.getKey()), entry.getValue());
            }
            sorted.forEach((key, v) -> walk(path + "{" + key + "}", v));
        } else if (value instanceof Set<?> set) {
            this.values.put(path, "set of " + set.size());
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Object element : set) {
                sorted.put(keyOf(element), element);
            }
            sorted.forEach((key, element) -> walk(path + "{" + key + "}", element));
        } else if (value instanceof Collection<?> collection) {
            this.values.put(path, "list of " + collection.size());
            int i = 0;
            for (Object element : collection) {
                walk(path + "[" + i++ + "]", element);
            }
        } else if (type.isRecord()) {
            this.values.put(path, type.getName());
            for (RecordComponent component : type.getRecordComponents()) {
                walk(child(path, component.getName()), component(component, value));
            }
        } else if (type.getName().startsWith(OURS) && !type.isHidden()) {
            this.values.put(path, type.getName());
            for (Class<?> c = type; c != null && c.getName().startsWith(OURS); c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Ephemeral.class)) {
                        continue;
                    }
                    walk(child(path, field.getName()), read(field, value));
                }
            }
        } else {
            this.values.put(path, "(" + (type.isHidden() ? "lambda in " + host(type) : type.getName()) + ")");
            this.opaque.add(path.isEmpty() ? "root" : path);
        }
    }

    /** The value itself, for what is compared by value; null for anything walked into. */
    private static String plain(Object value) {
        if (value instanceof String s) {
            return '"' + s + '"';
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof UUID) {
            return value.toString();
        }
        if (value instanceof Enum<?> e) {
            return e.getDeclaringClass().getSimpleName() + "." + e.name();
        }
        if (value instanceof Class<?> c) {
            return "class " + c.getName();
        }
        return null;
    }

    /** A stable name for a set element or a map key: its value, or its whole flattened state. */
    private static String keyOf(Object key) {
        if (key == null) {
            return "null";
        }
        String plain = plain(key);
        if (plain != null) {
            return plain;
        }
        StateGraph inner = capture(key);
        StringBuilder out = new StringBuilder();
        inner.values.forEach((path, v) -> out.append(path).append('=').append(v).append(';'));
        return out.toString();
    }

    private static String child(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    private static String host(Class<?> hidden) {
        String name = hidden.getName();
        int cut = name.indexOf("$$");
        return cut < 0 ? name : name.substring(0, cut);
    }

    private static Object component(RecordComponent component, Object record) {
        try {
            var accessor = component.getAccessor();
            accessor.setAccessible(true);
            return accessor.invoke(record);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "(unreadable: " + e.getClass().getSimpleName() + ")";
        }
    }

    private static Object read(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "(unreadable: " + e.getClass().getSimpleName() + ")";
        }
    }
}
