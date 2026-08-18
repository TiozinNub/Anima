package dev.luizloyola.anima.mod.webdebug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.mod.webdebug.WebBrowsers.Admission;
import dev.luizloyola.anima.mod.webdebug.WebBrowsers.Outcome;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The door. Every test drives the clock by hand — the guards here are all timing, and a test that
 * slept for them would be slow where it was not flaky.
 */
class WebBrowsersTest {

    private static final String MINE = "jolly-otter";
    private static final String THEIRS = "quiet-badger";
    private static final String FROM = "127.0.0.1";

    private final AtomicInteger saves = new AtomicInteger();
    private WebBrowsers browsers;

    @BeforeEach
    void freshInstallation() {
        Config.reset();
        browsers = new WebBrowsers(saves::incrementAndGet);
    }

    @AfterEach
    void clearConfig() {
        Config.reset();
    }

    // --- the shape ------------------------------------------------------------------------------

    @Test
    @DisplayName("a key is hyphenated lower-case words and nothing else")
    void keyShape() {
        for (String good : List.of("jolly-otter", "quiet-badger-7", "a-b", "one-two-three-four")) {
            assertTrue(WebBrowsers.wellFormed(good), good);
        }
        // It goes into a chat line, a TOML key and a log line — anything that would need escaping,
        // or that could be mistaken for a command, is not a key.
        for (String bad : List.of("", "jollyotter", "-otter", "otter-", "Jolly-Otter", "jolly otter",
                "jolly--otter", "jolly.otter", "jolly_otter", "jolly-otter/../etc", "§4otter")) {
            assertFalse(WebBrowsers.wellFormed(bad), bad);
        }
        assertFalse(WebBrowsers.wellFormed("a-" + "b".repeat(WebBrowsers.MAX_KEY_LENGTH)),
                "a key nobody could retype is not a key");
    }

    // --- the door -------------------------------------------------------------------------------

    @Test
    @DisplayName("a browser nobody asked for is refused, and asking again does not help")
    void aShutDoorRefuses() {
        assertEquals(Outcome.REFUSED, browsers.register(MINE, FROM, 0));
        assertEquals(Outcome.REFUSED, browsers.register(MINE, FROM, 60_000));
        assertTrue(browsers.waiting().isEmpty(), "a refused browser must not reach the queue");
    }

    @Test
    @DisplayName("an operator opens the door, one browser comes through, and it shuts behind them")
    void oneThroughAndItShuts() {
        browsers.open(0);
        assertEquals(Outcome.ASKED, browsers.register(MINE, FROM, 100));
        assertEquals(List.of(MINE),
                browsers.waiting(100).stream().map(WebBrowsers.Waiting::key).toList());

        // Still inside the minute, and still refused: the door closes on the first arrival, not on
        // the clock. That is what keeps an open door from being a window for a guesser.
        assertEquals(Outcome.REFUSED, browsers.register(THEIRS, FROM, 200));
        assertFalse(browsers.isOpen(200));
    }

    @Test
    @DisplayName("an open door that nobody uses shuts on its own")
    void theDoorTimesOut() {
        browsers.open(0);
        assertTrue(browsers.isOpen(WebBrowsers.OPEN_MILLIS - 1));
        assertFalse(browsers.isOpen(WebBrowsers.OPEN_MILLIS));
        assertEquals(Outcome.REFUSED, browsers.register(MINE, FROM, WebBrowsers.OPEN_MILLIS));
    }

    @Test
    @DisplayName("a browser already in the queue keeps its place however often it asks")
    void waitingIsIdempotent() {
        browsers.open(0);
        assertEquals(Outcome.ASKED, browsers.register(MINE, FROM, 100));
        assertEquals(Outcome.WAITING, browsers.register(MINE, FROM, 200));
        assertEquals(Outcome.WAITING, browsers.register(MINE, FROM, 300));
        assertEquals(1, browsers.waiting(300).size());
        assertEquals(100, browsers.waiting(300).get(0).askedAtMillis(), "it asked once");
    }

    @Test
    @DisplayName("polling while you wait never trips the lockout — that is what waiting looks like")
    void aWaitingBrowserIsNotAGuess() {
        browsers.open(0);
        browsers.register(MINE, FROM, 100);
        browsers.register(MINE, FROM, 200);
        browsers.open(300);
        // If the poll above had counted as a miss, this door would be shut before it opened.
        assertEquals(Outcome.ASKED, browsers.register(THEIRS, FROM, 400));
    }

    // --- the lockout ----------------------------------------------------------------------------

