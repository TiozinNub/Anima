package dev.luizloyola.anima.mod.rest;

import dev.luizloyola.anima.compat.agent.Sleeping;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.rest.BedClaims;
import dev.luizloyola.anima.core.rest.OwnBed;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** The {@link OwnBed} port over a live body and the world's {@link BedClaimsData}. Overworld beds only. */
public final class AgentOwnBed implements OwnBed {

    private final AgentBody person;

    public AgentOwnBed(AgentBody person) {
        this.person = person;
    }

    @Override
    public Optional<Pos> bed() {
        ServerLevel level = overworld();
        AgentId id = person.agentId();
        if (level == null || id == null) {
            return Optional.empty();
        }
        BedClaims claims = BedClaimsData.get(level.getServer()).claims();
        Optional<Pos> held = claims.bedOf(id);
        if (held.isEmpty()) {
            return held;
        }
        BlockPos head = toBlock(held.get());
        if (!level.isLoaded(head)) {
            return held; // nothing to check it against: kept until it can be
        }
        if (!Sleeping.head(level, head).filter(head::equals).isPresent()) {
            letGo(claims, id, held.get(), "the bed is gone");
            return Optional.empty();
        }
        if (!Beds.mayHold(level, head, person)) {
            letGo(claims, id, held.get(), "no longer this body's to keep");
            return Optional.empty();
        }
        return held;
    }

    @Override
    public Optional<Pos> bedIn(Set<Pos> space) {
        Optional<Pos> held = bed();
        if (held.isPresent()) {
            return within(space, held.get()) ? held : Optional.empty();
        }
        ServerLevel level = overworld();
        AgentId id = person.agentId();
        if (level == null || id == null || hasClaim(level, id)) {
            return Optional.empty(); // a bed out of sight is still held
        }
        BedClaims claims = BedClaimsData.get(level.getServer()).claims();
        BlockPos at = person.entity().blockPosition();
        Pos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Pos cell : space) {
            for (int dy = 0; dy >= -1; dy--) {
                Optional<BlockPos> head = Sleeping.head(level, new BlockPos(cell.x(), cell.y() + dy, cell.z()));
                if (head.isEmpty()) {
                    continue;
                }
                Pos bed = new Pos(head.get().getX(), head.get().getY(), head.get().getZ());
                double distance = head.get().distSqr(at);
                if (distance < bestDistance && claims.sleeperIn(bed).isEmpty()
                        && !Sleeping.occupied(level.getBlockState(head.get()))
                        && Beds.mayHold(level, head.get(), person)) {
                    best = bed;
                    bestDistance = distance;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public Optional<Pos> claimIn(Set<Pos> space) {
        Optional<Pos> bed = bedIn(space);
        ServerLevel level = overworld();
        AgentId id = person.agentId();
        if (bed.isEmpty() || level == null || id == null) {
            return bed;
        }
        BedClaims claims = BedClaimsData.get(level.getServer()).claims();
        if (claims.bedOf(id).isEmpty()) {
            if (!claims.claim(id, bed.get())) {
                return Optional.empty();
            }
            Pos at = bed.get();
            person.journal().record(Category.BRAIN, "bed", String.format(Locale.ROOT,
                    "claimed the bed at (%d, %d, %d)", at.x(), at.y(), at.z()));
        }
        return bed;
    }

    private boolean hasClaim(ServerLevel level, AgentId id) {
        return BedClaimsData.get(level.getServer()).claims().bedOf(id).isPresent();
    }

    private void letGo(BedClaims claims, AgentId id, Pos bed, String why) {
        claims.release(id);
        person.journal().record(Category.BRAIN, "bed", String.format(Locale.ROOT,
                "let go of the bed at (%d, %d, %d): %s", bed.x(), bed.y(), bed.z(), why));
    }

    /** Whether the bed lies among the space's cells, either half, or under one. */
    private boolean within(Set<Pos> space, Pos head) {
        ServerLevel level = overworld();
        BlockPos foot = level != null && Sleeping.head(level, toBlock(head)).isPresent()
                ? Sleeping.foot(level, toBlock(head)) : toBlock(head);
        for (Pos half : new Pos[] {head, new Pos(foot.getX(), foot.getY(), foot.getZ())}) {
            if (space.contains(half) || space.contains(new Pos(half.x(), half.y() + 1, half.z()))) {
                return true;
            }
        }
        return false;
    }

    private ServerLevel overworld() {
        return person.level() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD ? level : null;
    }

    private static BlockPos toBlock(Pos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }
}
