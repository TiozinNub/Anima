package dev.luizloyola.anima.core.agent;

import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;

/**
 * What one body is like — the dimensions of a mind that differ between a settler, a wolf and a
 * rabbit. Anima names them; who answers them is the body's business.
 *
 * <p><b>Why this exists.</b> Anima's tunables were global: every agent saw the same 24-block
 * perception radius out of {@code anima.json}, where a rabbit's flight distance and a wolf's
 * eyesight are different bodies, not one dial at two settings. Full design (species profiles from
 * the consumer, per-agent modifiers on top, {@code anima.json} demoted to server-wide caps):
 * {@code docs/superpowers/specs/2026-07-28-per-species-minds-design.md}.
 *
 * <p><b>The seam, not the whole design.</b> Only the dimensions more than one organ reads live
 * here: the sense query, the attention curve and the debug ring all draw on
 * {@link #perceptionRadius()} and each used to reach for the global independently. The rest move
 * when the species profile lands.
 *
 * <p>An interface rather than a record on purpose: {@link #CONFIGURED} is a live view over the
 * config store, so a body may hold one for life and still see a {@code /anima config reload}.
 * Whatever replaces it must keep that property.
 */
public interface AgentTraits {

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
     * Every dimension read from Anima's own config file — what every agent used before any of them
     * had a species.
     *
     * <p>A bridge meant to be crossed: introducing the seam changes no behaviour and costs a
     * consuming mod no edit, and once a consumer declares its species profiles its bodies answer
     * from those instead.
     */
    AgentTraits CONFIGURED = new AgentTraits() {
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
            return "AgentTraits.CONFIGURED";
        }
    };
}
