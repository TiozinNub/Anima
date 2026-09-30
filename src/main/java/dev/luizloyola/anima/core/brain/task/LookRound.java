package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.SenseEvent;
import dev.luizloyola.anima.core.brain.knowledge.Sighting;
import dev.luizloyola.anima.core.brain.knowledge.Survey;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Stand and look all the way round, once: one {@link Survey} from where the body stands, what it
 * sights filed as glimpses as each bearing is looked down. What a scout does at a stop before
 * choosing a way; a dog on a hilltop does the same.
 *
 * <p>Saved by the bearing reached, so a restart turns on from there rather than starting again.
 */
public final class LookRound implements PrimitiveTask {

    private int bearing;
    private @Nullable Survey survey;
    private final List<SenseEvent> seen = new ArrayList<>();

    public LookRound() {
        this(0);
    }

    /** A look that turns on from {@code bearing} — a restored one. */
    public LookRound(int bearing) {
        this.bearing = Math.max(0, bearing);
    }

    /** The bearing reached, for the codec. */
    public int bearing() {
        return this.survey != null ? this.survey.bearing() : this.bearing;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        if (this.survey == null) {
            this.survey = new Survey(ctx.profile(), here, this.bearing);
            if (!this.survey.possible()) {
                return TaskStatus.SUCCESS; // no reach past the near field: there is nothing to see
            }
        }
        this.seen.clear();
        this.survey.step(ctx.percepts().blocks(), SurveyArea.READS_PER_TICK, this.seen);
        long now = ctx.percepts().time();
        int maxPerKind = AgentKnowledge.maxPerKind(ctx.profile());
        for (SenseEvent event : this.seen) {
            if (event.type() == SenseEvent.Type.GLIMPSED) {
                ctx.knowledge().glimpse(new Sighting(event.kind(), event.anchor(), here, now,
                        Sighting.Provenance.SURVEY), maxPerKind);
            }
        }
        this.bearing = this.survey.bearing();
        return this.survey.done() ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
    }

    @Override
    public void cancel(BrainContext ctx) {
        this.survey = null;
    }

    @Override
    public String describe() {
        return "look round" + (this.survey != null ? " (" + this.survey.progress() + "%)" : "");
    }
}
