package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Enclosure.Holes;
import dev.luizloyola.anima.core.brain.sense.Enclosure.Openness;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What a body remembers of shelters from the enclosure check (shelter spec, rung 5).
 *
 * <p>The room is seven by seven at y = 64, x and z 0 to 6, its door in the north wall at
 * (3, 64, -1); the cell inside the door is (3, 64, 0).
 */
class ShelterNoterTest {

    private static final Pos DOOR = new Pos(3, 64, -1);
    private static final Pos INSIDE_THE_DOOR = new Pos(3, 64, 0);
    private static final Pos MIDDLE = new Pos(3, 64, 3);
    private static final int CAP = 16;

    private final AgentKnowledge knowledge = new AgentKnowledge();

    @Test
    void aShutRoofedRoomIsRememberedByTheCellInsideItsDoor() {
        List<SenseEvent> events = ShelterNoter.note(room(Openness.OPENABLE, Holes.NONE, true, MIDDLE,
                List.of(DOOR)), knowledge, CAP);
        assertEquals(1, events.size());
        PoiMemory way = only();
        assertEquals(INSIDE_THE_DOOR, way.anchor());
        assertEquals(DOOR, ShelterNoter.door(way));
        assertEquals(49, way.units());
        assertTrue(way.bounds().contains(new Pos(6, 64, 6)) && way.bounds().contains(DOOR));
        assertFalse(ShelterNoter.smallGetsIn(way));
    }

    @Test
    void askingAgainRefreshesWithoutSayingSo() {
        ShelterNoter.note(room(Openness.OPENABLE, Holes.NONE, true, MIDDLE, List.of(DOOR)),
                knowledge, CAP);
        List<SenseEvent> again = ShelterNoter.note(
                room(Openness.OPENABLE, Holes.NONE, true, MIDDLE, List.of(DOOR)), knowledge, CAP);
        assertTrue(again.isEmpty());
        assertEquals(1, knowledge.sighted(PoiKind.SHELTER).size());
    }

    @Test
    void aRoomItsDoorsWouldCloseCountsAndOneWithoutARoofDoesNot() {
        ShelterNoter.note(room(Openness.CLOSEABLE, Holes.NONE, true, MIDDLE, List.of(DOOR)),
                knowledge, CAP);
        assertEquals(1, knowledge.sighted(PoiKind.SHELTER).size());
        AgentKnowledge other = new AgentKnowledge();
        ShelterNoter.note(room(Openness.OPENABLE, Holes.NONE, false, MIDDLE, List.of(DOOR)),
                other, CAP);
        assertTrue(other.sighted(PoiKind.SHELTER).isEmpty(), "a spider climbs in");
    }

    @Test
    void smallHolesAreRemembered() {
        ShelterNoter.note(room(Openness.OPENABLE, Holes.SMALL, true, MIDDLE, List.of(DOOR)),
                knowledge, CAP);
        assertTrue(ShelterNoter.smallGetsIn(only()));
    }

    @Test
    void anOpenAnswerFromInsideTheDoorForgetsItAndOneFromAfarDoesNot() {
        ShelterNoter.note(room(Openness.OPENABLE, Holes.NONE, true, MIDDLE, List.of(DOOR)),
                knowledge, CAP);
        ShelterNoter.note(Enclosure.open(new Pos(30, 64, 30), 5L), knowledge, CAP);
        assertEquals(1, knowledge.sighted(PoiKind.SHELTER).size(), "somewhere else entirely");
        ShelterNoter.note(Enclosure.open(new Pos(3, 64, -1), 5L), knowledge, CAP);
        assertEquals(1, knowledge.sighted(PoiKind.SHELTER).size(),
                "standing in the doorway proves nothing about the room");
        List<SenseEvent> events = ShelterNoter.note(Enclosure.open(INSIDE_THE_DOOR, 5L), knowledge,
                CAP);
        assertTrue(knowledge.sighted(PoiKind.SHELTER).isEmpty(), "the wall came down");
        assertEquals(SenseEvent.Type.FORGOT, events.get(0).type());
    }

    @Test
    void aDoorNoLongerOnTheEdgeIsForgotten() {
        Pos east = new Pos(7, 64, 3);
        ShelterNoter.note(room(Openness.OPENABLE, Holes.NONE, true, MIDDLE, List.of(DOOR, east)),
                knowledge, CAP);
        assertEquals(2, knowledge.sighted(PoiKind.SHELTER).size());
        ShelterNoter.note(room(Openness.OPENABLE, Holes.NONE, true, MIDDLE, List.of(east)),
                knowledge, CAP);
        assertEquals(east, ShelterNoter.door(only()), "the north door was walled up");
    }

    @Test
    void eitherHalfOfTheDoorIsKnown() {
        ShelterNoter.note(room(Openness.OPENABLE, Holes.NONE, true, MIDDLE, List.of(DOOR)),
                knowledge, CAP);
        assertTrue(ShelterNoter.knownDoor(knowledge, DOOR));
        assertTrue(ShelterNoter.knownDoor(knowledge, new Pos(3, 65, -1)));
        assertFalse(ShelterNoter.knownDoor(knowledge, new Pos(4, 64, -1)));
    }

    private PoiMemory only() {
        List<PoiMemory> all = new ArrayList<>(knowledge.sighted(PoiKind.SHELTER));
        assertEquals(1, all.size());
        return all.get(0);
    }

    private static Enclosure room(Openness openness, Holes holes, boolean roofed, Pos from,
                                  List<Pos> doors) {
        Set<Pos> space = new HashSet<>();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                space.add(new Pos(x, 64, z));
            }
        }
        return new Enclosure(openness, holes, roofed, from, space,
                openness == Openness.CLOSEABLE ? doors : List.of(), doors, 1L);
    }
}
