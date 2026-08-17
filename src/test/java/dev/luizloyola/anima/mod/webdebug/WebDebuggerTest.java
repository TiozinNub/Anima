package dev.luizloyola.anima.mod.webdebug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parts of the dashboard that hold without a world: the tick-to-HTTP hand-off, the watch, and
 * the two guards that are the whole reason a loopback socket is safe to open.
 *
 * <p>The frame builder itself needs a running server and is exercised in-game.
 */
class WebDebuggerTest {

    // --- the hand-off ---------------------------------------------------------------------------

    @Test
    @DisplayName("a reader parked on the feed is woken by the next publish, and sees that frame")
    void publishWakesAReader() throws Exception {
        WebFeed feed = new WebFeed();
        feed.publish("{\"tick\":0}");
        long caughtUp = feed.version();

        AtomicReference<WebFeed.Snapshot> got = new AtomicReference<>();
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
        assertNull(new WebFeed().awaitAfter(-1, 50));
    }

    @Test
    @DisplayName("a reader that already saw the latest version waits rather than re-sending it")
    void anUpToDateReaderWaits() throws Exception {
        WebFeed feed = new WebFeed();
        feed.publish("{\"tick\":1}");
        long version = feed.version();
        // Nothing new: this is the keepalive path, and re-sending here would spin the socket.
        assertNull(feed.awaitAfter(version, 50));
    }

    @Test
    @DisplayName("closing releases parked readers instead of leaking their threads")
    void closeReleasesReaders() throws Exception {
        WebFeed feed = new WebFeed();
        feed.publish("{\"tick\":1}");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<WebFeed.Snapshot> got = new AtomicReference<>(
                new WebFeed.Snapshot(-1, "sentinel"));
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
        WebWatch watch = WebWatch.NONE.toggled(one, true).toggled(two, true);
        assertTrue(watch.isExpanded(one));
        assertTrue(watch.isExpanded(two));

        WebWatch collapsed = watch.toggled(one, false);
        assertFalse(collapsed.isExpanded(one));
        assertTrue(collapsed.isExpanded(two), "collapsing one card closed another");
        assertTrue(watch.isExpanded(one), "the original was mutated — it must be copy-on-write");
    }

    @Test
    @DisplayName("choosing who to act as leaves the expansion alone")
    void actingAsIsIndependentOfExpansion() {
        AgentId one = new AgentId(UUID.randomUUID());
        UUID player = UUID.randomUUID();
        WebWatch watch = WebWatch.NONE.toggled(one, true).actingAs(player);
        assertEquals(player, watch.actingAs());
        assertTrue(watch.isExpanded(one));
        assertNull(watch.actingAs(null).actingAs());
    }

    // --- the launcher overrides -------------------------------------------------------------------

    @AfterEach
    void clearLauncherOverrides() {
        System.clearProperty(WebDebugger.PORT_PROPERTY);
        System.clearProperty(WebDebugger.AUTOSTART_PROPERTY);
        System.clearProperty(WebDebugger.APP_URL_PROPERTY);
        Config.reset();
    }

    @Test
    @DisplayName("the launcher's app URL is a DEFAULT — a knob somebody set beats it")
    void launcherAppUrlLosesToAnEditedKnob() {
        String dev = "http://localhost:25597/src/dev.tsx";
        System.setProperty(WebDebugger.APP_URL_PROPERTY, dev);
        assertEquals(dev, WebDebugger.appUrl(), "a dev world loads the UI from the server beside it");

        Config.install(Config.get().with(Knob.WEB_APP_URL, "https://elsewhere.example/app.v1.js"));
        assertEquals("https://elsewhere.example/app.v1.js", WebDebugger.appUrl(),
                "an operator who edited web_debugger.app_url must get what they typed");
    }

    @Test
    @DisplayName("an empty app-URL property is no property at all")
    void launcherAppUrlIgnoresBlanks() {
        System.setProperty(WebDebugger.APP_URL_PROPERTY, "   ");
        assertEquals(Knob.WEB_APP_URL.defText(), WebDebugger.appUrl());
    }

    @Test
    @DisplayName("the launcher's port is a DEFAULT — a knob somebody set beats it")
    void launcherPortLosesToAnEditedKnob() {
        System.setProperty(WebDebugger.PORT_PROPERTY, "25598");
        assertEquals(25598, WebDebugger.port(), "an untouched knob takes the launcher's port");

        Config.install(Config.get().with(Knob.WEB_PORT, 30_000.0));
        assertEquals(30_000, WebDebugger.port(),
                "an operator who edited web_debugger.port must get what they typed");
    }

    @Test
    @DisplayName("a nonsense launcher port is ignored rather than crashing the bind")
    void launcherPortIsBoundsChecked() {
        for (String nonsense : List.of("0", "80", "99999", "not-a-port", "")) {
            System.setProperty(WebDebugger.PORT_PROPERTY, nonsense);
            assertEquals((int) Knob.WEB_PORT.def(), WebDebugger.port(), nonsense);
        }
    }

    @Test
    @DisplayName("the launcher can only turn autostart ON, never off")
    void autostartIsOneWay() {
        assertFalse(WebDebugger.enabled(), "nothing that ships defaults to serving");

        System.setProperty(WebDebugger.AUTOSTART_PROPERTY, "true");
        assertTrue(WebDebugger.enabled(), "the dev launcher opts in");

        System.clearProperty(WebDebugger.AUTOSTART_PROPERTY);
        Config.install(Config.get().with(Knob.WEB_ENABLED, 1.0));
        assertTrue(WebDebugger.enabled(), "and the knob still works on its own");
    }