    @Test
    @DisplayName("a guess costs three seconds, in which no key is even compared")
    void aMissLocksEveryoneOut() {
        // The attack this is priced against: hammering /api/register with candidate words until
        // one turns out to be a key somebody accepted. Twenty bits at one guess per three seconds
        // is centuries.
        browsers.accept(MINE);
        assertEquals(Outcome.REFUSED, browsers.register("wrong-guess", FROM, 100));
        assertEquals(Outcome.REFUSED, browsers.register(MINE, FROM, 200),
                "an accepted key is refused too — comparing it first is the leak");
        assertEquals(Outcome.ACCEPTED, browsers.register(MINE, FROM, 100 + WebBrowsers.LOCKOUT_MILLIS));
    }

    @Test
    @DisplayName("a guesser gets one attempt every three seconds, however fast it asks")
    void everyNewGuessPaysAgain() {
        browsers.accept(MINE);
        browsers.register("guess-one", FROM, 100);
        // Hammering inside the lockout buys nothing and costs nothing: the key is never looked at,
        // so it cannot extend the wait either. The rate is what is capped, not the attacker.
        browsers.register("guess-two", FROM, 1_000);
        assertEquals(Outcome.ACCEPTED, browsers.register(MINE, FROM, 3_100));

        // A guess that waits out the lockout gets its one look, and pays for the next one.
        browsers.register("guess-three", FROM, 3_200);
        assertEquals(Outcome.REFUSED, browsers.register(MINE, FROM, 5_000));
        assertEquals(Outcome.ACCEPTED,
                browsers.register(MINE, FROM, 3_200 + WebBrowsers.LOCKOUT_MILLIS));
    }

    @Test
    @DisplayName("a browser polling with a key nobody accepted pays once, not once a poll")
    void aKeyPaysForItsMissOnce() {
        // The case this exists for: a tab whose key was revoked, or that is asking before anyone
        // opened the door, keeps asking every second or two.
        browsers.accept(MINE);
        browsers.register("revoked-tab", FROM, 100);
        browsers.register("revoked-tab", FROM, 3_200);
        assertEquals(Outcome.ACCEPTED, browsers.register(MINE, FROM, 3_300),
                "a repeat of the same key must not push the lockout out");

        // A key nobody has charged yet does: that is the guesser's bill.
        browsers.register("another-guess", FROM, 3_400);
        assertEquals(Outcome.REFUSED, browsers.register(MINE, FROM, 3_500));
    }

    @Test
    @DisplayName("two browsers polling unaccepted keys cannot lock an accepted one out between them")
    void twoPollersDoNotAlternateTheLockoutOpen() {
        // Found with a real page open, against the first shape of this, which charged a miss
        // whenever the key differed from the one before it. Two tabs then take turns re-arming
        // the lockout forever, and the browser that IS accepted is refused most of the time.
        browsers.accept(MINE);
        long now = 0;
        for (int round = 0; round < 5; round++) {
            browsers.register("first-tab", FROM, now += 1_500);
            browsers.register("second-tab", FROM, now += 1_500);
        }
        assertEquals(Outcome.ACCEPTED, browsers.register(MINE, FROM, now + WebBrowsers.LOCKOUT_MILLIS),
                "each tab pays once; between them they cannot hold the lockout on");
    }

    @Test
    @DisplayName("opening the door clears a lockout — the operator is the only one who can")
    void openingClearsTheLockout() {
        browsers.open(0);
        browsers.register("wrong-guess", FROM, 100);
        browsers.open(200);
        assertEquals(Outcome.ASKED, browsers.register(MINE, FROM, 300));
    }

    // --- accepting ------------------------------------------------------------------------------

    @Test
    @DisplayName("accepting writes the key to the config and takes it out of the queue")
    void acceptingAdmits() {
        browsers.open(0);
        browsers.register(MINE, FROM, 100);

        assertEquals(Admission.ADDED, browsers.accept(MINE));
        assertEquals(List.of(MINE), browsers.accepted());
        assertTrue(browsers.waiting().isEmpty());
        assertEquals(1, saves.get(), "the grant has to survive a restart");

        assertEquals(Outcome.ACCEPTED, browsers.register(MINE, FROM, 200));
        assertEquals(Outcome.ACCEPTED, browsers.check(MINE, 200));
    }

    @Test
    @DisplayName("a key can be accepted before it has ever asked — read off the screen and typed")
    void acceptingWorksAheadOfTime() {
        assertEquals(Admission.ADDED, browsers.accept(MINE));
        // And the door stays irrelevant to it: it was admitted by name, not let in through one.
        assertEquals(Outcome.ACCEPTED, browsers.register(MINE, FROM, 100));
    }

