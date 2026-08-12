package dev.luizloyola.anima.core.brain.attention;

import dev.luizloyola.anima.core.brain.sense.Being;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Living things, priced by how much a body would want to look at them: <em>look at someone</em> is
 * the social weight, <em>look at what moved</em> the motion term, <em>look toward a sound</em> what
 * a body does about something heard and not seen.
 *
 * <p>The heard branch closes a loop: both perception organs aim their cone down the head's bearing,
 * so a body that turns toward a noise can then SEE what made it, climbing the identification ladder
 * because the head moved.
 *
 * <p>Nothing here knows what a Person is: every species runs the same four multiplications,
 * differing only in {@code DangerTable} and the species profile.
 */
public final class BeingSource implements Salience.Source {

    /** A body somebody could talk to: the highest ordinary pull there is, short of a fright. */
    public static final double PERSON_WEIGHT = 0.85;

    /** Anything else alive. Worth a glance, not worth watching. */
    public static final double CREATURE_WEIGHT = 0.45;

    /**
     * Heard and not yet seen — the startle. Above every ordinary weight on purpose: whatever made
     * the noise is the one thing in the scene the body does not know about.
     */
    public static final double UNSEEN_NOISE_WEIGHT = 1.2;

    public static final double MOVING_BONUS = 1.35;

    /** Multiplier for somebody whose own gaze is on us. */
    public static final double WATCHING_BONUS = 1.4;

    /** How long an ordinary look at a body lasts (ticks) before novelty has to earn it again. */
    public static final int LOOK_TICKS = 50;

    /** A startle holds longer: the body is working out what it just heard, not admiring it. */
    public static final int STARTLE_TICKS = 70;

    /** How far above a body's cell its face is — where a look lands on somebody who has one. */
    private static final double FACE_HEIGHT = 1.5;

    /** And on something that walks on four legs, which is not looked in the eye. */
    private static final double FLANK_HEIGHT = 0.8;

    @Override
    public List<Salience.Candidate> propose(Salience.Scene scene) {
        List<Salience.Candidate> candidates = new ArrayList<>();
        for (Being being : scene.percepts().beings()) {
            Salience.Candidate candidate = price(scene, being);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    @Override
    public boolean owns(String key) {
        return key.startsWith(PREFIX);
    }

    @Override
    public Optional<Salience.Candidate> track(Salience.Scene scene, String key) {
        for (Being being : scene.percepts().beings()) {
            if (key(being).equals(key)) {
                return Optional.ofNullable(price(scene, being));
            }
        }
        return Optional.empty(); // gone from perception entirely — there is nothing left to watch
    }

    @Override
    public String name() {
        return "beings";
    }

    /** What this source's keys begin with, so it knows its own when asked to track one. */
    private static final String PREFIX = "being:";

    /** The stable handle for one perceived body — see {@link Salience.Candidate#key}. */
    public static String key(Being being) {
        return PREFIX + being.id();
    }

    /**
     * What this body is worth looking at, or {@code null} for something not worth a candidate at
     * all — a remembered body is a belief about where something used to be.
     */
    private Salience.Candidate price(Salience.Scene scene, Being being) {
        if (being.awareness() == Being.Awareness.REMEMBERED) {
            return null;
        }
        boolean unseen = being.awareness() == Being.Awareness.HEARD;
        double base = unseen ? UNSEEN_NOISE_WEIGHT
                : being.kind().minded() ? PERSON_WEIGHT : CREATURE_WEIGHT;
        if (unseen) {
            // Alarm depends on what the listener thinks made the noise; below SPECIES the answer
            // is "no idea", its own reason to look. The table answers 1.0 where it has no opinion.
            base *= Math.max(1.0, scene.danger().weight(being.species()));
        }
        double height = being.kind().minded() ? FACE_HEIGHT : FLANK_HEIGHT;
        double x = being.pos().x() + 0.5;
        double y = being.pos().y() + height;
        double z = being.pos().z() + 0.5;
        double score = base * scene.nearness(scene.distanceTo(x, y, z))
                * scene.novelty(key(being), state(being));
        if (being.locomotion() != Being.Locomotion.STILL) {
            score *= MOVING_BONUS;
        }
        if (being.watching()) {
            score *= WATCHING_BONUS;
        }
        return new Salience.Candidate(key(being), x, y, z, score,
                unseen ? STARTLE_TICKS : LOOK_TICKS, unseen, describe(being, unseen),
                state(being));
    }

    /**
     * What this body is DOING, as the observer can tell — the sight rather than the thing, which is
     * what boredom is about (see {@link Salience.Scene#novelty}). Every axis the sense renders goes
     * in, so a neighbour who stands up, walks off or looks back is worth seeing again.
     */
    private static String state(Being being) {
        return being.awareness() + "/" + being.locomotion() + "/" + being.activity()
                + (being.watching() ? "/watching" : "");
    }

    /**
     * What the body would say it is looking at, in its terms — {@link Being#knownAs()} answers that
     * for every readout. Sound gets its own phrasing: a body that heard something and saw nothing
     * knows only that there was a noise, and at best what kind of throat made it.
     */
    private static String describe(Being being, boolean unseen) {
        if (!unseen) {
            return being.knownAs();
        }
        return being.identified() == Being.Identified.NONE
                ? "a noise" : "a noise (" + being.knownAs() + ")";
    }
}
