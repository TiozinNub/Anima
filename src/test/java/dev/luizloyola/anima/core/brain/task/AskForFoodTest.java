package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AskForFoodTest {

    private final FakeContext ctx = new FakeContext();
    private final AskForFood ask = new AskForFood();
    private final BeingId otherId = BeingId.of(AgentId.random());

    private void hungryNear(double distance) {
        ctx.percepts.metabolism.setFoodLevel(8);
        ctx.percepts.beings = List.of(FakePercepts.personAt(otherId, new Pos((int) distance, 64, 0), distance, "Rex"));
    }

    @Test
    @DisplayName("hungry, nothing ready in hand, somebody to go to: asking is priced by the walk and a premium")
    void pricedByTheWalk() {
        hungryNear(10.0);

        assertTrue(ask.applicable(ctx));
        assertEquals(10.0 + AskForFood.ASK_PREMIUM, ask.estimateCost(ctx), 1e-9);
        List<Task> plan = ask.decompose(ctx);
        assertInstanceOf(SeekCompany.class, plan.get(0), "the same walk and hail company makes");
        assertInstanceOf(EatCarried.class, plan.get(1));
    }

    @Test
    @DisplayName("not with ready food in hand, nobody to ask, or nobody not already asked")
    void notApplicable() {
        hungryNear(10.0);
        ctx.percepts.called.add(otherId);
        assertFalse(ask.applicable(ctx), "the one person in sight was already asked");

        ctx.percepts.called.clear();
        ctx.percepts.food("minecraft:bread", new FoodValue(5, 6.0f, false));
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:bread", 2, 64));
        assertFalse(ask.applicable(ctx), "bread in hand is eaten, not asked for");

        ctx.percepts.inventory.set(0, ItemStack.EMPTY);
        ctx.percepts.beings = List.of();
        assertFalse(ask.applicable(ctx), "nobody to ask");
    }

    @Test
    @DisplayName("appended to hunger's methods, never inserted: a saved plan resumes by index")
    void appended() {
        List<Method> methods = new SatisfyHunger().methods();
        assertInstanceOf(AskForFood.class, methods.get(methods.size() - 1));
        assertEquals(7, methods.size());
    }
}
