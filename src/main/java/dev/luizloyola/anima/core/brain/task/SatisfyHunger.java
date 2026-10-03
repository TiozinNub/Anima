package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;

/**
 * "They are hungry" as something to ACHIEVE, with no opinion about how: {@link EatReadyFood}
 * (free), {@link EatLastResort} (desperation-priced), {@link EatFromStore} (priced by the walk) and
 * {@link EatObtained} (priced by the walk and the work), and for the starving {@link EatAnything}
 * (any food at all, raw included), and {@link EatCooked} (raw food cooked at a campfire, priced by the
 * walk and the wait), and {@link AskForFood} (somebody nearby asked, priced by the walk to them).
 * Cheapest-wins prefers ready food when any is in hand, and the arbiter's cost tolerance decides
 * what hunger can afford. The methods list is the extension point ({@link CompoundTask}), appended
 * to and never inserted into, since a saved plan resumes its method by index; all methods failing
 * or priced out bubbles a root FAILED.
 */
public final class SatisfyHunger implements CompoundTask {
    private final List<Method> methods = List.of(new EatReadyFood(), new EatLastResort(),
            new EatFromStore(), new EatObtained(), new EatAnything(), new EatCooked(),
            new AskForFood());

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "satisfy hunger";
    }

    /**
     * Everything a meal could come from, as one reading for the eat drive's back-off: the pack's
     * food and how much of it is ready, what the party's stores were seen holding, and what the
     * depot reads in the body's site. Mixed, not summed, so a raw steak taken out of a chest to be
     * cooked still counts as a change.
     */
    public static long stock(BrainContext ctx) {
        long reading = ctx.percepts().inventory().count(Food.SPEC.matcher());
        reading = 31 * reading + ctx.percepts().inventory().count(ReadyFood.SPEC.matcher());
        long seen = 0;
        for (PoiMemory store : Store.ours(ctx)) {
            seen += ctx.knowledge().insideOf(store.anchor()).map(inside -> inside.count(Food.SPEC)).orElse(0);
        }
        reading = 31 * reading + seen;
        return 31 * reading + ctx.heldAtDepot(Food.SPEC);
    }
}
