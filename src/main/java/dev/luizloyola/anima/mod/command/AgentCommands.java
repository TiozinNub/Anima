package dev.luizloyola.anima.mod.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import dev.luizloyola.anima.core.agent.AgentModifiers;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.AspectModifier;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.mod.debug.DebugView;
import dev.luizloyola.anima.mod.debug.DebugLayer;
import dev.luizloyola.anima.mod.debug.PoiLabels;
import net.minecraft.commands.CommandBuildContext;
import java.util.Map;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.core.agent.PrivateIdentity;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.command.AgentSelection;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.luizloyola.anima.compat.inv.ItemStacks;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.CrescentSampler;
import dev.luizloyola.anima.core.brain.knowledge.HorizonBuffer;
import dev.luizloyola.anima.core.brain.knowledge.HorizonScanner;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Sighting;
import dev.luizloyola.anima.mod.debug.HorizonViewer;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.task.BreakBlock;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.ObtainItem;
import dev.luizloyola.anima.core.brain.task.SatisfyHunger;
import dev.luizloyola.anima.core.config.ConfigValues;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.inv.ArmorType;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.log.Entry;
import dev.luizloyola.anima.core.log.JournalService;
import dev.luizloyola.anima.core.agent.Needs;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.mod.brain.KnowledgeViewer;
import dev.luizloyola.anima.core.brain.board.SiteClaims;
import dev.luizloyola.anima.core.brain.board.WorkLease;
import dev.luizloyola.anima.mod.brain.Claims;
import dev.luizloyola.anima.mod.brain.Knowledges;
import dev.luizloyola.anima.mod.brain.BeingViewer;
import dev.luizloyola.anima.mod.log.Journals;
import dev.luizloyola.anima.mod.log.ThoughtBroadcast;
import dev.luizloyola.anima.mod.net.ContactsSync;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.social.ContactData;
import dev.luizloyola.anima.mod.social.PartyData;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.core.agent.PrivateIdentity;

/**
 * The command surface of <em>having a mind</em> rather than of being any particular creature —
 * navigation, journal, remembered places, perception, brain state, inventory, the selection pin,
 * the contact book. A work board, a chop, a quota or an appearance are a consuming mod's.
 *
 * <p>Every subcommand is a <b>factory</b>: Brigadier parents a builder at registration, so a cached
 * node could be mounted only once, and more than one root mounts this tree.
 */
public final class AgentCommands {

    /** How far the bare resolve ladder looks for a body when nothing is pinned. */
    private static final double NEAREST_RADIUS = 32.0;

    private AgentCommands() {
    }

