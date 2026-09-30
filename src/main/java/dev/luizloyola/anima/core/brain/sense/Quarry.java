package dev.luizloyola.anima.core.brain.sense;

/**
 * What a hunter reads off an animal before choosing it, the way fight or flight reads health: a
 * young one drops nothing, and an owned one — tamed, named, or on a lead — is somebody's.
 */
public record Quarry(boolean young, boolean owned) {

    public boolean fair() {
        return !young && !owned;
    }
}
