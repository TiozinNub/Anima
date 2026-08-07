package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cone test, held against the arithmetic it replaced. {@link CrescentSampler} used to take a
 * sine, a cosine and a square root <em>per cell of the disc</em> — 9.5 microseconds of every sensor
 * tick once block reads had been made cheap, more than the near-field probe loop it feeds. It now
 * resolves the bearing once per sweep and compares squares instead of dividing by a root.
 *
 * <p>The same predicate rearranged: every bearing and aperture below enumerates what the old
 * formula would, cell for cell, save for a cell sitting <em>exactly</em> on the edge, which
 * squaring can move by one ulp. The suite proves every disagreement is one of those.
 *
 * <p>Written against the FULL view on purpose: it is the enumeration every cell of the disc passes
 * through.
 */
class CrescentSamplerGeometryTest {

    private static AgentProfile eyed(int radius, int cone, int near) {
        Map<ProfileAspect, Double> overrides = Map.of(
                ProfileAspect.PLACES_RADIUS, (double) radius,
                ProfileAspect.PLACES_CONE_DEGREES, (double) cone,
                ProfileAspect.PLACES_NEAR_RADIUS, (double) near);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_geometry");
        for (ProfileAspect aspect : ProfileAspect.all()) {
            builder.set(aspect, overrides.getOrDefault(aspect, TestSpecies.BIPED.get(aspect)));
        }
        return builder.build().fixed();
    }

    /** Exactly what the sampler used to do, transcendentals and all. */
    private static Set<Column> theOldWay(Pos feet, double yawDegrees, int radius, int cone,
            int near) {
        long radiusSq = (long) radius * radius;
        long nearSq = (long) near * near;
        double cosHalf = Math.cos(Math.toRadians(cone / 2.0));
        Set<Column> view = new LinkedHashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                long distSq = (long) dx * dx + (long) dz * dz;
                if (distSq > radiusSq) {
                    continue;
                }
                if (distSq > nearSq) {
                    double yaw = Math.toRadians(yawDegrees);
                    double dot = (-Math.sin(yaw) * dx + Math.cos(yaw) * dz) / Math.sqrt(distSq);
                    if (dot < cosHalf) {
                        continue;
                    }
                }
                view.add(new Column(feet.x() + dx, feet.z() + dz));
            }
        }
        return view;
    }

    /**
     * How far off the cone's edge a cell sits, relative to the edge — 0 exactly on it.
     *
     * <p>Only a cell lying <em>exactly</em> on the boundary can move: {@code (a·7)²} and
     * {@code a²·49} are the same number in algebra and not always the same double. Such a cell is
     * at the limit of peripheral vision and the next crescent re-offers it, so which way it falls
     * is of no consequence — but it is a real difference.
     */
    private static double edgeSlack(int dx, int dz, double yawDegrees, double cosHalf) {
        long distSq = (long) dx * dx + (long) dz * dz;
        double yaw = Math.toRadians(yawDegrees);
        double dot = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;
        double edge = cosHalf * Math.sqrt(distSq);
        return Math.abs(dot - edge) / Math.max(1e-12, Math.abs(edge));
    }

    @Test
    @DisplayName("every bearing and aperture agrees with the old arithmetic but for edge ties")
    void theRearrangedTestAgreesEverywhere() {
        int[] cones = {30, 90, 150, 180, 210, 300, 360};
        int[] radii = {4, 12, 24};
        int checked = 0;
        int ties = 0;
        for (int cone : cones) {
            for (int radius : radii) {
                int near = Math.min(4, radius);
                double cosHalf = Math.cos(Math.toRadians(cone / 2.0));
                AgentProfile profile = eyed(radius, cone, near);
                for (int yaw = -180; yaw < 180; yaw += 7) {
                    // A fresh sampler each time: the first advance is always the full view, which
                    // is the enumeration every cell of the disc passes through.
                    CrescentSampler sampler = new CrescentSampler(profile);
                    Pos feet = new Pos(100, 64, -37);
                    Set<Column> got = new LinkedHashSet<>(sampler.advance(feet, yaw));
                    Set<Column> want = theOldWay(feet, yaw, radius, cone, near);

                    Set<Column> differing = new LinkedHashSet<>(want);
                    differing.removeAll(got);
                    Set<Column> extra = new LinkedHashSet<>(got);
                    extra.removeAll(want);
                    differing.addAll(extra);
                    for (Column cell : differing) {
                        double slack = edgeSlack(cell.x() - feet.x(), cell.z() - feet.z(),
                                yaw, cosHalf);
                        org.junit.jupiter.api.Assertions.assertTrue(slack < 1e-9,
                                "cone=" + cone + " radius=" + radius + " yaw=" + yaw + " cell="
                                        + cell + " is not an edge tie (slack " + slack + ")");
                        ties++;
                    }
                    checked++;
                }
            }
        }
        assertEquals(cones.length * radii.length * 52, checked, "every combination was checked");
        org.junit.jupiter.api.Assertions.assertTrue(ties < checked / 10,
                "edge ties are a rounding curiosity, not a behaviour change — " + ties + " of "
                        + checked + " views had one");
    }

    @Test
    @DisplayName("a full turn sees the whole disc, and a slit sees a slit")
    void theExtremesAreStillTheExtremes() {
        Pos feet = new Pos(0, 64, 0);
        int radius = 8;
        Set<Column> whole = new LinkedHashSet<>(
                new CrescentSampler(eyed(radius, 360, 1)).advance(feet, 12.0));
        assertEquals(theOldWay(feet, 12.0, radius, 360, 1).size(), whole.size(),
                "a full turn is the whole disc");

        Set<Column> slit = new LinkedHashSet<>(
                new CrescentSampler(eyed(radius, 30, 1)).advance(feet, 12.0));
        Set<Column> wanted = theOldWay(feet, 12.0, radius, 30, 1);
        org.junit.jupiter.api.Assertions.assertTrue(slit.size() < whole.size() / 4,
                "a slit is a small fraction of the disc");
        org.junit.jupiter.api.Assertions.assertTrue(wanted.containsAll(slit),
                "and everything in it was in the old slit too");
    }
}
