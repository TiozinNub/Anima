package dev.luizloyola.anima.mod.body;

/**
 * Vanilla's per-tick mob cap calculator, asked whether its last yes came from a body's cap rather
 * than a player's: a spawn only a body allows re-checks the global cap first.
 */
public interface BodyGrant {

    boolean anima$byBody();

    /** Whether any body anchors in this level, which rounds the global animal cap up. */
    boolean anima$anyBody();
}
