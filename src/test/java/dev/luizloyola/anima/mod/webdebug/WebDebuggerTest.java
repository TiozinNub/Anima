package dev.luizloyola.anima.mod.webdebug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
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
        feed.publish(WebModel.EMPTY, "{\"tick\":0}");
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

        feed.publish(WebModel.EMPTY, "{\"tick\":1}");
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
        feed.publish(WebModel.EMPTY, "{\"tick\":1}");
        long version = feed.version();
        // Nothing new: this is the keepalive path, and re-sending here would spin the socket.
        assertNull(feed.awaitAfter(version, 50));
    }

    @Test
    @DisplayName("a closed feed answers instantly, which is why a reader must check it and not loop")
    void aClosedFeedDoesNotBlock() throws Exception {
        // The shape behind "stopping and restarting broke it": stop() closed the feed, a restart
        // handed readers that same dead one, and awaitAfter returned null with no delay at all —
        // so the keepalive branch wrote eleven million lines in four seconds. The fix is a new
        // feed per run; this pins the property that made the old bug so loud.
        WebFeed feed = new WebFeed();
        feed.publish(WebModel.EMPTY, "{\"tick\":1}");
        feed.close(true);

        assertTrue(feed.isClosed());
        long startedNanos = System.nanoTime();
        assertNull(feed.awaitAfter(-1, 30_000));
        assertTrue(System.nanoTime() - startedNanos < TimeUnit.SECONDS.toNanos(5),
                "a closed feed does not wait out its timeout — the caller has to stop asking");
    }

    @Test
    @DisplayName("a wake releases parked readers without a frame — how a revoke hangs up at once")
    void wakeReleasesReadersWithoutPublishing() throws Exception {
        // A quiet world publishes nothing, so without this a revoked browser would keep streaming
        // until its keepalive came due fifteen seconds later.
        WebFeed feed = new WebFeed();
        feed.publish(WebModel.EMPTY, "{\"tick\":1}");
        long caughtUp = feed.version();

        AtomicReference<WebFeed.Snapshot> got = new AtomicReference<>(
                new WebFeed.Snapshot(-1, "sentinel"));
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
        Thread.sleep(50);

        feed.wake();
        assertTrue(done.await(5, TimeUnit.SECONDS), "the reader was not woken");
        assertNull(got.get(), "a wake is the keepalive path, not a frame");
        assertEquals(caughtUp, feed.version(), "waking must not look like a publish");
    }

    @Test
    @DisplayName("closing releases parked readers instead of leaking their threads")
    void closeReleasesReaders() throws Exception {
        WebFeed feed = new WebFeed();
        feed.publish(WebModel.EMPTY, "{\"tick\":1}");
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
        feed.close(true);
        assertTrue(done.await(5, TimeUnit.SECONDS), "close did not release the reader");
        assertNull(got.get());
    }

    @Test
    @DisplayName("a reader's first write is the whole retained world, not the next delta")
    void aReaderIsGreetedWithTheWholeModel() throws Exception {
        WebFeed feed = new WebFeed();
        // LinkedHashMap, not Map.of: Map.of randomises its iteration order per JVM run, and the
        // order full() emits keys in — pinned in the assertion below — is what this test checks.
        java.util.Map<String, String> fresh = new java.util.LinkedHashMap<>();
        fresh.put("agents", "[]");
        fresh.put("health", "{\"tps\":20}");
        WebModel model = WebModel.EMPTY.against(4, fresh).model();
        feed.publish(model, "{\"tick\":4,\"agents\":[],\"health\":{\"tps\":20}}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicBoolean welcome = new AtomicBoolean(true);
        Thread reader = new Thread(() -> {
            try {
                WebDebugger.pump(out, () -> 0L, feed, welcome::get);
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        reader.setDaemon(true);
        reader.start();
        Thread.sleep(100);
        welcome.set(false);
        feed.wake();
        reader.join(5_000);

        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith(
                        "data: {\"tick\":4,\"full\":true,\"agents\":[],\"health\":{\"tps\":20}}"),
                "a partial frame merged onto nothing is a dashboard with holes in it");
    }

    @Test
    @DisplayName("a reader the hand-off outran is greeted again, not handed a delta it cannot merge")
    void aReaderThatFellBehindIsGreetedAgain() throws Exception {
        // The feed holds one frame, so two publishes across one slow socket write leave the reader
        // a version short — and the retained model already counts the skipped change as told, so
        // nothing later resends it. This is the only thing that repairs that.
        WebFeed feed = new WebFeed();
        java.util.Map<String, String> opening = new java.util.LinkedHashMap<>();
        opening.put("agents", "[]");
        WebModel.Update first = WebModel.EMPTY.against(1, opening);
        feed.publish(first.model(), WebModel.frame(1, first.delta()));

        CountDownLatch greeted = new CountDownLatch(1);
        CountDownLatch overtaken = new CountDownLatch(1);
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        // Blocks inside the greeting's write, which is the real window: pump writes the whole world
        // to a socket before it ever parks, and HEALTH publishes every 50ms.
        OutputStream slow = new OutputStream() {
            private boolean blocked;

            @Override
            public void write(int b) {
                sent.write(b);
            }

            @Override
            public void write(byte[] bytes, int off, int len) {
                sent.write(bytes, off, len);
                if (!blocked) {
                    blocked = true;
                    greeted.countDown();
                    try {
                        overtaken.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };

        AtomicBoolean welcome = new AtomicBoolean(true);
        Thread reader = new Thread(() -> {
            try {
                WebDebugger.pump(slow, () -> 0L, feed, welcome::get);
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        reader.setDaemon(true);
        reader.start();
        assertTrue(greeted.await(5, TimeUnit.SECONDS), "the greeting never went out");

        WebModel.Update second = first.model().against(2, java.util.Map.of("health", "{\"tps\":20}"));
        feed.publish(second.model(), WebModel.frame(2, second.delta()));
        WebModel.Update third = second.model().against(3, java.util.Map.of("health", "{\"tps\":12}"));
        feed.publish(third.model(), WebModel.frame(3, third.delta()));
        overtaken.countDown();

        Thread.sleep(100);
        welcome.set(false);
        feed.wake();
        reader.join(5_000);

        String[] frames = sent.toString(StandardCharsets.UTF_8).split("\n\n");
        assertTrue(frames.length >= 2, "the reader never got past the greeting: " + sent);
        assertTrue(frames[1].startsWith("data: {\"tick\":3,\"full\":true,\"agents\":[]"),
                "version 2 was skipped, so version 3's delta is unmergeable: " + frames[1]);
    }

    @Test
    @DisplayName("a reader arriving before the first frame is greeted with nothing at all")
    void nothingToSayHelloWith() throws Exception {
        WebFeed feed = new WebFeed();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicBoolean welcome = new AtomicBoolean(true);
        Thread reader = new Thread(() -> {
            try {
                WebDebugger.pump(out, () -> 0L, feed, welcome::get);
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        reader.setDaemon(true);
        reader.start();
        Thread.sleep(100);
        welcome.set(false);
        feed.wake();
        reader.join(5_000);

        String sent = out.toString(StandardCharsets.UTF_8);
        assertFalse(sent.contains("data:"), "an empty model must not be greeted as a world of nothing");

        // A reader parked correctly writes no keepalive inside a 100ms window — the interval is
        // 15 seconds — and exactly one when wake() releases it above. A reader that instead
        // busy-spins on a feed with nothing published writes thousands in the same window, so the
        // count is the whole test: 2 passes a parked reader, thousands fails a spinning one.
        int keepalives = sent.split(": keepalive", -1).length - 1;
        assertTrue(keepalives <= 2,
                "a reader before the first frame must park, not spin: " + keepalives + " keepalives");
    }

    // --- the goodbye ----------------------------------------------------------------------------

    @Test
    @DisplayName("a stream whose feed closes signs off with a stop event")
    void aClosedFeedEndsTheStreamWithStop() throws Exception {
        WebFeed feed = new WebFeed();
        feed.publish(WebModel.EMPTY, "{\"tick\":1}");
        ByteArrayOutputStream wire = new ByteArrayOutputStream();

        CountDownLatch done = new CountDownLatch(1);
        Thread stream = new Thread(() -> {
            try {
                WebDebugger.pump(wire, wire::size, feed, () -> true);
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        stream.setDaemon(true);
        stream.start();
        Thread.sleep(50); // let it send the frame and park; closing first would not test the loop

        feed.close(true);
        assertTrue(done.await(5, TimeUnit.SECONDS), "the stream never ended");
        String sent = wire.toString(StandardCharsets.UTF_8);
        assertTrue(sent.startsWith("data: {\"tick\":1}\nwire: 0\n\n"), sent);
        assertTrue(sent.endsWith("event: stop\ndata: {}\n\n"),
                "a browser cannot tell a stopped server from a dropped socket without this: " + sent);
    }

    @Test
    @DisplayName("a stream the browser lost standing on hangs up silently — 401 says the rest")
    void anUnwelcomeStreamDoesNotSayStop() throws Exception {
        // The two endings must not look alike: a revoke is worth reconnecting into, a stop is not.
        // The model has to be non-empty, or hello() answers null and nothing is written whether or
        // not the greeting is guarded — silence for the wrong reason, proving nothing.
        WebFeed feed = new WebFeed();
        feed.publish(greetable(), "{\"tick\":1}");
        ByteArrayOutputStream wire = new ByteArrayOutputStream();

        WebDebugger.pump(wire, wire::size, feed, () -> false);
        assertEquals("", wire.toString(StandardCharsets.UTF_8),
                "a browser whose key went in the microseconds after the handshake still got a world");
    }

    @Test
    @DisplayName("a restart says nothing — the browser reconnects into the server already coming up")
    void aRestartDoesNotSayStop() throws Exception {
        // Non-empty for the same reason as above: an empty model is silent by accident.
        WebFeed feed = new WebFeed();
        feed.publish(greetable(), "{\"tick\":1}");
        feed.close(false);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();

        WebDebugger.pump(wire, wire::size, feed, () -> true);
        assertEquals("", wire.toString(StandardCharsets.UTF_8),
                "a stop screen behind a listening server is a screen nobody can get past");
    }

    /** A model with something in it, so {@link WebFeed#hello} has a world to greet a reader with. */
    private static WebModel greetable() {
        return WebModel.EMPTY.against(1, java.util.Map.of("agents", "[]")).model();
    }

    // --- the wire -------------------------------------------------------------------------------

    @Test
    @DisplayName("a gzipped frame is readable the moment it is written, not when the stream ends")
    void aGzippedFrameArrivesWhole() throws Exception {
        // The failure this guards is silent and total: without syncFlush the deflater holds a
        // flushed frame back until later ones fill a block, so the dashboard connects, shows
        // nothing, and looks like a dead server.
        WebFeed feed = new WebFeed();
        feed.publish(WebModel.EMPTY, "{\"tick\":1}");
        ByteArrayOutputStream socket = new ByteArrayOutputStream();
        WebDebugger.Counted wire = new WebDebugger.Counted(socket);
        GZIPOutputStream zipped = new GZIPOutputStream(wire, true);

        CountDownLatch done = new CountDownLatch(1);
        Thread stream = new Thread(() -> {
            try {
                WebDebugger.pump(zipped, wire::written, feed, () -> true);
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        stream.setDaemon(true);
        stream.start();

        // GZIPOutputStream's header is ten bytes and is written on construction, so anything past
        // it is the frame having actually left.
        long waited = 0;
        while (socket.size() <= GZIP_HEADER && waited < 5_000) {
            Thread.sleep(10);
            waited += 10;
        }
        assertTrue(socket.size() > GZIP_HEADER, "the frame never left the deflater");

        feed.close(true);
        assertTrue(done.await(5, TimeUnit.SECONDS), "the stream never ended");

        // Not `wire: 0`: gzip's own header has already gone down the socket by the time the first
        // frame names a count. Harmless — a reader takes differences — but it is why nothing here
        // asserts an absolute.
        String decoded = inflate(socket.toByteArray());
        assertTrue(decoded.matches("(?s)data: \\{\"tick\":1}\nwire: \\d+\n\n.*"),
                "a mid-stream reader must be able to decode what has arrived so far: " + decoded);
    }

    @Test
    @DisplayName("the wire count is what the socket took, so gzip cannot flatter the meter")
    void theWireCountIsTheCompressedSize() throws Exception {
        // A browser's fetch decodes before JavaScript sees a byte, so this count is the only
        // honest one there is — and it has to be the small number, not the JSON's length.
        WebFeed feed = new WebFeed();
        String frame = "{\"tick\":1,\"agents\":[" + "\"aaaaaaaaaaaaaaaaaaaa\",".repeat(200) + "\"z\"]}";
        feed.publish(WebModel.EMPTY, frame);
        ByteArrayOutputStream socket = new ByteArrayOutputStream();
        WebDebugger.Counted wire = new WebDebugger.Counted(socket);

        // One pass and out: the loop re-asks before every frame, so a welcome that is true once
        // writes exactly one and then leaves — with no goodbye, the feed never having closed.
        AtomicBoolean once = new AtomicBoolean(true);
        try (GZIPOutputStream zipped = new GZIPOutputStream(wire, true)) {
            WebDebugger.pump(zipped, wire::written, feed, () -> once.getAndSet(false));
        }

        assertEquals(socket.size(), wire.written(), "the counter must be the socket, not the input");
        assertTrue(wire.written() < frame.length() / 4,
                "a repetitive frame should compress hard: " + wire.written() + " of " + frame.length());
    }

    @Test
    @DisplayName("gzip is offered, never assumed — a caller that said nothing still gets text")
    void gzipIsNegotiated() {
        assertTrue(WebDebugger.wantsGzip("gzip"));
        assertTrue(WebDebugger.wantsGzip("gzip, deflate, br"));
        assertTrue(WebDebugger.wantsGzip("deflate, GZIP;q=1.0"), "the token is case-insensitive");
        assertTrue(WebDebugger.wantsGzip("gzip;q=0.5"));

        // The one that matters: a `curl` with no header, and a caller actively refusing. Both get
        // readable text, because binary in a terminal is how this route stops being debuggable.
        assertFalse(WebDebugger.wantsGzip(null));
        assertFalse(WebDebugger.wantsGzip("identity"));
        assertFalse(WebDebugger.wantsGzip("gzip;q=0"));
        assertFalse(WebDebugger.wantsGzip("gzip;q=0.0"));
        assertFalse(WebDebugger.wantsGzip("br, deflate"), "not a prefix match on another coding");
    }

    /** The fixed header {@link GZIPOutputStream} writes before any deflated byte. */
    private static final int GZIP_HEADER = 10;

    /**
     * What a browser would have decoded so far. Raw inflate past the header rather than a
     * {@code GZIPInputStream}, which wants a trailer a live stream has not written yet.
     */
    private static String inflate(byte[] sent) throws Exception {
        Inflater inflater = new Inflater(true);
        inflater.setInput(sent, GZIP_HEADER, sent.length - GZIP_HEADER);
        byte[] out = new byte[64 * 1024];
        int size = inflater.inflate(out);
        inflater.end();
        return new String(out, 0, size, StandardCharsets.UTF_8);
    }

    // --- the clock ------------------------------------------------------------------------------

    @Test
    @DisplayName("a server with headroom runs at the rate it was told, not at the rate it could")
    void headroomIsNotSpeed() {
        // 4ms a tick could carry 250, and the server sleeps the other 46 — the 20 is the answer.
        assertEquals(20.0, WebSnapshot.achieved(4.0, 20.0f, false, false), 0.001);
        assertEquals(300.0, WebSnapshot.achieved(1.0, 300.0f, false, false), 0.001);
    }

    @Test
    @DisplayName("a server that cannot keep up runs at what it costs, whatever it was told")
    void costWinsWhenItIsTheLowerOfTheTwo() {
        assertEquals(12.5, WebSnapshot.achieved(80.0, 20.0f, false, false), 0.001);
        assertEquals(12.5, WebSnapshot.achieved(80.0, 300.0f, false, false), 0.001);
    }

    @Test
    @DisplayName("a sprinting server is not held to its rate — that is what sprinting means")
    void sprintIgnoresTheConfiguredRate() {
        // The bug this is here for: clamped to the configured 20, a world tearing through 2000
        // ticks a second reported 20 and read as idle.
        assertEquals(2000.0, WebSnapshot.achieved(0.5, 20.0f, true, false), 0.001);
        assertEquals(12.5, WebSnapshot.achieved(80.0, 20.0f, true, false), 0.001,
                "sprinting does not make a slow tick fast");
    }

    @Test
    @DisplayName("a tick too cheap for two decimals is not rounded away to nothing")
    void aMicrosecondTickSurvivesRounding() {
        // What two decimals are for, and all that most worlds ever need.
        assertEquals(4.21, WebSnapshot.round(4.2149), 0.0001);
        assertEquals(80.23, WebSnapshot.round(80.2345), 0.0001);
        // A sprinting server ticks in microseconds. Rounded to zero, the chart is a flat line on
        // the floor and anything dividing 1000 by it to get a rate divides by zero.
        assertEquals(0.0042, WebSnapshot.round(0.00423), 0.00001);
        assertEquals(0.00012, WebSnapshot.round(0.000121), 0.000001);
        // A genuine zero stays one — an unmeasured tick is not an infinitely cheap tick.
        assertEquals(0.0, WebSnapshot.round(0.0), 0.0);
    }

    @Test
    @DisplayName("a frozen world is not moving, whatever the server thread is still doing")
    void frozenIsNotMoving() {
        // The server thread keeps going at 20 a second and the tick counter with it — it is the
        // WORLD that has stopped, and the rate this reports is the world's. Vanilla's own
        // `/tick query` says "the game is frozen" and declines to name a rate at all.
        assertEquals(0.0, WebSnapshot.achieved(0.1, 20.0f, false, true), 0.001);
        assertEquals(0.0, WebSnapshot.achieved(80.0, 20.0f, false, true), 0.001);
    }

    @Test
    @DisplayName("an unmeasured tick falls back to the rate rather than dividing by zero")
    void anUnmeasuredTickIsNotInfinite() {
        // The first ticks of a world, before the ring has anything in it.
        assertEquals(20.0, WebSnapshot.achieved(0.0, 20.0f, false, false), 0.001);
        assertEquals(20.0, WebSnapshot.achieved(0.0, 20.0f, true, false), 0.001);
    }

    // --- the pace -------------------------------------------------------------------------------

    /** A second, in the nanoseconds {@link WebPace} is asked in. */
    private static final long SECOND = 1_000_000_000L;

    @Test
    @DisplayName("a world ticking slower than the cap publishes every tick — the ordinary case")
    void everyTickPassesAtTwenty() {
        WebPace pace = new WebPace(60);
        int published = 0;
        // Two seconds of vanilla: a tick every 50ms, none of them due to be dropped.
        for (long at = 0; at < 2 * SECOND; at += SECOND / 20) {
            if (pace.due(at)) {
                published++;
            }
        }
        assertEquals(40, published, "a 20 TPS world lost frames to a 60 a second cap");
    }

    @Test
    @DisplayName("a sprinting world is held to the cap, however fast it ticks")
    void sprintIsHeldToTheCap() {
        WebPace pace = new WebPace(60);
        int published = 0;
        // A tick every 2ms — 500 a second, which is what a sprint on a quiet world looks like.
        for (long at = 0; at < SECOND; at += SECOND / 500) {
            if (pace.due(at)) {
                published++;
            }
        }
        assertEquals(60, published, "the cap did not hold: " + published + " frames in a second");
    }

    @Test
    @DisplayName("a rate just above the cap does not beat against it and halve the feed")
    void aRateJustOverTheCapDoesNotBeat() {
        WebPace pace = new WebPace(60);
        int published = 0;
        // 100 ticks a second. A floor measured from the last frame rather than from the deadline
        // would drop every other one and report 50 — worse than the cap it is enforcing.
        for (long at = 0; at < SECOND; at += SECOND / 100) {
            if (pace.due(at)) {
                published++;
            }
        }
        assertEquals(60, published, "the gate beat against the tick and lost frames: " + published);
    }

    @Test
    @DisplayName("a frozen world owes nothing — the first tick back is one frame, not a burst")
    void aLongGapDoesNotOweABurst() {
        WebPace pace = new WebPace(60);
        assertTrue(pace.due(0), "the first frame was withheld");
        // An hour frozen, then two ticks 1ms apart. A deadline that kept advancing by the gap
        // would owe 216,000 frames and let both through.
        assertTrue(pace.due(3600 * SECOND));
        assertFalse(pace.due(3600 * SECOND + 1_000_000L), "the gate paid out a backlog");
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
    @DisplayName("the dead section is watched on its own, and starts closed")
    void theDeadAreWatchedIndependently() {
        // The roster's other rows cost one frame each; the dead accumulate forever and are read
        // once a session, so they are off until somebody asks — which is a flag, not an id.
        AgentId one = new AgentId(UUID.randomUUID());
        assertFalse(WebWatch.NONE.dead(), "the dead were being built before anyone asked for them");

        WebWatch watch = WebWatch.NONE.toggled(one, true).withDead(true);
        assertTrue(watch.dead());
        assertTrue(watch.isExpanded(one), "opening the dead section closed a card");
        assertFalse(watch.withDead(false).dead());
        assertTrue(watch.dead(), "the original was mutated — it must be copy-on-write");
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
        Config.reset();
    }

    @Test
    @DisplayName("the UI address is the file's to state — no launcher override reads over it")
    void appUrlComesFromTheKnobAlone() {
        assertEquals(Knob.WEB_APP_URL.defText(), WebDebugger.appUrl());

        String dev = "http://localhost:25597/src/dev.tsx";
        Config.install(Config.get().with(Knob.WEB_APP_URL, dev));
        assertEquals(dev, WebDebugger.appUrl(), "a config pointed at a dev server must be obeyed");
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
