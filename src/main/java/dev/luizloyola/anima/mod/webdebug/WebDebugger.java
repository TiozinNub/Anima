package dev.luizloyola.anima.mod.webdebug;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Keys;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.mod.AnimaMod;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * <p><b>Off by default, loopback by default, and a generated key.</b> The key is
 * required on every route, so a page that guessed the port still cannot read a frame, and the
 * {@code Host} check is what a DNS-rebinding attempt fails — see {@link #isAcceptableHost}, which
 * refuses DNS names outright and so holds at any bind address.
 *
 * <p><b>{@link Knob#WEB_HOST} can widen that, and nothing else about the design changes to
 * compensate.</b> There is no TLS and no login; the key rides in the URL. Bound anywhere but
 * loopback, every mind is readable and drivable by whatever can reach the port, so the bind is
 * announced with a warning rather than left to be discovered. A non-loopback origin also is not
 * <em>potentially trustworthy</em>, so the page stops being a secure context and the APIs that
 * needs go with it.
 *
 * <p>See {@code docs/superpowers/specs/2026-08-17-dashboard-design.md}.
 */
public final class WebDebugger {

    /** Snapshot cadence. {@code DebugView}'s: four ticks reads as instant and costs nothing. */
    private static final int SEND_INTERVAL_TICKS = 4;

    /** How long an idle stream waits before emitting a keepalive — and noticing a dead socket. */
    private static final long KEEPALIVE_MILLIS = 15_000L;

    private static final WebFeed FEED = new WebFeed();

    private static @Nullable HttpServer http;
    private static @Nullable ExecutorService pool;
    private static @Nullable MinecraftServer world;
    private static volatile WebWatch watch = WebWatch.NONE;

    private WebDebugger() {
    }

    /** Whether the dashboard is switched on. @see Knob#WEB_ENABLED */
    public static boolean enabled() {
        return Config.get().b(Knob.WEB_ENABLED);
    }

    /** The port to listen on. @see Knob#WEB_PORT */
    public static int port() {
        return Config.get().i(Knob.WEB_PORT);
    }

    /** The address to bind to — {@code 127.0.0.1} unless an operator widened it. @see Knob#WEB_HOST */
    public static String host() {
        return Config.get().s(Knob.WEB_HOST);
    }

    /** Whether the current binding keeps the dashboard on this machine. */
    public static boolean loopbackOnly() {
        return isLoopbackName(host());
    }

    /** Where the stub loads the UI from. @see Knob#WEB_APP_URL */
    public static String appUrl() {
        return Config.get().s(Knob.WEB_APP_URL);
    }

    /**
     * This installation's key — the one thing guarding every route.
     *
     * <p>Normally {@code ConfigFile} has already generated and saved it on load, and this is a
     * field read. The generate-and-persist here is the safety net for a config that was never
     * loaded from a file at all (a test, an embedded run): without it the key would be empty,
     * {@link #authorised} would refuse everything, and the failure would read as a broken server
     * rather than a missing key.
     *
     * <p>Synchronised so two requests arriving together cannot generate two different keys and
     * leave whichever lost holding an address that no longer works.
     */
    public static synchronized String key() {
        String existing = Config.get().s(Knob.WEB_KEY);
        if (!existing.isEmpty()) {
            return existing;
        }
        String fresh = Keys.generate();
        Config.install(Config.get().with(Knob.WEB_KEY, fresh));
        AnimaMod.CONFIG.save(Config.get());
        AnimaMod.LOGGER.info("web-debugger: generated a key for this installation");
        return fresh;
    }

    /** Whether a server is listening right now. */
    public static boolean running() {
        return http != null;
    }

    /**
     * The address to open, key included — what {@code /anima web-debugger} prints.
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
        return "http://" + inUrl + ":" + port() + "/?key=" + key();
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
                FEED.publish(WebSnapshot.render(server, watch));
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
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(bindAddress(), port()), 0);
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
            created.createContext("/", WebDebugger::serveStub);
            created.createContext("/api/stream", WebDebugger::serveStream);
            created.createContext("/api/watch", WebDebugger::serveWatch);
            created.createContext("/api/command", WebDebugger::serveCommand);
            created.start();
            http = created;
            pool = created_pool;
            AnimaMod.LOGGER.info("web-debugger: listening — open {}", address());
            warnIfExposed();
            return null;
        } catch (UnknownHostException e) {
            AnimaMod.LOGGER.warn("web-debugger: \"{}\" is not an address this machine can bind", host());
            return "dash.host \"" + host() + "\" is not an address this machine can bind";
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
                + "and its commands drivable, by anything that can reach {}:{} and read the key "
                + "off the URL. There is no TLS and no login. Set web_debugger.host back to "
                + "127.0.0.1 unless you meant this.", host(), host(), port());
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
        watch = WebWatch.NONE;
        AnimaMod.LOGGER.info("web-debugger: stopped");
    }

    // --- routes ---------------------------------------------------------------------------------

    /**
     * The stub. No inline script, so the page needs no {@code 'unsafe-inline'} in its CSP and the
     * one origin it will run code from is stated in the header for anyone to read: the key
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
                <div id="anima-dash" data-key="%s" data-app="%s">
                  <noscript>The dashboard needs JavaScript.</noscript>
                </div>
                <script type="module" src="%s"></script>
                """.formatted(key(), escape(app), escape(app));
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().add("Content-Security-Policy",
                "default-src 'none'; script-src " + origin + "; style-src " + origin
                        + " 'unsafe-inline'; img-src 'self' data: " + origin
                        + "; font-src " + origin + "; connect-src 'self'; base-uri 'none'");
        // The key is in the body; a referrer carrying it to the app's host would leak it.
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
                WebFeed.Snapshot snapshot = FEED.awaitAfter(seen, KEEPALIVE_MILLIS);
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
        WebWatch next = watch;
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
     * {@link WebFeed}; an HTTP thread touching an agent is the race this whole design avoids.
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
        WebWatch acting = watch;
        server.execute(() -> WebActions.run(server, acting, verb, query));
        send(exchange, 202, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
    }

    // --- guards ---------------------------------------------------------------------------------

    /**
     * The two checks every route makes, answering the exchange itself when either fails.
     *
     * <p>The {@code Host} check is the anti-rebinding one: a name that resolves to 127.0.0.1 today
     * gets the browser to send the request, but it cannot forge the header it arrives with. The
     * key is what a local page that guessed the port does not have.
     */
    private static boolean authorised(HttpExchange exchange) throws IOException {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || !isAcceptableHost(host, host())) {
            send(exchange, 421, "wrong host".getBytes(StandardCharsets.UTF_8));
            return false;
        }
        String expected = key();
        String supplied = query(exchange.getRequestURI()).get("key");
        // Constant-time: a byte-at-a-time compare leaks the key's prefix to anything that can time
        // the reply, and this endpoint answers as fast as an attacker cares to ask.
        if (expected.isEmpty() || supplied == null || !constantTimeEquals(expected, supplied)) {
            send(exchange, 403, "bad key".getBytes(StandardCharsets.UTF_8));
            return false;
        }
        return true;
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

    /** Compares without an early exit, so the time taken says nothing about how much matched. */
    private static boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
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
