package dev.luizloyola.anima.mod.webdebug;

/**
 * The hand-off between the server tick and the HTTP threads: one rendered frame, replaced whole,
 * plus the version that lets a reader block until there is something new.
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

    /** Bumped on every publish. Readers remember the last one they sent. */
    private volatile long version;

    /** Set once on shutdown, to release every parked reader instead of leaking their threads. */
    private volatile boolean closed;

    /** Replaces the frame and wakes every waiting reader. Called on the server tick thread. */
    void publish(String json) {
        synchronized (this) {
            frame = json;
            version++;
            notifyAll();
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

    /** Releases every parked reader. After this {@link #awaitAfter} returns null forever. */
    void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
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
     */
    Snapshot awaitAfter(long seen, long timeoutMillis) throws InterruptedException {
        synchronized (this) {
            if (!closed && version <= seen) {
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
