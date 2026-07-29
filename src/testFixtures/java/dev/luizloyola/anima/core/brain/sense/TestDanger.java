package dev.luizloyola.anima.core.brain.sense;

import java.util.Map;
import java.util.Set;

/**
 * What the test body is afraid of: weights that were Anima's before the split and are nobody's now
 * — a table belongs to whoever ships a body. Every number here is one a test asserts, not one
 * production code relies on.
 */
public final class TestDanger {

    /** A settler-shaped set of fears: a zombie is the unit, a creeper is worse, a skeleton shoots. */
    public static final DangerTable TABLE = new DangerTable(
            Map.of(),
            Map.of(DangerTable.DEFAULT_KEY, 1.0,
                    DangerTable.HOSTILE_KEY, 1.5,
                    "zombie", 1.0,
                    "skeleton", 1.2,
                    "creeper", 1.6,
                    "cow", 0.0),
            Set.of("skeleton", "stray", "bogged", "pillager", "witch", "blaze", "ghast"));

    private TestDanger() {
    }
}
