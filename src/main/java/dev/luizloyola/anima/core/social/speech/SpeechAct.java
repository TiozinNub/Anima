package dev.luizloyola.anima.core.social.speech;

import java.util.List;
import java.util.Objects;

/**
 * One word of the conversational vocabulary — data, not behaviour. Machinery is Anima's, the
 * vocabulary is the consumer's; the flags are what the machinery may act on without knowing
 * what the act means:
 * {@code obliges} puts an obligation on the addressee (ignored past patience is a snub);
 * {@code introduces} gives the speaker's name away — addressee and eligible bystanders learn;
 * {@code ends} closes the record; {@code negotiable} distinguishes proposals from verdicts.
 * {@code responses} constrains what discharges the obligation when non-empty; empty means any
 * reply does (REQUEST_END_CHAT: raise a new topic or say goodbye).
 * {@code topics} is what a topic-bearing act may be ABOUT — the payload keys a speaker with no
 * gauges to read picks from; empty for every act that carries no topic.
 */
public record SpeechAct(String key, String langKey, int variants, boolean negotiable,
		boolean obliges, boolean introduces, boolean ends, List<String> responses,
		List<String> topics) {

	public SpeechAct {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(langKey, "langKey");
		responses = List.copyOf(responses);
		topics = List.copyOf(topics);
		if (variants < 1) {
			throw new IllegalArgumentException(key + " needs at least one line to say");
		}
	}

	/** An act about nothing in particular — every word declared before topics existed. */
	public SpeechAct(String key, String langKey, int variants, boolean negotiable,
			boolean obliges, boolean introduces, boolean ends, List<String> responses) {
		this(key, langKey, variants, negotiable, obliges, introduces, ends, responses, List.of());
	}
}
