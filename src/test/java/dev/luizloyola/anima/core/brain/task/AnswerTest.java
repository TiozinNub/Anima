package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.speech.Speech;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Coming when called: walk over, then talk. The stand-and-look beat this used to end on is gone —
 * {@link Converse} faces every tick on its own — so the walk now flows straight into the
 * conversation the hail was for.
 */
class AnswerTest {

    private final FakeContext ctx = new FakeContext();

    private List<Task> decompose(BeingId who, Pos where) {
        Answer answer = new Answer(who, where);
        Method only = answer.methods().get(0);
        assertTrue(only.applicable(ctx));
        return only.decompose(ctx);
    }

    @Test
    void decomposesIntoAWalkThenTheConversationTheHailWasFor() {
        BeingId caller = BeingId.of(UUID.randomUUID());
        Pos where = new Pos(6, 64, 0);

        List<Task> steps = decompose(caller, where);

        assertEquals(2, steps.size(), "the walk, then the talk — no beat stands between them");
        GoTo walk = assertInstanceOf(GoTo.class, steps.get(0));
        assertEquals(where.x(), walk.x());
        assertEquals(where.y(), walk.y());
        assertEquals(where.z(), walk.z());
        Converse converse = assertInstanceOf(Converse.class, steps.get(1));
        assertEquals(caller, converse.other());
        assertEquals(Speech.Opening.THEY_HAILED, converse.opening(),
                "the answerer only exists because it was hailed — never asked for again here");
    }

    @Test
    void theWalkTargetsWhereTheHailCarriedFrom() {
        BeingId caller = BeingId.of(UUID.randomUUID());
        Pos where = new Pos(-12, 70, 33);

        GoTo walk = assertInstanceOf(GoTo.class, decompose(caller, where).get(0));

        assertEquals(-12, walk.x());
        assertEquals(70, walk.y());
        assertEquals(33, walk.z());
    }
}
