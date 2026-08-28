package dev.luizloyola.anima.core.social.speech;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The registry is open (a consumer adds its acts) but a key registers once — like NeedKind. */
class SpeechActsTest {

	@Test
	@DisplayName("Anima's own vocabulary is registered: a wolf hails, greets and ends")
	void animaVocabulary() {
		assertSame(SpeechActs.GREETING, SpeechActs.byKey("greeting").orElseThrow());
		assertSame(SpeechActs.END_CHAT, SpeechActs.byKey("end_chat").orElseThrow());
		assertTrue(SpeechActs.REQUEST_END_CHAT.obliges(),
				"a proposal to end waits on an answer");
		assertTrue(SpeechActs.END_CHAT.ends());
		assertFalse(SpeechActs.IGNORED.negotiable(), "the world's lines are not proposals");
	}

	@Test
	@DisplayName("registering a duplicate key throws — one meaning per word")
	void duplicateThrows() {
		SpeechAct once = SpeechActs.register(new SpeechAct(
				"test_dup", "anima.speech.test_dup", 1, true, false, false, false, java.util.List.of()));
		assertNotNull(once);
		assertThrows(IllegalStateException.class, () -> SpeechActs.register(new SpeechAct(
				"test_dup", "anima.speech.test_dup", 1, true, false, false, false, java.util.List.of())));
	}

	@Test
	@DisplayName("an act's lang key is declared, not derived — the consumer owns its namespace")
	void langKeyDeclared() {
		assertEquals("anima.speech.greeting", SpeechActs.GREETING.langKey());
	}
}