    // --- the guards -----------------------------------------------------------------------------

    @Test
    @DisplayName("a loopback bind serves loopback Hosts and refuses every DNS name")
    void hostCheckOnALoopbackBind() {
        for (String good : List.of("127.0.0.1:25599", "localhost:25599", "LOCALHOST:25599",
                "[::1]:25599", "127.0.0.1", "127.1.2.3:25599")) {
            assertTrue(WebDebugger.isAcceptableHost(good, "127.0.0.1"), good);
        }
        // A name that resolves to 127.0.0.1 gets the browser to send the request; what it cannot
        // do is forge the header the request arrives with.
        for (String bad : List.of("evil.example:25599", "anima-debugger.tioz.in",
                "127.0.0.1.evil.example:25599", "127.evil.example:25599", "")) {
            assertFalse(WebDebugger.isAcceptableHost(bad, "127.0.0.1"), bad);
        }
    }

    @Test
    @DisplayName("the rebinding guard survives a LAN bind — literals pass, names still do not")
    void hostCheckOnALanBind() {
        // The point of generalising rather than dropping the check: bound to a LAN address, the
        // browser sends that literal, and a rebinding attempt still arrives as a name.
        assertTrue(WebDebugger.isAcceptableHost("192.168.1.5:25599", "192.168.1.5"));
        assertTrue(WebDebugger.isAcceptableHost("127.0.0.1:25599", "192.168.1.5"),
                "the box it runs on must still reach it");
        assertTrue(WebDebugger.isAcceptableHost("10.0.0.9:25599", "0.0.0.0"),
                "a wildcard bind is reached at whichever literal the client used");
        assertFalse(WebDebugger.isAcceptableHost("evil.example:25599", "192.168.1.5"));
        assertFalse(WebDebugger.isAcceptableHost("evil.example:25599", "0.0.0.0"),
                "a wildcard bind must not become a wildcard Host check");
        // An operator who bound to a name is served under that name and no other.
        assertTrue(WebDebugger.isAcceptableHost("devbox.lan:25599", "devbox.lan"));
        assertFalse(WebDebugger.isAcceptableHost("other.lan:25599", "devbox.lan"));
    }

    @Test
    @DisplayName("loopbackOnly answers for the knob, and 127/8 is not a prefix match on a name")
    void loopbackOnlyIsLiteralAware() {
        assertTrue(WebDebugger.isLoopbackName("127.0.0.1"));
        assertTrue(WebDebugger.isLoopbackName("127.0.0.53"));
        assertTrue(WebDebugger.isLoopbackName("::1"));
        assertTrue(WebDebugger.isLoopbackName("localhost"));
        assertFalse(WebDebugger.isLoopbackName("0.0.0.0"));
        assertFalse(WebDebugger.isLoopbackName("192.168.1.5"));
        assertFalse(WebDebugger.isLoopbackName("127.evil.example"),
                "a DNS name starting \"127.\" is not loopback");
    }

    @Test
    @DisplayName("a Host header is split without ever resolving it")
    void hostNameParsing() {
        assertEquals("127.0.0.1", WebDebugger.hostName("127.0.0.1:25599"));
        assertEquals("127.0.0.1", WebDebugger.hostName("127.0.0.1"));
        assertEquals("::1", WebDebugger.hostName("[::1]:25599"));
        assertEquals("fe80::1%eth0", WebDebugger.hostName("[fe80::1%eth0]:25599"));
        assertEquals("", WebDebugger.hostName("[unclosed:25599"));
    }

    @Test
    @DisplayName("the CSP origin fails closed — a knob holding nonsense must not widen the policy")
    void cspOriginFailsClosed() {
        assertEquals("https://anima-debugger.tioz.in",
                WebDebugger.originOf("https://anima-debugger.tioz.in/app.v1.js"));
        assertEquals("http://localhost:5173", WebDebugger.originOf("http://localhost:5173/app.js"));
        for (String nonsense : List.of("", "not a url", "/relative/app.js", "app.js")) {
            assertEquals("'none'", WebDebugger.originOf(nonsense), nonsense);
        }
    }

    @Test
    @DisplayName("only a loopback UI may be connected to — the site never can, which is the guarantee")
    void connectSrcOpensForADevServerOnly() {
        // A dev server's HMR socket, and the ws: origin spelled out because Chrome will not read
        // it out of the http: one.
        assertEquals("'self' http://localhost:25597 ws://localhost:25597",
                WebDebugger.connectSrc("http://localhost:25597/src/dev.tsx"));
        assertEquals("'self' http://127.0.0.1:25597 ws://127.0.0.1:25597",
                WebDebugger.connectSrc("http://127.0.0.1:25597/src/dev.tsx"));

        // Everything else, including the shipped default: the page may talk to the mod and to
        // nothing else, so a bundle that wanted to phone home cannot.
        for (String remote : List.of("https://anima-debugger.tioz.in/app.v1.js",
                "http://127.0.0.1.evil.example/app.js", "https://localhost.evil.example/app.js",
                "not a url", "")) {
            assertEquals("'self'", WebDebugger.connectSrc(remote), remote);
        }
    }
}
