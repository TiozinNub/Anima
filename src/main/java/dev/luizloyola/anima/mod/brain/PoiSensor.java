package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.sense.LevelProbe;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.PoiSensorCore;
import dev.luizloyola.anima.core.brain.knowledge.SenseEvent;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.debug.PoiLabels;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Mounts the pure {@link PoiSensorCore} pipeline on an {@link AgentBody} — the perception twin of
 * {@link BrainDriver}, and only the Minecraft boundary (feet, game time, a {@link LevelProbe} over
 * their level and eyes). A <em>body</em> sense, not a brain organ: it runs beside the brain in
 * {@code serverAiStep} and writes into the person's knowledge; the brain reads memory, never the
 * world.
 *
 * <p>Resolved lazily on first tick, the {@code AgentId} and the server being absent at
 * construction. What it learns is narrated to the journal ({@link Category#SENSE}) and to the
 * viewer's discovery chat.
 */
public final class PoiSensor {
    private final AgentBody person;
    private @Nullable KnowledgeData data;
    private @Nullable PoiSensorCore core;
    private @Nullable LevelProbe probe;

    public PoiSensor(AgentBody person) {
        this.person = person;
    }

    /** One perception tick, from {@link AgentBody#serverAiStep()}. */
    public void tick() {
        ServerLevel level = (ServerLevel) this.person.level();
        if (this.core == null) {
            this.data = KnowledgeData.get(level.getServer());
            this.core = new PoiSensorCore(
                    this.data.registry().forPerson(this.person.agentId()),
                    this.person.profile(),
                    RegionCaches.of(level), PlaceIndexes.of(level),
                    ReadPools.of(level.getServer()));
            this.probe = new LevelProbe(this.person.entity());
            applyPending(); // a survey read from NBT before the pipeline existed
        }
        BlockPos feet = this.person.blockPosition();
        // Head rotation, not body rotation — the same bearing the being sense takes its cone
        // from, so what a body notices about places and about bodies agrees about where it looks.
        List<SenseEvent> events = this.core.tick(
                new Pos(feet.getX(), feet.getY(), feet.getZ()),
                this.person.entity().getYHeadRot(), level.getGameTime(), this.probe);
        // Unconditional: setDirty is a boolean flag, and knowledge mutates silently (refreshes
        // don't surface as events) — cheaper to always flag than to track what changed.
        this.data.setDirty();
        for (SenseEvent event : events) {
            this.person.journal().record(Category.SENSE, verb(event.type()), describe(event));
            KnowledgeViewer.onEvent(level.getServer(), this.person.agentId(),
                    this.person.entity().getName().getString(), event);
        }
    }

    /** The journal's event column, one word per outcome. */
    private static String verb(SenseEvent.Type type) {
        return switch (type) {
            case NOTED -> "noticed";
            case FORGOT -> "forgot";
            case OVERLOOKED -> "overlooked";
            case DISMISSED -> "dismissed";
            case GLIMPSED -> "glimpsed";
        };
    }

    /** The far sense's skyline readout, or null before the first tick built one. */
    public dev.luizloyola.anima.core.brain.knowledge.HorizonBuffer horizon() {
        return this.core == null ? null : this.core.horizon();
    }

    /** Transient claim count — the debug command's "how full is the dismissal index" line. */
    public int claimCount() {
        return this.core == null ? 0 : this.core.claimCount();
    }

    /** One line description, shared with the viewer chat:
     *  {@code TREE (10, 64, 8) 4 logs} / {@code HERD cow (…) 6 head} / {@code WATER … partial}. */
    static String describe(SenseEvent event) {
        StringBuilder line = new StringBuilder(event.kind().key().toUpperCase(Locale.ROOT));
        PoiMemory memory = event.memory();
        if (memory != null && !memory.detail().isEmpty()) {
            line.append(' ').append(PoiLabels.detail(memory));
        }
        line.append(" (").append(event.anchor().x()).append(", ").append(event.anchor().y())
                .append(", ").append(event.anchor().z()).append(")");
        if (memory != null) {
            // The kind carries what its units count; a kind that never registered one is
            // still countable, just not in anything more specific than cells.
            String unit = memory.kind().unit();
            line.append(' ').append(memory.units()).append(unit.isEmpty() ? " cells" : unit);
            if (memory.partial()) {
                line.append(", partial");
            }
        }
        return line.toString();
    }

    /** What this body has already accounted for, or empty before the pipeline is built. */
    public java.util.Optional<PoiSensorCore.State> snapshot() {
        return core == null ? java.util.Optional.empty() : java.util.Optional.of(core.snapshot());
    }

    /**
     * Puts that back. Held until the pipeline exists — the core is built lazily on the first
     * sense, which is after the body has been read from its chunk.
     */
    public void restore(PoiSensorCore.State state) {
        this.pendingState = state;
        if (core != null) {
            core.restore(state);
            this.pendingState = null;
        }
    }

    /** A saved survey waiting for the pipeline to exist. */
    private PoiSensorCore.@Nullable State pendingState;

    /** Called once the core is built, to hand over anything that was waiting. */
    private void applyPending() {
        if (pendingState != null && core != null) {
            core.restore(pendingState);
            pendingState = null;
        }
    }
}