    /**
     * Pin the agent this source's later commands target.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> select() {
        return Commands.literal("select")
                                .executes(ctx -> selectHere(ctx.getSource()))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> selectClear(ctx.getSource())))
                                .then(Commands.literal("show")
                                        .executes(ctx -> selectShow(ctx.getSource())))
                                .then(Commands.argument("person", StringArgumentType.string())
                                        .suggests(PERSON_SUGGESTIONS)
                                        .executes(ctx -> selectByToken(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "person"))));
    }

    /**
     * Who knows whom — and the scaffold that stands in for being introduced.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> contacts() {
        return Commands.literal("contacts")
                                .executes(ctx -> contactsList(ctx.getSource()))
                                .then(Commands.literal("of")
                                        .then(Commands.argument("person", StringArgumentType.string())
                                                .suggests(ALL_PERSON_SUGGESTIONS)
                                                .executes(ctx -> contactsOf(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "person")))))
                                .then(Commands.literal("meet")
                                        .then(Commands.argument("person", StringArgumentType.string())
                                                .suggests(ALL_PERSON_SUGGESTIONS)
                                                .executes(ctx -> contactsMeet(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "person")))))
                                .then(Commands.literal("forget")
                                        .then(Commands.argument("person", StringArgumentType.string())
                                                .suggests(ALL_PERSON_SUGGESTIONS)
                                                .executes(ctx -> contactsForget(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "person")))))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> contactsClear(ctx.getSource())));
    }

    /**
     * Who belongs with whom — the party roster, and the dev stand-ins for joining and leaving.
     *
     * <p>The skeleton of layer 3's scope: a loner is a party of one, and boards will key off the
     * {@link PartyId}. {@code join}/{@code leave} stand in for the group-up handshake exactly as
     * {@code contacts meet} stands in for the identity exchange.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> party() {
        return Commands.literal("party")
                                .executes(ctx -> partyList(ctx.getSource()))
                                .then(Commands.literal("of")
                                        .then(Commands.argument("person", StringArgumentType.string())
                                                .suggests(ALL_PERSON_SUGGESTIONS)
                                                .executes(ctx -> partyOfAgent(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "person")))))
                                .then(Commands.literal("join")
                                        .then(Commands.argument("person", StringArgumentType.string())
                                                .suggests(ALL_PERSON_SUGGESTIONS)
                                                .executes(ctx -> partyJoin(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "person")))))
                                .then(Commands.literal("leave")
                                        .executes(ctx -> partyLeave(ctx.getSource())));
    }

    /**
     * Drive the navigator directly, below the brain.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> nav() {
        return Commands.literal("nav")
                                .then(Commands.literal("goto")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> navGoto(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                                .then(Commands.literal("stop")
                                        .executes(ctx -> navStop(ctx.getSource())))
                                .then(Commands.literal("status")
                                        .executes(ctx -> navStatus(ctx.getSource())))
                                .then(NavDump.node());
    }

    /**
     * The mind: what it is doing, and the dev overrides that take the wheel.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> brain() {
        return Commands.literal("brain")
                                .then(Commands.literal("goto")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> brainGoto(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                                .then(Commands.literal("eat")
                                        .executes(ctx -> brainEat(ctx.getSource())))
                                .then(Commands.literal("break")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> brainBreak(ctx.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                                .then(Commands.literal("status")
                                        .executes(ctx -> brainStatus(ctx.getSource())))
                                .then(Commands.literal("cancel")
                                        .executes(ctx -> brainCancel(ctx.getSource())))
                                // The autonomy switch — spawns start ON. Bare, it READS the
                                // switch rather than flipping it (see the note on toggles below).
                                .then(Commands.literal("auto")
                                        .executes(ctx -> brainAutoShow(ctx.getSource()))
                                        .then(Commands.literal("true")
                                                .executes(ctx -> brainAuto(ctx.getSource(), true)))
                                        .then(Commands.literal("false")
                                                .executes(ctx -> brainAuto(ctx.getSource(), false))))
                                // The wander mute — one drive off, the brain otherwise whole.
                                // Bare, it READS the switch, same as `auto`.
                                .then(Commands.literal("wander")
                                        .executes(ctx -> brainWanderShow(ctx.getSource()))
                                        .then(Commands.literal("true")
                                                .executes(ctx -> brainWander(ctx.getSource(), true)))
                                        .then(Commands.literal("false")
                                                .executes(ctx -> brainWander(ctx.getSource(), false))));
    }

    /**
     * Forward the resolved agent's thoughts to chat.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> think() {
        return Commands.literal("think")
                                .executes(ctx -> thinkToggle(ctx.getSource()));
    }

    /**
     * The per-agent journal.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> log() {
        return Commands.literal("log")
                                .executes(ctx -> logDump(ctx.getSource(), null, DEFAULT_LOG_COUNT))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(ctx -> logDump(ctx.getSource(), null,
                                                IntegerArgumentType.getInteger(ctx, "count"))))
                                .then(logCategory("brain", Category.BRAIN))
                                .then(logCategory("pathfind", Category.PATHFIND))
                                .then(logCategory("body", Category.BODY))
                                .then(logCategory("sense", Category.SENSE))
                                .then(logCategory("project", Category.PROJECT))
                                // "for <name|id>" reaches any person by directory lookup — including one
                                // whose entity is unloaded (the ring is AgentId-keyed and outlives the
                                // entity), which the loaded-only nearest/pinned resolve() cannot.
                                .then(Commands.literal("for")
                                        .then(Commands.argument("person", StringArgumentType.string())
                                                .suggests(ALL_PERSON_SUGGESTIONS)
                                                .executes(ctx -> logFor(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "person"),
                                                        null, DEFAULT_LOG_COUNT))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> logFor(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "person"),
                                                                null, IntegerArgumentType.getInteger(ctx, "count"))))
                                                .then(logForCategory("brain", Category.BRAIN))
                                                .then(logForCategory("pathfind", Category.PATHFIND))
                                                .then(logForCategory("body", Category.BODY))
                                                .then(logForCategory("sense", Category.SENSE))));
    }

    /**
     * Remembered points of interest, and the particle viewer over them.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> knowledge() {
        return Commands.literal("knowledge")
                                .executes(ctx -> knowledgeList(ctx.getSource()))
                                .then(Commands.literal("view")
                                        .executes(ctx -> knowledgeViewShow(ctx.getSource()))
                                        .then(Commands.literal("true")
                                                .executes(ctx -> knowledgeView(ctx.getSource(), true)))
                                        .then(Commands.literal("false")
                                                .executes(ctx -> knowledgeView(ctx.getSource(), false))));
    }

    /** Who they can currently perceive. */
    /**
     * Who is holding what, both keyspaces at once: the server's work-SITE claims and the resolved
     * agent's boards' ITEM leases.
     *
     * <p>Shown together because they are one semantics — a hold that lives a fixed span past its
     * last heartbeat — and because for an anchor-keyed errand the two holds are on the same thing.
     */
    /**
     * The far sense: what the agent is currently making out on the skyline, in text or painted over
     * the world.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> horizon() {
        return Commands.literal("horizon")
                                .executes(ctx -> horizonShow(ctx.getSource()))
                                .then(Commands.literal("view")
                                        .then(Commands.literal("true")
                                                .executes(ctx -> horizonView(ctx.getSource(), true)))
                                        .then(Commands.literal("false")
                                                .executes(ctx -> horizonView(ctx.getSource(), false))));
    }

    /** The skyline as a line of text — how much of it is swept, and what it is topped by. */
    private static int horizonShow(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        String name = person.entity().getName().getString();
        int radius = HorizonScanner.radius(person.profile());
        if (radius <= CrescentSampler.radius(person.profile())) {
            Replies.send(source, () -> Component.literal(name
                            + " has no skyline — places.horizon_radius is inside the sense radius.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        HorizonBuffer buffer = person.poiSensor() == null ? null : person.poiSensor().horizon();
        if (buffer == null) {
            Replies.fail(source, Component.literal(name + " hasn't perceived anything yet."));
            return 0;
        }
        int swept = 0;
        int cutShort = 0;
        double highest = Double.NEGATIVE_INFINITY;
        for (int bin = 0; bin < HorizonBuffer.BINS; bin++) {
            if (!buffer.wasSwept(bin)) continue;
            swept++;
            if (buffer.truncated(bin)) cutShort++;
            if (buffer.filled(bin)) highest = Math.max(highest, buffer.tan(bin));
        }
        AgentKnowledge knowledge = Knowledges.of(source.getServer()).forPerson(person.agentId());
        int finalSwept = swept;
        int finalCutShort = cutShort;
        double finalHighest = highest;
        Replies.send(source, () -> Component.literal(name + " — skyline to " + radius
                        + " blocks across a " + CrescentSampler.coneDegrees(person.profile())
                        + "° cone: " + finalSwept + " of " + HorizonBuffer.BINS
                        + " bearings walked, " + knowledge.glimpseCount() + " glimpsed"
                        + (finalCutShort > 0 ? ", " + finalCutShort + " ran out of loaded world" : "")
                        + (finalHighest > Double.NEGATIVE_INFINITY
                                ? ", steepest " + Math.round(Math.toDegrees(Math.atan(finalHighest)))
                                        + "° up"
                                : ""))
                .withStyle(ChatFormatting.AQUA));
        return swept;
    }

    /** Paints that skyline over the world for the asking player. */
    private static int horizonView(CommandSourceStack source, boolean on) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Replies.fail(source, Component.literal("horizon view needs a player to draw for."));
            return 0;
        }
        if (!on) {
            boolean was = HorizonViewer.unwatch(source.getServer(), player);
            Replies.send(source, () -> Component.literal(was
                            ? "Stopped drawing the skyline."
                            : "You weren't watching a skyline.")
                    .withStyle(ChatFormatting.GRAY));
            return 1;
        }
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        AgentId id = person.agentId();
        if (id == null) {
            Replies.fail(source, Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        String name = person.entity().getName().getString();
        HorizonViewer.watch(source.getServer(), player, id);
        Replies.send(source, () -> Component.literal("Drawing " + name
                        + "'s skyline — one cell per bearing, coloured by how high it stands from "
                        + "their eye; gold marks the edges of what they can look at, magenta the "
                        + "glimpses and any bearing that ran out of world.")
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> claims() {
        return Commands.literal("claims")
                                .executes(ctx -> claimsShow(ctx.getSource()));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> peers() {
        return Commands.literal("peers")
                                .executes(ctx -> peersList(ctx.getSource()))
                                .then(Commands.literal("view")
                                        .executes(ctx -> peersViewShow(ctx.getSource()))
                                        .then(Commands.literal("true")
                                                .executes(ctx -> peersView(ctx.getSource(), true)))
                                        .then(Commands.literal("false")
                                                .executes(ctx -> peersView(ctx.getSource(), false))));
    }


    /**
     * What the resolved agent is actually running: its species' value for every aspect, whatever
     * is shifting it, and the number the organs really see.
     *
     * <p>The config file holds only the first tier, so it cannot answer "I set the radius to 24
     * but he sees 31": species, then modifiers, then effective.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> profile() {
        SuggestionProvider<CommandSourceStack> aspects = (ctx, builder) ->
                SharedSuggestionProvider.suggest(
                        java.util.Arrays.stream(ProfileAspect.values())
                                .map(ProfileAspect::key).toList(), builder);
        return Commands.literal("profile")
                                .executes(ctx -> profileShow(ctx.getSource(), null))
                                .then(Commands.literal("all")
                                        .executes(ctx -> profileAll(ctx.getSource())))
                                // The only way to exercise the modifier stack until something
                                // grows real jobs and traits: shift one aspect under a fixed id,
                                // or drop it again. A hand on the machinery, not a game mechanic.
                                .then(Commands.literal("debug")
                                        .then(Commands.literal("clear")
                                                .executes(ctx -> profileDebugClear(ctx.getSource())))
                                        .then(Commands.argument("aspect", StringArgumentType.string())
                                                .suggests(aspects)
                                                .then(Commands.argument("amount",
                                                                DoubleArgumentType.doubleArg())
                                                        .executes(ctx -> profileDebug(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "aspect"),
                                                                DoubleArgumentType.getDouble(ctx, "amount"))))))
                                .then(Commands.argument("aspect", StringArgumentType.string())
                                        .suggests(aspects)
                                        .executes(ctx -> profileShow(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "aspect"))));
    }

    /** The id every {@code profile debug} modifier carries, so one call undoes them all. */
    private static final String DEBUG_MODIFIER = "debug";

    /** Shifts one aspect on the resolved agent by a flat amount, under the debug id. */
    private static int profileDebug(CommandSourceStack source, String aspectKey, double amount) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        ProfileAspect aspect = ProfileAspect.byKey(aspectKey).orElse(null);
        if (aspect == null) {
            Replies.fail(source, Component.literal("No such aspect \"" + aspectKey + "\""));
            return 0;
        }
        if (person.modifiers() == AgentModifiers.NONE) {
            Replies.fail(source, Component.literal(person.entity().getName().getString()
                    + " has no modifier set — this body's mod has not given it one, so it is "
                    + "exactly its species and cannot be shifted"));
            return 0;
        }
        person.modifiers().apply(AspectModifier.add(DEBUG_MODIFIER, aspect, amount));
        for (String line : explain(person.profile(), aspect, true)) {
            Replies.send(source, () -> Component.literal("  " + line)
                    .withStyle(ChatFormatting.YELLOW), true);
        }
        return 1;
    }

    /** Drops every {@code profile debug} shift on the resolved agent. */
    private static int profileDebugClear(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean removed = person.modifiers() != AgentModifiers.NONE
                && person.modifiers().remove(DEBUG_MODIFIER);
        Replies.send(source, () -> Component.literal(removed
                        ? person.entity().getName().getString() + " is exactly a "
                                + person.profile().species() + " again"
                        : "nothing to clear").withStyle(ChatFormatting.GREEN), true);
        return removed ? 1 : 0;
    }

    /** Bare: only what differs from the species. With an aspect: that one, in full. */
    private static int profileShow(CommandSourceStack source, String aspectKey) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        AgentProfile profile = person.profile();
        String name = person.entity().getName().getString();

        if (aspectKey != null) {
            ProfileAspect aspect = ProfileAspect.byKey(aspectKey).orElse(null);
            if (aspect == null) {
                Replies.fail(source, Component.literal("No such aspect \"" + aspectKey
                        + "\" — try tab-completion, or \"profile all\""));
                return 0;
            }
            Replies.send(source, () -> Component.literal(name + " — " + profile.species())
                    .withStyle(ChatFormatting.AQUA));
            for (String line : explain(profile, aspect, true)) {
                Replies.send(source, () -> Component.literal("  " + line));
            }
            return 1;
        }

        List<ProfileAspect> shifted = java.util.Arrays.stream(ProfileAspect.values())
                .filter(aspect -> !profile.modifiers(aspect).isEmpty())
                .toList();
        Replies.send(source, () -> Component.literal(name + " is a " + profile.species()
                + (shifted.isEmpty() ? ", exactly" : " with " + shifted.size() + " aspect(s) shifted"))
                .withStyle(ChatFormatting.AQUA));
        if (shifted.isEmpty()) {
            // No root in the hint: this subcommand is mounted by every consumer as well as by
            // /anima, so naming one would be wrong under all the others.
            Replies.send(source, () -> Component.literal(
                    "  nothing is modifying " + person.pronouns().object()
                            + " — \"profile all\" shows every aspect")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        for (ProfileAspect aspect : shifted) {
            for (String line : explain(profile, aspect, true)) {
                Replies.send(source, () -> Component.literal("  " + line));
            }
        }
        return shifted.size();
    }

    /** Every aspect, grouped by section — "what is this agent running", in full. */
    private static int profileAll(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        AgentProfile profile = person.profile();
        Replies.send(source, () -> Component.literal(person.entity().getName().getString()
                + " — " + profile.species()).withStyle(ChatFormatting.AQUA));
        String section = null;
        for (ProfileAspect aspect : ProfileAspect.values()) {
            if (!aspect.section().equals(section)) {
                section = aspect.section();
                String heading = section;
                Replies.send(source, () -> Component.literal("  " + heading)
                        .withStyle(ChatFormatting.DARK_AQUA));
            }
            for (String line : explain(profile, aspect, false)) {
                Replies.send(source, () -> Component.literal("    " + line)
                        .withStyle(profile.modifiers(aspect).isEmpty()
                                ? ChatFormatting.GRAY : ChatFormatting.YELLOW));
            }
        }
        return ProfileAspect.values().length;
    }

    /**
     * One aspect's derivation: species value, each contribution, effective. Only the aspects with
     * something to derive get more than a line — an unmodified aspect is its species value, and
     * printing "24 -> 24" thirty times would bury the two that matter.
     */
    private static List<String> explain(AgentProfile profile, ProfileAspect aspect,
            boolean withKey) {
        List<AspectModifier> applied = profile.modifiers(aspect);
        String label = withKey ? aspect.key() : aspect.key().substring(aspect.key().indexOf('.') + 1);
        String effective = format(aspect, profile.raw(aspect));
        if (applied.isEmpty()) {
            return List.of(label + " = " + effective);
        }
        List<String> lines = new ArrayList<>();
        lines.add(label + " = " + effective + "  (species " + format(aspect, profile.base(aspect))
                + ")");
        for (AspectModifier modifier : applied) {
            lines.add("    " + modifier.describe() + "  from " + modifier.id());
        }
        return lines;
    }

    private static String format(ProfileAspect aspect, double value) {
        return switch (aspect.kind()) {
            case BOOL -> value != 0.0 ? "true" : "false";
            case INT -> Long.toString((long) value);
            case DOUBLE -> String.format(Locale.ROOT, "%s", value);
        };
    }

    /**
     * What they are carrying.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> inv(CommandBuildContext registryAccess) {
        return Commands.literal("inv")
                                .then(Commands.literal("list")
                                        .executes(ctx -> invList(ctx.getSource())))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> invClear(ctx.getSource())))
                                .then(Commands.literal("give")
                                        .then(Commands.argument("item", ItemArgument.item(registryAccess))
                                                .executes(ctx -> invGive(ctx.getSource(),
                                                        ItemArgument.getItem(ctx, "item"), 1))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> invGive(ctx.getSource(),
                                                                ItemArgument.getItem(ctx, "item"),
                                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(Commands.literal("equip")
                                        .then(Commands.argument("item", ItemArgument.item(registryAccess))
                                                .executes(ctx -> invEquip(ctx.getSource(),
                                                        ItemArgument.getItem(ctx, "item")))));
    }

    /** Default number of journal lines {@code /anima log} prints when no count is given. */
    private static final int DEFAULT_LOG_COUNT = 30;

    /** The equipment slots a Person actually has (a player's set): both hands + the four armor pieces. */
    private static final java.util.Set<EquipmentSlot> PERSON_EQUIP_SLOTS = java.util.EnumSet.of(
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private static int navGoto(CommandSourceStack source, BlockPos pos) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.navigateTo(Vec3.atBottomCenterOf(pos));
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + " -> "
                + pos.toShortString()).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int navStop(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.navigator().stop();
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + " stopped.")
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int navStatus(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + ": "
                + person.navigator().describe()).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** Runs a {@link GoTo} task on the resolved Person through the brain's executor — same walk
     *  as {@link #navGoto}, but through the task machinery, so the whole pipeline is exercised. */
    private static int brainGoto(CommandSourceStack source, BlockPos pos) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new GoTo(pos.getX(), pos.getY(), pos.getZ()));
        String suffix = autoDisabledSuffix(autoDisabled);
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** Runs a {@link BreakBlock} on the resolved Person — the working arm's debug leaf (slice-2
     *  ladder step 1): reach-checked, vanilla break time for the held stack, real drops. */
    private static int brainBreak(CommandSourceStack source, BlockPos pos) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new BreakBlock(pos.getX(), pos.getY(), pos.getZ()));
        String suffix = autoDisabledSuffix(autoDisabled);
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /**
     * Toggles live narration of the resolved Person's peer events ({@code BeingViewer}) — the
     * people-sense twin of {@code knowledge view}. From a player, the lines whisper to that
     * player; from the console they broadcast (and land in the server log). That is what
     * makes the toggle usable headlessly.
     */
    private static int peersView(CommandSourceStack source, boolean on) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        AgentId id = person.agentId();
        if (id == null) {
            Replies.fail(source, Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        String name = person.entity().getName().getString();
        if (on) {
            ServerPlayer player = source.getPlayer();
            BeingViewer.watch(source.getServer(), id, player == null ? null : player.getUUID());
            Replies.send(source, () -> Component.literal("Narrating " + name
                            + "'s people sense — spotted/lost/activity lines land in "
                            + (player == null ? "everyone's chat (console toggle)." : "your chat."))
                    .withStyle(ChatFormatting.GREEN));
        } else {
            boolean was = BeingViewer.unwatch(source.getServer(), id);
            Replies.send(source, () -> Component.literal(was
                            ? "Stopped narrating " + name + "'s people sense."
                            : name + "'s people sense wasn't being narrated.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        return 1;
    }

    /** {@code peers view} with no {@code true|false}: who, if anyone, hears the narration. */
    private static int peersViewShow(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        AgentId id = person.agentId();
        if (id == null) {
            Replies.fail(source, Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        String name = person.entity().getName().getString();
        UUID viewer = BeingViewer.viewer(source.getServer(), id);
        if (viewer == null) {
            Replies.send(source, () -> Component.literal(name + "'s peers view is false.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.literal(name + "'s peers view is true — "
                        + "spotted/lost/activity lines land in " + describeViewer(source, viewer))
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /**
     * Names where a viewer's chat is going, from the asker's point of view: their own chat, another
     * player's, or everyone's (the console toggle). Shared by both {@code view} readouts — telling
     * "on, but narrating to someone else" apart from "off" is the whole point of asking.
     */
    private static String describeViewer(CommandSourceStack source, UUID viewer) {
        if (BeingViewer.EVERYONE.equals(viewer)) {
            return "everyone's chat (console toggle).";
        }
        ServerPlayer self = source.getPlayer();
        if (self != null && self.getUUID().equals(viewer)) {
            return "your chat.";
        }
        ServerPlayer other = source.getServer().getPlayerList().getPlayer(viewer);
        return other == null
                ? "the chat of a player who has since logged off."
                : other.getName().getString() + "'s chat.";
    }

    /** The resolved Person's live {@code beings()} reading — everything they make out. */
    private static int peersList(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        List<Being> beings = person.brain().percepts().beings();
        String name = person.entity().getName().getString();
        if (beings.isEmpty()) {
            Replies.send(source, () -> Component.literal(name + " sees nobody around.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.literal(name + " — " + beings.size() + " perceived")
                .withStyle(ChatFormatting.AQUA));
        String pronoun = person.pronouns().object();
        for (Being being : beings) {
            String kind = being.kind() == Being.Kind.AGENT || being.kind() == Being.Kind.UNKNOWN
                    ? "" : " [" + being.kind().key()
                            + (being.aggressive() ? "!" : "") + "]";
            String line = String.format(Locale.ROOT, "%s%s (%d, %d, %d) - %.1f blocks away, %s%s",
                    being.knownAs(), kind, being.pos().x(), being.pos().y(), being.pos().z(),
                    being.distance(), being.tell(pronoun),
                    being.awareness() == Being.Awareness.SEEN
                            ? "" : " [" + being.awareness().name().toLowerCase(Locale.ROOT) + "]");
            Replies.send(source, () -> Component.literal(line)
                    .withStyle(being.awareness() == Being.Awareness.REMEMBERED
                            ? ChatFormatting.GRAY : ChatFormatting.GREEN));
        }
        return beings.size();
    }

    /** Runs {@link SatisfyHunger} on the resolved Person — the first COMPOUND task, and the
     *  machinery the Eat instinct also drives (autonomously) via the arbiter. */
    private static int brainEat(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new SatisfyHunger());
        String suffix = autoDisabledSuffix(autoDisabled);
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int brainStatus(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + ": "
                + person.brain().describe()).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int brainCancel(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.brain().cancel();
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + " task cancelled; "
                + person.brain().describe()).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** Flips the resolved Person's autonomy switch and echoes the new describe() line (now
     *  reporting auto|manual up front). */
    private static int brainAuto(CommandSourceStack source, boolean auto) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.brain().setAuto(auto);
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + ": "
                + person.brain().describe()).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** {@code brain auto} with no {@code true|false}: reads the switch instead of flipping it. */
    /** Toggle the thinking-out-loud chat channel for the resolved Person — see
     *  {@link ThoughtBroadcast}. */
    private static int thinkToggle(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean on = ThoughtBroadcast.toggle(person.agentId());
        Replies.send(source, () -> Component.literal(person.entity().getName().getString()
                        + (on ? " is thinking out loud in chat now." : "'s thoughts are quiet again."))
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int brainAutoShow(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean auto = person.brain().isAuto();
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + "'s brain auto is "
                        + auto + " — " + (auto
                                ? "the arbiter is deciding."
                                : "manual; hand it back with /anima brain auto true."))
                .withStyle(auto ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        return auto ? 1 : 0;
    }

    /** Mutes or unmutes the resolved agent's idle wander drive and echoes the new brain readout —
     *  the pressure line then reads {@code wander (muted) 0.00}. */
    private static int brainWander(CommandSourceStack source, boolean wander) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.brain().setWander(wander);
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + ": "
                + person.brain().describe()).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** {@code brain wander} with no {@code true|false}: reads the mute instead of flipping it. */
    private static int brainWanderShow(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean wander = person.brain().isWander();
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + "'s brain wander is "
                        + wander + " — " + (wander
                                ? "they drift when nothing else is pressing."
                                : "muted; they stand still when idle. /anima brain wander true to restore."))
                .withStyle(wander ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        return wander ? 1 : 0;
    }

    /** The note appended to a manual {@code brain goto}/{@code brain eat} reply when that very
     *  call is what took the wheel from the arbiter. */
    /** Public: a consumer's own brain verbs report the autonomy switch the same way. */
    public static String autoDisabledSuffix(boolean autoDisabled) {
        return autoDisabled ? " (auto disabled — re-enable with /anima brain auto true)" : "";
    }

    /** A {@code log <category>} branch: dumps only that subsystem's lines (optionally a count). */
    private static LiteralArgumentBuilder<CommandSourceStack> logCategory(String name, Category category) {
        return Commands.literal(name)
                .executes(ctx -> logDump(ctx.getSource(), category, DEFAULT_LOG_COUNT))
                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                        .executes(ctx -> logDump(ctx.getSource(), category,
                                IntegerArgumentType.getInteger(ctx, "count"))));
    }

    /** As {@link #logCategory}, but under {@code log for <person>} — targets that resolved token. */
    private static LiteralArgumentBuilder<CommandSourceStack> logForCategory(String name, Category category) {
        return Commands.literal(name)
                .executes(ctx -> logFor(ctx.getSource(),
                        StringArgumentType.getString(ctx, "person"), category, DEFAULT_LOG_COUNT))
                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                        .executes(ctx -> logFor(ctx.getSource(),
                                StringArgumentType.getString(ctx, "person"), category,
                                IntegerArgumentType.getInteger(ctx, "count"))));
    }

    /**
     * Dumps the journal of the {@link #resolve resolved} (pinned or nearest, and so necessarily
     * <em>loaded</em>) Person — the common case. {@code log for <name|id>} ({@link #logFor}) is the
     * variant that reaches an unloaded one.
     */
    private static int logDump(CommandSourceStack source, @Nullable Category category, int count) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        AgentId id = person.agentId();
        if (id == null) {
            Replies.fail(source, Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        return dumpJournal(source, id, person.entity().getName().getString(), true, category, count);
    }

    /**
     * Dumps the journal of the person the {@code token} names — resolved against the whole
     * {@link PersonDirectory} ({@link #resolveDirectory}), so it reaches a person whose entity is
     * unloaded (or never spawned): the ring is {@code AgentId}-keyed and outlives the entity. The
     * readout is tagged {@code (not loaded)} when there is no live entity, so a ghost's log reads as one.
     */
    private static int logFor(CommandSourceStack source, String token, @Nullable Category category, int count) {
        AgentId id = resolveDirectory(source, token);
        if (id == null) return 0;
        MinecraftServer server = source.getServer();
        String name = label(server, id);
        boolean loaded = AgentBodies.findLoaded(server, id) != null;
        return dumpJournal(source, id, name, loaded, category, count);
    }

    /**
     * The shared journal readout: the last {@code count} lines for {@code id} (newest last),
     * optionally filtered to one {@link Category}, read from the in-memory ring off the server-scoped
     * {@link Journals} service — the ephemeral tier; the full archive is the per-person file.
     */
    private static int dumpJournal(CommandSourceStack source, AgentId id, String name, boolean loaded,
                                   @Nullable Category category, int count) {
        List<Entry> all = Journals.of(source.getServer()).recent(id, Integer.MAX_VALUE); // whole ring; tail below
        List<Entry> matched = category == null ? all
                : all.stream().filter(entry -> entry.category() == category).toList();
        List<Entry> lines = matched.subList(Math.max(0, matched.size() - count), matched.size());
        String scope = category == null ? "" : " (" + category.name().toLowerCase(Locale.ROOT) + ")";
        String tag = loaded ? "" : " (not loaded)";
        if (lines.isEmpty()) {
            Replies.send(source, () -> Component.literal(name + " has no" + scope + " log yet" + tag + ".")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.literal(name + " — last " + lines.size() + " lines" + scope + tag)
                .withStyle(ChatFormatting.AQUA));
        for (Entry entry : lines) {
            Replies.send(source, () -> Component.literal(formatLine(name, entry))
                    .withStyle(colorFor(entry.category())));
        }
        return lines.size();
    }

    /**
     * Resolves a {@code name|id} token against everyone {@link #nameable} — the whole directory
     * (every registered agent, loaded or not) and the online players — to a single {@link AgentId},
     * or {@code null} (having reported why). An id or short-id prefix is tried first, then a
     * case-insensitive name. Names are not unique and there is no "nearest" for an unloaded agent,
     * so an ambiguous name is a hard failure listing the candidates' short-ids — a Person sharing a
     * player's name lands there too.
     */
    private static @Nullable AgentId resolveDirectory(CommandSourceStack source, String rawToken) {
        String token = rawToken.trim();
        String lower = token.toLowerCase(Locale.ROOT);
        Map<AgentId, String> all = nameable(source.getServer());
        List<AgentId> matches = all.keySet().stream()
                .filter(id -> id.toString().toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
        if (matches.isEmpty()) {
            matches = all.entrySet().stream()
                    .filter(e -> e.getValue().equalsIgnoreCase(token))
                    .map(Map.Entry::getKey)
                    .toList();
        }
        if (matches.isEmpty()) {
            Replies.fail(source, Component.literal(
                    "No agent matches '" + token + "' — try a name or id from the list command."));
            return null;
        }
        if (matches.size() > 1) {
            String ids = matches.stream().map(AgentCommands::shortId)
                    .collect(java.util.stream.Collectors.joining(", "));
            Replies.fail(source, Component.literal(matches.size() + " agents named '" + token
                    + "' — pick one by id: " + ids));
            return null;
        }
        return matches.get(0);
    }

    /** One journal line, the {@code Bob - pathfind - target(…) - success N nodes} shape, tick-prefixed. */
    private static String formatLine(String name, Entry entry) {
        StringBuilder line = new StringBuilder()
                .append('[').append(entry.tick()).append("] ")
                .append(name).append(" - ").append(entry.category().name().toLowerCase(Locale.ROOT))
                .append(" - ").append(entry.event());
        if (!entry.detail().isEmpty()) {
            line.append(" - ").append(entry.detail());
        }
        return line.toString();
    }

    /**
     * Prints the server's live site claims, then the resolved agent's boards' item leases.
     *
     * <p>LIVE holds only: a lapsed hold is no claim — every reader of both registries already
     * ignores it, and printing it would invent a state nobody can act on. Ordered by how soon each
     * dies, so the thing about to change is at the top.
     */
    private static int claimsShow(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        long now = server.overworld().getGameTime();
        List<SiteClaims.Held> sites = Claims.of(server).held(now);
        if (sites.isEmpty()) {
            Replies.send(source, () -> Component.literal("No site is claimed right now.")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            Replies.send(source, () -> Component.literal("Site claims — " + sites.size() + " live:")
                    .withStyle(ChatFormatting.AQUA));
            for (SiteClaims.Held held : sites) {
                String line = "  " + held.kind().key().toUpperCase(java.util.Locale.ROOT)
                        + " (" + held.anchor().x() + ", " + held.anchor().y() + ", "
                        + held.anchor().z() + ")"
                        + " — " + label(server, held.who()) + ", " + held.remaining() + "t left";
                Replies.send(source, () -> Component.literal(line).withStyle(ChatFormatting.GRAY));
            }
        }
        AgentBody person = resolveBody(source);
        if (person == null) return 1; // the site half stands on its own; no agent, no lease half
        String name = person.entity().getName().getString();
        List<WorkLease> leases = person.brain().leases();
        if (leases.isEmpty()) {
            Replies.send(source, () -> Component.literal(name + "'s boards: nothing leased out.")
                    .withStyle(ChatFormatting.GRAY));
            return 1;
        }
        Replies.send(source, () -> Component.literal(name + "'s boards — " + leases.size()
                + " item lease(s):").withStyle(ChatFormatting.AQUA));
        for (WorkLease lease : leases) {
            String line = "  " + lease.board() + " · " + lease.item()
                    + " — " + label(server, lease.who()) + ", " + lease.remaining() + "t left";
            Replies.send(source, () -> Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    /**
     * Prints everything the resolved Person remembers — the knowledge store's debug view.
     * Beliefs, not world state: a listed grove may already be chopped (that is the staleness
     * the store is designed around), and "claimed blocks" is the transient dismissal index.
     */
    private static int knowledgeList(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        AgentId id = person.agentId();
        if (id == null) {
            Replies.fail(source, Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        AgentKnowledge knowledge = Knowledges.of(source.getServer()).forPerson(id);
        String name = person.entity().getName().getString();
        if (knowledge.size() == 0 && knowledge.glimpseCount() == 0) {
            Replies.send(source, () -> Component.literal(name + " remembers no POIs yet.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        long now = source.getServer().overworld().getGameTime();
        Replies.send(source, () -> Component.literal(name + " — " + knowledge.size()
                        + " remembered POI(s), " + knowledge.glimpseCount() + " glimpsed, "
                        + person.poiSensor().claimCount() + " claimed blocks")
                .withStyle(ChatFormatting.AQUA));
        for (PoiKind kind : PoiKind.all()) {
            for (PoiMemory memory : knowledge.all(kind)) {
                String line = formatPoi(person, memory, now);
                Replies.send(source, () -> Component.literal(line)
                        .withStyle(ChatFormatting.GREEN));
            }
        }
        // The gist tier last and dimmer: these are not things they know, they are places worth
        // going to look at.
        for (PoiKind kind : PoiKind.all()) {
            for (Sighting sighting : knowledge.glimpses(kind)) {
                String line = formatGlimpse(person, sighting, now);
                Replies.send(source, () -> Component.literal(line)
                        .withStyle(ChatFormatting.AQUA));
            }
        }
        return knowledge.size() + knowledge.glimpseCount();
    }

    /** One rumour line: {@code ~TREE (0, 68, 30) - 30 blocks away, made out from 42 off, 12s ago}. */
    private static String formatGlimpse(AgentBody person, Sighting sighting, long now) {
        double distance = Math.sqrt(person.entity().distanceToSqr(
                sighting.at().x() + 0.5, sighting.at().y() + 0.5, sighting.at().z() + 0.5));
        return "~" + sighting.kind().key().toUpperCase(java.util.Locale.ROOT)
                + " (" + sighting.at().x() + ", " + sighting.at().y() + ", " + sighting.at().z()
                + ") - " + Math.round(distance) + " blocks away, made out from "
                + sighting.range() + " off, " + PoiLabels.ticks(sighting.age(now)) + " ago"
                + (sighting.provenance() == Sighting.Provenance.PASSIVE
                        ? "" : ", " + sighting.provenance().name().toLowerCase(java.util.Locale.ROOT));
    }

    /** One belief line: {@code TREE (10, 64, 8) - 14 blocks away, 4 logs, seen 32s ago, partial}. */
    private static String formatPoi(AgentBody person, PoiMemory memory, long now) {
        double distance = Math.sqrt(person.entity().distanceToSqr(
                memory.anchor().x() + 0.5, memory.anchor().y() + 0.5, memory.anchor().z() + 0.5));
        String age = PoiLabels.age(memory, now);
        int lifetime = memory.kind().lifetimeTicks();
        StringBuilder line = new StringBuilder(memory.kind().key().toUpperCase(java.util.Locale.ROOT));
        if (!memory.detail().isEmpty()) {
            line.append(' ').append(memory.detail());
        }
        line.append(" (").append(memory.anchor().x()).append(", ").append(memory.anchor().y())
                .append(", ").append(memory.anchor().z()).append(") - ")
                .append(Math.round(distance)).append(" blocks away, ")
                .append(memory.units()).append(memory.kind().unit().isEmpty() ? " cells" : memory.kind().unit())
                .append(", seen ").append(age.equals("now") ? "just now" : age + " ago");
        if (lifetime > 0) {
            line.append(", ").append(PoiLabels.when(memory, now));
        }
        if (memory.partial()) {
            line.append(", partial");
        }
        return line.toString();
    }

    /**
     * Toggles the POI viewer for the resolved Person: particles on every remembered anchor +
     * bounds corner, and discovery chat routed to the toggling player (which is why "true" needs
     * a player source; "false" works from anywhere).
     */
    private static int knowledgeView(CommandSourceStack source, boolean on) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        AgentId id = person.agentId();
        if (id == null) {
            Replies.fail(source, Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        String name = person.entity().getName().getString();
        if (on) {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                Replies.fail(source, Component.literal(
                        "knowledge view true needs a player — the discovery chat goes to you."));
                return 0;
            }
            KnowledgeViewer.watch(source.getServer(), id, player.getUUID());
            Replies.send(source, () -> Component.literal("Viewing " + name
                            + "'s knowledge — particles mark beliefs (ghosts included), discoveries land in your chat.")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            boolean was = KnowledgeViewer.unwatch(source.getServer(), id);
            Replies.send(source, () -> Component.literal(was
                            ? "Stopped viewing " + name + "'s knowledge."
                            : name + " wasn't being viewed.")
                    .withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    /**
     * {@code knowledge view} with no {@code true|false}: whether their beliefs are on screen, and
     * whose. Unlike switching it on, asking works from the console — the answer is a chat line
     * here, not particles over there.
     */
    private static int knowledgeViewShow(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        AgentId id = person.agentId();
        if (id == null) {
            Replies.fail(source, Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        String name = person.entity().getName().getString();
        UUID viewer = KnowledgeViewer.viewer(source.getServer(), id);
        if (viewer == null) {
            Replies.send(source, () -> Component.literal(name + "'s knowledge view is false.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.literal(name + "'s knowledge view is true — "
                        + "particles mark " + person.pronouns().possessive() + " beliefs, "
                        + "discoveries land in " + describeViewer(source, viewer))
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static ChatFormatting colorFor(Category category) {
        return switch (category) {
            case BODY -> ChatFormatting.RED;
            case PATHFIND -> ChatFormatting.AQUA;
            case BRAIN -> ChatFormatting.GOLD;
            case SENSE -> ChatFormatting.GREEN;
            case PROJECT -> ChatFormatting.LIGHT_PURPLE;
        };
    }

    /** Prints every non-empty slot of the resolved Person's inventory (storage + equipment). */
    private static int invList(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        List<Inventory.Entry> occupied = person.inventory().occupied();
        if (occupied.isEmpty()) {
            Replies.send(source, () -> Component.literal(
                            person.entity().getName().getString() + " carries nothing.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + " carries:")
                .withStyle(ChatFormatting.AQUA));
        for (Inventory.Entry entry : occupied) {
            String line = "  " + slotLabel(entry.slot()) + "  " + entry.stack().id() + " x" + entry.stack().count();
            Replies.send(source, () -> Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        return occupied.size();
    }

    /** Adds {@code count} of the given item to the resolved Person, reporting anything that didn't fit. */
    private static int invGive(CommandSourceStack source, ItemInput input, int count)
            throws CommandSyntaxException {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        // A count-1 template never trips the item argument's stack-size guard; the real count is set
        // in the core layer, which splits it across slots at the item's own cap. Components (from any
        // {…} the command carried) ride along via toCore.
        dev.luizloyola.anima.core.inv.ItemStack template =
                ItemStacks.templateOf(input, source.registryAccess());
        dev.luizloyola.anima.core.inv.ItemStack remainder = person.inventory().add(template.withCount(count));
        int placed = count - remainder.count();
        // LOGGED: the pack is persisted, nothing journals a change to it, and layer 3 reads it to
        // decide whether a want is satisfied — a quiet hand-out silently completes an errand.
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + " +" + placed + " "
                + template.id() + (remainder.isEmpty() ? "" : "  (" + remainder.count() + " didn't fit)"))
                .withStyle(ChatFormatting.AQUA), true);
        return placed;
    }

    /**
     * Moves one of the given item from storage into its natural equipment slot, returning any
     * piece it displaces to storage. The brain-less driver for wearing gear — like
     * {@code nav goto} is for walking.
     */
    private static int invEquip(CommandSourceStack source, ItemInput input) throws CommandSyntaxException {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        // The template resolves the kind + its natural slot only; the piece actually equipped is
        // pulled from storage below, so its own components (enchants, damage, …) are preserved.
        dev.luizloyola.anima.core.inv.ItemStack want =
                ItemStacks.templateOf(input, source.registryAccess());
        EquipmentSlot slot = ItemStacks.equipmentSlotOf(want);
        if (slot == null) {
            Replies.fail(source, Component.literal(want.id() + " is not equippable."));
            return 0;
        }
        if (!PERSON_EQUIP_SLOTS.contains(slot)) { // e.g. BODY/SADDLE — no such slot on a Person
            Replies.fail(source, Component.literal(
                    want.id() + " can't be worn by a Person (" + slot.getName() + ")."));
            return 0;
        }
        Inventory inv = person.inventory();
        dev.luizloyola.anima.core.inv.ItemStack piece = inv.takeOne(want.id());
        if (piece.isEmpty()) {
            Replies.fail(source, Component.literal(person.entity().getName().getString()
                    + " has no " + want.id() + " to equip."));
            return 0;
        }
        dev.luizloyola.anima.core.inv.ItemStack displaced = placeEquipment(inv, slot, piece);
        if (!displaced.isEmpty()) inv.add(displaced); // whatever was worn there goes back to storage
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + " equipped "
                + want.id() + " (" + slot.getName() + ")")
                .withStyle(ChatFormatting.AQUA), true); // LOGGED: persisted, unjournalled
        return 1;
    }

    private static int invClear(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.inventory().clear();
        Replies.send(source, () -> Component.literal(person.entity().getName().getString() + " inventory cleared.")
                .withStyle(ChatFormatting.AQUA), true); // LOGGED: destroys persisted state
        return 1;
    }

    /** Human-readable region label for a raw inventory slot index. */
    private static String slotLabel(int slot) {
        if (slot < Inventory.MAIN_START) return "hotbar[" + slot + "]";
        if (slot < Inventory.ARMOR_START) return "main[" + (slot - Inventory.MAIN_START) + "]";
        if (slot < Inventory.OFFHAND_SLOT) {
            return ArmorType.values()[slot - Inventory.ARMOR_START].name().toLowerCase(Locale.ROOT);
        }
        return "offhand";
    }

    /** The nearest live agent body of any kind — the generic half of the resolve ladder. */
    private static @Nullable AgentBody nearestBody(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();
        AABB box = AABB.ofSize(origin, NEAREST_RADIUS * 2, NEAREST_RADIUS * 2, NEAREST_RADIUS * 2);
        AgentBody nearest = level.getEntitiesOfClass(LivingEntity.class, box,
                        e -> e.isAlive() && e instanceof AgentBody).stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(origin), b.distanceToSqr(origin)))
                .map(AgentBody.class::cast)
                .orElse(null);
        if (nearest == null) {
            Replies.fail(source, Component.literal(
                    "Nobody with a mind within " + (int) NEAREST_RADIUS + " blocks."));
        }
        return nearest;
    }

    /**
     * The target for an agent-scoped command, in precedence order: the body this command runs
     * <em>as</em>, else the source's pin, else the nearest body. Reports the reason and returns
     * {@code null} when nothing resolves.
     */
    /** Public: a consuming mod's own subcommands resolve their target the same way. */
    public static @Nullable AgentBody resolveBody(CommandSourceStack source) {
        if (source.getEntity() instanceof AgentBody self) {
            if (!self.entity().isAlive()) {
                Replies.fail(source, Component.literal(
                        self.entity().getName().getString() + " is dead — nothing left to command."));
                return null;
            }
            return self;
        }
        Optional<AgentId> pin = AgentSelection.pinned(source);
        if (pin.isEmpty()) return nearestBody(source);
        AgentId id = pin.get();
        AgentBody live = AgentBodies.findLoaded(source.getServer(), id);
        if (live == null) {
            Replies.fail(source, Component.literal("Selected " + label(source.getServer(), id)
                    + " isn't loaded — /anima select clear, or select someone else."));
        }
        return live;
    }

    private static final SuggestionProvider<CommandSourceStack> PERSON_SUGGESTIONS = (ctx, builder) -> {
        MinecraftServer server = ctx.getSource().getServer();
        AgentDirectory directory = AgentDirectory.of(server);
        Stream<String> tokens = AgentBodies.loaded(server).stream().flatMap(body -> {
            AgentId id = body.agentId();
            if (id == null) return Stream.empty();
            String shortId = shortId(id);
            return directory.nameOf(id)
                    .map(name -> Stream.of(name.contains(" ") ? '"' + name + '"' : name, shortId))
                    .orElseGet(() -> Stream.of(shortId));
        });
        return SharedSuggestionProvider.suggest(tokens, builder);
    };

    /** Suggests every name + short id that {@link #resolveDirectory} accepts — every registered
     *  agent, loaded or not (so {@code log for} can reach an unloaded person's journal, which
     *  {@code select} cannot), plus the online players a contact book has to be able to name. */
    private static final SuggestionProvider<CommandSourceStack> ALL_PERSON_SUGGESTIONS = (ctx, builder) -> {
        Stream<String> tokens = nameable(ctx.getSource().getServer()).entrySet().stream()
                .flatMap(entry -> Stream.of(
                        entry.getValue().contains(" ") ? '"' + entry.getValue() + '"' : entry.getValue(),
                        shortId(entry.getKey())));
        return SharedSuggestionProvider.suggest(tokens, builder);
    };

    /** Pins the Person named (or short-id-prefixed) by {@code rawToken}. */
    private static int selectByToken(CommandSourceStack source, String rawToken) {
        String token = rawToken.trim();
        MinecraftServer server = source.getServer();
        AgentDirectory directory = AgentDirectory.of(server);
        List<AgentBody> loaded = AgentBodies.loaded(server);

        // An id (or short-id prefix) is an unambiguous handle, so it's tried first; only if nothing
        // matches by id do we fall back to a case-insensitive name match (names aren't unique).
        String lower = token.toLowerCase(Locale.ROOT);
        List<AgentBody> matches = loaded.stream()
                .filter(b -> b.agentId() != null
                        && b.agentId().toString().toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
        if (matches.isEmpty()) {
            matches = loaded.stream()
                    .filter(b -> b.agentId() != null
                            && directory.nameOf(b.agentId()).map(n -> n.equalsIgnoreCase(token)).orElse(false))
                    .toList();
        }
        if (matches.isEmpty()) {
            Replies.fail(source, Component.literal(
                    "Nobody loaded matches '" + token + "' — try the list command."));
            return 0;
        }
        // Ambiguity FAILS rather than guessing (decision: Luiz): a name collides across kinds once
        // several mods share a world, and picking the closer of a settler and a wolf is a worse
        // answer than asking. Ids are always unambiguous.
        if (matches.size() > 1) {
            String ids = matches.stream()
                    .map(b -> shortId(b.agentId()))
                    .collect(java.util.stream.Collectors.joining(", "));
            Replies.fail(source, Component.literal(matches.size() + " agents match '" + token
                    + "' — pick one by id: " + ids));
            return 0;
        }
        AgentId id = matches.get(0).agentId();
        AgentSelection.pin(source, id);
        Replies.send(source, () -> Component.literal("Selected " + label(server, id))
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** No-argument {@code select}: running <em>as</em> a Person pins that Person
     *  ({@code /execute as @e[…,limit=1] run anima select}); a player pins the Person under their
     *  crosshair, or unpins when looking at nobody; the console pins the nearest one. */
    private static int selectHere(CommandSourceStack source) {
        Entity self = source.getEntity();
        AgentBody target = self instanceof AgentBody body ? body
                : self instanceof ServerPlayer player ? lookedAt(player)
                : nearestBody(source);
        if (target == null) {
            if (self instanceof ServerPlayer) {
                // Looking at nobody clears any current pin — the same as `select clear`.
                return selectClear(source);
            }
            // the console branch already reported via nearest()
            return 0;
        }
        AgentId id = target.agentId();
        if (id == null) {
            Replies.fail(source, Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        AgentSelection.pin(source, id);
        Replies.send(source, () -> Component.literal("Selected " + label(source.getServer(), id))
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int selectClear(CommandSourceStack source) {
        if (AgentSelection.clear(source)) {
            Replies.send(source, () -> Component.literal(
                            "Selection cleared — commands use the nearest Person again.")
                    .withStyle(ChatFormatting.AQUA));
            return 1;
        }
        Replies.send(source, () -> Component.literal("No Person was selected.").withStyle(ChatFormatting.GRAY));
        return 0;
    }

    private static int selectShow(CommandSourceStack source) {
        Optional<AgentId> pin = AgentSelection.pinned(source);
        if (pin.isEmpty()) {
            Replies.send(source, () -> Component.literal("No selection — commands use the nearest Person.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        AgentId id = pin.get();
        boolean loaded = AgentBodies.findLoaded(source.getServer(), id) != null;
        Replies.send(source, () -> Component.literal("Selected: " + label(source.getServer(), id)
                + (loaded ? "" : " (not loaded)")).withStyle(loaded ? ChatFormatting.AQUA : ChatFormatting.GRAY));
        return loaded ? 1 : 0;
    }

    /** The Person a player's crosshair is on, within {@link #NEAREST_RADIUS}, or {@code null}. */
    private static @Nullable AgentBody lookedAt(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 far = eye.add(player.getViewVector(1.0F).scale(NEAREST_RADIUS));
        AABB search = player.getBoundingBox().expandTowards(player.getViewVector(1.0F).scale(NEAREST_RADIUS)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, far, search,
                e -> e instanceof AgentBody body && body.entity().isAlive(), // never pin a corpse
                NEAREST_RADIUS * NEAREST_RADIUS);
        return hit != null && hit.getEntity() instanceof AgentBody body ? body : null;
    }

    /** The agent's name if anyone can put one to it, else the short id — a stable label for messages. */
    /** Public: the name-or-short-id an operator sees, shared by every surface. */
    public static String label(MinecraftServer server, AgentId id) {
        return AgentDirectory.of(server).nameOf(id)
                .or(() -> Optional.ofNullable(server.getPlayerList().getPlayer(id.value()))
                        .map(player -> player.getName().getString()))
                .orElse(shortId(id));
    }

    /**
     * Every agent a command may put a NAME to: the whole directory (loaded or not) and the online
     * players, mapped to what each is called.
     *
     * <p>Players belong here because a player's account uuid <em>is</em> their agent id — the rule
     * {@link #sourceIdentity} mints by and {@link ContactData} persists by — while no directory
     * ever holds them. Without this half no {@code name|id} argument could name a player at all.
     *
     * <p>The player list is the only version-neutral name source, so an <em>offline</em> player
     * still labels as a short id: the profile cache is a different class on either side of 26.1
     * and would want a compat facade first.
     *
     * <p>Directory first, so a consumer's own record for an id wins over the account behind it.
     */
    private static Map<AgentId, String> nameable(MinecraftServer server) {
        Map<AgentId, String> named = new LinkedHashMap<>();
        AgentDirectory.of(server).known().forEach((id, identity) -> named.put(id, identity.name()));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            named.putIfAbsent(AgentId.of(player.getUUID()), player.getName().getString());
        }
        return named;
    }

    /**
     * The identity the SOURCE holds a contact book under: a Person when running {@code execute as}
     * one, else the player who typed it (their account uuid is their person-identity, the same rule
     * the sense uses). The console has no book — it is nobody, and omniscient besides.
     */
    private static @Nullable AgentId sourceIdentity(CommandSourceStack source) {
        Entity self = source.getEntity();
        if (self instanceof AgentBody body) {
            AgentId id = body.agentId();
            if (id == null) {
                Replies.fail(source, Component.literal("That Person isn't identified yet (still spawning)."));
            }
            return id;
        }
        if (self instanceof ServerPlayer player) {
            return AgentId.of(player.getUUID());
        }
        Replies.fail(source, Component.literal(
                "The console knows everyone and nobody — run this as a player, or "
                        + "/execute as <person> run anima contacts …"));
        return null;
    }

    /**
     * Everyone the source can put a name to.
     *
     * <p>A creative or spectator player knows everyone, by the same
     * {@link ContactsSync#seesEveryone} rule that decides what their client is sent, so the listing
     * cannot disagree with the nameplates. A vantage point, not a book: stepping back into survival
     * lists only what they earned.
     */
    private static int contactsList(CommandSourceStack source) {
        AgentId self = sourceIdentity(source);
        if (self == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (source.getEntity() instanceof ServerPlayer player && ContactsSync.seesEveryone(player)) {
            String vantage = player.isSpectator() ? "As a spectator" : "In creative";
            return printNames(source, AgentDirectory.of(server).known().keySet(),
                    vantage + ", you know");
        }
        return printNames(source, ContactData.get(server).contactsOf(self), "You know");
    }

    /** Everyone that Person can name — the omniscient view: a dev tool reads any book. */
    private static int contactsOf(CommandSourceStack source, String token) {
        AgentId who = resolveDirectory(source, token);
        return who == null ? 0
                : printContacts(source, who, label(source.getServer(), who) + " knows");
    }

    private static int printContacts(CommandSourceStack source, AgentId who, String heading) {
        return printNames(source, ContactData.get(source.getServer()).contactsOf(who), heading);
    }

    private static int printNames(CommandSourceStack source, Set<AgentId> known, String heading) {
        MinecraftServer server = source.getServer();
        if (known.isEmpty()) {
            Replies.send(source, () -> Component.literal(heading + " nobody yet.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.literal(heading + " " + known.size()
                + (known.size() == 1 ? " person:" : " people:")).withStyle(ChatFormatting.AQUA));
        for (AgentId id : known) {
            String line = "  " + label(server, id) + "  " + shortId(id);
            Replies.send(source, () -> Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        return known.size();
    }

    /**
     * A mutual introduction — the hand-run stand-in for the identity exchange the encounter rung
     * will do in-world. Both books gain an entry, because being told a name and telling yours are
     * two facts, not one.
     */
    private static int contactsMeet(CommandSourceStack source, String token) {
        AgentId self = sourceIdentity(source);
        if (self == null) return 0;
        AgentId other = resolveDirectory(source, token);
        if (other == null) return 0;
        MinecraftServer server = source.getServer();
        if (self.equals(other)) {
            Replies.fail(source, Component.literal("You have already met yourself."));
            return 0;
        }
        if (!ContactData.get(server).introduce(self, other)) {
            Replies.send(source, () -> Component.literal("Already acquainted.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        ContactsSync.learned(server, self, other);
        ContactsSync.learned(server, other, self);
        // LOGGED: the contact book is persisted SavedData and nothing journals a change to it —
        // the journal is the agent's own, and an agent does not narrate what was done TO it.
        Replies.send(source, () -> Component.literal(label(server, self) + " and " + label(server, other)
                + " have been introduced.").withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    /** One-sided forgetting: the source loses the name, the other keeps theirs. */
    private static int contactsForget(CommandSourceStack source, String token) {
        AgentId self = sourceIdentity(source);
        if (self == null) return 0;
        AgentId other = resolveDirectory(source, token);
        if (other == null) return 0;
        MinecraftServer server = source.getServer();
        if (!ContactData.get(server).forget(self, other)) {
            Replies.send(source, () -> Component.literal("You never knew who that is.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        resyncIfOnline(server, self);
        Replies.send(source, () -> Component.literal(label(server, other) + " is a stranger again.")
                .withStyle(ChatFormatting.AQUA), true); // LOGGED: persisted, unjournalled
        return 1;
    }

    private static int contactsClear(CommandSourceStack source) {
        AgentId self = sourceIdentity(source);
        if (self == null) return 0;
        MinecraftServer server = source.getServer();
        if (!ContactData.get(server).clear(self)) {
            Replies.send(source, () -> Component.literal("You knew nobody to begin with.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        resyncIfOnline(server, self);
        Replies.send(source, () -> Component.literal("Every name forgotten — everyone is a stranger.")
                .withStyle(ChatFormatting.AQUA), true); // LOGGED: persisted, unjournalled
        return 1;
    }

    /** The source's own party — who they belong with, themselves included. */
    private static int partyList(CommandSourceStack source) {
        AgentId self = sourceIdentity(source);
        if (self == null) return 0;
        MinecraftServer server = source.getServer();
        return printParty(source, PartyData.get(server).partyOf(self), "Your party");
    }

    /** That agent's party — the omniscient view: a dev tool reads any roster. */
    private static int partyOfAgent(CommandSourceStack source, String token) {
        AgentId who = resolveDirectory(source, token);
        if (who == null) return 0;
        MinecraftServer server = source.getServer();
        return printParty(source, PartyData.get(server).partyOf(who),
                label(server, who) + "'s party");
    }

    private static int printParty(CommandSourceStack source, PartyId party, String heading) {
        MinecraftServer server = source.getServer();
        List<AgentId> members = PartyData.get(server).members(party);
        String tagged = heading + " (" + shortId(party) + ")";
        if (members.size() == 1) {
            Replies.send(source, () -> Component.literal(tagged + " — a party of one.")
                    .withStyle(ChatFormatting.GRAY));
            return 1;
        }
        Replies.send(source, () -> Component.literal(tagged + " — " + members.size() + " members:")
                .withStyle(ChatFormatting.AQUA));
        for (AgentId member : members) {
            String line = "  " + label(server, member) + "  " + shortId(member);
            Replies.send(source, () -> Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        return members.size();
    }

    /**
     * The source joins {@code token}'s party — the joiner moves, their old party disbands if
     * emptied, matching the social spec's handshake direction ("Bob joins Alice's party, his
     * disbands"). A hand-run stand-in until the group-up encounter rung exists.
     */
    private static int partyJoin(CommandSourceStack source, String token) {
        AgentId self = sourceIdentity(source);
        if (self == null) return 0;
        AgentId other = resolveDirectory(source, token);
        if (other == null) return 0;
        MinecraftServer server = source.getServer();
        if (self.equals(other)) {
            Replies.fail(source, Component.literal("You are already in your own party."));
            return 0;
        }
        PartyData parties = PartyData.get(server);
        PartyId theirs = parties.partyOf(other);
        if (!parties.join(self, theirs)) {
            Replies.send(source, () -> Component.literal("Already in the same party.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        // LOGGED: membership is persisted and it is what layer 3 scopes a board to — a party
        // moved out from under someone silently is a board's worth of work changing hands.
        Replies.send(source, () -> Component.literal(label(server, self) + " joined "
                + label(server, other) + "'s party (" + parties.members(theirs).size()
                + " members).").withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    /** The source strikes out on their own; their next ask mints a fresh party of one. */
    private static int partyLeave(CommandSourceStack source) {
        AgentId self = sourceIdentity(source);
        if (self == null) return 0;
        MinecraftServer server = source.getServer();
        if (!PartyData.get(server).leave(self)) {
            Replies.send(source, () -> Component.literal("You are already on your own.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.literal("You left the party — on your own again.")
                .withStyle(ChatFormatting.AQUA), true); // LOGGED: persisted, scopes a board
        return 1;
    }

    /** The first eight characters of a party id — the same handle style agents get. */
    public static String shortId(PartyId id) {
        String text = id.toString();
        return text.substring(0, Math.min(8, text.length()));
    }

    /** Pushes a whole book after a REMOVAL (an incremental add cannot express forgetting). */
    private static void resyncIfOnline(MinecraftServer server, AgentId who) {
        ServerPlayer player = server.getPlayerList().getPlayer(who.value());
        if (player != null) {
            ContactsSync.resync(player);
        }
    }

    /** The first 8 characters of an id — enough to eyeball and to prefix-match in {@code select}. */
    /** Public: consumers print the same short handles in their own listings. */
    public static String shortId(AgentId id) {
        String text = id.toString();
        return text.substring(0, Math.min(8, text.length()));
    }

    /** Places {@code stack} in the core inventory slot for {@code slot}, returning what was there. */
    private static dev.luizloyola.anima.core.inv.ItemStack placeEquipment(
            Inventory inv, EquipmentSlot slot, dev.luizloyola.anima.core.inv.ItemStack stack) {
        switch (slot) {
            case OFFHAND -> {
                dev.luizloyola.anima.core.inv.ItemStack prev = inv.offhand();
                inv.setOffhand(stack);
                return prev;
            }
            case MAINHAND -> {
                dev.luizloyola.anima.core.inv.ItemStack prev = inv.mainHand();
                inv.set(inv.selectedSlot(), stack);
                return prev;
            }
            default -> { // the four armor slots — HEAD/CHEST/LEGS/FEET names match ArmorType
                ArmorType type = ArmorType.valueOf(slot.name());
                dev.luizloyola.anima.core.inv.ItemStack prev = inv.armor(type);
                inv.setArmor(type, stack);
                return prev;
            }
        }
    }

    /**
     * Every agent the world knows, whatever mod gave it a mind — Anima's own listing.
     *
     * <p>Labels each row with the body's entity type ("Person", "Wolf") rather than anything
     * species-specific: it is the only command that can see across every consumer at once.
     *
     * <p>Directory-backed, so an agent whose chunk is unloaded still appears.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> list() {
        return Commands.literal("list").executes(ctx -> listAgents(ctx.getSource()));
    }

    private static int listAgents(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Map<AgentId, PrivateIdentity> known = AgentDirectory.of(server).known();
        if (known.isEmpty()) {
            Replies.send(source, () -> Component.literal("Nobody has a mind yet.")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Vec3 origin = source.getPosition();
        known.forEach((id, identity) -> {
            AgentBody body = AgentBodies.findLoaded(server, id);
            String kind = body == null ? "unloaded"
                    : body.entity().getType().getDescription().getString();
            String where = body == null ? ""
                    : String.format(Locale.ROOT, "  %s  %.1fm",
                            body.entity().level().dimension().identifier().getPath(),
                            Math.sqrt(body.entity().distanceToSqr(origin)));
            Replies.send(source, () -> Component.literal(String.format(Locale.ROOT,
                    "  %s: %s (%s)%s", kind, identity.name(), shortId(id), where)));
        });
        Replies.send(source, () -> Component.literal("  " + known.size() + " known")
                .withStyle(ChatFormatting.GRAY));
        return known.size();
    }

    /**
     * The in-world gizmo view over the selected agent — path, brain, memory and peers, each
     * layer a different question about the same mind.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> debug() {
        return Commands.literal("debug")
                                .executes(ctx -> debugShow(ctx.getSource()))
                                .then(Commands.literal("show")
                                        .executes(ctx -> debugShow(ctx.getSource())))
                                .then(Commands.literal("off")
                                        .executes(ctx -> debugOff(ctx.getSource())))
                                .then(Commands.argument("layer", StringArgumentType.word())
                                        .suggests(LAYER_SUGGESTIONS)
                                        .executes(ctx -> debugLayerShow(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "layer")))
                                        .then(Commands.literal("true")
                                                .executes(ctx -> debugLayer(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "layer"), true)))
                                        .then(Commands.literal("false")
                                                .executes(ctx -> debugLayer(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "layer"), false))));
    }

    /**
     * Switches one debug-view layer on or off for this player. Layers are per-player and combine
     * freely, which the debug wand's one-at-a-time cycle cannot; what gets drawn is whoever the
     * player has selected, so the view moves with the pin.
     *
     * <p>Player-only, and it fails loudly: the layers are drawn by a client, and the console has
     * none.
     */
    private static int debugLayer(CommandSourceStack source, String token, boolean on) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Replies.fail(source, Component.literal(
                    "The debug view draws on a client — run it as a player. "
                            + "(For a headless readout use knowledge view / peers view.)"));
            return 0;
        }
        DebugLayer layer = parseLayer(source, token);
        if (layer == null) {
            return 0;
        }
        EnumSet<DebugLayer> now = DebugView.set(source.getServer(), player.getUUID(), layer, on);
        Replies.send(source, () -> Component.literal("Debug " + layer.key() + " " + (on ? "on" : "off")
                        + " — showing " + describeLayers(now))
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        return 1;
    }

    /**
     * {@code /anima debug <layer>} with the {@code true|false} left off: READS that layer for this
     * player instead of moving it. Every {@code true|false} switch in this file answers bare the
     * same way — bare never toggles, so a status line is safe to run when you have forgotten what
     * the state is.
     */
    private static int debugLayerShow(CommandSourceStack source, String token) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Replies.fail(source, Component.literal("The debug view is per-player — run it as a player."));
            return 0;
        }
        DebugLayer layer = parseLayer(source, token);
        if (layer == null) {
            return 0;
        }
        EnumSet<DebugLayer> now = DebugView.layers(source.getServer(), player.getUUID());
        boolean on = now.contains(layer);
        Replies.send(source, () -> Component.literal("Debug " + layer.key() + " is " + on
                        + " — showing " + describeLayers(now))
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        return on ? 1 : 0;
    }

    /** The layer this token names, or null having already reported the vocabulary it should use. */
    private static @Nullable DebugLayer parseLayer(CommandSourceStack source, String token) {
        DebugLayer layer = DebugLayer.byKey(token).orElse(null);
        if (layer == null) {
            Replies.fail(source, Component.literal("Unknown debug layer '" + token + "' — try "
                    + Stream.of(DebugLayer.values()).map(DebugLayer::key)
                            .collect(Collectors.joining(", "))));
        }
        return layer;
    }

    /** What this player currently has drawn, and what else there is to draw. */
    private static int debugShow(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Replies.fail(source, Component.literal("The debug view is per-player — run it as a player."));
            return 0;
        }
        EnumSet<DebugLayer> now = DebugView.layers(source.getServer(), player.getUUID());
        Replies.send(source, () -> Component.literal("Debug view — showing " + describeLayers(now))
                .withStyle(ChatFormatting.AQUA));
        Replies.send(source, () -> Component.literal("  layers: "
                        + Stream.of(DebugLayer.values()).map(DebugLayer::key)
                                .collect(Collectors.joining(", ")))
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /** Clears every layer for this player — the command twin of cycling the wand back past the end. */
    private static int debugOff(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Replies.fail(source, Component.literal("The debug view is per-player — run it as a player."));
            return 0;
        }
        boolean had = DebugView.clear(source.getServer(), player.getUUID());
        Replies.send(source, () -> Component.literal(had
                        ? "Debug view off." : "Debug view was already off.")
                .withStyle(ChatFormatting.YELLOW));
        return had ? 1 : 0;
    }

    private static String describeLayers(EnumSet<DebugLayer> layers) {
        return layers.isEmpty()
                ? "nothing"
                : layers.stream().map(DebugLayer::key).collect(Collectors.joining(", "));
    }

    /** Suggests every loaded Person's name (quoted when it has spaces) and short id, so {@code select}
     *  tab-completes to something that actually resolves. */
    /** Every debug layer's name — the completions behind {@code debug <layer>}. */
    private static final SuggestionProvider<CommandSourceStack> LAYER_SUGGESTIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(
                    Stream.of(DebugLayer.values()).map(DebugLayer::key), builder);
}
