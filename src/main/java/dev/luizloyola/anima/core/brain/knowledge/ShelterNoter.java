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
 *
 * <p>A pocket a body dug itself into for the night is a shelter with no door,
 * {@code "dug x y z"} naming the block over its head ({@link #dugIn}). It cannot be walked into, so
 * nothing runs for it; it says the body is shut in on purpose, and it is forgotten once the body
 * is seen out of it.
 */
public final class ShelterNoter {

    private static final String SMALL = "small";
    private static final String DUG = "dug ";

    /** How near a dug pocket an answer from outside it must be to say the body left it. */
    private static final int LEFT_POCKET = 3;

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
            if (dug(known)) {
                if (!known.bounds().contains(from) && within(known.anchor(), from, LEFT_POCKET)) {
                    knowledge.forget(PoiKind.SHELTER, known.anchor());
                    events.add(SenseEvent.forgot(PoiKind.SHELTER, known.anchor()));
                }
                continue;
            }
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

    /**
     * A pocket dug for the night: {@code feet} its floor cell, {@code cover} the block put back over
     * the head, the bounds the column between them and the cell the body stood in to dig it.
     */
    public static PoiMemory dugIn(Pos feet, Pos cover, long now) {
        Region column = Region.of(feet).including(new Pos(cover.x(), cover.y() + 1, cover.z()));
        return new PoiMemory(PoiKind.SHELTER, DUG + cover.x() + " " + cover.y() + " " + cover.z(),
                feet, column, cover.y() - feet.y(), false, now);
    }

    /** Whether this is a pocket dug for the night rather than a way in by a door. */
    public static boolean dug(PoiMemory memory) {
        return memory.detail().startsWith(DUG);
    }

    /** The block over a dug pocket's head. */
    public static Pos cover(PoiMemory memory) {
        String[] parts = memory.detail().substring(DUG.length()).split(" ");
        return new Pos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }

    /** The door a shelter memory is the way in by, by its lowest cell. Not asked of a dug one. */
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
            if (dug(memory)) {
                continue;
            }
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
        return within(a, b, 1);
    }

    private static boolean within(Pos a, Pos b, int cells) {
        return Math.abs(a.x() - b.x()) <= cells && Math.abs(a.y() - b.y()) <= cells
                && Math.abs(a.z() - b.z()) <= cells;
    }
}
