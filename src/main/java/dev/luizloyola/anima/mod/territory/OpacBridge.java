package dev.luizloyola.anima.mod.territory;

import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.territory.ChunkKey;
import dev.luizloyola.anima.core.territory.Claimed;
import dev.luizloyola.anima.core.territory.Territory;
import dev.luizloyola.anima.mod.AnimaMod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import xaero.pac.common.claims.api.SpecialClaimOwners;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
import xaero.pac.common.claims.player.api.IPlayerClaimInfoAPI;
import xaero.pac.common.claims.player.api.IPlayerClaimPosListAPI;
import xaero.pac.common.claims.player.api.IPlayerDimensionClaimsAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.api.IServerClaimsManagerAPI;
import xaero.pac.common.server.player.config.api.v2.IPlayerConfigAPI;
import xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions;

/**
 * Open Parties and Claims beside the territory: a chunk a player holds there is taken, and, with
 * {@code territory.opac_map}, every party's area is mirrored as server claims, which Xaero's minimap
 * and world map draw with the party's name and colour
 * (docs/superpowers/specs/2026-10-01-home-area-design.md, decision 7). Loaded only when OPAC is:
 * nothing else names this class's types.
 *
 * <p><b>OPAC mirrors the territory, never the reverse.</b> One server sub-config per party, its
 * protection off, so a player builds there as before. A chunk OPAC holds for anybody else is
 * <i>taken</i>: no party claims it, so this never has to overwrite a player's claim — OPAC's
 * {@code claim} would, without asking.
 *
 * <p>Server claims rather than an owner made up per party: a player's claims expire after a year
 * with their owner offline, lock the owner out past the claim limit, and show a UUID as their name.
 * Only the shared API is called, never OPAC's Fabric-only addon event, which the NeoForge jar that
 * Sinytra Connector loads does not have.
 */
final class OpacBridge {

    /** OPAC allows sixteen characters of {@code A-Za-z0-9-_} in a sub-config id. */
    private static final String PREFIX = "anima-";

    private final IServerClaimsManagerAPI claims;
    private final IPlayerConfigAPI serverConfig;
    private final Territory territory;
    private final boolean shown;
    /** Parties OPAC would not make a sub-config for, so the log says it once. */
    private final Set<PartyId> refused = new HashSet<>();

    private OpacBridge(OpenPACServerAPI api, Territory territory, boolean shown) {
        this.claims = api.getServerClaimsManager();
        this.serverConfig = api.getPlayerConfigManager().getServerClaimConfig();
        this.territory = territory;
        this.shown = shown;
    }

    /**
     * Call when the server has started, after the territory is wired. Hidden, the reconcile still
     * runs: it lets go of whatever an earlier start mirrored, which OPAC would otherwise draw forever.
     */
    static void attach(MinecraftServer server, Territory territory, boolean shown) {
        OpacBridge bridge = new OpacBridge(OpenPACServerAPI.get(server), territory, shown);
        territory.takenBy(bridge::taken);
        if (shown) {
            territory.onEvent(bridge::mirror);
        }
        bridge.reconcile(server);
    }

    /** Held in OPAC by anybody but one of the parties — a player, or a server claim of an op's. */
    private boolean taken(ChunkKey chunk) {
        IPlayerChunkClaimAPI claim = claims.get(Identifier.parse(chunk.dimension()), chunk.x(), chunk.z());
        return claim != null && !ours(claim);
    }

    private boolean ours(IPlayerChunkClaimAPI claim) {
        if (!SpecialClaimOwners.SERVER.equals(claim.getPlayerId())) {
            return false;
        }
        for (String id : serverConfig.getSubConfigIds()) {
            IPlayerConfigAPI sub = serverConfig.getSubConfig(id);
            if (id.startsWith(PREFIX) && sub != null && sub.getSubIndex() == claim.getSubConfigIndex()) {
                return true;
            }
        }
        return false;
    }

    private void mirror(Claimed event) {
        if (!event.granted()) {
            return;
        }
        Integer index = index(event.party());
        if (index == null) {
            return;
        }
        for (ChunkKey chunk : event.added()) {
            claims.claim(Identifier.parse(chunk.dimension()), SpecialClaimOwners.SERVER, index, chunk.x(), chunk.z(),
                    false);
        }
        for (ChunkKey chunk : event.removed()) {
            Identifier dimension = Identifier.parse(chunk.dimension());
            IPlayerChunkClaimAPI claim = claims.get(dimension, chunk.x(), chunk.z());
            if (claim != null && SpecialClaimOwners.SERVER.equals(claim.getPlayerId())
                    && claim.getSubConfigIndex() == index) {
                claims.unclaim(dimension, chunk.x(), chunk.z());
            }
        }
    }

