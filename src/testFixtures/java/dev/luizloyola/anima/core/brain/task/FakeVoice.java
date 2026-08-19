package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.Voice;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import java.util.Set;

/** A throat that records that it was used, and who the last reach was meant for. */
public final class FakeVoice implements Voice {
    public boolean hailed;
    public BeingId at;

    /**
     * Where the per-target mark lands — the fake sensor's {@code called} set, so a test sees what
     * a live body sees: the mark a reach leaves is the one {@code calledLately} answers from.
     */
    private final Set<BeingId> marks;

    public FakeVoice(Set<BeingId> marks) {
        this.marks = marks;
    }

    @Override
    public void hail(BeingId whom) {
        hailed = true;
        reachedOut(whom);
    }

    @Override
    public void reachedOut(BeingId whom) {
        at = whom;
        marks.add(whom);
    }
}
