package dev.luizloyola.anima.core.social.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.history.Slot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HandoverTest {

    private final AgentId giver = AgentId.random();
    private final AgentId taker = AgentId.random();

    @Test
    @DisplayName("an offer's items survive the payload, and its line names the first of them")
    void payloadRoundTrips() {
        List<Handover.Item> items = List.of(new Handover.Item("minecraft:bread", 3),
                new Handover.Item("minecraft:cooked_beef", 1));
        Map<String, String> payload = Handover.payload(items);

        assertEquals(items, Handover.read(payload));
        assertEquals(Optional.of(List.of(Slot.item("minecraft:bread"))), LineSlots.of(payload));
    }

    @Test
    @DisplayName("an offer of nothing is refused, and an unreadable payload holds out nothing")
    void nothingIsNothing() {
        assertThrows(IllegalArgumentException.class, () -> Handover.payload(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Handover.Item("minecraft:bread", 0));
        assertEquals(List.of(), Handover.read(Map.of()));
        assertEquals(List.of(), Handover.read(Map.of(Handover.ITEMS, "minecraft:bread*lots")));
        assertEquals(List.of(), Handover.read(Map.of(Handover.ITEMS, "*3")));
    }

    @Test
    @DisplayName("a pending offer is held out by its giver and offered to the other — until answered")
    void pendingOfferIsHeldOut() {
        Encounter e = new Encounter(UUID.randomUUID(), List.of(giver, taker), 0);
        Utterance offer = new Utterance(giver, SpeechActs.OFFER.key(),
                Handover.payload(List.of(new Handover.Item("minecraft:bread", 2))), 1);
        e.append(offer);

        assertEquals(Optional.of(offer), Handover.offerTo(e, taker));
        assertEquals(Optional.of(offer), Handover.heldOutBy(e, giver));
        assertTrue(Handover.heldOutBy(e, taker).isEmpty());

        e.append(new Utterance(taker, SpeechActs.ACCEPT_OFFER.key(), Map.of(), 2));
        assertTrue(Handover.heldOutBy(e, giver).isEmpty(), "answered: the hand comes back");
        assertEquals(Optional.of(offer), Handover.accepted(e, 1), "what the accept took");
    }

    @Test
    @DisplayName("an accept answers the latest offer of somebody else's, never its taker's own")
    void acceptAnswersTheirOffer() {
        Encounter e = new Encounter(UUID.randomUUID(), List.of(giver, taker), 0);
        Utterance first = new Utterance(giver, SpeechActs.OFFER.key(),
                Handover.payload(List.of(new Handover.Item("minecraft:apple", 1))), 1);
        e.append(first);
        e.append(new Utterance(taker, SpeechActs.DECLINE_OFFER.key(), Map.of(), 2));
        Utterance second = new Utterance(giver, SpeechActs.OFFER.key(),
                Handover.payload(List.of(new Handover.Item("minecraft:bread", 1))), 3);
        e.append(second);
        e.append(new Utterance(taker, SpeechActs.ACCEPT_OFFER.key(), Map.of(), 4));

        assertEquals(Optional.of(second), Handover.accepted(e, 3));
    }

    @Test
    @DisplayName("a thank-you for something already given answers no offer — nothing moves twice")
    void thanksForAGiftMovesNothing() {
        Encounter e = new Encounter(UUID.randomUUID(), List.of(giver, taker), 0);
        e.append(new Utterance(giver, SpeechActs.OFFER.key(),
                Handover.payload(List.of(new Handover.Item("minecraft:apple", 1))), 1));
        e.append(new Utterance(taker, SpeechActs.DECLINE_OFFER.key(), Map.of(), 2));
        e.append(new Utterance(giver, SpeechActs.GIVE.key(),
                Handover.payload(List.of(new Handover.Item("minecraft:bread", 2))), 3));
        e.append(new Utterance(taker, SpeechActs.ACCEPT_OFFER.key(), Map.of(), 4));

        assertTrue(Handover.accepted(e, 3).isEmpty(),
                "the give was owed, not the old apple — and the bread already moved on the give");
    }

    @Test
    @DisplayName("a take-back answers the not-wanted pending on its speaker")
    void takeBackAnswersTheNotWanted() {
        Encounter e = new Encounter(UUID.randomUUID(), List.of(giver, taker), 0);
        e.append(new Utterance(giver, SpeechActs.GIVE.key(),
                Handover.payload(List.of(new Handover.Item("minecraft:dirt", 8))), 1));
        Utterance unwanted = new Utterance(taker, SpeechActs.NOT_WANTED.key(),
                Handover.payload(List.of(new Handover.Item("minecraft:dirt", 8))), 2);
        e.append(unwanted);
        e.append(new Utterance(giver, SpeechActs.TAKE_BACK.key(), Map.of(), 3));

        assertEquals(Optional.of(unwanted), Handover.takenBack(e, 2));
        assertTrue(Handover.accepted(e, 2).isEmpty());
    }
}
