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
	public static final SpeechAct REQUEST_END_CHAT = register(new SpeechAct(
			"request_end_chat", "anima.speech.request_end_chat", 2, true, true, false, false, List.of()));
	public static final SpeechAct END_CHAT = register(new SpeechAct(
			"end_chat", "anima.speech.end_chat", 2, true, false, false, true, List.of()));
	/** SYSTEM vocabulary — written by whoever notices, never chosen by a chooser. */
	public static final SpeechAct IGNORED = register(new SpeechAct(
			"ignored", "anima.speech.system.ignored", 1, false, false, false, true, List.of()));
	public static final SpeechAct STALE = register(new SpeechAct(
			"stale", "anima.speech.system.stale", 1, false, false, false, true, List.of()));

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
