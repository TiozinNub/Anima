package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.Voice;
import dev.luizloyola.anima.core.brain.sense.BeingId;

/** A throat that records that it was used, and who the call was meant for. */
public final class FakeVoice implements Voice {
    public boolean hailed;
    public BeingId at;

    @Override
    public void hail(BeingId whom) {
        hailed = true;
        at = whom;
    }
}
