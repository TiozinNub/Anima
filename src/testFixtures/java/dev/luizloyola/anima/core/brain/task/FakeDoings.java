package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.history.Doing;
import dev.luizloyola.anima.core.brain.history.Doings;
import java.util.List;

/** Doings for rigs: one a history keeps, one it drops. Their lang keys resolve to nothing. */
public final class FakeDoings {

    public static final Doing DID_IT = Doings.register(
            new Doing("test_did_it", "test.doing.did_it", List.of(), true));
    public static final Doing IDLED = Doings.register(
            new Doing("test_idled", "test.doing.idled", List.of(), false));

    private FakeDoings() {
    }
}
