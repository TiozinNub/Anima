package dev.luizloyola.anima.core.rest;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Optional;
import java.util.Set;

/**
 * One body's bed (sleep spec): the one it holds, and taking one. Which beds a body may take is the
 * consuming mod's to say; with nobody saying, it takes none.
 */
public interface OwnBed {

    /** A body that never holds a bed. */
    OwnBed NONE = new OwnBed() {
        @Override
        public Optional<Pos> bed() {
            return Optional.empty();
        }

        @Override
        public Optional<Pos> bedIn(Set<Pos> space) {
            return Optional.empty();
        }

        @Override
        public Optional<Pos> claimIn(Set<Pos> space) {
            return Optional.empty();
        }
    };

    /** The bed this body holds, while it stands and is still this body's to keep. */
    Optional<Pos> bed();

    /**
     * The bed this body would sleep in among {@code space}'s cells: its own when it lies there, else,
     * holding none, a free one it may take. Claims nothing. A body whose bed is elsewhere has none here.
     */
    Optional<Pos> bedIn(Set<Pos> space);

    /** {@link #bedIn}, and the free one claimed. */
    Optional<Pos> claimIn(Set<Pos> space);
}
