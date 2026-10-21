package dev.luizloyola.anima.core.social;

import java.util.Objects;

/** Where the consumer's {@link GroupUp} rule is installed; {@link GroupUp#NEVER} until it is. */
public final class GroupUps {

    private static volatile GroupUp current = GroupUp.NEVER;

    private GroupUps() {
    }

    public static void install(GroupUp rule) {
        current = Objects.requireNonNull(rule, "rule");
    }

    public static GroupUp get() {
        return current;
    }
}
