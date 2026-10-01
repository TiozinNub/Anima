package dev.luizloyola.anima.mod.territory;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.territory.Claimed;
import dev.luizloyola.anima.core.territory.Reason;
import dev.luizloyola.anima.core.territory.Territory;
import dev.luizloyola.anima.mod.AnimaMod;
import dev.luizloyola.anima.mod.log.Journals;
import dev.luizloyola.anima.mod.social.PartyData;
import dev.luizloyola.anima.mod.store.StoreGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/**
 * The territory's place in a running server: its store, its log, and the party's end.
 *
 * <p>Every event is written three ways (docs/superpowers/specs/2026-10-01-home-area-design.md,
 * <i>The claim log</i>): each member's journal, {@code logs/anima/<run>/territory.log} — an area is
 * no one member's, so the world needs a log of its own — and the server log.
 */
public final class Territories {

    public static final String EVENT = "territory";
    private static final String OPAC = "openpartiesandclaims";
    private static final String FILE = "territory.log";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Territories() {
    }

    /** Call once from mod init. */
    public static void install() {
        StoreGuard.guard(EVENT, TerritoryData.ID, TerritoryData::get);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Territory territory = of(server);
            territory.keeps(() -> Config.get().i(Knob.TERRITORY_LOG_KEPT));
            territory.onEvent(event -> log(server, territory, event));
            PartyData.get(server).onDisband(party -> {
                territory.releaseAll(party, Reason.of(Reason.Kind.DISBAND, ""), now(server));
                territory.forget(party);
            });
            if (FabricLoader.getInstance().isModLoaded(OPAC) && Config.get().b(Knob.TERRITORY_OPAC)) {
                try {
                    OpacBridge.attach(server, territory, Config.get().b(Knob.TERRITORY_OPAC_MAP));
                } catch (LinkageError | RuntimeException e) {
                    // An OPAC whose API moved, or one Connector loads differently: the areas still
                    // work, they are only not on the map.
                    AnimaMod.LOGGER.warn("territory: could not mirror into Open Parties and Claims", e);
                }
            }
        });
        TerritoryViewer.init();
    }

    public static Territory of(MinecraftServer server) {
        return TerritoryData.get(server).territory();
    }

    /** How far round a footprint an area grows, read on use so a reload applies. */
    public static int margin() {
        return Config.get().i(Knob.TERRITORY_MARGIN);
    }

    public static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    private static void log(MinecraftServer server, Territory territory, Claimed event) {
        String name = territory.name(event.party());
        String line = event.describe();
        AnimaMod.LOGGER.info("territory: {} {}", name, line);
        for (AgentId member : PartyData.get(server).members(event.party())) {
            Journals.of(server).record(member, Category.PROJECT, EVENT, line);
        }
        append(server, "%s tick=%d party=%s name=\"%s\" %s".formatted(LocalTime.now().format(STAMP),
                event.tick(), event.party(), name, line));
    }

    /** Claims are rare — one a building — so the line is written on the spot, not queued. */
    private static void append(MinecraftServer server, String line) {
        try {
            Path dir = Journals.runDir(server);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(FILE), line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            AnimaMod.LOGGER.warn("territory: could not write {}", FILE, e);
        }
    }
}
