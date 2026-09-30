package dev.luizloyola.anima.core.brain.sense;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import org.jspecify.annotations.Nullable;

/**
 * Which side of the walls a threat is on (spec: {@code 2026-09-28-shelter-design.md}). A body in a
 * shelter is safe from what cannot come in; everything else is on its side, including every threat
 * when it has no shelter at all.
 */
public final class Sides {

    private Sides() {
    }

    /**
     * Whether {@code being} cannot get at this body: the body stands in a shelter, the being stands
     * outside it, and nothing about the being gets it in. A door-opener needs a space with no way
     * out at all, a small body one with no holes, a door-breaker must not be heard at it, and
     * something that shoots or explodes must have no line to the body.
     *
     * <p>A being with no body to size up (a sound with nothing behind it) is taken for a walker of
     * ordinary size, which is what most things are.
     */
    public static boolean shutOut(Percepts percepts, Being being) {
        return shutOut(percepts, being, percepts.combatant(being.id()).orElse(null));
    }

    /** {@link #shutOut(Percepts, Being)}, with the being already sized up. */
    public static boolean shutOut(Percepts percepts, Being being, @Nullable Combatant them) {
        Enclosure here = percepts.enclosure();
        if (!here.shelter() || here.covers(being.pos())) {
            return false;
        }
        if (!keptOut(here.openness(), here.holes(), them)) {
            return false;
        }
        if (percepts.batteringLately(being.id())) {
            return false;
        }
        return !fromRange(being, them) || !percepts.reaches(being.id());
    }

    /**
     * Whether walls and shut doors like these keep {@code them} out: a door-opener needs no way out
     * at all, a small body no holes. Asked of the space as it stands, or as it would be once its
     * doors are shut.
     */
    public static boolean keptOut(Enclosure.Openness openness, Enclosure.Holes holes,
                                  @Nullable Combatant them) {
        Combatant.Entry entry = them == null ? Combatant.Entry.WALKS : them.entry();
        if (entry == Combatant.Entry.PASSES_WALLS
                || entry == Combatant.Entry.OPENS_DOORS && openness != Enclosure.Openness.CLOSED) {
            return false;
        }
        return them == null || !them.small() || holes == Enclosure.Holes.NONE;
    }

    /**
     * Whether a walk to {@code goal} would take this body out of its shelter while something it
     * would fear waits outside — the reason to stay in (spec, decision 11).
     *
     * <p>"Would fear" is the fear ramp's own reach, so a zombie across the field does not keep a
     * household indoors: within {@code flee.range} for something that has to reach you, within
     * sight for something that shoots.
     */
    public static boolean keepsIn(Percepts percepts, AgentProfile profile, DangerTable danger,
                                  Pos goal) {
        Enclosure here = percepts.enclosure();
        if (!here.shelter() || here.covers(goal)) {
            return false;
        }
        for (Being being : percepts.beings()) {
            if (!being.aggressive()) {
                continue;
            }
            String species = being.species().isEmpty() ? DangerTable.HOSTILE_KEY : being.species();
            if (danger.weight(species) <= 0.0) {
                continue;
            }
            Combatant them = percepts.combatant(being.id()).orElse(null);
            double reach = fromRange(being, them) || danger.ranged(being.species())
                    ? profile.i(ProfileAspect.SENSES_RADIUS)
                    : profile.d(ProfileAspect.FLEE_RANGE);
            if (being.distance() < reach && shutOut(percepts, being, them)) {
                return true;
            }
        }
        return false;
    }

    /** Whether it hurts from where it stands: shoots, is seen to, or would explode. */
    public static boolean fromRange(Being being, @Nullable Combatant them) {
        return being.gear().ranged() || being.activity() == Being.Activity.AIMING
                || them != null && (them.shoots() || them.blastReach() > 0.0);
    }
}
