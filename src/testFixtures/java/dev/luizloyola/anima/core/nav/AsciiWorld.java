package dev.luizloyola.anima.core.nav;

import java.util.HashMap;
import java.util.Map;

/**
 * A hand-drawable {@link NavGrid} for tests: rows of characters form a heightmap (row index = z,
 * column index = x), so a small terrain reads like a top-down map in the test source.
 *
 * <pre>
 *   '1'..'9'  column of solid ground below height d — feet level is y = d ("111" is flat ground
 *             you walk on at y=1, "12" is a one-block step up)
 *   '#'       wall: solid at every y, never passable
 *   ' '       bottomless hole: passable at every y, nothing to land on
 *   'L'       lava pool: the surface cell (y = 0) is DANGER, solid below, open above
 *   'W'       OPEN water: two cells deep (y = 0 and y = -1), bed below, open above — deep
 *             enough that a body must swim, since it cannot stand on the bed with its head out
 *   'w'       a PUDDLE: one cell deep (y = 0), bed at y = -1 — shallow enough to wade
 * </pre>
 *
 * Anything outside the drawn rows is {@link CellType#OBSTACLE}, per the {@link NavGrid} contract.
 * For shapes a heightmap cannot draw (ceilings, tunnels), {@link #fill} overrides a box of cells
 * with an explicit type; {@link #step} puts a partial floor (a slab, a carpet) into one cell,
 * {@link #stair} a ramp, {@link #door} a doorway and {@link #climb} a ladder; {@link #fixed} marks
 * passable cells nothing may be laid into.
 */
public final class AsciiWorld implements NavGrid {
    private final String[] rows;
    private final Map<Long, CellType> overrides = new HashMap<>();
    private final Map<Long, Double> surfaces = new HashMap<>();
    private final Map<Long, Integer> payloads = new HashMap<>();
    private final java.util.Set<Long> hatches = new java.util.HashSet<>();
    private final java.util.Set<Long> fixed = new java.util.HashSet<>();
    private final java.util.Set<Long> soft = new java.util.HashSet<>();
    private final java.util.Set<Long> regrowing = new java.util.HashSet<>();
    private final java.util.Set<Long> farmland = new java.util.HashSet<>();
    /** Whether this map has a surface — see {@link #natural}. */
    private boolean natural;

    private AsciiWorld(String[] rows) {
        this.rows = rows;
    }

    public static AsciiWorld of(String... rows) {
        return new AsciiWorld(rows);
    }

