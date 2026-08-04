package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The flood fill, held against a plain hash-set walk of the same shape: {@link RegionGrowth}
 * keeps its seen-set as a bit per cell of a box around the seed rather than a
 * {@code HashSet<Pos>}, bookkeeping only since nothing iterates that set.
 *
 * <p><b>Order is asserted, not just membership.</b> Rules individuate in collection order and an
 * anchor's identity hangs off it, so a set comparison would wave a wrong-order mass through.
 *
 * <p>Randomised over real boundaries, the spread cap, the block cap, and unloaded world.
 */
class RegionGrowthEquivalenceTest {

    @BeforeEach
    void registerWhatGrows() {
        FakeGrowthRule.register();
    }

    @AfterEach
    void restore() {
        GrowthRules.reset();
        Config.reset();
    }

    private static AgentProfile spread(int cap) {
        Map<ProfileAspect, Double> overrides = Map.of(
                ProfileAspect.PLACES_REGION_MAX_SPREAD, (double) cap);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_growth_" + cap);
        for (ProfileAspect aspect : ProfileAspect.values()) {
            builder.set(aspect, overrides.getOrDefault(aspect, TestSpecies.BIPED.get(aspect)));
        }
        return builder.build().fixed();
    }

    /**
     * The walk as it was before the bitset: a {@code HashSet<Pos>}, a cell object per neighbour.
     * Not budgeted — a reference need not be resumable.
     */
    private static Map<Pos, BlockKind> theOldWay(GrowthRule rule, Pos seed, BlockKind seedKind,
            BlockProbe probe, int spreadCap, int blockCap) {
        Map<Pos, BlockKind> blocks = new LinkedHashMap<>();
        Set<Pos> seen = new HashSet<>();
        Deque<Pos> frontier = new ArrayDeque<>();
        blocks.put(seed, seedKind);
        seen.add(seed);
        frontier.add(seed);
        while (!frontier.isEmpty()) {
            Pos p = frontier.pollFirst();
            for (int[] d : neighbours()) {
                Pos n = new Pos(p.x() + d[0], p.y() + d[1], p.z() + d[2]);
                if (!seen.add(n)) {
                    continue;
                }
                BlockKind kind = probe.at(n.x(), n.y(), n.z());
                if (kind == BlockKind.UNKNOWN || !rule.joins(n, kind, probe)) {
                    continue;
                }
                int cheb = Math.max(Math.abs(n.x() - seed.x()),
                        Math.max(Math.abs(n.y() - seed.y()), Math.abs(n.z() - seed.z())));
                if (cheb > spreadCap) {
                    continue;
                }
                if (blocks.size() >= blockCap) {
                    frontier.clear();
                    break;
                }
                blocks.put(n, kind);
                frontier.addLast(n);
            }
        }
        return blocks;
    }

