package dev.luizloyola.anima.core.rest;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Whose bed is whose (sleep spec): one bed a body, one body a bed, each bed by its head. Kept
 * world-wide, so a bed is taken even while its sleeper's chunk is unloaded.
 */
public final class BedClaims {

    public record Row(Pos bed, AgentId sleeper) {
    }

    private final Map<Pos, AgentId> byBed = new LinkedHashMap<>();
    private final Map<AgentId, Pos> byBody = new HashMap<>();
    private Runnable onChange = () -> {
    };

    public void onChange(Runnable onChange) {
        this.onChange = onChange;
    }

    public Optional<Pos> bedOf(AgentId who) {
        return Optional.ofNullable(byBody.get(who));
    }

    public Optional<AgentId> sleeperIn(Pos bed) {
        return Optional.ofNullable(byBed.get(bed));
    }

    /** Take {@code bed} for {@code who}, letting go of any other it held; false when someone else holds it. */
    public boolean claim(AgentId who, Pos bed) {
        AgentId holder = byBed.get(bed);
        if (holder != null) {
            return holder.equals(who);
        }
        Pos old = byBody.put(who, bed);
        if (old != null) {
            byBed.remove(old);
        }
        byBed.put(bed, who);
        onChange.run();
        return true;
    }

    public void release(AgentId who) {
        Pos bed = byBody.remove(who);
        if (bed != null) {
            byBed.remove(bed);
            onChange.run();
        }
    }

    public List<Row> rows() {
        List<Row> rows = new ArrayList<>(byBed.size());
        byBed.forEach((bed, who) -> rows.add(new Row(bed, who)));
        return rows;
    }
}
