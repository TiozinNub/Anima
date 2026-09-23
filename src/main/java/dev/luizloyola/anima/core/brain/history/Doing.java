package dev.luizloyola.anima.core.brain.history;

import java.util.List;
import java.util.Objects;

/**
 * One kind of thing a body does, as it would tell it afterwards — data, not behaviour, and the
 * vocabulary small talk draws a body's own day from. Every {@code Instinct} and {@code WorkItem}
 * has to name one, so no action goes without.
 *
 * <p>{@code slots} name a line's arguments in order — {@code %1$s} is the first slot — and
 * {@link When} always rides one past the last. A doing that is not {@code remembered} never enters
 * a {@link History} and needs no lines; declaring that here is what makes leaving one out a
 * decision rather than an oversight.
 */
public record Doing(String key, String langKey, List<String> slots, boolean remembered) {

    /** Lines per remembered doing, {@code <langKey>.1} to {@code .3} (decision: Luiz, 2026-09-23). */
    public static final int VARIANTS = 3;

    public Doing {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(langKey, "langKey");
        slots = List.copyOf(slots);
    }

    /** The argument position {@link When} takes in this doing's lines, 1-based. */
    public int whenPosition() {
        return slots.size() + 1;
    }
}
