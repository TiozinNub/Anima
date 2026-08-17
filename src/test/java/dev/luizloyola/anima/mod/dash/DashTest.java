package dev.luizloyola.anima.mod.dash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parts of the dashboard that hold without a world: the tick-to-HTTP hand-off, the watch, and
 * the two guards that are the whole reason a loopback socket is safe to open.
 *
 * <p>The frame builder itself needs a running server and is exercised in-game.
 */
class DashTest {

    // --- the hand-off ---------------------------------------------------------------------------

    @Test
    @DisplayName("a reader parked on the feed is woken by the next publish, and sees that frame")
    void publishWakesAReader() throws Exception {
        DashFeed feed = new DashFeed();
        feed.publish("{\"tick\":0}");
        long caughtUp = feed.version();

        AtomicReference<DashFeed.Snapshot> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try {
                got.set(feed.awaitAfter(caughtUp, 30_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        reader.setDaemon(true);
        reader.start();
        Thread.sleep(50); // let it reach the wait; publishing first would pass without parking

        feed.publish("{\"tick\":1}");
        assertTrue(done.await(5, TimeUnit.SECONDS), "the reader was never woken");
        assertNotNull(got.get());
        assertEquals("{\"tick\":1}", got.get().json());
    }

    @Test
    @DisplayName("before the first frame there is nothing to send — the keepalive path, not a block")
    void nothingToSendBeforeTheFirstFrame() throws Exception {
        // A browser can connect between the server starting and the first cadence tick. It must
        // get a keepalive and loop, not a frame of null.
        assertNull(new DashFeed().awaitAfter(-1, 50));
    }

    @Test
    @DisplayName("a reader that already saw the latest version waits rather than re-sending it")
    void anUpToDateReaderWaits() throws Exception {
        DashFeed feed = new DashFeed();
        feed.publish("{\"tick\":1}");
        long version = feed.version();
        // Nothing new: this is the keepalive path, and re-sending here would spin the socket.
        assertNull(feed.awaitAfter(version, 50));
    }

    @Test
    @DisplayName("closing releases parked readers instead of leaking their threads")
    void closeReleasesReaders() throws Exception {
        DashFeed feed = new DashFeed();
        feed.publish("{\"tick\":1}");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<DashFeed.Snapshot> got = new AtomicReference<>(
                new DashFeed.Snapshot(-1, "sentinel"));
        Thread reader = new Thread(() -> {
            try {
                got.set(feed.awaitAfter(feed.version(), 30_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        reader.setDaemon(true);
        reader.start();
        Thread.sleep(50);
        feed.close();
        assertTrue(done.await(5, TimeUnit.SECONDS), "close did not release the reader");
        assertNull(got.get());
    }

    // --- the watch ------------------------------------------------------------------------------

    @Test
    @DisplayName("expanding and collapsing a card is the whole of what the frame builder reads")
    void watchTogglesOneAgent() {
        AgentId one = new AgentId(UUID.randomUUID());
        AgentId two = new AgentId(UUID.randomUUID());
        DashWatch watch = DashWatch.NONE.toggled(one, true).toggled(two, true);
        assertTrue(watch.isExpanded(one));
        assertTrue(watch.isExpanded(two));

        DashWatch collapsed = watch.toggled(one, false);
        assertFalse(collapsed.isExpanded(one));
        assertTrue(collapsed.isExpanded(two), "collapsing one card closed another");
        assertTrue(watch.isExpanded(one), "the original was mutated — it must be copy-on-write");
    }

    @Test
    @DisplayName("choosing who to act as leaves the expansion alone")
    void actingAsIsIndependentOfExpansion() {
        AgentId one = new AgentId(UUID.randomUUID());
        UUID player = UUID.randomUUID();
        DashWatch watch = DashWatch.NONE.toggled(one, true).actingAs(player);
        assertEquals(player, watch.actingAs());
        assertTrue(watch.isExpanded(one));
        assertNull(watch.actingAs(null).actingAs());
    }

    // --- the guards -----------------------------------------------------------------------------

    @Test
    @DisplayName("only a loopback Host is served — this is what a DNS-rebinding attempt fails")
    void hostCheckAcceptsOnlyLoopback() {
        for (String good : List.of("127.0.0.1:25599", "localhost:25599", "LOCALHOST:25599",
                "[::1]:25599", "127.0.0.1")) {
            assertTrue(DashServer.isLoopbackHost(good), good);
        }
        // A name that resolves to 127.0.0.1 gets the browser to send the request; what it cannot
        // do is forge the header the request arrives with.
        for (String bad : List.of("evil.example:25599", "anima-debugger.tioz.in",
                "127.0.0.1.evil.example:25599", "192.168.1.5:25599", "")) {
            assertFalse(DashServer.isLoopbackHost(bad), bad);
        }
    }

    @Test
    @DisplayName("the CSP origin fails closed — a knob holding nonsense must not widen the policy")
    void cspOriginFailsClosed() {
        assertEquals("https://anima-debugger.tioz.in",
                DashServer.originOf("https://anima-debugger.tioz.in/app.v1.js"));
        assertEquals("http://localhost:5173", DashServer.originOf("http://localhost:5173/app.js"));
        for (String nonsense : List.of("", "not a url", "/relative/app.js", "app.js")) {
            assertEquals("'none'", DashServer.originOf(nonsense), nonsense);
        }
    }
}
