package dev.luizloyola.anima.core.brain.instinct;

import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.brain.task.SatisfyHunger;
import dev.luizloyola.anima.core.brain.task.SeekCompany;

/**
 * The drives Anima's own needs declared, wired to the tasks that serve them — the one place a
 * binding meets a {@code Task}, which is what keeps {@code core/agent/need} from having to know
 * what a task is.
 *
 * <p><b>Shared instances.</b> A {@link NeedDrive} is stateless, so one serves every body; the
 * arbiter keys cooldowns by {@link Instinct#key()} and holds the active drive by identity, neither
 * of which is per-body state.
 *
 * <p>Company's LONELY end declares {@link #SEEK_PEOPLE}; its crowded end declares a modulator
 * rather than a drive, because the wander's comfort term already walks a crowded body away — see
 * {@code Comfort}. Breath declares none at all; the legs read it directly, which is why a swimmer
 * surfaces without the brain deciding to.
 */
public final class Drives {

    /**
     * Hunger's drive — the canonical worked example from the brain design doc. Its bid is the food
     * bar read through {@link NeedKind#HUNGER}'s ramp, which reproduces the {@code 1 - food/20} the
     * brain has always used, and its cost ceiling is whatever the level it is at will spend: a
     * peckish body waits for a task boundary and buys a short errand, a starving one pays anything.
     */
    public static final NeedDrive EAT =
            new NeedDrive(NeedKind.HUNGER.binding("eat"), ctx -> new SatisfyHunger());

    /**
     * Company's lonely end: go and be near somebody. Its bid is the gauge's own V-shaped ramp, so
     * only the BELOW side fires here — a crowded body's opinion is the wander's to act on.
     */
    public static final NeedDrive SEEK_PEOPLE =
            new NeedDrive(NeedKind.COMPANY.binding("seek_people"), ctx -> new SeekCompany());

    private Drives() {
    }
}
