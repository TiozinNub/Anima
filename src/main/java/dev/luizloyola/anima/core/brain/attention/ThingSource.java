package dev.luizloyola.anima.core.brain.attention;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Things rather than bodies — the fourth ask: <em>look at something</em>. Two kinds, the same term
 * at different weights: <b>loose items</b>, a live percept, and <b>remembered places</b>, which is
 * what gives idling a sense of place.
 *
 * <p><b>Only the nearest of each kind, and only within sight.</b> A body remembers up to a hundred
 * and sixty places per kind and can see two dozen blocks; proposing all of them would price a
 * hundred and sixty candidates every decision so that one could win.
 *
 * <p>It walks {@link PoiKind#all()}, so a tree, a crop, a bed or anything else a consumer taught
 * the library to remember is looked at by the same rule, at a weight that says "a place I know".
 */
public final class ThingSource implements Salience.Source {

    /** A dropped item: small, close, and the sort of thing an eye goes to. */
    public static final double DROP_WEIGHT = 0.55;

    /** A place this body remembers being somewhere. Below anything alive. */
    public static final double PLACE_WEIGHT = 0.35;

    /**
     * A place that frightened it, worth more of a look than a nice one — the wary glance at the
     * dark corner. Fear is not only about running.
     */
    public static final double DANGER_WEIGHT = 0.7;

    /** How long a look at a thing lasts. Shorter than at a body: things do not look back. */
    public static final int LOOK_TICKS = 40;

    /** Where a look lands on a remembered place — head height above its anchor cell. */
    private static final double PLACE_HEIGHT = 1.0;

    @Override
    public List<Salience.Candidate> propose(Salience.Scene scene) {
        List<Salience.Candidate> candidates = new ArrayList<>();
        for (Drop drop : scene.percepts().drops()) {
            add(candidates, scene, "drop:" + key(drop.pos()), drop.pos(), 0.5, DROP_WEIGHT,
                    drop.itemId());
        }
        Pos from = scene.percepts().position();
        for (PoiKind kind : PoiKind.all()) {
            Optional<PoiMemory> nearest = scene.knowledge().nearest(kind, from);
            if (nearest.isEmpty()) {
                continue;
            }
            PoiMemory place = nearest.get();
            double weight = kind == PoiKind.DANGER ? DANGER_WEIGHT : PLACE_WEIGHT;
            add(candidates, scene, "place:" + kind.key() + ":" + key(place.anchor()), place.anchor(),
                    PLACE_HEIGHT, weight, kind.key());
        }
        return candidates;
    }

    @Override
    public String name() {
        return "things";
    }

    /** Prices one fixed thing and keeps it only if it is somewhere this body could see it. */
    private static void add(List<Salience.Candidate> into, Salience.Scene scene, String key,
            Pos cell, double height, double weight, String reason) {
        double x = cell.x() + 0.5;
        double y = cell.y() + height;
        double z = cell.z() + 0.5;
        double distance = scene.distanceTo(x, y, z);
        if (distance > scene.profile().i(ProfileAspect.SENSES_RADIUS)) {
            // Out past what this body could make out. The place stays remembered; it is just not
            // worth a glance from here.
            return;
        }
        double score = weight * scene.nearness(distance) * scene.novelty(key);
        into.add(new Salience.Candidate(key, x, y, z, score, LOOK_TICKS, false, reason));
    }

    private static String key(Pos cell) {
        return cell.x() + "," + cell.y() + "," + cell.z();
    }
}
