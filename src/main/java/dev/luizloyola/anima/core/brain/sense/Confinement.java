package dev.luizloyola.anima.core.brain.sense;

/**
 * Whether this body can get out of where it is — the search's own verdict, carried to the brain.
 *
 * <p>Not an inference from failures. A route search that runs out of anywhere to go has enumerated
 * every cell this body can reach, and (given the guards in {@code Pathfinder}) only the world
 * itself stopped it — a fact about the terrain, arrived at for free.
 *
 * <p>It has to be a fact rather than a pattern because the pattern does not exist: a body sealed in
 * a small room succeeds at most of what it tries and fails only intermittently, so no streak
 * counter watching its failures would ever fire.
 *
 * @param sealed whether the body is shut in
 * @param cells  how many cells it can reach; only a statement about its whole world when sealed
 */
public record Confinement(boolean sealed, int cells) {

    /** Nothing known — what a body reports before anything has looked, and what a fake answers. */
    public static final Confinement NONE = new Confinement(false, 0);
}
