package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.inv.ArmorType;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Which piece of armour, if any, to put on next: the best the pack holds for a slot, by armour
 * points and then toughness (combat spec, *Armour*). One piece at a time, head to feet, since each
 * is its own timed move.
 *
 * <p>A piece is worn only if it beats what is worn there: a pumpkin, with no points, never replaces
 * an empty head. A piece with Curse of Binding is never put on, and one already worn is never taken
 * off — a player cannot take it off either.
 */
public final class ArmorChoice {

    /**
     * One measured piece.
     *
     * @param slot  the storage slot it is in; ignored for a worn piece
     * @param bound whether it carries Curse of Binding
     */
    public record Piece(int slot, ArmorType type, double armor, double toughness, boolean bound) {

        boolean beats(@Nullable Piece other) {
            if (other == null) {
                return armor > 0.0 || toughness > 0.0;
            }
            return armor > other.armor || armor == other.armor && toughness > other.toughness;
        }
    }

    private ArmorChoice() {
    }

    /**
     * @param pack every armour piece in storage, measured
     * @param worn what is worn now, by slot; a slot with nothing on is absent
     * @return the piece to put on next, or null when nothing beats what is worn
     */
    public static @Nullable Piece choose(List<Piece> pack, Map<ArmorType, Piece> worn) {
        for (ArmorType type : ArmorType.values()) {
            Piece current = worn.get(type);
            if (current != null && current.bound()) {
                continue;
            }
            Piece best = current;
            for (Piece piece : pack) {
                if (piece.type() == type && !piece.bound() && piece.beats(best)) {
                    best = piece;
                }
            }
            if (best != current) {
                return best;
            }
        }
        return null;
    }
}
