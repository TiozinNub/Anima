package dev.luizloyola.anima.core.nav;

/**
 * Reads a {@link CellType#DOOR} cell's code ({@link NavGrid#doorway}): which of its four faces a
 * body can cross, and whether it can stand in its middle, as the door stands and once swung — and
 * who can swing it, from which side.
 *
 * <p>Faces, not an axis, because what blocks the way is the panel and a panel lies on one face. A
 * door facing north, shut, has its panel on the south face: a body crosses it east–west, and may
 * come in from the north and leave by the west. Swung open, the panel moves to the east or west face
 * and the ways through turn with it. A shut gate's bar crosses the middle, so nothing stands in it
 * until it is swung.
 *
 * <p>Faces are named by {@link NavGrid#heading}: a body leaving the cell northward crosses its
 * {@link NavGrid#NORTH} face, one coming in northward crosses its {@link NavGrid#SOUTH} face.
 */
public final class Doorway {
    private static final int FACES = 0xF;
    private static final int MIDDLE_NOW = 1 << 4;
    private static final int SWUNG_SHIFT = 5;
    private static final int MIDDLE_SWUNG = 1 << 9;
    private static final int FROM_SHIFT = 10;
    private static final int BY_HAND = 1 << 14;

    private Doorway() {
    }

    /**
     * Packs one door's readings.
     *
     * @param facesNow   the faces a body cannot cross as it stands, as heading bits
     * @param middleNow  whether something stands across the middle as it stands (a shut gate)
     * @param facesSwung the same, once swung
     * @param swingFrom  the faces a body can swing it from, standing outside that face: every face
     *                   for a hand, the sides with a button, lever or pressure plate for an iron
     *                   door, none for something nobody moves
     * @param byHand     whether a hand swings it — so also from inside, standing in the doorway
     */
    public static int of(int facesNow, boolean middleNow, int facesSwung, boolean middleSwung,
                         int swingFrom, boolean byHand) {
        return (facesNow & FACES) | (middleNow ? MIDDLE_NOW : 0)
                | (facesSwung & FACES) << SWUNG_SHIFT | (middleSwung ? MIDDLE_SWUNG : 0)
                | (swingFrom & FACES) << FROM_SHIFT | (byHand ? BY_HAND : 0);
    }

    /** The same door, swingable from these faces too — an activator found beside it. */
    public static int swingableFrom(int code, int faces) {
        return code | (faces & FACES) << FROM_SHIFT;
    }

    /** Whether a body crosses these faces, and the middle between them, with the door as it stands or swung. */
    public static boolean free(int code, boolean swung, int faces) {
        int blocked = swung ? code >> SWUNG_SHIFT & FACES : code & FACES;
        boolean middle = (code & (swung ? MIDDLE_SWUNG : MIDDLE_NOW)) != 0;
        return !middle && (blocked & faces) == 0;
    }

    /** Whether a body standing outside {@code face} can swing it, given whether it has a hand. */
    public static boolean swingsFrom(int code, int face, boolean hands) {
        return hands && (code >> FROM_SHIFT & face) != 0;
    }

    /** Whether a body standing in the doorway can swing it: only a hand reaches from there. */
    public static boolean swingsInside(int code, boolean hands) {
        return hands && (code & BY_HAND) != 0;
    }

    /** Whether a hand swings it at all — never an iron door. */
    public static boolean byHand(int code) {
        return (code & BY_HAND) != 0;
    }

    /** Whether swinging it changes anything a body could use — any way at all to swing it. */
    public static boolean swings(int code) {
        return (code >> FROM_SHIFT & FACES) != 0;
    }

    /**
     * Whether a body can be in this cell at all: its middle is clear, or can be made clear by a
     * swing this body can make. A door's panel is on a face, so it always can; a shut gate's bar is
     * not.
     */
    public static boolean holds(int code, boolean hands) {
        return free(code, false, 0) || (hands && swings(code) && free(code, true, 0));
    }

    /**
     * The state a body should have the door in for a crossing that comes in through {@code in} and
     * leaves through {@code out} (either may be 0 for "none"): {@code false} for as it stands,
     * {@code true} for swung, or {@code null} when no one state lets it through both — it is then
     * entered as {@link #entryState} says and swung again inside.
     */
    public static Boolean crossingState(int code, int in, int out) {
        if (free(code, false, in | out)) return Boolean.FALSE;
        if (free(code, true, in | out)) return Boolean.TRUE;
        return null;
    }

    /** The state a body comes in through {@code in} with: as it stands if that lets it, else swung. */
    public static boolean entryState(int code, int in) {
        return !free(code, false, in);
    }
}
