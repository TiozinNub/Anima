package dev.luizloyola.anima.core.social;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Going round the circle when somebody joins a party: who learns whom, and whether with a face.
 * Every newcomer and every member already there learn each other — in full when the learner can see
 * the other now, by name otherwise (group-up spec, decision 18). Newcomers who came together are left
 * as they were.
 */
public final class Circle {

    /** {@code learner} learns {@code whom}: with a face, or a name only. */
    public record Step(AgentId learner, AgentId whom, boolean face) {
    }

    private Circle() {
    }

    public static List<Step> round(List<AgentId> newcomers, List<AgentId> there,
            BiPredicate<AgentId, AgentId> knows, BiPredicate<AgentId, AgentId> sees) {
        List<Step> steps = new ArrayList<>();
        for (AgentId newcomer : newcomers) {
            for (AgentId member : there) {
                step(steps, newcomer, member, knows, sees);
                step(steps, member, newcomer, knows, sees);
            }
        }
        return steps;
    }

    private static void step(List<Step> steps, AgentId learner, AgentId whom,
            BiPredicate<AgentId, AgentId> knows, BiPredicate<AgentId, AgentId> sees) {
        if (!knows.test(learner, whom)) {
            steps.add(new Step(learner, whom, sees.test(learner, whom)));
        }
    }
}
