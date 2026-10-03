package dev.luizloyola.anima.core.social.speech;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The open registry of speech acts — NeedKind's pattern. Anima declares only what a wolf
 * could say; asking a name is a consumer's word.
 */
public final class SpeechActs {

	private static final Map<String, SpeechAct> REGISTRY = new LinkedHashMap<>();

	public static final SpeechAct HAIL = register(new SpeechAct(
			"hail", "anima.speech.hail", 2, true, false, false, false, List.of()));
	public static final SpeechAct GREETING = register(new SpeechAct(
			"greeting", "anima.speech.greeting", 3, true, false, false, false, List.of()));
	public static final SpeechAct DEFLECT = register(new SpeechAct(
			"deflect", "anima.speech.deflect", 1, true, false, false, false, List.of()));
	/**
	 * The goodbye — a statement, never a proposal (decision: Luiz, 2026-09-13). It obliges AND
	 * ends: said once to leave, and once more by the other side to acknowledge, which is what
	 * closes the record. Nothing else answers it, and nobody can refuse to let somebody go.
	 */
	public static final SpeechAct END_CHAT = register(new SpeechAct(
			"end_chat", "anima.speech.end_chat", 4, true, true, false, true, List.of("end_chat")));
	/**
	 * Asking for food — what a wolf begs and a settler asks (2026-10-02-food-and-replies-design.md).
	 * Obliges; turning it down comes first, so a chooser with no rule of its own ({@link
	 * Choosers#BASIC}) refuses rather than offering nothing. Waited on twice a question's patience,
	 * thirty seconds by default: a player may be filling the give screen (Luiz, 2026-10-03).
	 */
	public static final SpeechAct ASK_FOOD = register(new SpeechAct(
			"ask_food", "anima.speech.ask_food", 2, true, true, false, false,
			List.of("cannot_spare", "offer", "give"), List.of(), 2));
	/** Nothing to spare: the answer to {@link #ASK_FOOD} that gives nothing. */
	public static final SpeechAct CANNOT_SPARE = register(new SpeechAct(
			"cannot_spare", "anima.speech.cannot_spare", 2, true, false, false, false, List.of()));
	/**
	 * Holding something out ({@link Handover}): the items ride the payload, and the giver holds the
	 * first of them in hand until the offer is answered.
	 */
	public static final SpeechAct OFFER = register(new SpeechAct(
			"offer", "anima.speech.offer", 2, true, true, false, false,
			List.of("accept_offer", "decline_offer")));
	/** Taking what was held out — the line the items move on. */
	public static final SpeechAct ACCEPT_OFFER = register(new SpeechAct(
			"accept_offer", "anima.speech.accept_offer", 2, true, false, false, false, List.of()));
	public static final SpeechAct DECLINE_OFFER = register(new SpeechAct(
			"decline_offer", "anima.speech.decline_offer", 2, true, false, false, false, List.of()));
	/**
	 * Something already handed over — a player's, through the give screen, where closing it is the
	 * hand-over (decision: Luiz, 2026-10-02). The items ride the payload as an offer's do, but are
	 * in the other's pack by the time it is said; what is owed back is the verdict on them.
	 */
	public static final SpeechAct GIVE = register(new SpeechAct(
			"give", "anima.speech.give", 2, true, true, false, false,
			List.of("accept_offer", "not_wanted")));
	/**
	 * What came was not what was wanted — not food, food with something else, or nothing asked for
	 * at all, as its topic. The unwanted items ride the payload, and the giver picks whether to take
	 * them back or leave them as a gift.
	 */
	public static final SpeechAct NOT_WANTED = register(new SpeechAct(
			"not_wanted", "anima.speech.not_wanted", 2, true, true, false, false,
			List.of("take_back", "keep_gift")));
	/** The giver takes back what {@link #NOT_WANTED} named — the line those items move on. */
	public static final SpeechAct TAKE_BACK = register(new SpeechAct(
			"take_back", "anima.speech.take_back", 2, true, false, false, false, List.of()));
	/** The giver leaves them: a gift, kept on the record for whatever later judges one. */
	public static final SpeechAct KEEP_GIFT = register(new SpeechAct(
			"keep_gift", "anima.speech.keep_gift", 2, true, false, false, false, List.of()));
	/** SYSTEM vocabulary — written by whoever notices, never chosen by a chooser. */
	public static final SpeechAct IGNORED = register(new SpeechAct(
			"ignored", "anima.speech.system.ignored", 1, false, false, false, true, List.of()));
	public static final SpeechAct STALE = register(new SpeechAct(
			"stale", "anima.speech.system.stale", 1, false, false, false, true, List.of()));
	/**
	 * A body's mind took the wheel back for something more pressing — a mob to flee, a fire —
	 * and the counterpart's seat noticed. Between settlers an interruption leaves the record open
	 * to resume; this is written only by a player's seat, which does not wait (2026-09-14).
	 */
	public static final SpeechAct INTERRUPTED = register(new SpeechAct(
			"interrupted", "anima.speech.system.interrupted", 1, false, false, false, true, List.of()));

	private SpeechActs() {
	}

	public static synchronized SpeechAct register(SpeechAct act) {
		SpeechAct prior = REGISTRY.putIfAbsent(act.key(), act);
		if (prior != null) {
			throw new IllegalStateException("speech act '" + act.key() + "' is already a word");
		}
		return act;
	}

	public static synchronized Optional<SpeechAct> byKey(String key) {
		return Optional.ofNullable(REGISTRY.get(key));
	}

	public static synchronized Collection<SpeechAct> all() {
		return Collections.unmodifiableCollection(REGISTRY.values());
	}
}
