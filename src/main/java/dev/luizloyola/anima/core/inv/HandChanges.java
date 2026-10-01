package dev.luizloyola.anima.core.inv;

import org.jspecify.annotations.Nullable;

/**
 * Changing what the hand holds, or what the body wears, takes time (combat spec, decision 11 and
 * *Timed wield*): a backpack stack costs one stack move ({@code handling.stack_ticks}), a hotbar
 * slot a short select ({@code handling.select_ticks}), as a player scrolls the hotbar almost at
 * once but opens the inventory for anything else (Luiz, 2026-09-30).
 *
 * <p>A caller asks every tick until the answer is true; the move lands on the tick it is due,
 * inside that call. A tick nobody asks abandons it, and a different request replaces it — one pair
 * of hands. So a fight that ends mid-draw leaves the sword where it was, and the next fight pays the
 * whole draw again.
 */
public final class HandChanges {

    /** How long each kind of move takes this body, in ticks. */
    public record Timing(int selectTicks, int stackTicks) {
        public static final Timing INSTANT = new Timing(0, 0);
    }

    private HandChanges() {
    }

    /** Asks for the stack in storage {@code slot} in the hand; true once it is there. */
    public static boolean wield(Inventory inv, int slot, long now, Timing timing) {
        if (slot == Inventory.HOTBAR_START + inv.selectedSlot()) {
            return true;
        }
        return run(inv, HandChange.Kind.WIELD, slot, -1, wieldTicks(slot, timing), now,
                () -> inv.wield(slot));
    }

    /**
     * Ticks until the stack in storage {@code slot} would be in hand if asked for from now on: 0 if
     * it is there, what is left if that very move is under way, the whole move otherwise.
     */
    public static int ticksToWield(Inventory inv, int slot, long now, Timing timing) {
        if (slot == Inventory.HOTBAR_START + inv.selectedSlot()) {
            return 0;
        }
        return ticksLeft(inv, HandChange.Kind.WIELD, slot, -1, wieldTicks(slot, timing), now);
    }

    /** {@link #ticksToWield}'s answer for an empty hand; 0 when it cannot be emptied. */
    public static int ticksToStow(Inventory inv, long now, Timing timing) {
        int ticks = stowTicks(inv, timing);
        return ticks < 0 ? 0 : ticksLeft(inv, HandChange.Kind.STOW, -1, -1, ticks, now);
    }

    private static int wieldTicks(int slot, Timing timing) {
        return slot < Inventory.MAIN_START ? timing.selectTicks() : timing.stackTicks();
    }

    /** Ticks a stow takes: 0 for a hand already empty, -1 for a pack with no room. */
    private static int stowTicks(Inventory inv, Timing timing) {
        if (inv.mainHand().isEmpty()) {
            return 0;
        }
        if (inv.firstEmpty(Inventory.HOTBAR_START, Inventory.MAIN_START) >= 0) {
            return timing.selectTicks();
        }
        return inv.firstEmpty(Inventory.MAIN_START, Inventory.ARMOR_START) >= 0 ? timing.stackTicks() : -1;
    }

    private static int ticksLeft(Inventory inv, HandChange.Kind kind, int from, int to, int whole,
                                 long now) {
        HandChange change = current(inv, now);
        if (change != null && change.is(kind, from, to) && change.askedAt() >= now - 1) {
            return (int) Math.max(0L, change.readyAt() - now);
        }
        return whole;
    }

    /**
     * Asks for an empty hand; true once it is empty, or at once when the pack has no room to put
     * the held stack in — a little wear beats dropping something.
     */
    public static boolean stow(Inventory inv, long now, Timing timing) {
        int ticks = stowTicks(inv, timing);
        if (ticks <= 0) {
            return true; // empty already, or no room to empty it
        }
        return run(inv, HandChange.Kind.STOW, -1, -1, ticks, now, inv::stow);
    }

    /**
     * Asks for the piece in storage {@code slot} to be worn as {@code type}, whatever was worn there
     * going to {@code slot}; true once it is worn.
     */
    public static boolean equip(Inventory inv, int slot, ArmorType type, long now, Timing timing) {
        int to = Inventory.ARMOR_START + type.ordinal();
        return run(inv, HandChange.Kind.EQUIP, slot, to, timing.stackTicks(), now, () -> {
            ItemStack worn = inv.get(to);
            inv.set(to, inv.get(slot));
            inv.set(slot, worn);
        });
    }

    /** Asks for storage slots {@code from} and {@code to} to swap; true once they have. */
    public static boolean move(Inventory inv, int from, int to, long now, Timing timing) {
        return run(inv, HandChange.Kind.MOVE, from, to, timing.stackTicks(), now, () -> {
            ItemStack moved = inv.get(from);
            inv.set(from, inv.get(to));
            inv.set(to, moved);
        });
    }

    /** Whether a change is under way and was asked for last tick or this one. */
    public static boolean busy(Inventory inv, long now) {
        HandChange change = current(inv, now);
        return change != null && change.askedAt() >= now - 1;
    }

    private static boolean run(Inventory inv, HandChange.Kind kind, int from, int to, int ticks,
                               long now, Runnable move) {
        HandChange change = current(inv, now);
        if (change != null && change.is(kind, from, to) && change.askedAt() >= now - 1) {
            if (now < change.readyAt()) {
                inv.setChange(change.askedAt(now));
                return false;
            }
        } else if (ticks > 0) {
            inv.setChange(new HandChange(kind, from, to, now + ticks, now));
            return false;
        }
        inv.setChange(null);
        move.run();
        return true;
    }

    /**
     * The change under way, a restored one taken up as asked this tick: a reload may skip a tick or
     * two before anything asks, and a restart must not cost a body its half-done draw.
     */
    private static @Nullable HandChange current(Inventory inv, long now) {
        HandChange change = inv.change();
        if (change != null && change.restored()) {
            change = new HandChange(change.kind(), change.from(), change.to(), change.readyAt(), now);
            inv.setChange(change);
        }
        return change;
    }
}
