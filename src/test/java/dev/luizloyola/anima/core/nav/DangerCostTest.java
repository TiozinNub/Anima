package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.TestDanger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Walking round what you are afraid of. The load-bearing property is the negative one: with
 * nothing to fear, every path must be the path found before any of this existed — the
 * pathfinder's costs are coupled to each other and to the integrity rules.
 */
class DangerCostTest {

    /** Flat open ground, 21 by 21 at height 1 — a heightmap row per z, a character per x. */
    private static final String[] OPEN = openField();

    private static String[] openField() {
        String[] rows = new String[21];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = "1".repeat(21);
        }
        return rows;
    }

    private static Path route(DangerField danger) {
        AsciiWorld world = AsciiWorld.of(OPEN);
        return Pathfinder.find(world, PathRequest.of(0, 1, 10, 20, 1, 10,
                TestBodies.BIPED, danger));
    }

    private static DangerField fear(Pos at, String species, long age) {
        AgentKnowledge knowledge = new AgentKnowledge();
        knowledge.note(new PoiMemory(PoiKind.DANGER, species, UUID.randomUUID(), at,
                        Region.of(at), 1, false, 10_000L - age),
                AgentKnowledge.maxPerKind(TestSpecies.PROFILE));
        return DangerField.of(TestDanger.TABLE, List.of(), knowledge, 10_000L,
                DangerField.FADE_TICKS);
    }

    @Test
    @DisplayName("with nothing to fear, the path is exactly the path from before this existed")
    void zeroDangerCostsExactlyZero() {
        Path plain = Pathfinder.find(AsciiWorld.of(OPEN),
                PathRequest.of(0, 1, 10, 20, 1, 10, TestBodies.BIPED));
        Path withEmptyField = route(DangerField.NONE);

        assertEquals(plain.waypoints(), withEmptyField.waypoints(),
                "an empty field must sum an empty list — anything else moves the regression pair");
    }

    @Test
    @DisplayName("a remembered creeper on the straight line is walked around, not through")
    void aPathBendsAroundARememberedFright() {
        Path straight = route(DangerField.NONE);
        Path around = route(fear(new Pos(10, 1, 10), "creeper", 0));

        assertTrue(straight.waypoints().stream().allMatch(w -> w.z() == 10),
                "the undisturbed route should run straight down the middle");

        double closest = around.waypoints().stream()
                .mapToDouble(w -> Math.hypot(w.x() - 10, w.z() - 10))
                .min().orElse(0.0);
        assertTrue(closest >= 2.0,
                "walked within " + closest + " blocks of a remembered creeper it could have "
                        + "gone round");
        assertTrue(around.reachedGoal(), "fear is a preference, not a wall — it must still arrive");
    }

    @Test
    @DisplayName("an old fright bends the path less than a fresh one")
    void theBendFadesWithTheMemory() {
        double freshDetour = detour(route(fear(new Pos(10, 1, 10), "creeper", 0)));
        double staleDetour = detour(route(fear(new Pos(10, 1, 10), "creeper",
                (long) (DangerField.FADE_TICKS * 0.9))));

        assertTrue(staleDetour < freshDetour,
                "a fright nearly faded out should bend the route less than a fresh one: "
                        + staleDetour + " vs " + freshDetour);
    }

    @Test
    @DisplayName("a creeper is given a wider berth than a skeleton, because it is worth more")
    void theBerthScalesWithWhatTheThingIsWorth() {
        double fromCreeper = closest(route(fear(new Pos(10, 1, 10), "creeper", 0)));
        double fromSkeleton = closest(route(fear(new Pos(10, 1, 10), "skeleton", 0)));

        assertTrue(fromCreeper >= fromSkeleton,
                "the table says a creeper is worth more fear than a skeleton, and the route "
                        + "should say so too: " + fromCreeper + " vs " + fromSkeleton);
    }

    private static double closest(Path path) {
        return path.waypoints().stream()
                .mapToDouble(w -> Math.hypot(w.x() - 10, w.z() - 10))
                .min().orElse(0.0);
    }

    private static double detour(Path path) {
        return path.waypoints().stream().mapToDouble(w -> Math.abs(w.z() - 10)).max().orElse(0.0);
    }
}
