package dev.luizloyola.anima.core.inv;

import java.util.function.Predicate;

/**
 * A named CLASS of items — what a goal or a work item means by "logs": not one id but a family,
 * matched by predicate over the core inventory's id strings. One spec object serves everyone who
 * must agree on the meaning (the board's stock predicate, an {@code ObtainItem}, the drop filters),
 * so there is one matcher and no drift.
 *
 * <p>Anima declares no constants of its own: Which items matter belongs to the consuming mod,
 * which declares its specs and registers how to produce them with {@code Producers}.
 */
public record ItemSpec(String name, Predicate<String> matcher) {
    public boolean matches(String itemId) {
        return matcher.test(itemId);
    }
}
