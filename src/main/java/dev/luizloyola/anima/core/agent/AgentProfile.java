package dev.luizloyola.anima.core.agent;

import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;

/**
 * What one body is like — the aspects of a mind that differ between a settler, a wolf and a
 * rabbit. Anima names them; who answers them is the body's business. Full design (species profiles
 * declared by the consumer, per-agent modifiers on top, {@code anima.json} demoted to server-wide
 * caps): {@code docs/superpowers/specs/2026-07-28-per-species-minds-design.md}.
 *
 * <p>The resolved read, not the declaration: a consumer declares a profile per species, and
 * modifiers it contributes (a trait, a skill, a job) shift an agent's values before they arrive
 * here. "Traits" are one such modifier source, in the consumer's private identity tier.
 *
 * <p>A seam, not the whole design: it carries only the aspects more than one organ reads, since
 * those break when they disagree — the sense query, the attention curve and the debug ring all
 * draw on {@link #perceptionRadius()}. Aspects read in exactly one place move when the species
 * profile lands.
 *
 * <p>Pure core, and an interface rather than a record: {@link #CONFIGURED} is a live view over the
 * config store, not a snapshot, so a body may hold one for its whole life and still see a
 * {@code /anima config reload}. Whatever replaces it must keep that property.
 */
public interface AgentProfile {

    /**
     * How far away (blocks) this body can perceive another at all — the outer bound on the being
     * sense, narrowed further for sight by the view cone and by line of sight. Hearing has its own
     * shorter radius inside it; a deliberate hail carries beyond it. Also the far end of the
     * attention curve: a body at this distance is re-checked at the slowest cadence.
     */
    int perceptionRadius();

    /**
     * Horizontal field of view (degrees, full angle). Bodies outside it are unseen until they make
     * a noise; 360 is omniscience.
     */
    int coneDegrees();

    /**
     * Vertical field HALF-angle (degrees) around gaze pitch. Human vision is wide across and flat
     * up-down, so this is not half of {@link #coneDegrees()}; 90 removes the limit.
     */
    int verticalHalfDegrees();

    /**
     * Multiplier on {@link #perceptionRadius()} when the target is sneaking. Sneaking shrinks how
     * far away you are noticed; it never makes you invisible.
     */
    double sneakRangeMult();

    /**
     * Every aspect read from Anima's own config file — what every agent used before any of them had
     * a species. A bridge meant to be crossed: introducing the seam costs a consuming mod no edit,
     * and once it declares species profiles, Anima's own values stop being anybody's defaults.
     */
    AgentProfile CONFIGURED = new AgentProfile() {
        @Override
        public int perceptionRadius() {
            return Config.get().i(Knob.PEERS_RADIUS);
        }

        @Override
        public int coneDegrees() {
            return Config.get().i(Knob.PEERS_CONE_DEGREES);
        }

        @Override
        public int verticalHalfDegrees() {
            return Config.get().i(Knob.PEERS_VERTICAL_DEGREES);
        }

        @Override
        public double sneakRangeMult() {
            return Config.get().d(Knob.PEERS_SNEAK_RANGE_MULT);
        }

        @Override
        public String toString() {
            return "AgentProfile.CONFIGURED";
        }
    };
}
