package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.social.speech.Chooser;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Handover;
import dev.luizloyola.anima.core.social.speech.Speech;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.Utterance;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Holding an offer out, and stepping up to take one (2026-10-02-food-and-replies-design.md). */
class ConverseHandoverTest {

    private final FakeContext ctx = new FakeContext();
    private final BeingId otherId = BeingId.of(AgentId.random());
    private final Map<String, String> bread = Handover.payload(List.of(new Handover.Item("minecraft:bread", 3)));

    private Encounter at(double distance) {
        ctx.percepts.beings = List.of(FakePercepts.personAt(otherId, new Pos((int) distance, 64, 0), distance, "Rex"));
        return ctx.speech.join(otherId, Speech.Opening.QUIET).orElseThrow();
    }

    @Test
    @DisplayName("offered something from across the chat, it steps up to take it before a word")
    void stepsUpToTakeAnOffer() {
        Encounter e = at(6.0);
        e.append(new Utterance(otherId.asPerson(), SpeechActs.OFFER.key(), bread, 0));
        ctx.speech.chooser = (c, turn) -> Chooser.Line.of(SpeechActs.ACCEPT_OFFER);
        ctx.percepts.time = 100;
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);

        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));
        assertTrue(ctx.speech.saidLines.isEmpty(), "out of reach: nothing is taken yet");
        assertEquals(1, ctx.mover.moveToCalls, "it walks to them");
        assertEquals(6, ctx.mover.lastX);

        ctx.percepts.beings = List.of(FakePercepts.personAt(otherId, new Pos(1, 64, 0), 1.5, "Rex"));
        assertEquals(TaskStatus.RUNNING, converse.tick(ctx));
        assertEquals(SpeechActs.ACCEPT_OFFER.key(), ctx.speech.saidLines.get(0).act(), "within reach: taken");
    }

    @Test
    @DisplayName("with no offer pending it talks from across the chat as before")
    void noOfferNoStep() {
        at(6.0);
        ctx.speech.chooser = (c, turn) -> Chooser.Line.of(SpeechActs.GREETING);
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);

        converse.tick(ctx);

        assertEquals(0, ctx.mover.moveToCalls);
        assertEquals(1, ctx.speech.saidLines.size());
    }

    @Test
    @DisplayName("its own offer is held in hand until it is answered, then put away")
    void holdsItsOfferOut() {
        Encounter e = at(1.5);
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:stick", 1, 64));
        ctx.percepts.inventory.set(20, ItemStack.of("minecraft:bread", 3, 64));
        e.append(new Utterance(ctx.self, SpeechActs.OFFER.key(), bread, 0));
        ctx.speech.chooser = (c, turn) -> null;
        Converse converse = new Converse(otherId, Speech.Opening.QUIET);

        for (int t = 1; t <= 60 && !ctx.percepts.inventory.mainHand().id().equals("minecraft:bread"); t++) {
            ctx.percepts.time = t;
            converse.tick(ctx);
        }
        assertEquals("minecraft:bread", ctx.percepts.inventory.mainHand().id(), "held out");

        e.append(new Utterance(otherId.asPerson(), SpeechActs.DECLINE_OFFER.key(), Map.of(), 61));
        for (int t = 62; t <= 120 && !ctx.percepts.inventory.mainHand().isEmpty(); t++) {
            ctx.percepts.time = t;
            converse.tick(ctx);
        }
        assertTrue(ctx.percepts.inventory.mainHand().isEmpty(), "answered: the hand is put away");
    }
}
