package dev.luizloyola.anima.mod.webdebug;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.ConfigValues;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.mod.AnimaMod;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.zip.GZIPOutputStream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * The debug dashboard's server half: a loopback HTTP endpoint a browser reads a running world
 * through.
 *
 * <p><b>The UI is not bundled.</b> What is served at {@code /} is a stub whose only job is to load
 * the real application from {@link Knob#WEB_APP_URL}. That indirection is not cosmetic — it is
 * what makes the design work at all:
 *
 * <ul>
 *   <li>A page hosted on a site and reaching <em>into</em> {@code http://127.0.0.1} is a
 *       public-origin-to-loopback request. Chrome gates that behind a Local Network Access
 *       permission prompt, and WebKit forbids it outright as mixed content. Serving the document
 *       from loopback instead makes every {@code /api} call <b>same-origin</b>, which raises none
 *       of it, in every browser.
 *   <li>An {@code http:} page loading an {@code https:} script is always allowed — only the
 *       reverse is blocked — so the UI still ships from the site and still updates without a mod
 *       release.
 *   <li>The site therefore serves one static asset and sees no world data at all. The privacy
 *       guarantee is structural rather than a promise.
 * </ul>
 *
 * <p><b>Two guards, and they do not cover the same routes.</b> Every route requires a loopback
 * {@code Host} — that is what a DNS-rebinding attempt fails, and {@link #isAcceptableHost} refuses
 * DNS names outright so it holds at any bind address. Only the routes that return or change world
 * state require a key an operator has accepted, in an {@code X-Api-Key} header.
 *
 * <p>{@code GET /} is deliberately open, because it carries nothing: a fixed page naming the app
 * to load. The <b>app</b> owns its key from there — it makes one, keeps it, and offers it at
 * {@code /api/register} until somebody in the game admits it. Nothing in the served HTML and
 * nothing in the address is worth having, which is what lets the page be cached-never and shared
 * freely while the data behind it is not. See {@link WebBrowsers} for why a key that weak is
 * enough.
 *
 * <p><b>{@link Knob#WEB_HOST} can widen that, and nothing else about the design changes to
 * compensate.</b> There is no TLS. Bound anywhere but loopback, every mind is readable and
 * drivable by whatever can reach the port and holds an accepted key, so the bind is announced with
 * a warning rather than left to be discovered. A non-loopback origin also is not <em>potentially
 * trustworthy</em>, so the page stops being a secure context and the APIs that needs go with it.
 *
 * <p>See {@code docs/superpowers/specs/2026-08-17-dashboard-design.md}.
 */
public final class WebDebugger {

    /** Where a browser's key travels. Not the query string — see {@link #keyed}. */
    public static final String KEY_HEADER = "X-Api-Key";

    /**
     * Development-launcher overrides. Properties rather than knobs because they must not reach a
     * shipped config file: a mod that defaulted to serving its debugger would be a mod that opens a
     * socket on somebody's machine without being asked.
     */
    public static final String AUTOSTART_PROPERTY = "anima.web_debugger.autostart";

    /** @see #port() */
    public static final String PORT_PROPERTY = "anima.web_debugger.port";

    /** How long an idle stream waits before emitting a keepalive — and noticing a dead socket. */
    private static final long KEEPALIVE_MILLIS = 15_000L;

    /**
     * The most frames a second the feed carries, however fast the world is ticking. A screen
     * refresh, and nothing a person reads off a dashboard needs more than one of. @see WebPace
     */
    private static final long MAX_FRAMES_PER_SECOND = 60L;

    /**
     * The last thing a stream writes when the debugger is going down on purpose.
     *
     * <p>A browser cannot tell a stopped server from a dropped socket by the socket alone, and the
     * two want opposite answers: a drop is worth retrying, a stop is not. Saying so costs one event
     * and saves a tab reconnecting for as long as it is left open.
     *
     * <p>The {@code data:} line carries nothing and is there because it must be: an SSE event with
     * no data is not an event.
     */
    private static final String STOP_EVENT = "event: stop\ndata: {}\n\n";

    /**
     * How long {@link #stop} lets the parked streams write {@link #STOP_EVENT} before the sockets
     * go. It returns as soon as they have, so this is a ceiling and not a wait — the one handler
     * that can spend it all is a refusal being held (see {@link #refuse}), and a stop landing
     * inside one of those is rare enough to pay a second for.
     */
    private static final int STOP_GRACE_SECONDS = 1;

    /**
     * Where a player's head is rendered from, for the panel's picker.
     *
     * <p>Named <em>here</em> because {@link #serveStub}'s CSP has to allow exactly this origin, and
     * a bundle that picked its own host would simply watch its images blocked. One constant, one
     * file, and the frame carries the finished URL so the two cannot drift.
     *
     * <p><b>It is a third party, and it learns which UUIDs are online here.</b> The browser fetches
     * it, not the server, under the {@code Referrer-Policy: no-referrer} the stub already sets — so
     * the service never learns the address of the tool asking. A machine with no route out gets no
     * image and the app draws a monogram instead.
     */
    private static final String FACES = "https://mc-heads.net";

    /**
     * The feed for the <b>current</b> run of the server. Replaced by {@link #start} and closed by
     * {@link #stop}, rather than being one singleton reopened — a closed feed cannot come back, and
     * a stopped-then-started debugger kept handing new readers the dead one: no frame ever arrived
     * again, and the keepalive branch span at full tilt because a closed feed answers instantly.
     * A reader captures the feed it started on, so the two runs cannot be confused.
     */
    private static volatile WebFeed feed = new WebFeed();

    /**
     * What holds the feed to {@link #MAX_FRAMES_PER_SECOND}. Read and written only on the server
     * tick thread, which is why it is neither volatile nor replaced by {@link #start}: a deadline
     * left over from a previous run is already past, so the first tick of the next one publishes.
     */
    private static final WebPace PACE = new WebPace(MAX_FRAMES_PER_SECOND);

    /** Saving is passed in rather than called: {@link WebBrowsers} is core-shaped and has no file. */
    private static final WebBrowsers BROWSERS =
            new WebBrowsers(() -> AnimaMod.CONFIG.save(Config.get()));

    private static @Nullable HttpServer http;
    private static @Nullable ExecutorService pool;
    private static @Nullable MinecraftServer world;
    private static volatile WebWatch watch = WebWatch.NONE;

    private WebDebugger() {
    }

    /**
     * Whether a loading world should bring the web debugger up with it.
     *
     * <p>{@link #AUTOSTART_PROPERTY} is how a development launcher says yes without writing it into
     * a config file that a real installation might inherit — see the run configs in Autarkia's
     * build. It only ever turns this ON: nothing that ships defaults to serving.
     *
     * @see Knob#WEB_ENABLED
     */
    public static boolean enabled() {
        return Config.get().b(Knob.WEB_ENABLED) || Boolean.getBoolean(AUTOSTART_PROPERTY);
    }

    /**
     * The port to listen on.
     *
     * <p>{@link #PORT_PROPERTY} supplies a development default, and <b>loses to a knob anybody
     * actually set</b>. That order matters: in single-player the client hosts its own integrated
     * server, so a dev client and a dev server both want a port and cannot share one — the
     * launcher hands each a different default, exactly as it already does for the JDWP ports —
     * while an operator who edits {@code web_debugger.port} still gets what they typed.
     *
     * @see Knob#WEB_PORT
     */
    public static int port() {
        ConfigValues config = Config.get();
        if (config.isDefault(Knob.WEB_PORT)) {
            int fromLauncher = Integer.getInteger(PORT_PROPERTY, 0);
            if (fromLauncher >= 1024 && fromLauncher <= 65_535) {
                return fromLauncher;
            }
        }
        return config.i(Knob.WEB_PORT);
    }

    /** The address to bind to — {@code 127.0.0.1} unless an operator widened it. @see Knob#WEB_HOST */
    public static String host() {
        return Config.get().s(Knob.WEB_HOST);
    }

    /** Whether the current binding keeps the dashboard on this machine. */
    public static boolean loopbackOnly() {
        return isLoopbackName(host());
    }

    /**
     * Where the stub loads the UI from — the knob and nothing else. Working on the UI means
     * pointing it at a dev server, which is an edit to the config file like any other; the
     * workspace's {@code scripts/frontend.sh} makes that edit rather than the launcher smuggling
     * an address past the file that is supposed to be the record of it.
     *
     * @see Knob#WEB_APP_URL
     */
    public static String appUrl() {
        return Config.get().s(Knob.WEB_APP_URL);
    }

    /** Who may read this world, who is asking, and the door — {@code /anima web-debugger browser}. */
    public static WebBrowsers browsers() {
        return BROWSERS;
    }

    /**
     * Takes a browser off the list <b>and hangs up on it</b> — the whole of what revoking means, so
     * that no caller can do half of it.
     *
     * <p>The hang-up is the part that is easy to leave out: a stream is one request that lasts
     * hours, and a browser parked on it is not asking permission again. Waking every reader makes
     * each re-check its own key at once, which is a no-op for everyone but the one just revoked —
     * cheaper and simpler than tracking which socket belongs to which key.
     */
    public static boolean revoke(String key) {
        if (!BROWSERS.revoke(key)) {
            return false;
        }
        feed.wake();
        return true;
    }

    /** Whether a server is listening right now. */
    public static boolean running() {
        return http != null;
    }

    /**
     * The address to open — what {@code /anima web-debugger} prints. It carries nothing: the
     * browser brings its own key and asks to be let in, so this can be pasted anywhere.
     *
     * <p>A wildcard bind has no address to name, so it prints as loopback: every interface includes
     * this one, and the operator who set {@code 0.0.0.0} knows their own LAN address better than
     * this does.
     */
    public static String address() {
        String bound = host();
        String reachable = bound.equals("0.0.0.0") || bound.equals("::") ? "127.0.0.1" : bound;
        // An IPv6 literal needs brackets in a URL; a bare ::1 would parse as host "" port ":1".
        String inUrl = reachable.contains(":") ? "[" + reachable + "]" : reachable;
        return "http://" + inUrl + ":" + port() + "/";
    }

    /** Call once from common mod init. */
    public static void install() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            world = server;
            if (enabled()) {
                start(server);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            stop();
            world = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // The one place the world is read. Guarded on running(), so a switched-off dashboard
            // costs a field read per tick — and on the pace, so a sprinting one costs a clock read
            // rather than the whole roster rendered to JSON inside the tick.
            if (running() && PACE.due(System.nanoTime())) {
                // Bridge until Task 6 wires up WebClocks and the diff: every section is asked for
                // on every tick, so the model stays EMPTY and this still ships whole frames.
                feed.publish(WebModel.EMPTY, WebModel.frame(server.getTickCount(),
                        WebSnapshot.build(server, watch, java.util.EnumSet.allOf(WebClock.class))));
            }
        });
    }

    /**
     * Starts listening, replacing any running server. Returns the problem when it could not —
     * almost always the port being held — rather than throwing at a command handler.
     */
    public static synchronized @Nullable String start(MinecraftServer server) {
        // No goodbye: a browser told "it stopped" would sit behind that screen waiting to be
        // pressed, with the server this line is about to start already listening behind it. Its
        // stream drops and it reconnects, which is what a restart should look like from a tab.
        stop(false);
        world = server;
        // A fresh one, because stop() closed the last for good. This is the line whose absence
        // made a restarted debugger serve keepalives and nothing else.
        feed = new WebFeed();
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(bindAddress(), port()), 0);
            // Cached and daemon: an SSE stream holds its thread for as long as the browser is
            // open, so a fixed pool of N would wedge on the N+1th tab, and a non-daemon thread
            // would keep a crashed server's JVM alive.
            AtomicInteger counter = new AtomicInteger();
            ExecutorService created_pool = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "anima-web-debugger-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            created.setExecutor(created_pool);
            created.createContext("/", WebDebugger::serveStub);
            created.createContext("/api/register", WebDebugger::serveRegister);
            created.createContext("/api/stream", WebDebugger::serveStream);
            created.createContext("/api/watch", WebDebugger::serveWatch);
            created.createContext("/api/command", WebDebugger::serveCommand);
            created.start();
            http = created;
            pool = created_pool;
            AnimaMod.LOGGER.info("web-debugger: listening — open {} ({} browser(s) accepted)",
                    address(), BROWSERS.accepted().size());
            warnIfExposed();
            return null;
        } catch (UnknownHostException e) {
            AnimaMod.LOGGER.warn("web-debugger: \"{}\" is not an address this machine can bind", host());
            return "web_debugger.host \"" + host() + "\" is not an address this machine can bind";
        } catch (IOException e) {
            AnimaMod.LOGGER.warn("web-debugger: could not listen on {}:{} ({})", host(), port(), e.toString());
            return "could not listen on " + host() + ":" + port() + " — " + e.getMessage();
        }
    }

    /**
     * The address {@link Knob#WEB_HOST} names. {@code 0.0.0.0} and {@code ::} mean every
     * interface, which {@code InetSocketAddress} spells as the wildcard.
     *
     * @throws UnknownHostException when the knob holds something unresolvable — reported to the
     *     operator rather than swallowed, since silently falling back to loopback would leave
     *     somebody who asked for a LAN bind wondering why nothing can reach it.
     */
    private static InetAddress bindAddress() throws UnknownHostException {
        String bound = host();
        if (bound.equals("0.0.0.0") || bound.equals("::")) {
            return null; // InetSocketAddress reads null as the wildcard address
        }
        return InetAddress.getByName(bound);
    }

    /**
     * Says plainly what a non-loopback bind just did. Loud because it cannot be undone by anything
     * on the wire: the dashboard reads every mind and its commands drive them, the transport is
     * plain HTTP, and the key travels in the URL where anything on the path can read it.
     */
    private static void warnIfExposed() {
        if (loopbackOnly()) {
            return;
        }
        AnimaMod.LOGGER.warn("web-debugger: bound to {} — NOT loopback. Every agent's mind is readable, "
                + "and its commands drivable, by anything that can reach {}:{} and holds an "
                + "accepted browser key — which travels in the clear, there being no TLS. Set "
                + "web_debugger.host back to 127.0.0.1 unless you meant this.",
                host(), host(), port());
    }

    /**
     * Stops listening and releases every parked stream, after each has said goodbye. Safe to call
     * when nothing is running.
     */
    public static synchronized void stop() {
        stop(true);
    }

    /** @param farewell whether the streams should be told this is the end — see {@link WebFeed#close}. */
    private static synchronized void stop(boolean farewell) {
        HttpServer running = http;
        if (running == null) {
            return;
        }
        // Closed BEFORE the field is cleared. A reader that saw running() go false first would
        // leave its loop with the feed still open, read that as an ordinary hang-up, and write no
        // stop event — which is the whole difference between a browser that waits and one that
        // retries a server nobody is bringing back.
        feed.close(farewell);
        http = null;
        running.stop(STOP_GRACE_SECONDS);
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        watch = WebWatch.NONE;
        // The queue and the door are per-session: a browser that was waiting will ask again, and
        // a door left open across a restart is one nobody remembers opening.
        BROWSERS.clear();
        AnimaMod.LOGGER.info("web-debugger: stopped");
    }

    // --- routes ---------------------------------------------------------------------------------

    /**
     * The stub, served to anything on this machine that asks — <b>no key required</b>.
     *
     * <p>It can be, because it carries no secret: it is a fixed page naming the app to load. The
     * app makes its own key, keeps it in local storage, and sends it as {@code X-Api-Key} from
     * then on. Everything worth guarding is behind {@link #keyed}.
     *
     * <p>That is also why this page must stay boring. Anything the stub learned about the world
     * would be readable by any local page that guessed the port.
     *
     * <p>No inline script, so the page needs no {@code 'unsafe-inline'} in its CSP and the one
     * origin it will run code from is stated in the header for anyone to read.
     */
    private static void serveStub(HttpExchange exchange) throws IOException {
        if (!fromLoopback(exchange)) {
            return;
        }
        String app = appUrl();
        String origin = originOf(app);
        // The app replaces this on mount, so it is only ever read when the app did not arrive —
        // which names the URL that failed instead of leaving a blank page to explain itself.
        String html = """
                <!doctype html>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Anima</title>
                <div id="anima-dash" data-app="%s">
                  Loading the web debugger from %s
                  <noscript>The web debugger needs JavaScript.</noscript>
                </div>
                <script type="module" src="%s"></script>
                """.formatted(escape(app), escape(app), escape(app));
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().add("Content-Security-Policy",
                "default-src 'none'; script-src " + origin + "; style-src " + origin
                        + " 'unsafe-inline'; img-src 'self' data: " + FACES + " " + origin
                        + "; font-src " + origin + "; connect-src " + connectSrc(app)
                        + "; base-uri 'none'");
        // Nothing in the URL to leak any more, but the site has no business seeing the port and
        // path a private tool runs on either.
        exchange.getResponseHeaders().add("Referrer-Policy", "no-referrer");
        // A cached stub would pin an app_url the operator has since changed.
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        send(exchange, 200, html.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A browser offering its key — the only way into {@link WebBrowsers}'s queue, and the only
     * route a page with no standing here may usefully call.
     *
     * <p><b>200 or 401, and 401 says nothing about which.</b> Refused, queued, malformed and
     * locked out are one answer on the wire: telling them apart would hand a guesser the oracle
     * the whole design is built to withhold. The page's job is simply to keep asking.
     */
    private static void serveRegister(HttpExchange exchange) throws IOException {
        if (!fromLoopback(exchange)) {
            return;
        }
        String supplied = exchange.getRequestHeaders().getFirst(KEY_HEADER);
        String key = supplied == null ? "" : supplied;
        String from = remoteAddress(exchange);
        WebBrowsers.Outcome outcome = BROWSERS.register(key, from);
        if (outcome == WebBrowsers.Outcome.ASKED) {
            announce(key, from);
        }
        if (outcome == WebBrowsers.Outcome.ACCEPTED) {
            send(exchange, 200, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        } else {
            refuse(exchange);
        }
    }

    /**
     * Tells whoever can act on it that a browser is waiting, in the log and to every operator in
     * the game with the two commands as buttons.
     *
     * <p>This is the discovery path, and it replaces one: the address used to carry the key, so
     * printing it was the whole handoff. Now the key belongs to the browser and never appears in
     * chat unless it asks, which means nothing would say a browser is waiting at all.
     */
    private static void announce(String key, String from) {
        AnimaMod.LOGGER.info("web-debugger: a browser is asking to connect — \"{}\" from {}. "
                + "Let it in with /anima web-debugger browser accept {}", key, from, key);
        MinecraftServer server = world;
        if (server == null) {
            return;
        }
        // Off the HTTP thread: the player list is the server's, and this is the same hand-off
        // every other route makes for the same reason.
        server.execute(() -> WebCommands.tellOperators(server, key, from));
    }

    /**
     * The one answer every refusal gets — <b>held</b> for {@link WebBrowsers#LOCKOUT_MILLIS}, not
     * sent at once.
     *
     * <p>Refusing instantly caps how fast a guess is <em>evaluated</em> and not how fast one can be
     * <em>sent</em>, so a retry loop still spins the socket flat out and being wrong costs nothing.
     * Making the answer itself cost three seconds is what actually stops the retries, and it paces
     * the caller without the caller cooperating.
     *
     * <p><b>The same three seconds for every refusal, whatever the reason.</b> Holding only the
     * ones that armed the lockout was the first shape, and it leaks: a key already known to be
     * wrong came back in under a millisecond where a fresh one took three seconds, so the
     * <em>timing</em> answered the question the status code refuses to — has this key been seen
     * here before. A uniform hold is the only version that says nothing.
     *
     * <p>Applied to every route, not only {@code /api/register}: they all refuse without comparing
     * the key while a lockout runs, so an attacker would otherwise guess against whichever answered
     * fastest.
     *
     * <p>Slept <b>outside</b> {@link WebBrowsers}'s monitor. Holding it here would queue the
     * operator's own {@code browser accept} behind whoever is hammering the port. The parked thread
     * is affordable because the pool is unbounded and its threads are daemons — a stopping server
     * interrupts them rather than waiting. It does mean a flood of refusals parks a thread each,
     * which on loopback is a thing its author could do to themselves by other means anyway.
     */
    private static void refuse(HttpExchange exchange) throws IOException {
        try {
            Thread.sleep(WebBrowsers.LOCKOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // shutting down: answer now and let go
        }
        send(exchange, 401, "{\"ok\":false}".getBytes(StandardCharsets.UTF_8));
    }

    /** Who is calling, for the queue and the log. Never resolved to a name. */
    private static String remoteAddress(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? "?"
                : remote.getAddress().getHostAddress();
    }

    /**
     * The live feed. One SSE event per published frame, and a comment line when nothing has changed
     * for {@link #KEEPALIVE_MILLIS} — which is also how a browser that has gone away is noticed,
     * a write being the only thing that finds that out.
     *
     * <p><b>The key is checked between frames, not just at the handshake.</b> This is the one route
     * that outlives its own request: authenticating once and then holding the socket open meant a
     * revoked browser kept streaming every mind in the world until it happened to reconnect, which
     * is a revoke that does not revoke. {@link #revoke} pairs with this by waking the parked
     * readers, so a quiet world does not delay the hang-up by a keepalive.
     *
     * <p>It ends with {@link #STOP_EVENT} when the debugger is what stopped, and silently for every
     * other ending: a revoked browser hears 401 on its next call, and a restart is about to be
     * listening again, so both want the browser reconnecting rather than waiting to be pressed.
     *
     * <p><b>Gzipped when the browser offers it</b>, and the compression is worth far more here than
     * the ratio on any one frame suggests: <em>one</em> deflate stream spans the whole connection,
     * so its window still holds the last frame when the next one — near-identical, a tick later —
     * goes through, and most of it leaves as back-references. Measured against a synthetic roster
     * of twelve, it is ~18x with nothing expanded and ~20x with eight cards open, where the raw
     * feed is over a megabyte a second. Per-frame gzip with no shared history is only 5-14x.
     */
    private static void serveStream(HttpExchange exchange) throws IOException {
        if (!keyed(exchange)) {
            return;
        }
        String key = exchange.getRequestHeaders().getFirst(KEY_HEADER);
        // Captured once. A restart installs a new feed, and this reader belongs to the run it
        // started in — asking the field again each pass would have it drift onto the other one.
        WebFeed live = feed;
        boolean zip = wantsGzip(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        if (zip) {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
        }
        exchange.sendResponseHeaders(200, 0); // 0 = chunked, the stream stays open
        // Under the gzip, so it counts what the socket carries rather than what was handed in.
        Counted wire = new Counted(exchange.getResponseBody());
        // syncFlush, and it is load-bearing: without it a flushed frame sits in the deflater until
        // enough of the next ones arrive to fill a block, and the dashboard goes still.
        try (OutputStream out = zip ? new GZIPOutputStream(wire, true) : wire) {
            pump(out, wire::written, live, () -> running() && BROWSERS.stillAccepted(key));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The browser closed the tab. Not worth a log line — it is the normal ending.
        } finally {
            exchange.close();
        }
    }

    /**
     * Whether this caller said it would take gzip, given its {@code Accept-Encoding}. Asked rather
     * than assumed: {@code /api/stream} is a documented route, and a {@code curl} that offered
     * nothing has to keep getting text it can read rather than a screenful of binary.
     *
     * <p>Takes the header rather than the exchange so it can be tested, which the branch deserves —
     * getting it wrong is not a degraded feed but an unreadable one.
     */
    static boolean wantsGzip(@Nullable String offered) {
        if (offered == null) {
            return false;
        }
        for (String coding : offered.split(",")) {
            String[] parts = coding.trim().split(";q=", 2);
            if (parts[0].equalsIgnoreCase("gzip")) {
                // `gzip;q=0` is a refusal spelled as an offer.
                return parts.length < 2 || !parts[1].trim().matches("0(\\.0+)?");
            }
        }
        return false;
    }

    /**
     * How many bytes have gone down the socket. <b>This is the only place that number exists.</b>
     * Gzip is applied above it, and a browser's {@code fetch} hands JavaScript the DECODED bytes
     * with no way to ask what they arrived as — so a dashboard counting its own reads would report
     * the feed at twenty times its cost. {@link #pump} puts the count on the wire instead.
     */
    static final class Counted extends FilterOutputStream {

        private long written;

        Counted(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            written++;
        }

        @Override
        public void write(byte[] bytes, int off, int len) throws IOException {
            // Not inherited: FilterOutputStream's own loops byte-by-byte through write(int).
            out.write(bytes, off, len);
            written += len;
        }

        long written() {
            return written;
        }
    }

    /**
     * The stream itself, with no HTTP around it: frames while {@code welcome} holds and the feed
     * lives, then the goodbye if the debugger is what ended it.
     *
     * <p>Split out to be driven by a test. The loop has been wrong twice — once spinning on a feed
     * closed by a previous run, once outliving a revoke — and both were found in a running game,
     * which is the expensive place to find them.
     *
     * <p>Every frame carries a {@code wire:} field, and it is the count {@code before} that frame —
     * so it is what the socket had taken as of the previous one, one frame behind and unnoticeable
     * at twenty a second. It cannot be the count including itself: the bytes do not exist until the
     * line naming them has been compressed. A reader takes rates from the differences.
     *
     * <p><b>It goes after {@code data:}, and that is the whole reason it is safe to add.</b> SSE
     * ignores a field it does not know, but a hand-written consumer testing
     * {@code part.startsWith('data: ')} — which is what {@code docs/DASHBOARD-API.md} has always
     * shown — stops matching the moment anything precedes it, and stops rendering silently.
     */
    static void pump(OutputStream out, LongSupplier wire, WebFeed live, BooleanSupplier welcome)
            throws IOException, InterruptedException {
        long seen = -1;
        // The whole retained world first. Every frame after this one is a DELTA, and a delta
        // merged onto nothing is a dashboard with holes in it that nothing later fills.
        WebFeed.Snapshot hello = live.hello();
        if (hello != null) {
            seen = hello.version();
            write(out, hello.json(), wire);
        }
        // isClosed, and not just welcome: a restart makes running() true again while THIS feed
        // stays dead, and a dead feed answers instantly — the loop would spin.
        while (welcome.getAsBoolean() && !live.isClosed()) {
            WebFeed.Snapshot snapshot = live.awaitAfter(seen, KEEPALIVE_MILLIS);
            if (snapshot == null) {
                out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } else {
                seen = snapshot.version();
                write(out, snapshot.json(), wire);
            }
        }
        if (live.isFarewell()) {
            out.write(STOP_EVENT.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /** One SSE event, with the socket count as it stood before the bytes naming it existed. */
    private static void write(OutputStream out, String json, LongSupplier wire) throws IOException {
        out.write(("data: " + json + "\nwire: " + wire.getAsLong() + "\n\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** What the browser has expanded, and who it is acting as. */
    private static void serveWatch(HttpExchange exchange) throws IOException {
        if (!keyed(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        String id = query.get("id");
        String acting = query.get("as");
        WebWatch next = watch;
        if (id != null) {
            next = next.toggled(new dev.luizloyola.anima.core.agent.AgentId(UUID.fromString(id)),
                    !"0".equals(query.get("open")) && !"false".equals(query.get("open")));
        }
        if (acting != null) {
            next = next.actingAs(acting.isEmpty() ? null : UUID.fromString(acting));
        }
        String ticks = query.get("ticks");
        if (ticks != null) {
            next = next.ticks(!"0".equals(ticks) && !"false".equals(ticks));
        }
        String dead = query.get("dead");
        if (dead != null) {
            next = next.withDead(!"0".equals(dead) && !"false".equals(dead));
        }
        watch = next;
        send(exchange, 200, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A command from the panel. Queued onto the tick thread rather than run here — see
     * {@link WebFeed}; an HTTP thread touching an agent is the race this whole design avoids.
     */
    private static void serveCommand(HttpExchange exchange) throws IOException {
        if (!keyed(exchange)) {
            return;
        }
        MinecraftServer server = world;
        if (server == null) {
            send(exchange, 503, "{\"ok\":false,\"error\":\"no world\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        String verb = query.getOrDefault("verb", "");
        WebWatch acting = watch;
        server.execute(() -> WebActions.run(server, acting, verb, query));
        send(exchange, 202, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
    }

    // --- guards ---------------------------------------------------------------------------------

    /**
     * The check <b>every</b> route makes: the request came from this machine, under a name that
     * cannot have been forged.
     *
     * <p>The anti-rebinding one. A name that resolves to 127.0.0.1 today gets the browser to send
     * the request, but it cannot forge the {@code Host} header it arrives with — see
     * {@link #isAcceptableHost}.
     */
    private static boolean fromLoopback(HttpExchange exchange) throws IOException {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || !isAcceptableHost(host, host())) {
            send(exchange, 421, "wrong host".getBytes(StandardCharsets.UTF_8));
            return false;
        }
        return true;
    }

    /**
     * {@link #fromLoopback} plus an accepted key — what everything that returns or changes world
     * state asks for, and the stub does not.
     *
     * <p><b>The key arrives in {@code X-Api-Key}, never the query string.</b> A header does not
     * land in the address bar, in browser history, or in a {@code Referer}; it is also the one
     * place a same-origin request can carry a secret without a preflight.
     *
     * <p>401 rather than 403, and the same 401 {@code /api/register} gives: a browser that has not
     * been accepted <em>yet</em> is the ordinary state here, not an error, and the page must read
     * it as "keep asking" rather than as "forget your key".
     */
    private static boolean keyed(HttpExchange exchange) throws IOException {
        if (!fromLoopback(exchange)) {
            return false;
        }
        if (BROWSERS.check(exchange.getRequestHeaders().getFirst(KEY_HEADER))
                == WebBrowsers.Outcome.ACCEPTED) {
            return true;
        }
        refuse(exchange);
        return false;
    }

    /**
     * Whether a {@code Host} header may be served, given what the server is {@code bound} to.
     *
     * <p><b>An IP literal or a loopback name — never an arbitrary DNS name.</b> That is the whole
     * anti-rebinding guard, and stating it this way is what makes it survive a non-loopback bind:
     * rebinding works by getting the browser to resolve {@code attacker.example} to a private
     * address, and the browser then sends {@code Host: attacker.example}. A header follows the
     * URL's hostname and cannot be forged into an IP literal, so refusing names refuses the attack
     * at every bind address.
     *
     * <p>The configured host is accepted by name as well, for the operator who bound to one.
     */
    static boolean isAcceptableHost(String header, String bound) {
        String name = hostName(header);
        if (name.isEmpty()) {
            return false;
        }
        return isLoopbackName(name) || isIpLiteral(name) || name.equalsIgnoreCase(unbracket(bound));
    }

    /** The name in a {@code Host} header: brackets stripped, port dropped. Never resolved. */
    static String hostName(String header) {
        String trimmed = header.trim();
        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            return close < 0 ? "" : trimmed.substring(1, close);
        }
        int colon = trimmed.indexOf(':');
        return colon < 0 ? trimmed : trimmed.substring(0, colon);
    }

    /** {@code localhost}, {@code ::1}, or anything in {@code 127/8} — all of which stay on this box. */
    static boolean isLoopbackName(String name) {
        String bare = unbracket(name);
        if (bare.equalsIgnoreCase("localhost") || bare.equals("::1")) {
            return true;
        }
        // The literal test first: "127.evil.example" starts with "127." and is a DNS name.
        return isIpv4(bare) && bare.startsWith("127.");
    }

    private static String unbracket(String name) {
        return name.startsWith("[") && name.endsWith("]")
                ? name.substring(1, name.length() - 1)
                : name;
    }

    private static boolean isIpLiteral(String name) {
        return isIpv4(name) || isIpv6(name);
    }

    private static boolean isIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /** Hex groups, colons and a zone or embedded-v4 tail. Deliberately loose: this decides
     *  "is this a literal rather than a name", not "is this a valid address" — the bind already
     *  answered that. */
    private static boolean isIpv6(String text) {
        if (text.indexOf(':') < 0) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean legal = c == ':' || c == '.' || c == '%'
                    || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!legal) {
                return false;
            }
        }
        return true;
    }

    // --- plumbing -------------------------------------------------------------------------------

    /**
     * What the page may talk to. {@code 'self'} is the mod and nothing else, and that single word
     * is the structural half of "the site sees no world data": a bundle that wanted to phone home
     * cannot, whatever it contains.
     *
     * <p><b>A loopback app URL is the one exception, because there the guarantee has nothing left
     * to protect.</b> That is a dev server on this machine — it already serves the code the page
     * runs, and its owner already has the key — and the connection being allowed is the HMR socket
     * that makes editing the UI worth doing. A remote origin never gets this, so the shipped
     * default is unchanged.
     *
     * <p>The {@code ws:} origin is named separately on purpose: Chrome does not accept a {@code ws}
     * connection against an {@code http} source expression, which reads like it should work and
     * does not.
     */
    static String connectSrc(String app) {
        URI uri;
        try {
            uri = URI.create(app);
        } catch (IllegalArgumentException e) {
            return "'self'";
        }
        String host = uri.getHost();
        if (host == null || !isLoopbackName(host)) {
            return "'self'";
        }
        String origin = originOf(app);
        return "'self' " + origin + " " + origin.replaceFirst("^http", "ws");
    }

    /** This player's head, at the size a roster row wants. @see #FACES */
    static String faceUrl(UUID player) {
        return FACES + "/avatar/" + player + "/32";
    }

    /** The scheme-and-host of the app URL, for the CSP that names what may run on the page. */
    static String originOf(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "'none'";
            }
            return uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            return "'none'"; // a knob holding nonsense must not widen the policy
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(decode(pair), "");
            } else {
                out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String decode(String text) {
        return java.net.URLDecoder.decode(text, StandardCharsets.UTF_8);
    }

    /** Attribute-safe, for the app URL going into the stub's markup. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** Waits for the listener to be gone — the shutdown path a test needs to be deterministic. */
    static void awaitStopped(long millis) {
        ExecutorService running = pool;
        if (running == null) {
            return;
        }
        try {
            running.awaitTermination(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
