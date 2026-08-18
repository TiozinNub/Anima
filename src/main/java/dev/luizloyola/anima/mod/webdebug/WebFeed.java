package dev.luizloyola.anima.mod.webdebug;

/**
 * The hand-off between the server tick and the HTTP threads: one rendered frame, replaced whole,
 * plus the version that lets a reader block until there is something new.
 *
 * <p>It also carries the retained {@link WebModel} the frame leaves the browser in, so a reader
 * that has just connected can be handed the whole world through {@link #hello}, rather than the
 * next delta merged onto nothing — the load-bearing safety property behind sending partial frames
 * at all.
 *
 * <p><b>This is the whole thread-safety story of the dashboard.</b> {@code HttpServer} handlers run
 * on their own pool, and reading an {@code AgentBody}, a {@code Navigator} or the directory off the
 * server thread is a data race that will crash or, worse, quietly report a torn half-state. So no
 * handler reads the world at all: {@link WebSnapshot} builds the frame ON the tick thread, hands
 * the finished string here, and a handler only ever copies out a reference to an immutable String.
 * It is {@code DebugViewClient}'s volatile-swap idiom pointed the other way.
 *
 * <p>{@link #publish} must stay cheap and non-blocking — it runs inside the tick.
 */
final class WebFeed {

    /** The latest rendered frame, or null before the first tick has produced one. */
    private volatile String frame;

    /**
     * What the browser is assumed to be holding, kept in step with {@link #frame} under the same
     * lock so a reader cannot be greeted with a world one publish out of date.
     */
    private volatile WebModel model = WebModel.EMPTY;

    /** Bumped on every publish. Readers remember the last one they sent. */
    private volatile long version;

    /** Set once on shutdown, to release every parked reader instead of leaking their threads. */
    private volatile boolean closed;

    /** Whether that shutdown was the debugger stopping rather than a restart. @see #close */
    private volatile boolean farewell;

    /**
     * Replaces the frame and the model it leaves the browser in, and wakes every waiting reader.
     * Called on the server tick thread.
     *
     * <p>The two travel together because a delta and the state it produces are one fact: a reader
     * greeted between the two writes would be handed a world that the very next delta contradicts.
     */
    void publish(WebModel model, String json) {
        synchronized (this) {
            this.model = model;
            frame = json;
            version++;
            notifyAll();
        }
    }

    /** What the browser is assumed to hold. Read on the tick thread, to diff the next build. */
    WebModel model() {
        return model;
    }

    /**
     * The whole retained world, for a reader that has just connected — or null when nothing has
     * been published yet, which is a server whose first tick has not run.
     *
     * <p>The version travels with it so the reader knows what it has already been told. Taken under
     * the lock for the same reason {@link Snapshot} exists: read apart, the two can disagree.
     */
    Snapshot hello() {
        synchronized (this) {
            return model.isEmpty() ? null : new Snapshot(version, model.full());
        }
    }

    /**
     * Wakes every parked reader without publishing anything — each returns as if its keepalive had
     * come due, re-checks whatever it guards, and parks again.
     *
     * <p>What it is for: a revoked browser must lose its stream <em>now</em>, not at the end of a
     * fifteen-second keepalive wait. A quiet world publishes nothing to wake it with.
     */
    void wake() {
        synchronized (this) {
            notifyAll();
        }
    }

    /**
     * Releases every parked reader. After this {@link #awaitAfter} returns null forever — <b>and
     * returns it immediately</b>, without waiting out the timeout. A reader that treats that as
     * its keepalive tick and loops therefore spins as fast as the CPU allows; see
     * {@link #isClosed}. There is no reopening: a feed belongs to one run of the server.
     *
     * @param farewell whether the debugger is stopping, rather than restarting onto a fresh feed.
     *     Only the former is announced on the wire: a browser told "it stopped" by a restart would
     *     sit behind that screen waiting to be pressed, with a server already listening behind it.
     */
    void close(boolean farewell) {
        synchronized (this) {
            closed = true;
            this.farewell = farewell;
            notifyAll();
        }
    }

    /**
     * Whether this feed is done. <b>A reader must check it, not just loop on the null.</b>
     *
     * <p>Stopping and restarting the debugger left every stream parked on the feed the old run
     * closed, where {@link #awaitAfter} answers null with no delay at all: the loop wrote a
     * keepalive, asked again, and got another instant null — eleven million of them in four
     * seconds, on a socket nobody was reading.
     */
    boolean isClosed() {
        return closed;
    }

    /** Whether this feed closed because the debugger stopped. @see #close */
    boolean isFarewell() {
        return farewell;
    }

    long version() {
        return version;
    }

    /**
     * The frame once it is newer than {@code seen}, or null if the feed closed or nothing arrived
     * within {@code timeoutMillis}.
     *
     * <p>The timeout is not a poll — it is what lets an idle stream emit a keepalive and notice a
     * client that has gone away, since a socket write is the only thing that finds that out.
     *
     * <p><b>{@code frame == null} is in the wait condition, not just the return guard.</b> A fresh
     * reader has {@code seen = -1} and a never-published feed has {@code version = 0}, so
     * {@code version <= seen} is false before the first publish ever happens — the {@code -1}/{@code
     * 0} pairing that made the old condition look sufficient. Without this half a feed that has
     * published nothing fell straight through the wait and returned null immediately, over and
     * over, at whatever rate the loop could spin — the same zero-delay shape {@link #isClosed}
     * exists to name, but for "never started" instead of "stopped".
     */
    Snapshot awaitAfter(long seen, long timeoutMillis) throws InterruptedException {
        synchronized (this) {
            if (!closed && (frame == null || version <= seen)) {
                wait(timeoutMillis);
            }
            if (closed || version <= seen || frame == null) {
                return null;
            }
            return new Snapshot(version, frame);
        }
    }

    /** A frame and the version it was published at, read together so they cannot disagree. */
    record Snapshot(long version, String json) {
    }
}