    /**
     * The party's sub-config, made on first use, named and coloured each time — its members, and so
     * its name, change. Null when OPAC refuses to make one, which is said once in the log.
     */
    private @Nullable Integer index(PartyId party) {
        String id = PREFIX + party.value().toString().replace("-", "").substring(0, 10);
        IPlayerConfigAPI sub = serverConfig.getSubConfig(id);
        if (sub == null) {
            sub = serverConfig.createSubConfig(id);
        }
        if (sub == null) {
            if (refused.add(party)) {
                AnimaMod.LOGGER.warn("territory: Open Parties and Claims made no sub-config {} for {}", id,
                        territory.name(party));
            }
            return null;
        }
        sub.tryToSet(PlayerConfigOptions.CLAIMS_NAME, territory.name(party));
        sub.tryToSet(PlayerConfigOptions.CLAIMS_COLOR, Territory.colour(party));
        sub.tryToSet(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS, false);
        return sub.getSubIndex();
    }

    /**
     * Brings OPAC to what the territory holds, at server start: the parties' chunks claimed, and
     * every claim under a party's sub-config that the territory no longer gives that party let go.
     * A chunk a player claimed while the mirror was off stays the player's, and the log says so.
     * Hidden, no party is mirrored, so every claim under an {@code anima-} sub-config is let go.
     */
    private void reconcile(MinecraftServer server) {
        Map<Integer, PartyId> byIndex = new HashMap<>();
        int claimed = 0;
        for (PartyId party : shown ? territory.parties() : Set.<PartyId>of()) {
            Integer index = index(party);
            if (index == null) {
                continue;
            }
            byIndex.put(index, party);
            for (ChunkKey chunk : territory.area(party)) {
                Identifier dimension = Identifier.parse(chunk.dimension());
                IPlayerChunkClaimAPI claim = claims.get(dimension, chunk.x(), chunk.z());
                if (claim == null) {
                    claims.claim(dimension, SpecialClaimOwners.SERVER, index, chunk.x(), chunk.z(), false);
                    claimed++;
                } else if (!ours(claim)) {
                    AnimaMod.LOGGER.warn("territory: {} holds {} in {}, but Open Parties and Claims gives it "
                            + "to somebody else", territory.name(party), chunk, chunk.dimension());
                }
            }
        }
        int released = 0;
        IPlayerClaimInfoAPI held = claims.getPlayerInfo(SpecialClaimOwners.SERVER);
        for (ResourceKey<Level> key : server.levelKeys()) {
            Identifier dimension = key.identifier();
            IPlayerDimensionClaimsAPI there = held == null ? null : held.getDimension(dimension);
            if (there == null) {
                continue;
            }
            List<ChunkKey> stale = new ArrayList<>();
            there.getStream().forEach(list -> stale.addAll(stale(list, dimension.toString(), byIndex)));
            for (ChunkKey chunk : stale) {
                claims.unclaim(dimension, chunk.x(), chunk.z());
                released++;
            }
        }
        AnimaMod.LOGGER.info("territory: mirrored into Open Parties and Claims — {} chunks claimed, {} let go",
                claimed, released);
    }

    /** A sub-config's claims the territory does not give its party: a party gone, or ground let go. */
    private List<ChunkKey> stale(IPlayerClaimPosListAPI list, String dimension, Map<Integer, PartyId> byIndex) {
        List<ChunkKey> stale = new ArrayList<>();
        IPlayerChunkClaimAPI state = list.getClaimState();
        if (!ours(state)) {
            return stale;
        }
        PartyId party = byIndex.get(state.getSubConfigIndex());
        list.getStream().forEach(pos -> {
            ChunkKey chunk = chunk(dimension, pos);
            Optional<PartyId> owner = territory.owner(chunk);
            if (party == null || owner.isEmpty() || !owner.get().equals(party)) {
                stale.add(chunk);
            }
        });
        return stale;
    }

    /** By its corner rather than its fields: ChunkPos became a record at 26.1. */
    private static ChunkKey chunk(String dimension, ChunkPos pos) {
        return ChunkKey.at(dimension, pos.getMinBlockX(), pos.getMinBlockZ());
    }
}
