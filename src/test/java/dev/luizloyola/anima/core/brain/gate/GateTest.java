package dev.luizloyola.anima.core.brain.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.log.Entry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gate's two questions as a body asks them: the consumer's answer, a refusal written once, and
 * a family left open while any member of it may be had.
 */
class GateTest {

    private static final String STONE_PICKAXE = "minecraft:stone_pickaxe";
    private static final String WOODEN_PICKAXE = "minecraft:wooden_pickaxe";
    private static final Act ENCHANT = new Act("test:enchant");

    private final FakeContext ctx = new FakeContext();
    private Gate.View view;

    @BeforeEach
    void installStoneAgePolicy() {
        Gate.install(new Gate.Policy() {
            @Override
            public Optional<String> refuseItem(AgentId body, String itemId) {
                return itemId.startsWith("minecraft:stone_")
                        ? Optional.of("not in the Stone Age") : Optional.empty();
            }

            @Override
            public Optional<String> refuseAct(AgentId body, Act act) {
                return act.equals(ENCHANT) ? Optional.of("not in Enchanting") : Optional.empty();
            }
        });
        view = Gate.viewFor(ctx.self, ctx.journal());
    }

    @AfterEach
    void open() {
        Gate.install(Gate.OPEN);
    }

    private List<Entry> gateLines() {
        return ctx.journal().recent(50).stream().filter(e -> e.event().equals("gate")).toList();
    }

    @Test
    void aRigWithNoBodyIsNeverRefused() {
        assertTrue(Gate.View.OPEN.mayMake(STONE_PICKAXE));
        assertTrue(Gate.View.OPEN.mayDo(ENCHANT));
    }

    @Test
    void aRefusalIsWrittenOnceNotOnEveryAsk() {
        assertFalse(view.mayMake(STONE_PICKAXE));
        assertFalse(view.mayMake(STONE_PICKAXE));
        assertTrue(view.mayMake(WOODEN_PICKAXE));

        List<Entry> lines = gateLines();
        assertEquals(1, lines.size(), "asked twice, said once");
        assertEquals("won't make minecraft:stone_pickaxe: not in the Stone Age", lines.get(0).detail());
    }

    @Test
    void anAnswerThatMayHaveChangedIsNewsAgain() {
        view.mayMake(STONE_PICKAXE);
        Gate.changed();
        view.mayMake(STONE_PICKAXE);
        assertEquals(2, gateLines().size());
    }

    @Test
    void aFamilyStaysOpenWhileAnyMemberMayBeHad() {
        assertTrue(view.maySeek(ItemSpec.anyOf(Set.of(WOODEN_PICKAXE, STONE_PICKAXE))));
        assertFalse(view.maySeek(ItemSpec.anyOf(Set.of(STONE_PICKAXE, "minecraft:stone_axe"))));
        assertEquals("won't seek " + ItemSpec.anyOf(Set.of(STONE_PICKAXE, "minecraft:stone_axe")).name()
                + ": not in the Stone Age", gateLines().get(0).detail());
    }

    @Test
    void aDeclaredSpecHasNothingToJudge() {
        ItemSpec anyStone = ItemSpec.register(new ItemSpec("gate-test-stone",
                id -> id.startsWith("minecraft:stone_")));
        assertTrue(view.maySeek(anyStone), "a predicate names no items the gate could refuse");
    }

    @Test
    void anActIsAskedTheSameWay() {
        assertFalse(view.mayDo(ENCHANT));
        assertTrue(view.mayDo(new Act("test:trade")));
        assertEquals("won't test:enchant: not in Enchanting", gateLines().get(0).detail());
    }
}
