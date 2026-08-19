package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.agent.need.Company;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.agent.need.Needs;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What makes one patch of ground nicer to stand on: elbow room always, plus people drawing a
 * body in or pushing it away by which side of its company band it is on.
 *
 * <p>Guard the crowding term: without one that grows as bodies converge, the landscape's minimum
 * puts every settler in a village on the same cell — which reads as a pathfinder bug.
 */
class ComfortTest {

    private static final Pos HERE = new Pos(0, 64, 0);

    private static Being personAt(int x, int z) {
        return new Being(BeingId.of(UUID.randomUUID()), Being.Kind.AGENT, "person", "", null,
                new Pos(x, 64, z), Math.sqrt(x * x + z * z), 1, 0, false, List.of(),
                Being.Activity.IDLE, Being.Locomotion.STILL, false, false, false, false, false,
                false, Being.Gear.NONE, Being.Identified.INDIVIDUAL, Being.Awareness.SEEN);
    }

    private static Needs companyAt(double value) {
        Company company = new Company(() -> TestSpecies.PROFILE);
        company.setValue(value);
        return new Needs().add(company);
    }

    @Test
    @DisplayName("standing on somebody costs, whoever they are")
    void crowdingCosts() {
        Needs content = companyAt(0.6);
        double alone = Comfort.cost(HERE, List.of(), DangerField.NONE, content, TestSpecies.PROFILE);
        double onTop = Comfort.cost(HERE, List.of(personAt(1, 0)), DangerField.NONE, content,
                TestSpecies.PROFILE);
        assertTrue(onTop > alone, "an occupied cell has to cost more than an empty one");
        assertEquals(Comfort.CROWDING, onTop - alone, 1.0e-9);
        double roomy = Comfort.cost(HERE, List.of(personAt(6, 0)), DangerField.NONE, content,
                TestSpecies.PROFILE);
        assertEquals(alone, roomy, 1.0e-9, "six blocks off is not crowding, it is company");
    }

    @Test
    @DisplayName("a lonely body prefers the spot nearer people; a crowded one prefers the far spot")
    void companyPullsBothWays() {
        List<Being> neighbour = List.of(personAt(8, 0));
        Pos near = new Pos(4, 64, 0);   // four blocks off them: sociable, not in their face
        Pos far = new Pos(-8, 64, 0);

        Needs lonely = companyAt(0.05);
        assertTrue(Comfort.cost(near, neighbour, DangerField.NONE, lonely, TestSpecies.PROFILE)
                        < Comfort.cost(far, neighbour, DangerField.NONE, lonely, TestSpecies.PROFILE),
                "below the band, being far from everybody is the thing that costs");
        // Elbow room outranks wanting company, and the two terms meeting is what sets
        // conversation distance.
        Pos inTheirFace = new Pos(7, 64, 0);
        assertTrue(Comfort.cost(near, neighbour, DangerField.NONE, lonely, TestSpecies.PROFILE)
                        < Comfort.cost(inTheirFace, neighbour, DangerField.NONE, lonely,
                                TestSpecies.PROFILE),
                "loneliness does not buy the right to stand inside somebody");

        Needs crowded = companyAt(1.0);
        assertTrue(Comfort.cost(far, neighbour, DangerField.NONE, crowded, TestSpecies.PROFILE)
                        < Comfort.cost(near, neighbour, DangerField.NONE, crowded, TestSpecies.PROFILE),
                "above it, the same term with the other sign — a hermit is not a second mechanism");

        Needs content = companyAt(0.6);
        assertEquals(Comfort.cost(near, neighbour, DangerField.NONE, content, TestSpecies.PROFILE),
                Comfort.cost(far, neighbour, DangerField.NONE, content, TestSpecies.PROFILE), 1.0e-9,
                "inside the band there is nothing to want, and no reason to prefer either spot");
    }

    @Test
    @DisplayName("a body alone in a quiet place has nothing to weigh")
    void nothingToWeighWhenAloneAndUnafraid() {
        assertFalse(Comfort.worthWeighing(List.of(), DangerField.NONE),
                "four rolls to discover that one empty field is much like another");
        assertTrue(Comfort.worthWeighing(List.of(personAt(5, 5)), DangerField.NONE));
    }

    @Test
    @DisplayName("company's two sides are read off the ramp, not off a band written twice")
    void theCompanySideComesFromTheDeclaredLevels() {
        // The comfort stretch is wherever the declared pressure is zero — a fact about the levels
        // rather than a second copy of the band that could drift from them.
        assertEquals(-1, NeedKind.COMPANY.ramp().side(TestSpecies.PROFILE, 0.0));
        assertEquals(0, NeedKind.COMPANY.ramp().side(TestSpecies.PROFILE, 0.6));
        assertEquals(1, NeedKind.COMPANY.ramp().side(TestSpecies.PROFILE, 1.0));
    }

    @Test
    @DisplayName("somebody in your ribs is noticed here and now, not on the next lucky roll")
    void crowdingIsAskedAboutDirectly() {
        assertTrue(Comfort.crowded(HERE, List.of(personAt(1, 1))));
        assertFalse(Comfort.crowded(HERE, List.of(personAt(4, 4))));
        assertFalse(Comfort.crowded(HERE, List.of()));
    }
}
