package dev.luizloyola.anima.core.territory;

import java.util.Objects;

/**
 * Why a territory changed, for the claim log.
 *
 * @param detail what a reader needs to find the cause — the site and what it is for, the operator
 */
public record Reason(Kind kind, String detail) {

    public enum Kind {
        /** A party settled somewhere. */
        FOUND,
        /** Something was built and the area grew to take it. */
        GROW,
        /** An operator's command. */
        OP,
        /** The party moved; the old ground is let go. */
        MOVE,
        /** The party ceased to exist. */
        DISBAND,
        /** Read from a save that kept the area in an older shape. */
        MIGRATE
    }

    public Reason {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(detail, "detail");
    }

    public static Reason of(Kind kind, String detail) {
        return new Reason(kind, detail);
    }

    @Override
    public String toString() {
        return detail.isEmpty() ? kind.name() : kind.name() + " " + detail;
    }
}
