package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Turning the enclosure check's answer into remembered ways into a shelter (shelter spec, rung 5).
 * A body remembers its own house the first time it stops inside, and a stranger's the same way.
 *
 * <p>Every answer that finds a shelter, or a roofed space its doors would close, writes one
 * {@link PoiKind#SHELTER} memory per door on its edge and refreshes the ones already there. The
 * check is also what disproves one: an answer from inside that no longer finds the space shut, or
 * no longer finds that door on its edge, forgets it.
 *
 * <p>The door and whether small things get in ride in {@code detail}, {@code "x y z"} with
 * {@code " small"} after it when they do, because a memory has no other field to hold them.
 */
public final class ShelterNoter {

    private static final String SMALL = "small";

    private ShelterNoter() {
    }

    /** One answer, folded in. Returns what is worth narrating: shelters new to this body, and ones it lost. */
    public static List<SenseEvent> note(Enclosure answer, AgentKnowledge knowledge, int maxPerKind) {
        List<SenseEvent> events = new ArrayList<>();
        Pos from = answer.from();
        if (!answer.known() || from == null) {
            return events;
        }
        boolean shelter = answer.roofed() && !answer.space().isEmpty()
                && (answer.shelter() || answer.openness() == Enclosure.Openness.CLOSEABLE);
        Map<Pos, PoiMemory> ways = shelter ? ways(answer) : Map.of();
        for (PoiMemory known : List.copyOf(knowledge.sighted(PoiKind.SHELTER))) {
            Pos door = door(known);
            boolean disproved = shelter
                    ? answer.covers(known.anchor()) && !ways.containsKey(door)
                    : near(known.anchor(), from) && !(from.x() == door.x() && from.z() == door.z());
            if (disproved) {
                knowledge.forget(PoiKind.SHELTER, known.anchor());
                events.add(SenseEvent.forgot(PoiKind.SHELTER, known.anchor()));
            }
        }
        for (PoiMemory way : ways.values()) {
            PoiMemory before = find(knowledge, way.anchor());
            knowledge.note(way, maxPerKind);
            if (before == null || !before.detail().equals(way.detail())) {
                events.add(SenseEvent.noted(way));
            }
        }
        return events;
    }

    /** A memory per door on the space's edge, keyed by the door. */
    private static Map<Pos, PoiMemory> ways(Enclosure answer) {
        Region box = null;
        for (Pos cell : answer.space()) {
            box = box == null ? Region.of(cell) : box.including(cell);
        }
        Map<Pos, PoiMemory> ways = new LinkedHashMap<>();
        for (Pos door : answer.doors()) {
            Pos anchor = inside(answer, door);
            if (anchor == null) {
                continue;
            }
            String detail = door.x() + " " + door.y() + " " + door.z()
                    + (answer.holes() == Enclosure.Holes.NONE ? "" : " " + SMALL);
            ways.put(door, new PoiMemory(PoiKind.SHELTER, detail, anchor, box.including(door),
                    answer.space().size(), false, answer.at()));
        }
        return ways;
    }

    /** Where a body stands just inside the door: beside it rather than in its doorway. */
    private static @Nullable Pos inside(Enclosure answer, Pos door) {
        List<Pos> cells = answer.besideDoor(door);
        for (Pos cell : cells) {
            if (cell.x() != door.x() || cell.z() != door.z()) {
                return cell;
            }
        }
        return cells.isEmpty() ? null : cells.get(0);
    }

    /** The door a shelter memory is the way in by, by its lowest cell. */
    public static Pos door(PoiMemory memory) {
        String[] parts = memory.detail().split(" ");
        return new Pos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }

    /** Whether something a block high gets into this shelter without a door. */
    public static boolean smallGetsIn(PoiMemory memory) {
        return memory.detail().endsWith(" " + SMALL);
    }

    /** Whether {@code door}, named by either of its cells, is a way into a shelter this body knows. */
    public static boolean knownDoor(AgentKnowledge knowledge, Pos door) {
        for (PoiMemory memory : knowledge.sighted(PoiKind.SHELTER)) {
            Pos known = door(memory);
            if (known.x() == door.x() && known.z() == door.z()
                    && Math.abs(known.y() - door.y()) <= 1) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable PoiMemory find(AgentKnowledge knowledge, Pos anchor) {
        for (PoiMemory memory : knowledge.sighted(PoiKind.SHELTER)) {
            if (memory.anchor().equals(anchor)) {
                return memory;
            }
        }
        return null;
    }

    private static boolean near(Pos a, Pos b) {
        return Math.abs(a.x() - b.x()) <= 1 && Math.abs(a.y() - b.y()) <= 1
                && Math.abs(a.z() - b.z()) <= 1;
    }
}
