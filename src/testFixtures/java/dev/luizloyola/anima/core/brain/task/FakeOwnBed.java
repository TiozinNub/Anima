package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.rest.OwnBed;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** A scripted {@link OwnBed}: the bed {@link #held}, else the first of {@link #free} in the space. */
public final class FakeOwnBed implements OwnBed {
    public Pos held;
    public final Set<Pos> free = new LinkedHashSet<>();

    @Override
    public Optional<Pos> bed() {
        return Optional.ofNullable(held);
    }

    @Override
    public Optional<Pos> bedIn(Set<Pos> space) {
        if (held != null) {
            return space.contains(held) ? Optional.of(held) : Optional.empty();
        }
        return free.stream().filter(space::contains).findFirst();
    }

    @Override
    public Optional<Pos> claimIn(Set<Pos> space) {
        Optional<Pos> bed = bedIn(space);
        bed.ifPresent(b -> {
            held = b;
            free.remove(b);
        });
        return bed;
    }
}
