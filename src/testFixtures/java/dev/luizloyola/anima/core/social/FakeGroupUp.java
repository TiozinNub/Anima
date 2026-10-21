package dev.luizloyola.anima.core.social;

import dev.luizloyola.anima.core.agent.AgentId;

/** Group-up rules for tests. */
public final class FakeGroupUp {

    private FakeGroupUp() {
    }

    /** A rule that would have this body ask exactly {@code these}, and nobody else. */
    public static GroupUp seekingOnly(AgentId... these) {
        java.util.Set<AgentId> worth = java.util.Set.of(these);
        return new GroupUp() {
            @Override
            public java.util.Optional<Ask> ask(AgentId self, AgentId other) {
                return java.util.Optional.empty();
            }

            @Override
            public double seeking(AgentId self) {
                return 0.4;
            }

            @Override
            public boolean worthSeeking(AgentId self, AgentId other) {
                return worth.contains(other);
            }

            @Override
            public java.util.Optional<String> refusal(AgentId self, AgentId asker, Ask ask) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<Move> move(AgentId asker, AgentId asked, Ask ask) {
                return java.util.Optional.empty();
            }
        };
    }
}