    /** Overrides every cell in the inclusive box with {@code type} — for ceilings and tunnels. */
    public AsciiWorld fill(int x1, int y1, int z1, int x2, int y2, int z2, CellType type) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    this.overrides.put(Pathfinder.pack(x, y, z), type);
                }
            }
        }
        return this;
    }

    /**
     * Puts a {@link CellType#STEP} of the given surface height into the inclusive box: {@code 0.5}
     * is a bottom slab, {@code 0.0625} a carpet, {@code 0.9375} a dirt path. A separate call rather
     * than a map glyph because a drawn map is a heightmap of whole cells.
     */
    public AsciiWorld step(int x1, int y1, int z1, int x2, int y2, int z2, double surface) {
        fill(x1, y1, z1, x2, y2, z2, CellType.STEP);
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    this.surfaces.put(Pathfinder.pack(x, y, z), surface);
                }
            }
        }
        return this;
    }

    /**
     * A stair: a full block at {@code (x,y,z)} a body walks up by a low tread, heading the
     * {@link NavGrid#ramps} directions given.
     */
    public AsciiWorld stair(int x, int y, int z, int ramps) {
        fill(x, y, z, x, y, z, CellType.GROUND);
        this.payloads.put(Pathfinder.pack(x, y, z), ramps);
        return this;
    }

    /** A doorway through the inclusive box, with the {@link Doorway} passages given. */
    public AsciiWorld door(int x1, int y1, int z1, int x2, int y2, int z2, int passages) {
        fill(x1, y1, z1, x2, y2, z2, CellType.DOOR);
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    this.payloads.put(Pathfinder.pack(x, y, z), passages);
                }
            }
        }
        return this;
    }

    /**
     * Passable cells holding something a placement does not replace — a torch, a rail — through
     * the inclusive box: walked through, never laid into. See {@link NavGrid#layable}.
     */
    public AsciiWorld fixed(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    this.fixed.add(Pathfinder.pack(x, y, z));
                }
            }
        }
        return this;
    }

    /**
     * This map with its edge the edge of the world, as a capture's is: past the drawn rows
     * {@link NavGrid#inBounds} says no. The map itself keeps the default and reads its edge as
     * walls — which a pillar would lean on.
     */
    public NavGrid bounded() {
        AsciiWorld map = this;
        int depth = this.rows.length;
        int width = 0;
        for (String row : this.rows) {
            width = Math.max(width, row.length());
        }
        int w = width;
        return new NavGrid() {
            @Override public CellType cell(int x, int y, int z) { return map.cell(x, y, z); }
            @Override public double surface(int x, int y, int z) { return map.surface(x, y, z); }
            @Override public int ramps(int x, int y, int z) { return map.ramps(x, y, z); }
            @Override public int doorway(int x, int y, int z) { return map.doorway(x, y, z); }
            @Override public boolean hatch(int x, int y, int z) { return map.hatch(x, y, z); }
            @Override public boolean climbFloor(int x, int y, int z) { return map.climbFloor(x, y, z); }
            @Override public boolean hasDoors() { return map.hasDoors(); }
            @Override public boolean layable(int x, int y, int z) { return map.layable(x, y, z); }
            @Override public boolean soft(int x, int y, int z) { return map.soft(x, y, z); }
            @Override public boolean regrows(int x, int y, int z) { return map.regrows(x, y, z); }
            @Override public boolean farmland(int x, int y, int z) { return map.farmland(x, y, z); }
            @Override public int naturalTop(int x, int z) { return map.naturalTop(x, z); }
            @Override public boolean inBounds(int x, int y, int z) {
                return x >= 0 && x < w && z >= 0 && z < depth;
            }
        };
    }

    /** Ground cells through the inclusive box that are soft — see {@link NavGrid#soft}. */
    public AsciiWorld soft(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    this.soft.add(Pathfinder.pack(x, y, z));
                }
            }
        }
        return this;
    }

    @Override
    public boolean soft(int x, int y, int z) {
        return cell(x, y, z) == CellType.GROUND && this.soft.contains(Pathfinder.pack(x, y, z));
    }

    /** Soft cells through the inclusive box whose cover grows back — see {@link NavGrid#regrows}. */
    public AsciiWorld regrows(int x1, int y1, int z1, int x2, int y2, int z2) {
        soft(x1, y1, z1, x2, y2, z2);
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    this.regrowing.add(Pathfinder.pack(x, y, z));
                }
            }
        }
        return this;
    }

    @Override
    public boolean regrows(int x, int y, int z) {
        return soft(x, y, z) && this.regrowing.contains(Pathfinder.pack(x, y, z));
    }

    /**
     * Farmland through the inclusive box: a {@link CellType#STEP} at vanilla's 15/16, marked
     * {@link NavGrid#farmland}. Drawn at the floor's own y, like any partial floor.
     */
    public AsciiWorld farmland(int x1, int y1, int z1, int x2, int y2, int z2) {
        step(x1, y1, z1, x2, y2, z2, 0.9375);
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    this.farmland.add(Pathfinder.pack(x, y, z));
                }
            }
        }
        return this;
    }

    @Override
    public boolean farmland(int x, int y, int z) {
        return cell(x, y, z) == CellType.STEP && this.farmland.contains(Pathfinder.pack(x, y, z));
    }

    @Override
    public boolean layable(int x, int y, int z) {
        return cell(x, y, z) == CellType.PASSABLE && !this.fixed.contains(Pathfinder.pack(x, y, z));
    }

    /** A climbable (a ladder, vines) through the inclusive box. */
    public AsciiWorld climb(int x1, int y1, int z1, int x2, int y2, int z2) {
        return fill(x1, y1, z1, x2, y2, z2, CellType.CLIMB);
    }

    /** Scaffolding through the inclusive box: climbed inside, stood on top. */
    public AsciiWorld scaffolding(int x1, int y1, int z1, int x2, int y2, int z2) {
        fill(x1, y1, z1, x2, y2, z2, CellType.CLIMB);
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    this.payloads.put(Pathfinder.pack(x, y, z), 1);
                }
            }
        }
        return this;
    }

    /** A hatch at one cell: a shut trapdoor over a ladder, a floor of the given surface. */
    public AsciiWorld hatch(int x, int y, int z, double surface) {
        if (surface >= 1.0) {
            fill(x, y, z, x, y, z, CellType.GROUND);
        } else {
            step(x, y, z, x, y, z, surface);
        }
        this.hatches.add(Pathfinder.pack(x, y, z));
        return this;
    }

    @Override
    public boolean hatch(int x, int y, int z) {
        return this.hatches.contains(Pathfinder.pack(x, y, z));
    }

    @Override
    public boolean climbFloor(int x, int y, int z) {
        return cell(x, y, z) == CellType.CLIMB && this.payloads.getOrDefault(Pathfinder.pack(x, y, z), 0) != 0;
    }

    @Override
    public int ramps(int x, int y, int z) {
        return cell(x, y, z) == CellType.GROUND ? this.payloads.getOrDefault(Pathfinder.pack(x, y, z), 0) : 0;
    }

    @Override
    public int doorway(int x, int y, int z) {
        return cell(x, y, z) == CellType.DOOR ? this.payloads.getOrDefault(Pathfinder.pack(x, y, z), 0) : 0;
    }

    @Override
    public CellType cell(int x, int y, int z) {
        CellType override = this.overrides.get(Pathfinder.pack(x, y, z));
        if (override != null) return override;
        if (z < 0 || z >= this.rows.length || x < 0 || x >= this.rows[z].length()) {
            return CellType.OBSTACLE;
        }
        char c = this.rows[z].charAt(x);
        switch (c) {
            case '#': return CellType.GROUND;
            case ' ': return CellType.PASSABLE;
            case 'L': return y == 0 ? CellType.DANGER : y < 0 ? CellType.GROUND : CellType.PASSABLE;
            // Two cells deep, so a body of any ordinary height has to swim it. Drawn water was one
            // cell until wading arrived, when every test that meant "open water" got a puddle —
            // the glyph has to say which of the two it is.
            case 'W': return y >= -1 && y <= 0 ? CellType.WATER
                    : y < -1 ? CellType.GROUND : CellType.PASSABLE;
            case 'w': return y == 0 ? CellType.WATER : y < 0 ? CellType.GROUND : CellType.PASSABLE;
            default:
                if (c < '1' || c > '9') throw new IllegalArgumentException("bad map char: '" + c + "'");
                return y < c - '0' ? CellType.GROUND : CellType.PASSABLE;
        }
    }

    @Override
    public double surface(int x, int y, int z) {
        Double drawn = this.surfaces.get(Pathfinder.pack(x, y, z));
        if (drawn != null) return drawn;
        return NavGrid.super.surface(x, y, z);
    }

    /**
     * This map with a surface ({@link NavGrid#naturalTop}): every block drawn is natural, so a
     * column's top is over its highest ground, overrides included — a hill over a tunnel is the
     * hill's. Without it the map has none, as every map had before there was one.
     */
    public AsciiWorld natural() {
        this.natural = true;
        return this;
    }

    @Override
    public int naturalTop(int x, int z) {
        if (!this.natural || z < 0 || z >= this.rows.length || x < 0 || x >= this.rows[z].length()) {
            return Surface.UNKNOWN;
        }
        for (int y = NATURAL_SCAN; y >= -NATURAL_SCAN; y--) {
            if (cell(x, y, z) == CellType.GROUND) {
                return y + 1;
            }
        }
        return Surface.UNKNOWN;
    }

    /** How high and low {@link #naturalTop} looks: past any drawn height or override in a test. */
    private static final int NATURAL_SCAN = 40;
}
