package dev.luizloyola.anima.core.nav;

/**
 * Open-addressed maps keyed by a packed cell — the search's own hash tables, holding {@code long}
 * keys as {@code long}s.
 *
 * <p><b>Why not {@code HashMap}.</b> A {@code Map<Long, ?>} boxes its key on every call and
 * {@code computeIfAbsent} allocates a capture object whether or not the entry is there: of the
 * <b>229 MiB</b> spark attributed to navigation over 45 seconds of twenty-four settlers, 45% was
 * that lambda and 39% {@link Long#valueOf}, and the three maps took 470 ms of the 960 ms in
 * {@code isCareful} — itself a fifth of the server thread. A search probes each of thousands of
 * expanded cells from every incident edge, so the memo lookup is one of the hottest operations in
 * the mod.
 *
 * <p>Nothing here is clever — linear probing, power-of-two capacity, no removal. No third-party
 * primitive-collection library is available either: {@code core/} may not name anything that
 * arrives with the game (see {@code ArchitectureTest}), fastutil included.
 *
 * <p><b>Emptiness is the VALUE, never the key.</b> {@code Pathfinder.pack(0, 0, 0)} is {@code 0L},
 * so a zero key is an ordinary cell and a key-based sentinel would silently lose the origin. A
 * slot is free when its value is {@code null} (or byte {@code 0}), which no stored value can be.
 */
final class CellTable {
    private CellTable() {
    }

    /** Rounds up to a power of two, so the mask can replace a modulo. */
    private static int sized(int wanted) {
        int n = 16;
        while (n < wanted) {
            n <<= 1;
        }
        return n;
    }

    /**
     * Spreads a packed cell across the whole word before masking it to a bucket — the murmur3
     * 64-bit finalizer, and not optional: a packed cell is three coordinates in fixed bit ranges,
     * so neighbouring cells differ only in the low bits and a raw key would drop a whole row of
     * ground into one bucket. That clustering turned {@code HashMap}'s bins into trees.
     */
    private static int bucket(long key, int mask) {
        long h = key;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (int) h & mask;
    }

    /**
     * A {@code long}-keyed map of search nodes. Insert and lookup only — the search never removes
     * a cell, which lets probing stop at the first free slot with no tombstones. Iteration is by
     * slot index rather than an iterator or a callback, so the sweeps over every reached cell keep
     * their early exits and allocate nothing.
     */
    static final class Nodes {
        private long[] keys;
        private Pathfinder.Node[] values;
        private int size;
        private int mask;
        private int limit;

        Nodes(int capacity) {
            int n = sized(capacity);
            this.keys = new long[n];
            this.values = new Pathfinder.Node[n];
            this.mask = n - 1;
            this.limit = n * 3 / 4;
        }

        Pathfinder.Node get(long key) {
            int i = bucket(key, this.mask);
            while (true) {
                Pathfinder.Node v = this.values[i];
                if (v == null) {
                    return null;
                }
                if (this.keys[i] == key) {
                    return v;
                }
                i = (i + 1) & this.mask;
            }
        }

        void put(long key, Pathfinder.Node value) {
            int i = bucket(key, this.mask);
            while (true) {
                if (this.values[i] == null) {
                    this.keys[i] = key;
                    this.values[i] = value;
                    if (++this.size > this.limit) {
                        grow();
                    }
                    return;
                }
                if (this.keys[i] == key) {
                    this.values[i] = value;
                    return;
                }
                i = (i + 1) & this.mask;
            }
        }

        int size() {
            return this.size;
        }

        /** How many slots {@link #keyAt}/{@link #valueAt} span — most of them empty. */
        int capacity() {
            return this.values.length;
        }

        long keyAt(int slot) {
            return this.keys[slot];
        }

        /** The node in this slot, or {@code null} when the slot is free. */
        Pathfinder.Node valueAt(int slot) {
            return this.values[slot];
        }

        private void grow() {
            long[] oldKeys = this.keys;
            Pathfinder.Node[] oldValues = this.values;
            int n = oldValues.length << 1;
            this.keys = new long[n];
            this.values = new Pathfinder.Node[n];
            this.mask = n - 1;
            this.limit = n * 3 / 4;
            for (int i = 0; i < oldValues.length; i++) {
                if (oldValues[i] != null) {
                    int j = bucket(oldKeys[i], this.mask);
                    while (this.values[j] != null) {
                        j = (j + 1) & this.mask;
                    }
                    this.keys[j] = oldKeys[i];
                    this.values[j] = oldValues[i];
                }
            }
        }
    }

    /**
     * A {@code long}-keyed set of remembered yes/no answers — the shape both of the search's memos
     * want, and the one a {@code Map<Long, Boolean>} served worst. Three states in one byte: "not
     * asked yet" is a distinct answer from "asked, and false", and conflating them turns a memo
     * into a re-computation.
     */
    static final class Flags {
        /** No answer stored. Also what an empty slot reads as, which is the same thing. */
        static final byte UNKNOWN = 0;
        static final byte FALSE = 1;
        static final byte TRUE = 2;

        private long[] keys;
        private byte[] values;
        private int size;
        private int mask;
        private int limit;

        Flags(int capacity) {
            int n = sized(capacity);
            this.keys = new long[n];
            this.values = new byte[n];
            this.mask = n - 1;
            this.limit = n * 3 / 4;
        }

        /** {@link #UNKNOWN}, {@link #FALSE} or {@link #TRUE}. */
        byte get(long key) {
            int i = bucket(key, this.mask);
            while (true) {
                byte v = this.values[i];
                if (v == UNKNOWN) {
                    return UNKNOWN;
                }
                if (this.keys[i] == key) {
                    return v;
                }
                i = (i + 1) & this.mask;
            }
        }

        void put(long key, boolean value) {
            byte stored = value ? TRUE : FALSE;
            int i = bucket(key, this.mask);
            while (true) {
                if (this.values[i] == UNKNOWN) {
                    this.keys[i] = key;
                    this.values[i] = stored;
                    if (++this.size > this.limit) {
                        grow();
                    }
                    return;
                }
                if (this.keys[i] == key) {
                    this.values[i] = stored;
                    return;
                }
                i = (i + 1) & this.mask;
            }
        }

        private void grow() {
            long[] oldKeys = this.keys;
            byte[] oldValues = this.values;
            int n = oldValues.length << 1;
            this.keys = new long[n];
            this.values = new byte[n];
            this.mask = n - 1;
            this.limit = n * 3 / 4;
            for (int i = 0; i < oldValues.length; i++) {
                if (oldValues[i] != UNKNOWN) {
                    int j = bucket(oldKeys[i], this.mask);
                    while (this.values[j] != UNKNOWN) {
                        j = (j + 1) & this.mask;
                    }
                    this.keys[j] = oldKeys[i];
                    this.values[j] = oldValues[i];
                }
            }
        }
    }
}
