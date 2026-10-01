package dev.luizloyola.anima.core.brain.history;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Crafting tables this body put down to craft on, away from any base, and has not picked up yet.
 * Such a table is the body's to carry on rather than the party's to keep (Luiz, 2026-10-01); a
 * base's table is placed as a station and never listed here, so nothing here is ever a base's.
 */
public final class FieldTables {

    /** More than one only when something cut a craft short before the table was picked up. */
    public static final int CAPACITY = 8;

    private final List<Pos> tables = new ArrayList<>();

    public void record(Pos at) {
        tables.remove(at);
        tables.add(0, at);
        while (tables.size() > CAPACITY) {
            tables.remove(tables.size() - 1);
        }
    }

    public boolean forget(Pos at) {
        return tables.remove(at);
    }

    /** The saved form, newest first. */
    public List<Pos> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(tables));
    }

    public void restore(List<Pos> saved) {
        tables.clear();
        for (Pos at : saved) {
            if (tables.size() < CAPACITY && !tables.contains(at)) {
                tables.add(at);
            }
        }
    }
}
