package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Putting a station down near a place: the block is had first, and the cell is chosen only once it
 * is in the pack — so a craft that walks the body to a bench cannot leave the placement aimed from
 * there, and a cell somebody filled in the meantime is not the one aimed at.
 */
class PlaceStationTest {

    private static final Pos NEAR = new Pos(10, 64, 10);

    @Test
    void theBlockIsHadBeforeAnyWalk() {
        FakeContext ctx = new FakeContext();
        List<Task> plan = new PlaceStation(Store.POI, Store.ITEM_ID, NEAR).methods().get(0)
                .decompose(ctx);

        ObtainItem obtain = assertInstanceOf(ObtainItem.class, plan.get(0));
        assertTrue(obtain.spec().matches(Store.ITEM_ID));
        assertTrue(obtain.pursued().isEmpty(),
                "sought for itself, so the gate is asked about the chest like any other want");
        assertInstanceOf(PutDown.class, plan.get(1));
        assertEquals(2, plan.size());
    }

    @Test
    void carriedItWalksOverPlacesItAndClaimsIt() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.inventory.add(ItemStack.of(Workbench.ITEM_ID, 1, 64));

        List<Task> steps = new PutDown(Workbench.POI, Workbench.ITEM_ID, NEAR).methods().get(0)
                .decompose(ctx);

        assertInstanceOf(GoTo.class, steps.get(0));
        PlaceBlock place = assertInstanceOf(PlaceBlock.class, steps.get(1));
        assertEquals(NEAR, place.target(), "free ground at the asked-for cell is where it goes");
        FoundPlace claim = assertInstanceOf(FoundPlace.class, steps.get(2));
        assertEquals(Workbench.POI, claim.kind());
        assertEquals(NEAR, claim.anchor());
    }

    @Test
    void aCellFilledSinceThePlanWasMadeIsNotTheOneAimedAt() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.inventory.add(ItemStack.of(Store.ITEM_ID, 1, 64));
        // Somebody's workbench went down on the base's centre while this chest was being crafted.
        ctx.percepts.blocks.set(NEAR.x(), NEAR.y(), NEAR.z(), Workbench.BLOCK);

        List<Task> steps = new PutDown(Store.POI, Store.ITEM_ID, NEAR).methods().get(0).decompose(ctx);
        Pos spot = ((PlaceBlock) steps.get(1)).target();

        assertNotEquals(NEAR, spot);
        assertEquals(NEAR.y(), spot.y(), "beside the table, on the same ground");
        assertTrue(Math.abs(spot.x() - NEAR.x()) <= 1 && Math.abs(spot.z() - NEAR.z()) <= 1);
    }

    /**
     * Petals, leaf litter or a flower in the cell: walk-through, so the chooser takes it, but the
     * game will not build over it. A base's chest was refused beside its workbench on a forest floor
     * until the petals were cleared (2026-09-26); now the plan breaks them first.
     */
    @Test
    void whatGrowsInTheCellIsBrokenBeforeTheStationGoesIn() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.inventory.add(ItemStack.of(Store.ITEM_ID, 1, 64));
        ctx.percepts.blocks.plant(NEAR.x(), NEAR.y(), NEAR.z());

        List<Task> steps = new PutDown(Store.POI, Store.ITEM_ID, NEAR).methods().get(0).decompose(ctx);

        BreakBlock clear = assertInstanceOf(BreakBlock.class, steps.get(1));
        assertEquals(NEAR, clear.target(), "the cell still wins: what grows there is cleared, not avoided");
        PlaceBlock place = assertInstanceOf(PlaceBlock.class, steps.get(2));
        assertEquals(NEAR, place.target());
    }

    /**
     * An air pocket under the base's centre, the workbench on it: the column search went through the
     * ground and put the chest in the pocket, three below the table (2026-09-26). It stays on the
     * ground, beside the table.
     */
    @Test
    void aPocketUnderTheCentreDoesNotTakeTheChest() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.inventory.add(ItemStack.of(Store.ITEM_ID, 1, 64));
        ctx.percepts.blocks.set(NEAR.x(), NEAR.y(), NEAR.z(), Workbench.BLOCK);
        ctx.percepts.blocks.set(NEAR.x(), NEAR.y() - 2, NEAR.z(), BlockKind.AIR);
        ctx.percepts.blocks.set(NEAR.x(), NEAR.y() - 3, NEAR.z(), BlockKind.AIR);

        List<Task> steps = new PutDown(Store.POI, Store.ITEM_ID, NEAR).methods().get(0).decompose(ctx);
        Pos spot = ((PlaceBlock) steps.get(1)).target();

        assertEquals(NEAR.y(), spot.y(), "on the ground beside the table, not under it: " + spot);
    }

    @Test
    void withNothingCarriedThereIsNothingToPutDown() {
        FakeContext ctx = new FakeContext();
        assertFalse(new PutDown(Store.POI, Store.ITEM_ID, NEAR).methods().get(0).applicable(ctx));
    }

    @Test
    void aSpecForOneStationNamesJustThatItem() {
        ItemSpec chest = ((ObtainItem) new PlaceStation(Store.POI, Store.ITEM_ID, NEAR).methods()
                .get(0).decompose(new FakeContext()).get(0)).spec();
        assertFalse(chest.matches(Workbench.ITEM_ID));
    }
}
