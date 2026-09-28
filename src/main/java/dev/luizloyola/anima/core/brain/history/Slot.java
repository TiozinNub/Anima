package dev.luizloyola.anima.core.brain.history;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One argument of a {@link Deed}: a lang key, a name the teller knows somebody by, or something
 * only the game can name — an item id or a species. Carried as {@code "<type>:<value>"} wherever a string has to hold it (a saved history,
 * a line's payload), and resolved into words by the mod layer on each reader's client.
 *
 * <p>This class owns the prefix. Nothing else splits the string.
 */
public record Slot(Type type, String value) {

    public enum Type { LANG, ITEM, ENTITY, NAME }

    public Slot {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }

    /** A lang key — {@code autarkia.purpose.yard}. */
    public static Slot lang(String key) {
        return new Slot(Type.LANG, key);
    }

    /** An item id — {@code minecraft:cobblestone}. */
    public static Slot item(String id) {
        return new Slot(Type.ITEM, id);
    }

    /** A species as {@code Being.species} spells it — {@code zombie}, or {@code mod:thing}. */
    public static Slot entity(String species) {
        return new Slot(Type.ENTITY, species);
    }

    /** A name said as it is — {@code Luiz}. Never translated. */
    public static Slot name(String name) {
        return new Slot(Type.NAME, name);
    }

    public String encode() {
        return type.name().toLowerCase(Locale.ROOT) + ":" + value;
    }

    /** Empty for a string no {@link #encode} wrote — a save from a newer build, or corruption. */
    public static Optional<Slot> decode(String encoded) {
        int colon = encoded.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        String type = encoded.substring(0, colon).toUpperCase(Locale.ROOT);
        for (Type each : Type.values()) {
            if (each.name().equals(type)) {
                return Optional.of(new Slot(each, encoded.substring(colon + 1)));
            }
        }
        return Optional.empty();
    }
}
