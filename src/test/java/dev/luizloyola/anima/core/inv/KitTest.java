package dev.luizloyola.anima.core.inv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The kit vocabulary, headless: needs gate, wants never do, counts re-ask the pack. The specs are
 * registered through {@link ItemSpec#register} exactly as a consumer would, so the canonical-first
 * rule is exercised too.
 */
class KitTest {

    private static final ItemSpec AXES =
            ItemSpec.register(new ItemSpec("kit-test-axes", id -> id.endsWith("_axe")));
    private static final ItemSpec DIRT =
            ItemSpec.register(new ItemSpec("kit-test-dirt", id -> id.equals("minecraft:dirt")));

    @Test
    void missingNeedsListsOnlyUncoveredNeeds() {
        Kit kit = Kit.of(ItemCall.need(DIRT, 64), ItemCall.want(AXES, 1));
        Inventory pack = new Inventory();
        List<ItemCall> missing = kit.missingNeeds(pack);
        assertEquals(1, missing.size(), "the want is absent too, and gates nothing");
        assertEquals(DIRT, missing.get(0).spec());
    }

    @Test
    void aCoveredNeedStopsMissing() {
        Kit kit = Kit.of(ItemCall.need(DIRT, 64));
        Inventory pack = new Inventory();
        pack.add(ItemStack.of("minecraft:dirt", 64, 64));
        assertTrue(kit.missingNeeds(pack).isEmpty());
    }

    @Test
    void aDrainingStackReArmsTheNeed() {
        // The consumable shape: the check is re-asked, never checked off.
        Kit kit = Kit.of(ItemCall.need(DIRT, 64));
        Inventory pack = new Inventory();
        pack.add(ItemStack.of("minecraft:dirt", 64, 64));
        assertTrue(kit.missingNeeds(pack).isEmpty(), "stocked");
        pack.remove("minecraft:dirt", 10);
        assertEquals(1, kit.missingNeeds(pack).size(), "the pile drained below the call");
    }

    @Test
    void needsCountAcrossStacksAndMatchTheWholeSpec() {
        Kit kit = Kit.of(ItemCall.need(AXES, 2));
        Inventory pack = new Inventory();
        pack.add(ItemStack.of("minecraft:wooden_axe", 1, 1));
        pack.add(ItemStack.of("minecraft:stone_axe", 1, 1));
        assertTrue(kit.missingNeeds(pack).isEmpty(), "two axes of any tier cover 'axes x2'");
    }

    @Test
    void theEmptyKitIsCanonicalAndCallsForNothing() {
        assertSame(Kit.NONE, Kit.of());
        assertTrue(Kit.NONE.isEmpty());
        assertTrue(Kit.NONE.missingNeeds(new Inventory()).isEmpty());
    }

    @Test
    void aCallIsForAtLeastOneItem() {
        assertThrows(IllegalArgumentException.class, () -> ItemCall.need(DIRT, 0));
    }
}
