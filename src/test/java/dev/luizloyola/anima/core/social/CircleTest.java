package dev.luizloyola.anima.core.social;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Going round the circle: newcomers and members learn each other, faces only where seen. */
class CircleTest {

    private final AgentId bram = AgentId.random();
    private final AgentId eli = AgentId.random();
    private final AgentId ada = AgentId.random();
    private final AgentId cai = AgentId.random();

    @Test
    void theOnesInSightAreLearnedInFullAndTheRestByName() {
        // Bram joins Ada, who stands beside him, and Cai, who is away on a trip.
        Set<List<AgentId>> inSight = Set.of(List.of(bram, ada), List.of(ada, bram));
        List<Circle.Step> steps = Circle.round(List.of(bram), List.of(ada, cai),
                (a, b) -> false, (a, b) -> inSight.contains(List.of(a, b)));

        assertEquals(List.of(
                new Circle.Step(bram, ada, true), new Circle.Step(ada, bram, true),
                new Circle.Step(bram, cai, false), new Circle.Step(cai, bram, false)), steps);
    }

    @Test
    void whoAlreadyKnowsLearnsNothingAndNewcomersAreNotIntroducedToEachOther() {
        List<Circle.Step> steps = Circle.round(List.of(bram, eli), List.of(ada),
                (a, b) -> a.equals(ada) && b.equals(bram), (a, b) -> false);

        assertEquals(List.of(
                new Circle.Step(bram, ada, false),
                new Circle.Step(eli, ada, false), new Circle.Step(ada, eli, false)), steps);
    }
}
