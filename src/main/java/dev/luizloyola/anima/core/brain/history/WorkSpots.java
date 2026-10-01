package dev.luizloyola.anima.core.brain.history;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where this body's own work may lately have left something on the ground: a block it broke,
 * placed or used, and where something it killed fell. A sweep of its own drops searches round
 * these, so a settler picks up what its work let fall and leaves other people's things alone
 * (Luiz, 2026-09-30).
 */
public final class WorkSpots {

    public static final int CAPACITY = 16;
    /** Five minutes, the time a drop lies on the ground before it is gone. */
    public static final long MAX_AGE_TICKS = 6_000;
    /**
     * How far from a spot a drop still counts as its work's. A felled tree's saplings land a few
     * blocks out from the trunk it was felled by.
     */
    public static final int RADIUS = 6;

    public record Spot(Pos pos, long tick) {
    }

    private final List<Spot> spots = new ArrayList<>();

    /** A spot worked again moves to the front, so a body chopping one trunk keeps one entry. */
    public void record(Pos pos, long now) {
        spots.removeIf(spot -> spot.pos().equals(pos));
        spots.add(0, new Spot(pos, now));
        prune(now);
    }

    /** Whether {@code at} is within {@link #RADIUS} of a spot still recent at {@code now}. */
    public boolean near(Pos at, long now) {
        for (Spot spot : spots) {
            if (now - spot.tick() > MAX_AGE_TICKS) {
                continue;
            }
            int dx = at.x() - spot.pos().x();
            int dy = at.y() - spot.pos().y();
            int dz = at.z() - spot.pos().z();
            if (dx * dx + dy * dy + dz * dz <= RADIUS * RADIUS) {
                return true;
            }
        }
        return false;
    }

    /** The saved form, newest first. */
    public List<Spot> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(spots));
    }

    public void restore(List<Spot> saved) {
        spots.clear();
        spots.addAll(saved);
        while (spots.size() > CAPACITY) {
            spots.remove(spots.size() - 1);
        }
    }

    private void prune(long now) {
        spots.removeIf(spot -> now - spot.tick() > MAX_AGE_TICKS);
        while (spots.size() > CAPACITY) {
            spots.remove(spots.size() - 1);
        }
    }
}
