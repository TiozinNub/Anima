package dev.luizloyola.anima.mod.dash;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.mod.AnimaMod;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * The debug dashboard's server half: a loopback HTTP endpoint a browser reads a running world
 * through.
 *
 * <p><b>The UI is not bundled.</b> What is served at {@code /} is a stub whose only job is to load
 * the real application from {@link Knob#DASH_APP_URL}. That indirection is not cosmetic — it is
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
 * <p><b>Loopback and a fresh token, neither configurable.</b> A dashboard reachable from the
 * network is one somebody else can drive, and it exposes every mind and commands them. The token
 * is regenerated per start and required on every route, so a page that guessed the port still
 * cannot read a frame; the {@code Host} check is what a DNS-rebinding attempt fails.
 *
 * <p>See {@code docs/superpowers/specs/2026-08-17-dashboard-design.md}.
 */
public final class DashServer {

    /** Snapshot cadence. {@code DebugView}'s: four ticks reads as instant and costs nothing. */
    private static final int SEND_INTERVAL_TICKS = 4;

    /** How long an idle stream waits before emitting a keepalive — and noticing a dead socket. */
    private static final long KEEPALIVE_MILLIS = 15_000L;

    private static final DashFeed FEED = new DashFeed();

    private static @Nullable HttpServer http;
    private static @Nullable ExecutorService pool;
    private static @Nullable MinecraftServer world;
    private static String token = "";
    private static volatile DashWatch watch = DashWatch.NONE;

    private DashServer() {
    }

    /** Whether the dashboard is switched on. @see Knob#DASH_ENABLED */
    public static boolean enabled() {
        return Config.get().b(Knob.DASH_ENABLED);
    }

    /** The loopback port to listen on. @see Knob#DASH_PORT */
    public static int port() {
        return Config.get().i(Knob.DASH_PORT);
    }

    /** Where the stub loads the UI from. @see Knob#DASH_APP_URL */
    public static String appUrl() {
        return Config.get().s(Knob.DASH_APP_URL);
    }

    /** Whether a server is listening right now. */
    public static boolean running() {
        return http != null;
    }

    /** The address to open, token included — what {@code /anima dash} prints. */
    public static String address() {
        return "http://127.0.0.1:" + port() + "/?t=" + token;
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
            // costs a modulo and a field read per tick.
            if (running() && server.getTickCount() % SEND_INTERVAL_TICKS == 0) {
                FEED.publish(DashSnapshot.render(server, watch));
            }
        });
    }

    /**
     * Starts listening, replacing any running server. Returns the problem when it could not —
     * almost always the port being held — rather than throwing at a command handler.
     */
    public static synchronized @Nullable String start(MinecraftServer server) {
        stop();
        world = server;
        token = UUID.randomUUID().toString().replace("-", "");
        try {
            HttpServer created = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port()), 0);
            // Cached and daemon: an SSE stream holds its thread for as long as the browser is
            // open, so a fixed pool of N would wedge on the N+1th tab, and a non-daemon thread
            // would keep a crashed server's JVM alive.
            AtomicInteger counter = new AtomicInteger();
            ExecutorService created_pool = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "anima-dash-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            created.setExecutor(created_pool);
            created.createContext("/", DashServer::serveStub);
            created.createContext("/api/stream", DashServer::serveStream);
            created.createContext("/api/watch", DashServer::serveWatch);
            created.createContext("/api/command", DashServer::serveCommand);
            created.start();
            http = created;
            pool = created_pool;
            AnimaMod.LOGGER.info("dash: listening — open {}", address());
            return null;
        } catch (IOException e) {
            AnimaMod.LOGGER.warn("dash: could not listen on 127.0.0.1:{} ({})", port(), e.toString());
            return "could not listen on port " + port() + " — " + e.getMessage();
        }
    }

    /** Stops listening and releases every parked stream. Safe to call when nothing is running. */
    public static synchronized void stop() {
        HttpServer running = http;
        if (running == null) {
            return;
        }
        http = null;
        FEED.close();
        running.stop(0);
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        watch = DashWatch.NONE;
        AnimaMod.LOGGER.info("dash: stopped");
    }

    // --- routes ---------------------------------------------------------------------------------

    /**
     * The stub. No inline script, so the page needs no {@code 'unsafe-inline'} in its CSP and the
     * one origin it will run code from is stated in the header for anyone to read: the token
     * travels in a data attribute instead.
     */
    private static void serveStub(HttpExchange exchange) throws IOException {
        if (!authorised(exchange)) {
            return;
        }
        String app = appUrl();
        String origin = originOf(app);
        String html = """
                <!doctype html>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Anima</title>
                <div id="anima-dash" data-token="%s" data-app="%s">
                  <noscript>The dashboard needs JavaScript.</noscript>
                </div>
                <script type="module" src="%s"></script>
                """.formatted(token, escape(app), escape(app));
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().add("Content-Security-Policy",
                "default-src 'none'; script-src " + origin + "; style-src " + origin
                        + " 'unsafe-inline'; img-src 'self' data: " + origin
                        + "; font-src " + origin + "; connect-src 'self'; base-uri 'none'");
        // The token is in the body; a referrer carrying it to the app's host would leak it.
        exchange.getResponseHeaders().add("Referrer-Policy", "no-referrer");
        send(exchange, 200, html.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The live feed. One SSE event per published frame, and a comment line when nothing has changed
     * for {@link #KEEPALIVE_MILLIS} — which is also how a browser that has gone away is noticed,
     * a write being the only thing that finds that out.
     */
    private static void serveStream(HttpExchange exchange) throws IOException {
        if (!authorised(exchange)) {
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0); // 0 = chunked, the stream stays open
        long seen = -1;
        try (OutputStream out = exchange.getResponseBody()) {
            while (running()) {
                DashFeed.Snapshot snapshot = FEED.awaitAfter(seen, KEEPALIVE_MILLIS);
                if (snapshot == null) {
                    out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    seen = snapshot.version();
                    out.write(("data: " + snapshot.json() + "\n\n").getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The browser closed the tab. Not worth a log line — it is the normal ending.
        } finally {
            exchange.close();
        }
    }

    /** What the browser has expanded, and who it is acting as. */
    private static void serveWatch(HttpExchange exchange) throws IOException {
        if (!authorised(exchange)) {
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        String id = query.get("id");
        String acting = query.get("as");
        DashWatch next = watch;
        if (id != null) {
            next = next.toggled(new dev.luizloyola.anima.core.agent.AgentId(UUID.fromString(id)),
                    !"0".equals(query.get("open")) && !"false".equals(query.get("open")));
        }
        if (acting != null) {
            next = next.actingAs(acting.isEmpty() ? null : UUID.fromString(acting));
        }
        watch = next;
        send(exchange, 200, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A command from the panel. Queued onto the tick thread rather than run here — see
     * {@link DashFeed}; an HTTP thread touching an agent is the race this whole design avoids.
     */
    private static void serveCommand(HttpExchange exchange) throws IOException {
        if (!authorised(exchange)) {
            return;
        }
        MinecraftServer server = world;
        if (server == null) {
            send(exchange, 503, "{\"ok\":false,\"error\":\"no world\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        String verb = query.getOrDefault("verb", "");
        DashWatch acting = watch;
        server.execute(() -> DashActions.run(server, acting, verb, query));
        send(exchange, 202, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
    }

    // --- guards ---------------------------------------------------------------------------------

    /**
     * The two checks every route makes, answering the exchange itself when either fails.
     *
     * <p>The {@code Host} check is the anti-rebinding one: a name that resolves to 127.0.0.1 today
     * gets the browser to send the request, but it cannot forge the header it arrives with. The
     * token is what a local page that guessed the port does not have.
     */
    private static boolean authorised(HttpExchange exchange) throws IOException {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || !isLoopbackHost(host)) {
            send(exchange, 421, "wrong host".getBytes(StandardCharsets.UTF_8));
            return false;
        }
        String supplied = query(exchange.getRequestURI()).get("t");
        if (token.isEmpty() || !token.equals(supplied)) {
            send(exchange, 403, "bad token".getBytes(StandardCharsets.UTF_8));
            return false;
        }
        return true;
    }

    /** {@code 127.0.0.1:25599} / {@code localhost:25599} / {@code [::1]:25599} and nothing else. */
    static boolean isLoopbackHost(String host) {
        String name = host.startsWith("[")
                ? host.substring(0, Math.min(host.length(), host.indexOf(']') + 1))
                : host.split(":", 2)[0];
        return name.equals("127.0.0.1") || name.equalsIgnoreCase("localhost") || name.equals("[::1]");
    }

    // --- plumbing -------------------------------------------------------------------------------

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
