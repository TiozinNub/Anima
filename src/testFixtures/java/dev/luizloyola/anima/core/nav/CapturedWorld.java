package dev.luizloyola.anima.core.nav;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link NavGrid} read back from a region captured out of a live world by
 * {@code /anima nav dump} — the counterpart to {@link AsciiWorld}.
 *
 * <p>{@link AsciiWorld} is drawn by hand, so the map is the specification; this one is captured, so
 * the map is <em>evidence</em>: it carries what the live classifier said about real blockstates,
 * the only way a headless test catches it calling a staircase a wall.
 *
 * <p>Format: {@code # box minX minY minZ maxX maxY maxZ} in the header, then one
 * {@code <code> <x> <y> <z>} line per non-passable cell in world coordinates, {@code #} comments
 * ignored. Unmentioned cells inside the box are {@link CellType#PASSABLE}, everything outside is
 * {@link CellType#OBSTACLE} per the {@link NavGrid} contract. World-space coordinates mean a query
 * recorded from the game replays verbatim, with no origin arithmetic.
 */
public final class CapturedWorld implements NavGrid {
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final Map<Long, CellType> cells;

    private CapturedWorld(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                          Map<Long, CellType> cells) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.cells = cells;
    }

    /** Parses a capture. {@code lines} is the whole file, in order. */
    public static CapturedWorld parse(List<String> lines) {
        int[] box = null;
        Map<Long, CellType> cells = new HashMap<>();
        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                if (line.startsWith("# box ")) {
                    box = ints(line.substring("# box ".length()), 6, lineNo);
                }
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length != 4 || parts[0].length() != 1) {
                throw new IllegalArgumentException("line " + lineNo + ": expected '<code> x y z', got: " + line);
            }
            CellType type = CellType.byCode(parts[0].charAt(0));
            cells.put(Pathfinder.pack(parse(parts[1], lineNo), parse(parts[2], lineNo),
                    parse(parts[3], lineNo)), type);
        }
        if (box == null) {
            throw new IllegalArgumentException("capture has no '# box minX minY minZ maxX maxY maxZ' header");
        }
        return new CapturedWorld(box[0], box[1], box[2], box[3], box[4], box[5], cells);
    }

    private static int[] ints(String text, int count, int lineNo) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length != count) {
            throw new IllegalArgumentException("line " + lineNo + ": expected " + count + " numbers");
        }
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = parse(parts[i], lineNo);
        }
        return values;
    }

    private static int parse(String text, int lineNo) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("line " + lineNo + ": not a number: " + text);
        }
    }

    @Override
    public CellType cell(int x, int y, int z) {
        if (x < this.minX || x > this.maxX || y < this.minY || y > this.maxY
                || z < this.minZ || z > this.maxZ) {
            return CellType.OBSTACLE;
        }
        CellType type = this.cells.get(Pathfinder.pack(x, y, z));
        return type != null ? type : CellType.PASSABLE;
    }

    /** Whether the inclusive box {@code [min, max]} lies fully inside this capture. */
    public boolean covers(int x1, int y1, int z1, int x2, int y2, int z2) {
        return x1 >= this.minX && x2 <= this.maxX
                && y1 >= this.minY && y2 <= this.maxY
                && z1 >= this.minZ && z2 <= this.maxZ;
    }

    /** The capture's bounds, as {@code minX minY minZ maxX maxY maxZ} — for failure messages. */
    public String bounds() {
        return this.minX + " " + this.minY + " " + this.minZ
                + " .. " + this.maxX + " " + this.maxY + " " + this.maxZ;
    }

    /** How many cells the capture spells out (everything not {@link CellType#PASSABLE}). */
    public int recordedCells() {
        return this.cells.size();
    }

    public static List<String> lines(java.io.InputStream in) {
        List<String> out = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.add(line);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read capture", e);
        }
        return out;
    }
}
