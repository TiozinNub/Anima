package dev.luizloyola.anima.core.agent.need;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.AspectModifier;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Time awake since a bed (sleep spec): it counts every tick but those lying in a bed, which set it
 * back to nothing, and those of a night under cover with no bed, which hold it where it is — a hut or
 * a dug pocket is not a bed, and not a night in the open either.
 */
public final class Rest implements Gauge {

    /** The id the cost is applied under, on every aspect it touches. */
    public static final String MODIFIER = "anima:rest";

    /** How much slower every handling beat is, tired and exhausted (decision 5). */
    static final double TIRED_HANDLING = 0.10;
    static final double EXHAUSTED_HANDLING = 0.25;

    /** How much slower an exhausted body walks. */
    public static final double EXHAUSTED_WALK = 0.05;

    private static final List<ProfileAspect> HANDLING = List.of(ProfileAspect.HANDLING_OPEN_TICKS,
            ProfileAspect.HANDLING_SETTLE_TICKS, ProfileAspect.HANDLING_STACK_TICKS,
            ProfileAspect.HANDLING_SELECT_TICKS, ProfileAspect.HANDLING_CRAFT_TICKS,
            ProfileAspect.PLACE_COOLDOWN_TICKS);

    private final Supplier<AgentProfile> profile;
    private double awake;
    private boolean asleep;
    private boolean underCover;

    public Rest(Supplier<AgentProfile> profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public NeedKind kind() {
        return NeedKind.REST;
    }

    /** Pushed by the body each tick: lying in a bed, and spending the night under cover. */
    public void observe(boolean asleep, boolean nightUnderCover) {
        this.asleep = asleep;
        this.underCover = nightUnderCover;
    }

    @Override
    public void tick() {
        if (asleep) {
            awake = 0.0;
        } else if (!underCover) {
            awake = Math.min(NeedKind.REST.axisMax(), awake + 1.0);
        }
    }

    @Override
    public double value() {
        return awake;
    }

    @Override
    public double pressure() {
        return NeedKind.REST.ramp().pressureAt(profile.get(), awake);
    }

    @Override
    public NeedLevel level() {
        return NeedKind.REST.ramp().levelAt(profile.get(), awake);
    }

    /** The load path, and the dev command that stages a sleepless week. */
    public void setValue(double ticks) {
        this.awake = Math.max(0.0, Math.min(NeedKind.REST.axisMax(), ticks));
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "rest %.0f ticks awake (%s)%s", awake, level().key(),
                asleep ? ", asleep" : underCover ? ", under cover" : "");
    }

    /** The handling cost of a level, as modifiers under {@link #MODIFIER}; none rested. */
    public static List<AspectModifier> costOf(NeedLevel level) {
        double slower = switch (level.key()) {
            case "tired" -> TIRED_HANDLING;
            case "exhausted" -> EXHAUSTED_HANDLING;
            default -> 0.0;
        };
        return slower == 0.0 ? List.of()
                : HANDLING.stream().map(a -> AspectModifier.fractionOfBase(MODIFIER, a, slower)).toList();
    }

    /** Whether the level slows the legs too. */
    public static boolean slowsWalking(NeedLevel level) {
        return level.key().equals("exhausted");
    }
}
