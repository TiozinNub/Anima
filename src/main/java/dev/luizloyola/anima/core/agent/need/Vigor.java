package dev.luizloyola.anima.core.agent.need;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.Metabolism;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * How much of a beating this body can still take — the first need whose value is a COMPOSITE, and
 * therefore the one that proves the reason machinery instead of asserting it.
 *
 * <p><b>Hit points, less what is dragging it down, plus what is holding it up.</b> Health comes
 * from {@link Metabolism#health()} — a view over the body's own number, never a second copy — and
 * everything acting on it arrives through the {@link Effects} seam, because naming a status effect
 * is version-specific and this is {@code core/}.
 *
 * <p><b>It drives nothing, ever</b> (decision: Luiz, 2026-08-06). Vigor is why one kind of binding
 * was not enough: it never wins an arbitration, it changes how another one resolves — which is why
 * its levels declare a {@code tolerance} of zero and nothing reads them.
 *
 * <p><b>The reading stops at healthy.</b> A buff cannot make a body healthier than healthy: past
 * that corner the ramp has nothing left to interpolate toward and would pin at full pressure — a
 * body given Strength reading as if it were dying. Same reason {@code BREATH} stops at a lungful.
 */
public final class Vigor implements Gauge {

    /**
     * What one level of an effect is worth, in hit points of staying power. Deliberately a constant
     * and not a per-species aspect: nothing branches on vigor yet, so there is no case for two
     * species disagreeing about what a buff is worth, and an aspect nobody reads is a config line
     * an operator can only get wrong.
     */
    public static final double PER_EFFECT_LEVEL = 1.0;

    /** The level a body cannot read above — see the class note. */
    private static final String TOP_LEVEL = "healthy";

    private final Metabolism metabolism;
    private final Effects effects;
    private final Supplier<AgentProfile> profile;

    /**
     * @param metabolism the organ health is read off — the same one hunger is a view over
     * @param effects what is currently acting on this body; {@link Effects#NONE} for a body nothing
     *     can be applied to
     * @param profile this body's resolved aspects, as a supplier — bodies build their roster in
     *     field initialisers, before they can answer what species they are
     */
    public Vigor(Metabolism metabolism, Effects effects, Supplier<AgentProfile> profile) {
        this.metabolism = Objects.requireNonNull(metabolism, "metabolism");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public NeedKind kind() {
        return NeedKind.VIGOR;
    }

    /** Hit points plus what is acting on them, in hit points — never above healthy. */
    @Override
    public double value() {
        AgentProfile p = profile.get();
        if (metabolism.maxHealth() <= 0.0F) {
            // Nothing has been pushed yet — a body between a world load and its first tick. Reading
            // the zero would say it is dying, which is a statement about this gauge and not about
            // the body; the ceiling at least says nothing is known to be wrong.
            return ceiling(p);
        }
        double total = metabolism.health();
        for (Effects.Effect effect : effects.active()) {
            total += contribution(effect);
        }
        return Math.max(NeedKind.VIGOR.axisMin(), Math.min(ceiling(p), total));
    }

    @Override
    public double pressure() {
        return NeedKind.VIGOR.ramp().pressureAt(profile.get(), value());
    }

    @Override
    public NeedLevel level() {
        return NeedKind.VIGOR.ramp().levelAt(profile.get(), value());
    }

    /**
     * The three groups the acceptance readout is made of: what the body started with, what is
     * dragging it down, and what is holding it up. The last two print even when empty — that is the
     * difference between "nothing is dragging you down" and "nobody looked".
     */
    @Override
    public List<ReasonGroup> reasons() {
        List<Reason> health = List.of(new Reason(NeedKind.REASON_VALUE,
                Metabolism.HEALTH_NAME_KEY, metabolism.health()));
        List<Reason> debuffs = new ArrayList<>();
        List<Reason> buffs = new ArrayList<>();
        for (Effects.Effect effect : effects.active()) {
            Reason reason = new Reason(NeedKind.VIGOR.lang() + ".effect",
                    effect.nameKey(), contribution(effect));
            (effect.beneficial() ? buffs : debuffs).add(reason);
        }
        return List.of(
                new ReasonGroup(NeedKind.VIGOR.lang() + ".health", health),
                new ReasonGroup(NeedKind.VIGOR.lang() + ".debuffs", debuffs),
                new ReasonGroup(NeedKind.VIGOR.lang() + ".buffs", buffs));
    }

    @Override
    public String describe() {
        AgentProfile p = profile.get();
        return String.format(Locale.ROOT, "vigor %.1f (health %.1f/%.1f) (%s)",
                value(), metabolism.health(), metabolism.maxHealth(),
                NeedKind.VIGOR.ramp().levelAt(p, value()).key());
    }

    /** What one effect moves the number by, signed. */
    private static double contribution(Effects.Effect effect) {
        double amount = Math.max(1, effect.level()) * PER_EFFECT_LEVEL;
        return effect.beneficial() ? amount : -amount;
    }

    /**
     * The top corner of this species' ramp. Read live and by name, so a species that moves what
     * "healthy" means takes its ceiling with it; falls back to the axis when a consumer has
     * renamed the level out from under this.
     */
    private static double ceiling(AgentProfile profile) {
        return NeedKind.VIGOR.level(TOP_LEVEL)
                .map(level -> level.value(profile))
                .orElse(NeedKind.VIGOR.axisMax());
    }
}