    @Test
    @DisplayName("accepting the same browser twice changes nothing and writes nothing")
    void acceptingIsIdempotent() {
        browsers.accept(MINE);
        assertEquals(Admission.ALREADY, browsers.accept(MINE));
        assertEquals(List.of(MINE), browsers.accepted());
        assertEquals(1, saves.get());
    }

    @Test
    @DisplayName("a key the mod would never issue cannot be typed into the accepted list")
    void acceptingChecksTheShape() {
        assertEquals(Admission.MALFORMED, browsers.accept("not a key"));
        assertTrue(browsers.accepted().isEmpty());
        assertEquals(0, saves.get());
    }

    @Test
    @DisplayName("two browsers can be accepted at once — that is the whole point of a list")
    void severalBrowsersAreAllowed() {
        browsers.accept(MINE);
        browsers.accept(THEIRS);
        assertEquals(List.of(MINE, THEIRS), browsers.accepted());
        assertEquals(Outcome.ACCEPTED, browsers.check(THEIRS, 0));
    }

    // --- taking it back -------------------------------------------------------------------------

    @Test
    @DisplayName("revoking shuts a browser out, and it may ask again through an open door")
    void revokingForgets() {
        browsers.accept(MINE);
        assertTrue(browsers.revoke(MINE));
        assertTrue(browsers.accepted().isEmpty());
        assertEquals(Outcome.REFUSED, browsers.check(MINE, 0));

        // Forgotten, not blocked: there is no memory of a refusal, so the ordinary path works.
        browsers.open(10_000);
        assertEquals(Outcome.ASKED, browsers.register(MINE, FROM, 10_100));
    }

    @Test
    @DisplayName("revoking something that was never accepted says so instead of pretending")
    void revokingWhatIsNotThere() {
        assertFalse(browsers.revoke(MINE));
        assertEquals(0, saves.get());
    }

    @Test
    @DisplayName("rejecting drops a browser from the queue without remembering it")
    void rejectingDrops() {
        browsers.open(0);
        browsers.register(MINE, FROM, 100);
        assertTrue(browsers.reject(MINE));
        assertTrue(browsers.waiting().isEmpty());
        assertFalse(browsers.reject(MINE), "it was already gone");

        browsers.open(200);
        assertEquals(Outcome.ASKED, browsers.register(MINE, FROM, 300));
    }

    // --- the queue ------------------------------------------------------------------------------

    @Test
    @DisplayName("a browser that stops asking leaves the queue")
    void theQueueExpires() {
        browsers.open(0);
        browsers.register(MINE, FROM, 100);
        browsers.open(200);
        browsers.register(THEIRS, FROM, 300);

        browsers.register(THEIRS, FROM, WebBrowsers.QUEUE_TTL_MILLIS); // still here, still asking
        assertEquals(List.of(THEIRS),
                browsers.waiting(WebBrowsers.QUEUE_TTL_MILLIS + 200).stream()
                        .map(WebBrowsers.Waiting::key).toList());
    }

    @Test
    @DisplayName("stopping the server forgets the queue and shuts the door, but not the grants")
    void clearKeepsTheAccepted() {
        browsers.accept(MINE);
        browsers.open(0);
        browsers.register(THEIRS, FROM, 100);

        browsers.clear();
        assertTrue(browsers.waiting().isEmpty());
        assertFalse(browsers.isOpen(200));
        assertEquals(List.of(MINE), browsers.accepted(), "acceptance outlives the session");
    }

    // --- the other routes -----------------------------------------------------------------------

    @Test
    @DisplayName("a stream call never queues — asking to be let in is register's job alone")
    void checkNeverQueues() {
        browsers.open(0);
        assertEquals(Outcome.REFUSED, browsers.check(MINE, 100));
        assertTrue(browsers.waiting().isEmpty(),
                "an open door is for /api/register; nothing else may put a key in the queue");
    }

    @Test
    @DisplayName("a browser waiting its turn gets a plain no from the other routes, not a miss")
    void aWaitingBrowserIsNotLockedOutByItsOwnImpatience() {
        browsers.open(0);
        browsers.register(MINE, FROM, 100);
        browsers.open(200);
        assertEquals(Outcome.WAITING, browsers.check(MINE, 300));
        assertTrue(browsers.isOpen(300), "polling the stream while you wait is not an attack");
    }

    @Test
    @DisplayName("a call with no key at all is a miss like any other")
    void noKeyIsAMiss() {
        browsers.open(0);
        assertEquals(Outcome.REFUSED, browsers.check(null, 100));
        assertFalse(browsers.isOpen(200), "the missing header armed the lockout");
    }
}
