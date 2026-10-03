package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.history.Slot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Items changing hands in a conversation (2026-10-02-food-and-replies-design.md): what an
 * {@link SpeechActs#OFFER} holds out rides in its payload, and the transcript is the whole state.
 * A pending offer is the giver holding it out; the {@link SpeechActs#ACCEPT_OFFER} that answers it
 * is the moment it moves. Until then the items stay in the giver's pack, so a restart mid-hand-over
 * loses nothing and holds nothing in between.
 */
public final class Handover {

    /** Payload key of the items an offer holds out, {@code id*count} joined by {@code |}. */
    public static final String ITEMS = "items";

    /** Within this many blocks a hand reaches another's. */
    public static final double REACH = 2.0;

    /** One kind of item and how many. */
    public record Item(String id, int count) {
        public Item {
            Objects.requireNonNull(id, "id");
            if (id.isEmpty() || id.contains("|") || id.contains("*") || count < 1) {
                throw new IllegalArgumentException("not an item to hand over: " + id + " x" + count);
            }
        }
    }

    private Handover() {
    }

    /**
     * An offer's payload: the items, and the first of them as the line's one slot, so its words can
     * name what is held out.
     */
    public static Map<String, String> payload(List<Item> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("an offer of nothing");
        }
        List<String> parts = new ArrayList<>();
        for (Item item : items) {
            parts.add(item.id() + "*" + item.count());
        }
        Map<String, String> out = new HashMap<>(LineSlots.with(Map.of(),
                List.of(Slot.item(items.get(0).id()))));
        out.put(ITEMS, String.join("|", parts));
        return Map.copyOf(out);
    }

    /** The items a payload holds out; empty for a payload that names none or cannot be read. */
    public static List<Item> read(Map<String, String> payload) {
        String raw = payload.get(ITEMS);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Item> out = new ArrayList<>();
        for (String part : raw.split("\\|")) {
            int star = part.lastIndexOf('*');
            if (star <= 0) {
                return List.of();
            }
            try {
                out.add(new Item(part.substring(0, star), Integer.parseInt(part.substring(star + 1))));
            } catch (IllegalArgumentException unreadable) {
                return List.of();
            }
        }
        return List.copyOf(out);
    }

    /** The offer {@code taker} would be answering: one of somebody else's, still pending on them. */
    public static Optional<Utterance> offerTo(Encounter e, AgentId taker) {
        return Picker.pendingOn(e, taker).filter(u -> u.act().equals(SpeechActs.OFFER.key()));
    }

    /** {@code giver}'s own offer, held out and not yet answered by anybody. */
    public static Optional<Utterance> heldOutBy(Encounter e, AgentId giver) {
        for (AgentId other : e.participants()) {
            if (!other.equals(giver)) {
                Optional<Utterance> offer = offerTo(e, other)
                        .filter(u -> giver.equals(u.author()));
                if (offer.isPresent()) {
                    return offer;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The offer an {@link SpeechActs#ACCEPT_OFFER} said at {@code index} answered: the one pending
     * on its speaker at that moment. An accept of a {@link SpeechActs#GIVE} — a thank-you for items
     * already moved — answers no offer, and nothing moves on it.
     */
    public static Optional<Utterance> accepted(Encounter e, int index) {
        return pendingAt(e, index).filter(u -> u.act().equals(SpeechActs.OFFER.key()));
    }

    /** The {@link SpeechActs#NOT_WANTED} a {@link SpeechActs#TAKE_BACK} said at {@code index} answered. */
    public static Optional<Utterance> takenBack(Encounter e, int index) {
        return pendingAt(e, index).filter(u -> u.act().equals(SpeechActs.NOT_WANTED.key()));
    }

    /**
     * What was owed by whoever said line {@code index}, just before they said it — {@link
     * Picker#pendingOn}'s reading of the record as it stood then.
     */
    private static Optional<Utterance> pendingAt(Encounter e, int index) {
        List<Utterance> lines = e.transcript();
        AgentId speaker = lines.get(index).author();
        for (int i = index - 1; i >= 0 && speaker != null; i--) {
            Utterance u = lines.get(i);
            if (u.system()) {
                continue;
            }
            if (speaker.equals(u.author())) {
                return Optional.empty();
            }
            if (SpeechActs.byKey(u.act()).map(SpeechAct::obliges).orElse(false)) {
                return Optional.of(u);
            }
        }
        return Optional.empty();
    }
}
