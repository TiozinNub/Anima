package dev.luizloyola.anima.core.brain.knowledge;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What perception makes of a block — the whole vocabulary the crescent probe, the ray fan and the
 * growth rules need.
 *
 * <p><b>Open, not an enum</b>: closed, the LIBRARY would have to learn what a pumpkin is, as it
 * once knew trees before {@link PoiKind} and {@link GrowthRules} were opened. Instances are
 * canonical per key, so {@code ==} is safe and two mods cannot disagree about {@code "log"}.
 * Declaring a kind is half the job; {@code BlockKinds} in the compat layer recognises one, as
 * {@code Being.Kind} pairs with {@code BeingKinds}.
 *
 * <p><b>Nothing here reaches disk</b> (beliefs persist, the blocks behind them do not), so a key
 * may be renamed freely, and unlike {@link PoiKind} this carries no warning about it.
 *
 * <p>{@link #AIR}, {@link #OTHER} and {@link #UNKNOWN} are the three answers a sense must always
 * have; {@link #WATER} rides the collision-free floor rule. {@link #LOG} and {@link #LEAVES} are
 * botany on loan, kept only because Anima's {@code Flocks} reads a canopy to decide whether a
 * dropped item stands on something reachable.
 */
public final class BlockKind {

    /** Insertion-ordered so listings are stable between runs. */
    private static final Map<String, BlockKind> REGISTERED = new LinkedHashMap<>();

    public static final BlockKind AIR = register("air");

    /** Any log/stem the tree rule treats as trunk material. */
    public static final BlockKind LOG = register("log");

    /**
     * A leaf block that GREW — one that would decay if its tree were felled. Placed leaves never
     * decay and are building material; the compat probe hands them over as {@link #OTHER}.
     */
    public static final BlockKind LEAVES = register("leaves");

    /** A water source or flowing water. */
    public static final BlockKind WATER = register("water");

    /** Something, with no better name for it — stone, dirt, a chest, a pumpkin nobody claimed. */
    public static final BlockKind OTHER = registerGround("other");

    /**
     * Out of reach — unloaded chunk or outside the world. Growth stops here and marks the region
     * {@code partial}: there may be more beyond what they could see.
     */
    public static final BlockKind UNKNOWN = register("unknown");

    private final String key;
    private final boolean ground;

    /** Owned by {@link GrowthRules}: a field, not a map, because every probed column asks it. */
    GrowthRule growth;

    private BlockKind(String key, boolean ground) {
        this.key = key;
        this.ground = ground;
    }

    /**
     * Declares a kind of block worth telling apart, or returns the existing one for a key already
     * registered. Call during mod initialization, and pair it with a classifier.
     *
     * @param key stable id, and what a listing shows
     */
    public static synchronized BlockKind register(String key) {
        return register(key, false);
    }

    /**
     * Declares a kind that is solid ground as much as {@link #OTHER} is — stone, a bee nest —
     * so telling it apart takes nothing from whoever asks only whether a block would hold a body
     * or a trunk up ({@link #ground()}).
     */
    public static synchronized BlockKind registerGround(String key) {
        return register(key, true);
    }

    private static BlockKind register(String key, boolean ground) {
        BlockKind existing = REGISTERED.get(key);
        if (existing != null) {
            if (existing.ground != ground) {
                throw new IllegalStateException("block kind \"" + key + "\" is already registered "
                        + (existing.ground ? "as" : "as not") + " ground");
            }
            return existing;
        }
        BlockKind kind = new BlockKind(key, ground);
        REGISTERED.put(key, kind);
        return kind;
    }

    public static synchronized Optional<BlockKind> byKey(String key) {
        return Optional.ofNullable(REGISTERED.get(key));
    }

    /** Every registered kind, in registration order. */
    public static synchronized Collection<BlockKind> all() {
        return Collections.unmodifiableCollection(new LinkedHashMap<>(REGISTERED).values());
    }

    /**
     * Solid ground: {@link #OTHER}, or a kind declared with {@link #registerGround}. What a trunk's
     * foot or a body's footing is asked, rather than {@code == OTHER}, so a consumer's new kind of
     * rock stays rock to everybody else.
     */
    public boolean ground() {
        return this.ground;
    }

    /** Stable id — what a listing shows. */
    public String key() {
        return key;
    }

    @Override
    public String toString() {
        return key;
    }
}
