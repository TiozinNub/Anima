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
 * Who may read this world, who is asking, and what a guess costs. Every test drives the clock by
 * hand — the guards here are all timing, and a test that slept for them would be slow where it was
 * not flaky.
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

    // --- the queue meter -----------------------------------------------------------------------

    @Test
    @DisplayName("a browser nobody has seen joins the queue on its own — there is no door to open")
    void anUnknownBrowserJoinsTheQueue() {
        assertEquals(Outcome.ASKED, browsers.register(MINE, FROM, 0));
        assertEquals(List.of(MINE),
                browsers.waiting(0).stream().map(WebBrowsers.Waiting::key).toList());
        assertEquals(Outcome.WAITING, browsers.register(MINE, FROM, 100));
    }

    @Test
    @DisplayName("a full queue refuses the next name and charges it — the meter that replaced the door")
    void aFullQueueRefusesAndCharges() {
        for (int i = 0; i < WebBrowsers.MAX_QUEUED; i++) {
            assertEquals(Outcome.ASKED, browsers.register("guess-" + i, FROM, 100),
                    "the queue takes MAX_QUEUED names for free");
        }
        assertEquals(Outcome.REFUSED, browsers.register("guess-over", FROM, 100));
        assertEquals(WebBrowsers.MAX_QUEUED, browsers.waiting(100).size(),
                "a refused name must not reach the queue");

        // Charged, not merely refused. Without this the queue is an unmetered oracle: a guesser
        // pays nothing per attempt and the whole name space is days rather than decades.
        browsers.accept(MINE);
        assertEquals(Outcome.REFUSED, browsers.register(MINE, FROM, 200),
                "the refusal armed the lockout for everybody");
        assertEquals(Outcome.ACCEPTED,
                browsers.register(MINE, FROM, 100 + WebBrowsers.LOCKOUT_MILLIS));
    }

    @Test
    @DisplayName("a revoked browser is back in the waiting list on its next poll, and pays nothing")
    void revokingPutsItBackInTheQueue() {
        browsers.accept(MINE);
        assertTrue(browsers.revoke(MINE));

        // The page polls forever by design, so this is what revoke now means: back to waiting.
        assertEquals(Outcome.ASKED, browsers.register(MINE, FROM, 100));
        // And it must not be a miss — an operator who just revoked is usually about to act again.
        browsers.accept(THEIRS);
        assertEquals(Outcome.ACCEPTED, browsers.register(THEIRS, FROM, 200));
    }

    @Test
    @DisplayName("a browser already in the queue keeps its place however often it asks")
    void waitingIsIdempotent() {
        assertEquals(Outcome.ASKED, browsers.register(MINE, FROM, 100));
        assertEquals(Outcome.WAITING, browsers.register(MINE, FROM, 200));
        assertEquals(Outcome.WAITING, browsers.register(MINE, FROM, 300));
        assertEquals(1, browsers.waiting(300).size());
        assertEquals(100, browsers.waiting(300).get(0).askedAtMillis(), "it asked once");
    }

    @Test
    @DisplayName("polling while you wait never trips the lockout — that is what waiting looks like")
    void aWaitingBrowserIsNotAGuess() {
        browsers.register(MINE, FROM, 100);
        browsers.register(MINE, FROM, 200);
        // If the poll above had counted as a miss, the next name would be refused.
        assertEquals(Outcome.ASKED, browsers.register(THEIRS, FROM, 400));
    }

    // --- the lockout ----------------------------------------------------------------------------

    @Test
    @DisplayName("a guess costs three seconds, in which no key is even compared")
    void aMissLocksEveryoneOut() {
        // The attack this is priced against: hammering the API with candidate words until one
        // turns out to be a key somebody accepted. A name space in the hundreds of millions at one
        // guess per three seconds is decades.
        browsers.accept(MINE);
        assertEquals(Outcome.REFUSED, browsers.check("wrong-guess", 100));
        assertEquals(Outcome.REFUSED, browsers.register(MINE, FROM, 200),
                "an accepted key is refused too — comparing it first is the leak");
        assertEquals(Outcome.ACCEPTED, browsers.register(MINE, FROM, 100 + WebBrowsers.LOCKOUT_MILLIS));
    }

    @Test
    @DisplayName("a guesser gets one attempt every three seconds, however fast it asks")
    void everyNewGuessPaysAgain() {
        browsers.accept(MINE);
        browsers.check("guess-one", 100);
        // Hammering inside the lockout buys nothing and costs nothing: the key is never looked at,
        // so it cannot extend the wait either. The rate is what is capped, not the attacker.
        browsers.check("guess-two", 1_000);
        assertEquals(Outcome.ACCEPTED, browsers.register(MINE, FROM, 3_100));

        // A guess that waits out the lockout gets its one look, and pays for the next one.
        browsers.check("guess-three", 3_200);
        assertEquals(Outcome.REFUSED, browsers.register(MINE, FROM, 5_000));
        assertEquals(Outcome.ACCEPTED,
                browsers.register(MINE, FROM, 3_200 + WebBrowsers.LOCKOUT_MILLIS));
    }

    @Test
    @DisplayName("a browser polling with a key nobody accepted pays once, not once a poll")
    void aKeyPaysForItsMissOnce() {
        // The case this exists for: a tab whose key was revoked, or that has not been accepted
        // yet, polling every second or two.
        browsers.accept(MINE);
        browsers.check("revoked-tab", 100);
        browsers.check("revoked-tab", 3_200);
        assertEquals(Outcome.ACCEPTED, browsers.register(MINE, FROM, 3_300),
                "a repeat of the same key must not push the lockout out");

        // A key nobody has charged yet does: that is the guesser's bill.
        browsers.check("another-guess", 3_400);
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
            browsers.check("first-tab", now += 1_500);
            browsers.check("second-tab", now += 1_500);
        }
        assertEquals(Outcome.ACCEPTED, browsers.register(MINE, FROM, now + WebBrowsers.LOCKOUT_MILLIS),
                "each tab pays once; between them they cannot hold the lockout on");
    }

    // --- accepting ------------------------------------------------------------------------------

    @Test
    @DisplayName("accepting writes the key to the config and takes it out of the queue")
    void acceptingAdmits() {
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
        assertEquals(Outcome.ASKED, browsers.register(MINE, FROM, 10_100));
    }

    @Test
    @DisplayName("revoking something that was never accepted says so instead of pretending")
    void revokingWhatIsNotThere() {
        assertFalse(browsers.revoke(MINE));
        assertEquals(0, saves.get());
    }

    @Test
    @DisplayName("a live stream's key stops being accepted the moment it is revoked")
    void stillAcceptedFollowsTheList() {
        // What a parked stream asks between frames. It is the only guard on a request that
        // outlives its own handshake — without it a revoked browser streams until it reconnects.
        browsers.accept(MINE);
        assertTrue(browsers.stillAccepted(MINE));
        browsers.revoke(MINE);
        assertFalse(browsers.stillAccepted(MINE));
        assertFalse(browsers.stillAccepted(null));

        // And it charges nothing: the operator who just revoked is often about to accept another,
        // and a three-second lockout is the opposite of what they are doing.
        assertEquals(Outcome.ASKED, browsers.register(THEIRS, FROM, 1));
    }

    @Test
    @DisplayName("rejecting drops a browser from the queue without remembering it")
    void rejectingDrops() {
        browsers.register(MINE, FROM, 100);
        assertTrue(browsers.reject(MINE));
        assertTrue(browsers.waiting().isEmpty());
        assertFalse(browsers.reject(MINE), "it was already gone");

        assertEquals(Outcome.ASKED, browsers.register(MINE, FROM, 300));
    }

    // --- the queue ------------------------------------------------------------------------------

    @Test
    @DisplayName("a browser that stops asking leaves the queue")
    void theQueueExpires() {
        browsers.register(MINE, FROM, 100);
        browsers.register(THEIRS, FROM, 300);

        browsers.register(THEIRS, FROM, WebBrowsers.QUEUE_TTL_MILLIS); // still here, still asking
        assertEquals(List.of(THEIRS),
                browsers.waiting(WebBrowsers.QUEUE_TTL_MILLIS + 200).stream()
                        .map(WebBrowsers.Waiting::key).toList());
    }

    @Test
    @DisplayName("stopping the server forgets the queue, but not the grants")
    void clearKeepsTheAccepted() {
        browsers.accept(MINE);
        browsers.register(THEIRS, FROM, 100);

        browsers.clear();
        assertTrue(browsers.waiting().isEmpty());
        assertEquals(List.of(MINE), browsers.accepted(), "acceptance outlives the session");
    }

    // --- the other routes -----------------------------------------------------------------------

    @Test
    @DisplayName("a stream call never queues — asking to be let in is register's job alone")
    void checkNeverQueues() {
        assertEquals(Outcome.REFUSED, browsers.check(MINE, 100));
        assertTrue(browsers.waiting().isEmpty(),
                "only /api/register may put a name in the queue");
    }

    @Test
    @DisplayName("a browser waiting its turn gets a plain no from the other routes, not a miss")
    void aWaitingBrowserIsNotLockedOutByItsOwnImpatience() {
        browsers.register(MINE, FROM, 100);
        assertEquals(Outcome.WAITING, browsers.check(MINE, 300));

        browsers.accept(THEIRS);
        assertEquals(Outcome.ACCEPTED, browsers.check(THEIRS, 400),
                "polling the stream while you wait is not an attack");
    }

    @Test
    @DisplayName("a call with no key at all is a miss like any other")
    void noKeyIsAMiss() {
        browsers.accept(MINE);
        assertEquals(Outcome.REFUSED, browsers.check(null, 100));
        assertEquals(Outcome.REFUSED, browsers.check(MINE, 200),
                "the missing header armed the lockout");
    }
}
