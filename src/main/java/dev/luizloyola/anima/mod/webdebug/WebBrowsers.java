package dev.luizloyola.anima.mod.webdebug;

import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.config.KnobSpec;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Which browsers may read this world, which are asking to, and the door they have to come through.
 *
 * <p><b>The browser makes its own key and keeps it; an operator decides whether it means
 * anything.</b> There is no secret in the config for a page to be handed and no key in the address
 * — a browser generates two readable words, stores them, and presents them on every call. Until
 * {@code /anima web-debugger browser accept} writes those words into
 * {@link Knob#WEB_ACCEPTED_KEYS}, they open nothing. That inverts what a key <em>is</em> here: not
 * a secret that proves you were told the address, but a name a person recognised and admitted.
 *
 * <h2>Three guards, because the key itself is weak</h2>
 *
 * <p>Two readable words is around twenty bits — the price of a key an operator can read out of
 * chat and retype, and far too few to leave standing on its own. So a guess is made expensive
 * rather than made impossible:
 *
 * <ul>
 *   <li><b>The door is shut.</b> A key nobody has seen before is refused outright unless an
 *       operator has just run {@code browser open}, which lasts {@link #OPEN_MILLIS} and shuts
 *       again the moment one browser comes through. Guessing has no window to work in.
 *   <li><b>A miss shuts everything for {@link #LOCKOUT_MILLIS}.</b> Globally, not per address:
 *       every browser here shares {@code 127.0.0.1}, so a per-address counter would be one
 *       counter. Three seconds a guess turns a twenty-bit space into centuries.
 *   <li><b>Only a person can admit.</b> Even through an open door, a new key lands in a queue an
 *       operator has to act on.
 * </ul>
 *
 * <p><b>A key pays for its miss once.</b> That is deliberate rather than sloppy: a browser whose
 * key was revoked, or that is asking before anyone opened the door, keeps polling — and charging
 * every poll would hold the lockout on forever. A guesser is charged per key it tries, which is
 * the thing being rated.
 *
 * <p>Accepted keys live in the config file, where an operator can read and edit the list. The
 * queue and the door do not: a pending key means <em>a browser is asking right now</em>, so a
 * restart clears it and the browser asks again on its next poll.
 *
 * <p>Every method is synchronised. The traffic is a handful of requests plus the occasional
 * command — nothing here is worth a lock-free shape, and the state is small enough that one
 * monitor is easier to reason about than four.
 */
public final class WebBrowsers {

    /** How long {@code browser open} leaves the door open, when no browser comes through first. */
    static final long OPEN_MILLIS = 60_000L;

    /** How long one missing key shuts the door for everybody. */
    static final long LOCKOUT_MILLIS = 3_000L;

    /** How long a browser that stops asking stays in the queue. */
    static final long QUEUE_TTL_MILLIS = 10 * 60_000L;

    /** How many browsers may wait at once. The door admits one at a time, so this is slack. */
    static final int MAX_QUEUED = 8;

    /** How many distinct missing keys are remembered as already charged. @see #miss */
    static final int MAX_CHARGED = 64;

    /**
     * What a key may look like: lower-case words joined by hyphens. It ends up in a chat line, a
     * TOML file and a log line, so the shape is the mod's business even though the words are the
     * browser's — nothing here may need escaping or a second look. It also keeps every key a bare
     * TOML key, which is what lets the accepted list be edited by hand.
     */
    private static final Pattern SHAPE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)+");

    /** Long enough for four generous words; short enough to retype from a screen. */
    static final int MAX_KEY_LENGTH = 48;

    /** A browser waiting for an operator. */
    public record Waiting(String key, String from, long askedAtMillis, long lastAskedMillis) {

        Waiting seenAt(long now) {
            return new Waiting(key, from, askedAtMillis, now);
        }
    }

    /** What a browser presenting a key was told. */
    public enum Outcome {
        /** Accepted: 200, and every other route opens. */
        ACCEPTED,
        /** Already in the queue: keep polling, an operator has not answered yet. */
        WAITING,
        /** Just joined the queue — the one outcome the operators are told about. */
        ASKED,
        /** Anything else: malformed, unknown with the door shut, or inside the lockout. */
        REFUSED
    }

    /** What {@code browser accept} did. */
    public enum Admission { ADDED, ALREADY, MALFORMED }

    /**
     * How an accepted-key change reaches the disk. Injected because the config file is the mod's,
     * and this class is exercised without one.
     */
    private final Runnable flush;

    private final Map<String, Waiting> queue = new LinkedHashMap<>();

    /** Keys that have already paid for missing. @see #miss */
    private final Set<String> charged = new LinkedHashSet<>();

    private long openUntilMillis;
    private long lockedUntilMillis;

    public WebBrowsers(Runnable flush) {
        this.flush = flush;
    }

    // --- what a browser asks ----------------------------------------------------------------

    /**
     * A browser presenting its key at {@code /api/register} — the one route that can put a key in
     * the queue.
     *
     * <p>Idempotent by construction: the same key asked twice gets the same answer, and the answer
     * changes to {@link Outcome#ACCEPTED} the moment an operator accepts it. That is what lets the
     * page simply keep asking.
     */
    public Outcome register(String key, String from) {
        return register(key, from, System.currentTimeMillis());
    }

    synchronized Outcome register(String key, String from, long now) {
        expire(now);
        if (!wellFormed(key)) {
            miss(key, now);
            return Outcome.REFUSED;
        }
        if (locked(now)) {
            return Outcome.REFUSED;
        }
        if (isAccepted(key)) {
            return Outcome.ACCEPTED;
        }
        Waiting known = queue.get(key);
        if (known != null) {
            // Never refused and never counted as a miss: a browser waiting its turn is the one
            // thing here that is SUPPOSED to keep asking.
            queue.put(key, known.seenAt(now));
            return Outcome.WAITING;
        }
        if (!isOpen(now)) {
            miss(key, now);
            return Outcome.REFUSED;
        }
        queue.put(key, new Waiting(key, from, now, now));
        trim();
        openUntilMillis = 0; // one through, and it shuts behind them
        return Outcome.ASKED;
    }

    /**
     * A browser presenting its key at any other route. Never queues: asking to be let in is
     * {@link #register}'s job, and a stream call arriving with an unknown key is a guess.
     */
    public Outcome check(@Nullable String key) {
        return check(key, System.currentTimeMillis());
    }

    synchronized Outcome check(@Nullable String key, long now) {
        if (key == null || !wellFormed(key)) {
            miss(key == null ? "" : key, now);
            return Outcome.REFUSED;
        }
        if (locked(now)) {
            return Outcome.REFUSED;
        }
        if (isAccepted(key)) {
            return Outcome.ACCEPTED;
        }
        if (queue.containsKey(key)) {
            return Outcome.WAITING; // impatient, not hostile
        }
        miss(key, now);
        return Outcome.REFUSED;
    }

    // --- what an operator does --------------------------------------------------------------

    /** Opens the door for {@link #OPEN_MILLIS}, and clears a lockout a waiting page has armed. */
    public synchronized void open() {
        open(System.currentTimeMillis());
    }

    synchronized void open(long now) {
        openUntilMillis = now + OPEN_MILLIS;
        // Cleared here and nowhere else: an operator asking for the door is the only thing that
        // should be able to undo a lockout, and without this a page polling in the background
        // would keep shutting the door they just opened. What is NOT cleared is `charged` — a key
        // that has paid stays paid, or every open would let the pollers charge again.
        lockedUntilMillis = 0;
    }

    /** Shuts it again. */
    public synchronized void close() {
        openUntilMillis = 0;
    }

    /** Whether a new browser would be admitted to the queue right now. */
    public synchronized boolean isOpen() {
        return isOpen(System.currentTimeMillis());
    }

    synchronized boolean isOpen(long now) {
        return now < openUntilMillis && now >= lockedUntilMillis;
    }

    /** How much longer the door stays open, in whole seconds; 0 when it is shut. */
    public synchronized long openSecondsLeft() {
        long left = openUntilMillis - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** Lets {@code key} in from now on, writing it to the config file. */
    public synchronized Admission accept(String key) {
        if (!wellFormed(key)) {
            return Admission.MALFORMED;
        }
        if (isAccepted(key)) {
            return Admission.ALREADY;
        }
        List<String> keys = new ArrayList<>(accepted());
        keys.add(key);
        install(keys);
        queue.remove(key);
        return Admission.ADDED;
    }

    /** Drops a waiting browser from the queue. It may ask again through an open door. */
    public synchronized boolean reject(String key) {
        return queue.remove(key) != null;
    }

    /**
     * Takes an accepted browser back off the list. It may ask again through an open door — there
     * is no memory of a refusal, deliberately: a permanent block would need its own list, its own
     * command to undo, and a way to tell a mistake from an attack that nothing here has.
     */
    public synchronized boolean revoke(String key) {
        boolean queued = queue.remove(key) != null;
        List<String> keys = new ArrayList<>(accepted());
        if (!keys.remove(key)) {
            return queued;
        }
        install(keys);
        return true;
    }

    /** Everyone waiting, oldest first. */
    public List<Waiting> waiting() {
        return waiting(System.currentTimeMillis());
    }

    synchronized List<Waiting> waiting(long now) {
        expire(now);
        return List.copyOf(queue.values());
    }

    /** Everyone let in, as the config file holds them. */
    public List<String> accepted() {
        return KnobSpec.splitList(Config.get().s(Knob.WEB_ACCEPTED_KEYS));
    }

    /**
     * Whether this key is <em>still</em> on the list — what a live stream asks between frames, so
     * that a revoke hangs the browser up instead of taking effect at its next reconnection.
     *
     * <p>Deliberately not {@link #check}: it counts no miss and arms no lockout. A browser being
     * hung up on because an operator revoked it is not guessing, and three seconds of shut door is
     * the opposite of what that operator is in the middle of doing.
     */
    public boolean stillAccepted(@Nullable String key) {
        return key != null && accepted().contains(key);
    }

    /** Forgets the queue and shuts the door — what stopping the server means for this. */
    public synchronized void clear() {
        queue.clear();
        charged.clear();
        openUntilMillis = 0;
        lockedUntilMillis = 0;
    }

    // --- internals ----------------------------------------------------------------------------

    /** Whether {@code key} could be a browser's — see {@link #SHAPE}. */
    public static boolean wellFormed(@Nullable String key) {
        return key != null && key.length() <= MAX_KEY_LENGTH && SHAPE.matcher(key).matches();
    }

    private boolean isAccepted(String key) {
        return accepted().contains(key);
    }

    /**
     * Whether a miss is still costing everybody. Checked <b>before the key is looked at</b>, on
     * both routes: comparing it first and only then refusing would leave guessing an accepted key
     * as fast as the socket answers, which is the one attack the three seconds exist for.
     *
     * <p>The cost is that a browser already accepted is refused for those three seconds too. It
     * retries, and so does the page — a lockout that spared known keys would have to compare them,
     * which is the thing it must not do.
     */
    private boolean locked(long now) {
        return now < lockedUntilMillis;
    }

    /**
     * A key that means nothing here. Shuts everything for everybody — the <b>first</b> time that
     * key is seen, and only then.
     *
     * <p>Charging every attempt was the first shape and it does not survive two browsers: a tab
     * waiting to be accepted polls with key A, another with key B, and each is a different key
     * from the one before it, so between them they re-arm the lockout forever and an <em>accepted</em>
     * browser is refused most of the time. Found by hitting it, with a real page open. Charging
     * per key rather than per attempt rates exactly what needs rating.
     */
    private void miss(String key, long now) {
        if (charged.add(key)) {
            lockedUntilMillis = now + LOCKOUT_MILLIS;
        }
        // Bounded, and cleared wholesale rather than evicted one at a time: a guesser that has
        // spent MAX_CHARGED × 3s to fill it may re-pay for keys it already knows are wrong.
        if (charged.size() > MAX_CHARGED) {
            charged.clear();
        }
    }

    private void install(List<String> keys) {
        Config.install(Config.get().with(Knob.WEB_ACCEPTED_KEYS, KnobSpec.joinList(keys)));
        flush.run();
    }

    /** Drops browsers that stopped asking, so the list is who is waiting rather than who once did. */
    private void expire(long now) {
        Iterator<Waiting> waiting = queue.values().iterator();
        while (waiting.hasNext()) {
            if (now - waiting.next().lastAskedMillis() > QUEUE_TTL_MILLIS) {
                waiting.remove();
            }
        }
    }

    /** Keeps the queue bounded. Oldest goes: the newest arrival is the one somebody is watching. */
    private void trim() {
        Iterator<String> oldest = queue.keySet().iterator();
        while (queue.size() > MAX_QUEUED && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }
}
