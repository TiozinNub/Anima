package dev.luizloyola.anima.core.brain.instinct;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.FakePercepts;
import dev.luizloyola.anima.core.social.FakeGroupUp;
import dev.luizloyola.anima.core.social.GroupUp;
import dev.luizloyola.anima.core.social.GroupUps;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The home hail pulls as hard as the rule says, and only with somebody to go and ask. */
class SeekPartyInstinctTest {

    private final FakeContext ctx = new FakeContext();
    private final SeekPartyInstinct instinct = new SeekPartyInstinct();

    @AfterEach
    void uninstall() {
        GroupUps.install(GroupUp.NEVER);
    }

    @Test
    void aSpeciesThatNeverGroupsUpNeverGoesLooking() {
        ctx.percepts.beings = List.of(FakePercepts.personAt(new Pos(6, 64, 0), 6.0, ""));
        assertEquals(0.0, instinct.pressure(ctx));
    }

    @Test
    void itPullsAsHardAsTheRuleSaysWhileSomebodyIsWorthAsking() {
        Being stranger = FakePercepts.personAt(new Pos(6, 64, 0), 6.0, "");
        GroupUps.install(FakeGroupUp.seekingOnly(stranger.id().asPerson()));

        ctx.percepts.beings = List.of();
        assertEquals(0.0, instinct.pressure(ctx), "nobody in sight to ask");
        ctx.percepts.beings = List.of(stranger);
        assertEquals(0.4, instinct.pressure(ctx), 1e-9);
    }
}
