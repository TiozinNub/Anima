package dev.luizloyola.anima.core.continuity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StateGraphTest {

    static final class Walk {
        int steps;
        String goal;
        @Ephemeral("a search in flight, re-issued on restore")
        Object pending;
    }

    static final class Plan {
        Walk root;
        List<Walk> frames = new ArrayList<>();
    }

    record Cell(int x, int y) {
    }

    private static List<String> diff(Object before, Object after) {
        return StateGraph.capture(before).diff(StateGraph.capture(after));
    }

    @Test
    void aFieldTheSaveForgotIsReported() {
        Walk live = new Walk();
        live.steps = 7;
        live.goal = "home";
        Walk restored = new Walk();
        restored.goal = "home";
        assertEquals(List.of("steps: 7 → 0"), diff(live, restored));
    }

    @Test
    void anEphemeralFieldIsNotCompared() {
        Walk live = new Walk();
        live.pending = new Object();
        assertTrue(diff(live, new Walk()).isEmpty());
    }

    /** The executor's root is its first frame; a restore that decodes both apart is a finding. */
    @Test
    void aSharedObjectThatCameBackAsACopyIsReported() {
        Plan live = new Plan();
        live.root = new Walk();
        live.frames.add(live.root);
        Plan restored = new Plan();
        restored.root = new Walk();
        restored.frames.add(new Walk());
        List<String> found = diff(live, restored);
        assertEquals(1, found.size(), found::toString);
        assertTrue(found.get(0).startsWith("frames[0]: @root"), found::toString);
    }

    @Test
    void aSetComparesByContentNotByOrder() {
        Set<Cell> one = new HashSet<>(List.of(new Cell(1, 2), new Cell(3, 4), new Cell(5, 6)));
        Set<Cell> two = new HashSet<>(List.of(new Cell(5, 6), new Cell(1, 2), new Cell(3, 4)));
        assertTrue(diff(one, two).isEmpty());
    }

    /** One subtree that came back different is one finding, not one per leaf. */
    @Test
    void aDifferentSubtreeIsReportedOnce() {
        Plan live = new Plan();
        live.root = new Walk();
        live.root.steps = 3;
        live.root.goal = "a";
        Plan restored = new Plan();
        List<String> found = diff(live, restored);
        assertEquals(1, found.size(), found::toString);
        assertTrue(found.get(0).startsWith("root"), found::toString);
    }

    @Test
    void whatItCannotSeeIntoItNames() {
        StateGraph graph = StateGraph.capture(new Object[] {new java.util.Random(1)});
        assertEquals(Set.of("[0]"), graph.opaque());
    }
}
