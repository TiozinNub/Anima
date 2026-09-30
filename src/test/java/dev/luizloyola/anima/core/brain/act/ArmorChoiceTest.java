package dev.luizloyola.anima.core.brain.act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.luizloyola.anima.core.brain.act.ArmorChoice.Piece;
import dev.luizloyola.anima.core.inv.ArmorType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ArmorChoice}, with vanilla's numbers: helmets are leather 1, iron 2, diamond 3 with 2
 * toughness, netherite 3 with 3; chestplates leather 3, iron 6, diamond 8 with 2.
 */
class ArmorChoiceTest {

    private static Piece piece(int slot, ArmorType type, double armor, double toughness) {
        return new Piece(slot, type, armor, toughness, false);
    }

    private static Piece bound(int slot, ArmorType type, double armor) {
        return new Piece(slot, type, armor, 0.0, true);
    }

    @Test
    void theMostArmourGoesOn() {
        List<Piece> pack = List.of(piece(10, ArmorType.HEAD, 1, 0), piece(11, ArmorType.HEAD, 2, 0));
        assertEquals(11, ArmorChoice.choose(pack, Map.of()).slot());
    }

    @Test
    void toughnessDecidesBetweenEqualPoints() {
        List<Piece> pack = List.of(piece(10, ArmorType.HEAD, 3, 2), piece(11, ArmorType.HEAD, 3, 3));
        assertEquals(11, ArmorChoice.choose(pack, Map.of()).slot());
    }

    @Test
    void onlyWhatBeatsWhatIsWornGoesOn() {
        Map<ArmorType, Piece> worn = Map.of(ArmorType.CHEST, piece(-1, ArmorType.CHEST, 6, 0));
        assertNull(ArmorChoice.choose(List.of(piece(12, ArmorType.CHEST, 3, 0)), worn));
        assertNull(ArmorChoice.choose(List.of(piece(12, ArmorType.CHEST, 6, 0)), worn), "a tie stays");
        assertEquals(13, ArmorChoice.choose(List.of(piece(13, ArmorType.CHEST, 8, 2)), worn).slot());
    }

    @Test
    void somethingWithNoArmourNeverReplacesNothing() {
        assertNull(ArmorChoice.choose(List.of(piece(12, ArmorType.HEAD, 0, 0)), Map.of()));
    }

    @Test
    void oneSlotAtATimeHeadToFeet() {
        List<Piece> pack = List.of(piece(20, ArmorType.FEET, 1, 0), piece(21, ArmorType.CHEST, 3, 0));
        assertEquals(ArmorType.CHEST, ArmorChoice.choose(pack, Map.of()).type());
        Map<ArmorType, Piece> worn = Map.of(ArmorType.CHEST, piece(-1, ArmorType.CHEST, 3, 0));
        assertEquals(ArmorType.FEET, ArmorChoice.choose(pack, worn).type());
    }

    @Test
    void curseOfBindingIsNeverPutOnNorTakenOff() {
        assertNull(ArmorChoice.choose(List.of(bound(12, ArmorType.HEAD, 3)), Map.of()));
        Map<ArmorType, Piece> worn = Map.of(ArmorType.HEAD, bound(-1, ArmorType.HEAD, 1));
        assertNull(ArmorChoice.choose(List.of(piece(12, ArmorType.HEAD, 3, 2)), worn));
    }
}
