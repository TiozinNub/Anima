package dev.luizloyola.anima.core.brain.history;

import java.util.List;
import java.util.Objects;

/** A {@link Doing} with its slots filled — what a body did, before it is given a time. */
public record Deed(Doing doing, List<Slot> slots) {

    public Deed {
        Objects.requireNonNull(doing, "doing");
        slots = List.copyOf(slots);
        if (slots.size() != doing.slots().size()) {
            throw new IllegalArgumentException(doing.key() + " takes " + doing.slots()
                    + ", given " + slots.size() + " slot(s)");
        }
    }

    public static Deed of(Doing doing, Slot... slots) {
        return new Deed(doing, List.of(slots));
    }
}