    /** Faces first, then edges and corners — the same order {@link RegionGrowth} walks. */
    private static int[][] neighbours() {
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int[][] all = new int[26][];
        System.arraycopy(faces, 0, all, 0, faces.length);
        int i = faces.length;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1) {
                        all[i++] = new int[] {dx, dy, dz};
                    }
                }
            }
        }
        return all;
    }

    /** Runs the real growth to completion in small slices, so resumption is exercised too. */
    private static GrownRegion grow(GrowthRule rule, Pos seed, BlockKind kind, AgentProfile profile,
            BlockProbe probe, int slice) {
        RegionGrowth growth = new RegionGrowth(rule, seed, kind, profile);
        for (int guard = 0; guard < 100_000 && !growth.isDone(); guard++) {
            growth.step(probe, slice);
        }
        assertTrue(growth.isDone(), "the growth finished");
        return growth.result();
    }

    @Test
    @DisplayName("random woods collect the same cells, in the same order, as a hash-set walk")
    void theBitsetWalkMatchesTheHashSetWalk() {
        Random random = new Random(20260804L);
        int checked = 0;
        for (int trial = 0; trial < 60; trial++) {
            FakeProbe probe = new FakeProbe();
            // A clump of oaks whose canopies weld — the shape that makes the seen-set hot.
            int trees = 1 + random.nextInt(6);
            for (int t = 0; t < trees; t++) {
                probe.placeOak(random.nextInt(9) - 4, random.nextInt(9) - 4);
            }
            // Some trials cannot see the whole world, which drives the UNKNOWN arm.
            if (random.nextBoolean()) {
                probe.markUnloaded(random.nextInt(9) - 4, random.nextInt(9) - 4);
            }
            int spreadCap = 2 + random.nextInt(10);
            Config.install(Config.get().with(Knob.REGION_MAX_BLOCKS,
                    8 + random.nextInt(400)));
            // Read the cap BACK: ConfigValues clamps to the knob's range instead of rejecting,
            // so the growth may be working to a different number than the test asked for.
            int blockCap = RegionGrowth.maxBlocks();
            AgentProfile profile = spread(spreadCap);

            // Seed on a cell the fake rule actually grows from.
            Pos seed = null;
            BlockKind seedKind = null;
            for (int y = 68; y >= 64 && seed == null; y--) {
                for (int x = -4; x <= 4 && seed == null; x++) {
                    for (int z = -4; z <= 4 && seed == null; z++) {
                        BlockKind k = probe.at(x, y, z);
                        if (k == BlockKind.LOG || k == BlockKind.LEAVES) {
                            seed = new Pos(x, y, z);
                            seedKind = k;
                        }
                    }
                }
            }
            if (seed == null) {
                continue;
            }

            Map<Pos, BlockKind> want =
                    theOldWay(FakeGrowthRule.INSTANCE, seed, seedKind, probe, spreadCap, blockCap);
            GrownRegion got = grow(FakeGrowthRule.INSTANCE, seed, seedKind, profile, probe,
                    1 + random.nextInt(40));

            assertEquals(new ArrayList<>(want.keySet()), new ArrayList<>(got.blocks().keySet()),
                    "trial " + trial + " seed=" + seed + " spread=" + spreadCap
                            + " blockCap=" + blockCap + " — same cells, same order");
            assertEquals(want, got.blocks(), "and the same kind at each of them");
            checked++;
        }
        assertTrue(checked > 40, "the fixture produced enough growable trials — " + checked);
    }

    @Test
    @DisplayName("a cap too wide for a dense box still walks correctly, on the fallback")
    void anAbsurdSpreadFallsBackAndStillAgrees() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 0);
        probe.placeOak(2, 0);
        // 128 is the aspect's ceiling: (2*129+1)^3 is over 17 million cells, so no dense box.
        AgentProfile profile = spread(128);
        Pos seed = new Pos(0, 64, 0);

        Map<Pos, BlockKind> want = theOldWay(FakeGrowthRule.INSTANCE, seed, BlockKind.LOG, probe,
                128, RegionGrowth.maxBlocks());
        GrownRegion got = grow(FakeGrowthRule.INSTANCE, seed, BlockKind.LOG, profile, probe, 7);

        assertEquals(new ArrayList<>(want.keySet()), new ArrayList<>(got.blocks().keySet()));
    }

    @Test
    @DisplayName("a growth cut short by the read budget resumes to the same mass as one that was not")
    void slicingDoesNotChangeTheMass() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 0);
        probe.placeOak(2, 1);
        probe.placeOak(-2, 1);
        AgentProfile profile = spread(24);
        Pos seed = new Pos(0, 64, 0);

        List<Pos> inOneGo = new ArrayList<>(
                grow(FakeGrowthRule.INSTANCE, seed, BlockKind.LOG, profile, probe, 100_000)
                        .blocks().keySet());
        for (int slice : new int[] {1, 2, 3, 5, 13, 26, 27, 64}) {
            List<Pos> sliced = new ArrayList<>(
                    grow(FakeGrowthRule.INSTANCE, seed, BlockKind.LOG, profile, probe, slice)
                            .blocks().keySet());
            assertEquals(inOneGo, sliced, "slice of " + slice + " reads collected the same mass");
        }
    }
}
