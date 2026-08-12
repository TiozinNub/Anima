package dev.luizloyola.anima.core.brain.act;

/**
 * The gaze actuator port — the one way anything asks a body to look at something. A head used to
 * have three writers (the legs, each arm actuator, the swimmer's pitch) with none in charge, so a
 * body that stopped walking froze, staring at its last waypoint.
 *
 * <p><b>A claim, not a command.</b> {@link #lookAt} asks for the head, for a while, at a rank; the
 * organ resolves the claims each tick, eases the head toward the winner and decides when the body
 * comes along. Claims expire on their own, so a task cancelled at any tick need not release one.
 *
 * <p><b>Gaze is not decoration.</b> Both perception organs take the HEAD's bearing as the axis of
 * their cone, so what a body looks at is what a body can see.
 */
public interface Gazer {

    /**
     * Who outranks whom when two things want the same head, lowest first. The organ keeps the
     * highest live claim each tick; equal ranks go to whoever asked most recently.
     *
     * <p>The swimmer is absent by design: it owns the pitch of a wet body by running after the gaze
     * organ, which is an ordering of the tick rather than a rank.
     */
    enum Priority {
        /** The body's own attention: whatever it looks at when nothing needs its eyes. */
        IDLE,
        /** The legs: look where you are going. */
        NAV,
        /** The arms: a body looks at what it breaks, places or climbs. */
        WORK
    }

    /**
     * Ask for the head to point at a world point.
     *
     * @param x world X of the point to look at
     * @param y world Y — the point itself, not a cell corner: the organ aims the eyes, so a
     *     block's centre is what a caller working on a block should pass
     * @param z world Z of the point to look at
     * @param priority who is asking (see {@link Priority})
     * @param holdTicks how long this claim stays live without being re-asserted. A caller that
     *     runs every tick may pass 1 and keep asking; a one-shot act (a block placed, a
     *     greeting) passes enough ticks for the look to register, since a head that snapped
     *     back the instant the act finished would read as a twitch
     */
    void lookAt(double x, double y, double z, Priority priority, int holdTicks);

    /** As {@link #lookAt(double, double, double, Priority, int)}, for one tick only. */
    default void lookAt(double x, double y, double z, Priority priority) {
        lookAt(x, y, z, priority, 1);
    }

    /**
     * A body whose head nothing can steer — the default on {@link ActuatorAccess}. Silently ignores
     * every claim, so a task may ask to look at something with no fixture behind it.
     */
    Gazer NONE = (x, y, z, priority, holdTicks) -> {
    };
}
