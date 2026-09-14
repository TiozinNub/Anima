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
		assertTrue(SpeechActs.END_CHAT.obliges(), "a goodbye waits on its acknowledgement");
		assertTrue(SpeechActs.END_CHAT.ends());
		assertEquals(java.util.List.of("end_chat"), SpeechActs.END_CHAT.responses(),
				"the only answer to a goodbye is a goodbye");
		assertFalse(SpeechActs.IGNORED.negotiable(), "the world's lines are not proposals");
		assertTrue(SpeechActs.INTERRUPTED.ends(), "a body pulled away ends the record");
		assertFalse(SpeechActs.INTERRUPTED.obliges(), "and nobody owes an answer to it");
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

	@Test
	@DisplayName("an act is about nothing unless it says so; declared topics are copied")
	void topics() {
		assertTrue(SpeechActs.GREETING.topics().isEmpty(), "the eight-arg form declares none");
		java.util.List<String> declared = new java.util.ArrayList<>(java.util.List.of("rain"));
		SpeechAct about = new SpeechAct("test_about_copy", "anima.speech.test_about_copy", 1, true,
				false, false, false, java.util.List.of(), declared);
		declared.add("roads");
		assertEquals(java.util.List.of("rain"), about.topics());
	}

	/**
	 * A menu renders every offerable act as a button labelled {@code <langKey>.button}; a missing
	 * label is a raw lang key in a player's chat, and nothing at runtime would say so.
	 *
	 * <p>Three exemptions, each for a reason the registry itself cannot express. A SYSTEM verdict
	 * is never chosen. {@link SpeechActs#HAIL} is negotiable but structurally unofferable — the
	 * picker refuses it by identity ("the hail opens a record; it is not said"), so a label for it
	 * would be a string nothing can ever render. And a foreign namespace is somebody else's to
	 * translate: the registry is open, and sibling suites plus any consumer register into the same
	 * JVM-wide one.
	 */
	@Test
	@DisplayName("every act Anima can offer has a button label in en_us")
	void buttonLabels() {
		String lang = langSource();
		for (SpeechAct act : SpeechActs.all()) {
			if (!act.negotiable() || act == SpeechActs.HAIL
					|| !act.langKey().startsWith("anima.speech.")
					|| act.langKey().contains(".test")) {
				continue;
			}
			assertTrue(lang.contains("\"" + act.langKey() + ".button\""),
					act.key() + " has no " + act.langKey() + ".button label");
		}
	}

	private static String langSource() {
		String path = "/assets/anima/lang/en_us.json";
		try (java.io.InputStream in = SpeechActsTest.class.getResourceAsStream(path)) {
			assertNotNull(in, "missing resource " + path);
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new AssertionError("could not read " + path, e);
		}
	}
}
