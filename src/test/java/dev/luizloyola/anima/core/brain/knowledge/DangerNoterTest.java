package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.DangerTable;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A danger is news when it is first seen or has moved; a standing one refreshes silently. */
class DangerNoterTest {

    private final AgentKnowledge knowledge = new AgentKnowledge();
    private final DangerTable danger = new DangerTable(Map.of("creeper", 1.0), Map.of(), Set.of());
    private final Pos observer = new Pos(0, 64, 0);
    private final BeingId creeper = BeingId.of(UUID.randomUUID());

    private Being creeperAt(Pos at) {
        return new Being(creeper, Being.Kind.MONSTER, "creeper", "", null, at, 7.0,
                Being.HUMANOID_EYE_HEIGHT, false, 1, 0, false, List.of(), Being.Activity.IDLE,
                Being.Locomotion.STILL, false, false, false, false, false, true, Being.Gear.NONE,
                Being.Identified.INDIVIDUAL, Being.Awareness.SEEN);
    }

    private List<SenseEvent> note(Pos at, long now) {
        return DangerNoter.note(danger, observer, List.of(creeperAt(at)), knowledge, now, 16);
    }

    @Test
    void aStandingThreatIsNotedOnceAndRefreshedSilently() {
        assertEquals(1, note(new Pos(7, 64, 0), 100).size());
        assertTrue(note(new Pos(7, 64, 0), 200).isEmpty(), "unmoved: no line every beat");
        assertTrue(note(new Pos(9, 64, 1), 300).isEmpty(), "a shuffle is not news");

        List<PoiMemory> remembered = List.copyOf(knowledge.all(PoiKind.DANGER));
        assertEquals(1, remembered.size());
        assertEquals(300, remembered.get(0).lastSeenTick(), "…but the memory is still re-stamped");
        assertEquals(new Pos(9, 64, 1), remembered.get(0).anchor());
    }

    @Test
    void aThreatThatMovedIsNotedAgain() {
        note(new Pos(7, 64, 0), 100);
        List<SenseEvent> moved = note(new Pos(7 + DangerNoter.MOVED_RADIUS + 1, 64, 0), 200);
        assertEquals(1, moved.size());
        assertEquals(SenseEvent.Type.NOTED, moved.get(0).type());
    }
}
