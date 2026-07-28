package dev.luizloyola.anima.mod.command;

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
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
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
import dev.luizloyola.anima.mod.brain.Knowledges;
import dev.luizloyola.anima.mod.brain.BeingViewer;
import dev.luizloyola.anima.mod.log.Journals;
import dev.luizloyola.anima.mod.log.ThoughtBroadcast;
import dev.luizloyola.anima.mod.net.ContactsSync;
import dev.luizloyola.anima.mod.social.ContactData;
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
 * node could be mounted only once, where {@code /anima nav} and {@code /autarkia nav} are one.
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
                                        .executes(ctx -> navStatus(ctx.getSource())));
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
                                                .executes(ctx -> brainAuto(ctx.getSource(), false))));
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

    /** Default number of journal lines {@code /autarkia log} prints when no count is given. */
    private static final int DEFAULT_LOG_COUNT = 30;

    /** The equipment slots a Person actually has (a player's set): both hands + the four armor pieces. */
    private static final java.util.Set<EquipmentSlot> PERSON_EQUIP_SLOTS = java.util.EnumSet.of(
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private static int navGoto(CommandSourceStack source, BlockPos pos) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.navigateTo(Vec3.atBottomCenterOf(pos));
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + " -> "
                + pos.toShortString()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int navStop(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.navigator().stop();
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + " stopped.")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int navStatus(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + ": "
                + person.navigator().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Runs a {@link GoTo} task on the resolved Person through the brain's executor — same walk
     *  as {@link #navGoto}, but through the task machinery, so the whole pipeline is exercised. */
    private static int brainGoto(CommandSourceStack source, BlockPos pos) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new GoTo(pos.getX(), pos.getY(), pos.getZ()));
        String suffix = autoDisabledSuffix(autoDisabled);
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Runs a {@link BreakBlock} on the resolved Person — the working arm's debug leaf (slice-2
     *  ladder step 1): reach-checked, vanilla break time for the held stack, real drops. */
    private static int brainBreak(CommandSourceStack source, BlockPos pos) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new BreakBlock(pos.getX(), pos.getY(), pos.getZ()));
        String suffix = autoDisabledSuffix(autoDisabled);
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA), false);
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
            source.sendFailure(Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        String name = person.entity().getName().getString();
        if (on) {
            ServerPlayer player = source.getPlayer();
            BeingViewer.watch(source.getServer(), id, player == null ? null : player.getUUID());
            source.sendSuccess(() -> Component.literal("Narrating " + name
                            + "'s people sense — spotted/lost/activity lines land in "
                            + (player == null ? "everyone's chat (console toggle)." : "your chat."))
                    .withStyle(ChatFormatting.GREEN), false);
        } else {
            boolean was = BeingViewer.unwatch(source.getServer(), id);
            source.sendSuccess(() -> Component.literal(was
                            ? "Stopped narrating " + name + "'s people sense."
                            : name + "'s people sense wasn't being narrated.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    /** {@code peers view} with no {@code true|false}: who, if anyone, hears the narration. */
    private static int peersViewShow(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        AgentId id = person.agentId();
        if (id == null) {
            source.sendFailure(Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        String name = person.entity().getName().getString();
        UUID viewer = BeingViewer.viewer(source.getServer(), id);
        if (viewer == null) {
            source.sendSuccess(() -> Component.literal(name + "'s peers view is false.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(name + "'s peers view is true — "
                        + "spotted/lost/activity lines land in " + describeViewer(source, viewer))
                .withStyle(ChatFormatting.GREEN), false);
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
            source.sendSuccess(() -> Component.literal(name + " sees nobody around.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(name + " — " + beings.size() + " perceived")
                .withStyle(ChatFormatting.AQUA), false);
        String pronoun = person.pronouns().object();
        for (Being being : beings) {
            String kind = being.kind() == Being.Kind.AGENT || being.kind() == Being.Kind.UNKNOWN
                    ? "" : " [" + being.kind().name().toLowerCase(Locale.ROOT)
                            + (being.aggressive() ? "!" : "") + "]";
            String line = String.format(Locale.ROOT, "%s%s (%d, %d, %d) - %.1f blocks away, %s%s",
                    being.knownAs(), kind, being.pos().x(), being.pos().y(), being.pos().z(),
                    being.distance(), being.tell(pronoun),
                    being.awareness() == Being.Awareness.SEEN
                            ? "" : " [" + being.awareness().name().toLowerCase(Locale.ROOT) + "]");
            source.sendSuccess(() -> Component.literal(line)
                    .withStyle(being.awareness() == Being.Awareness.REMEMBERED
                            ? ChatFormatting.GRAY : ChatFormatting.GREEN), false);
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
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + ": "
                + person.brain().describe() + suffix).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int brainStatus(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + ": "
                + person.brain().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int brainCancel(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.brain().cancel();
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + " task cancelled; "
                + person.brain().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** Flips the resolved Person's autonomy switch and echoes the new describe() line (now
     *  reporting auto|manual up front). */
    private static int brainAuto(CommandSourceStack source, boolean auto) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.brain().setAuto(auto);
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + ": "
                + person.brain().describe()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** {@code brain auto} with no {@code true|false}: reads the switch instead of flipping it. */
    /** Toggle the thinking-out-loud chat channel for the resolved Person — see
     *  {@link ThoughtBroadcast}. */
    private static int thinkToggle(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean on = ThoughtBroadcast.toggle(person.agentId());
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString()
                        + (on ? " is thinking out loud in chat now." : "'s thoughts are quiet again."))
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int brainAutoShow(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean auto = person.brain().isAuto();
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + "'s brain auto is "
                        + auto + " — " + (auto
                                ? "the arbiter is deciding."
                                : "manual; hand it back with /autarkia brain auto true."))
                .withStyle(auto ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
        return auto ? 1 : 0;
    }

    /** The note appended to a manual {@code brain goto}/{@code brain eat} reply when that very
     *  call is what took the wheel from the arbiter. */
    /** Public: a consumer's own brain verbs report the autonomy switch the same way. */
    public static String autoDisabledSuffix(boolean autoDisabled) {
        return autoDisabled ? " (auto disabled — re-enable with /autarkia brain auto true)" : "";
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
            source.sendFailure(Component.literal("That Person isn't identified yet (still spawning)."));
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
        String name = AgentDirectory.of(server).nameOf(id).orElse(shortId(id));
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
            source.sendSuccess(() -> Component.literal(name + " has no" + scope + " log yet" + tag + ".")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(name + " — last " + lines.size() + " lines" + scope + tag)
                .withStyle(ChatFormatting.AQUA), false);
        for (Entry entry : lines) {
            source.sendSuccess(() -> Component.literal(formatLine(name, entry))
                    .withStyle(colorFor(entry.category())), false);
        }
        return lines.size();
    }

    /**
     * Resolves a {@code name|id} token against the whole directory (loaded or not) to a single
     * {@link AgentId}, or {@code null} having reported why. An id or short-id prefix is tried
     * first, then a case-insensitive name; since names are not unique and an unloaded person has no
     * "nearest", an ambiguous name fails hard, listing the candidates' short-ids.
     */
    private static @Nullable AgentId resolveDirectory(CommandSourceStack source, String rawToken) {
        String token = rawToken.trim();
        String lower = token.toLowerCase(Locale.ROOT);
        Map<AgentId, PrivateIdentity> all = AgentDirectory.of(source.getServer()).known();
        List<AgentId> matches = all.keySet().stream()
                .filter(id -> id.toString().toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
        if (matches.isEmpty()) {
            matches = all.entrySet().stream()
                    .filter(e -> e.getValue().name().equalsIgnoreCase(token))
                    .map(Map.Entry::getKey)
                    .toList();
        }
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal(
                    "No agent matches '" + token + "' — try a name or id from the list command."));
            return null;
        }
        if (matches.size() > 1) {
            String ids = matches.stream().map(AgentCommands::shortId)
                    .collect(java.util.stream.Collectors.joining(", "));
            source.sendFailure(Component.literal(matches.size() + " agents named '" + token
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
     * Prints everything the resolved Person remembers — the knowledge store's debug view.
     * Beliefs, not world state: a listed grove may already be chopped (that is the staleness
     * the store is designed around), and "claimed blocks" is the transient dismissal index.
     */
    private static int knowledgeList(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        AgentId id = person.agentId();
        if (id == null) {
            source.sendFailure(Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        AgentKnowledge knowledge = Knowledges.of(source.getServer()).forPerson(id);
        String name = person.entity().getName().getString();
        if (knowledge.size() == 0) {
            source.sendSuccess(() -> Component.literal(name + " remembers no POIs yet.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        long now = source.getServer().overworld().getGameTime();
        source.sendSuccess(() -> Component.literal(name + " — " + knowledge.size()
                        + " remembered POI(s), " + person.poiSensor().claimCount() + " claimed blocks")
                .withStyle(ChatFormatting.AQUA), false);
        for (PoiKind kind : PoiKind.all()) {
            for (PoiMemory memory : knowledge.all(kind)) {
                String line = formatPoi(person, memory, now);
                source.sendSuccess(() -> Component.literal(line)
                        .withStyle(ChatFormatting.GREEN), false);
            }
        }
        return knowledge.size();
    }

    /** One belief line: {@code TREE (10, 64, 8) - 14 blocks away, 4 logs, seen 32s ago, partial}. */
    private static String formatPoi(AgentBody person, PoiMemory memory, long now) {
        double distance = Math.sqrt(person.entity().distanceToSqr(
                memory.anchor().x() + 0.5, memory.anchor().y() + 0.5, memory.anchor().z() + 0.5));
        long ageSeconds = memory.age(now) / 20;
        String age = ageSeconds < 2 ? "just now"
                : ageSeconds < 120 ? ageSeconds + "s ago"
                : (ageSeconds / 60) + "m ago";
        StringBuilder line = new StringBuilder(memory.kind().key().toUpperCase(java.util.Locale.ROOT));
        if (!memory.detail().isEmpty()) {
            line.append(' ').append(memory.detail());
        }
        line.append(" (").append(memory.anchor().x()).append(", ").append(memory.anchor().y())
                .append(", ").append(memory.anchor().z()).append(") - ")
                .append(Math.round(distance)).append(" blocks away, ")
                .append(memory.units()).append(memory.kind().unit().isEmpty() ? " cells" : memory.kind().unit())
                .append(", seen ").append(age);
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
            source.sendFailure(Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        String name = person.entity().getName().getString();
        if (on) {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                source.sendFailure(Component.literal(
                        "knowledge view true needs a player — the discovery chat goes to you."));
                return 0;
            }
            KnowledgeViewer.watch(source.getServer(), id, player.getUUID());
            source.sendSuccess(() -> Component.literal("Viewing " + name
                            + "'s knowledge — particles mark beliefs (ghosts included), discoveries land in your chat.")
                    .withStyle(ChatFormatting.GREEN), false);
        } else {
            boolean was = KnowledgeViewer.unwatch(source.getServer(), id);
            source.sendSuccess(() -> Component.literal(was
                            ? "Stopped viewing " + name + "'s knowledge."
                            : name + " wasn't being viewed.")
                    .withStyle(ChatFormatting.GRAY), false);
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
            source.sendFailure(Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        String name = person.entity().getName().getString();
        UUID viewer = KnowledgeViewer.viewer(source.getServer(), id);
        if (viewer == null) {
            source.sendSuccess(() -> Component.literal(name + "'s knowledge view is false.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(name + "'s knowledge view is true — "
                        + "particles mark " + person.pronouns().possessive() + " beliefs, "
                        + "discoveries land in " + describeViewer(source, viewer))
                .withStyle(ChatFormatting.GREEN), false);
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
            source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + " carries nothing.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + " carries:")
                .withStyle(ChatFormatting.AQUA), false);
        for (Inventory.Entry entry : occupied) {
            String line = "  " + slotLabel(entry.slot()) + "  " + entry.stack().id() + " x" + entry.stack().count();
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
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
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + " +" + placed + " "
                + template.id() + (remainder.isEmpty() ? "" : "  (" + remainder.count() + " didn't fit)"))
                .withStyle(ChatFormatting.AQUA), false);
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
            source.sendFailure(Component.literal(want.id() + " is not equippable."));
            return 0;
        }
        if (!PERSON_EQUIP_SLOTS.contains(slot)) { // e.g. BODY/SADDLE — no such slot on a Person
            source.sendFailure(Component.literal(want.id() + " can't be worn by a Person (" + slot.getName() + ")."));
            return 0;
        }
        Inventory inv = person.inventory();
        dev.luizloyola.anima.core.inv.ItemStack piece = inv.takeOne(want.id());
        if (piece.isEmpty()) {
            source.sendFailure(Component.literal(person.entity().getName().getString() + " has no " + want.id() + " to equip."));
            return 0;
        }
        dev.luizloyola.anima.core.inv.ItemStack displaced = placeEquipment(inv, slot, piece);
        if (!displaced.isEmpty()) inv.add(displaced); // whatever was worn there goes back to storage
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + " equipped "
                + want.id() + " (" + slot.getName() + ")").withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int invClear(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.inventory().clear();
        source.sendSuccess(() -> Component.literal(person.entity().getName().getString() + " inventory cleared.")
                .withStyle(ChatFormatting.AQUA), false);
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

    /**
     * The living Person nearest the source's position, within {@link #NEAREST_RADIUS}, or
     * {@code null} having reported that there is none. Searches the SOURCE's dimension and position,
     * so {@code execute at}/{@code positioned} moves the search and {@code execute as} does not —
     * that one is {@link #resolve}'s job. {@code isAlive} is filtered on, like {@link Persons#loaded}:
     * a corpse would otherwise be the nearest thing to a console standing where they died.
     */
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
            source.sendFailure(Component.literal(
                    "Nobody with a mind within " + (int) NEAREST_RADIUS + " blocks."));
        }
        return nearest;
    }

    /**
     * The target of a person-scoped command: the Person it runs <em>as</em>, else the source's
     * pinned Person, else the nearest ({@link #nearest}). Returns {@code null} having reported why.
     *
     * <p>{@code as} outranks a pin because it names one Person for one invocation where a pin is a
     * sticky default — and it is the only handle that <em>iterates</em>, so
     * {@code /execute as @e[type=autarkia:person] run autarkia <anything>} addresses each in turn.
     *
     * <p>Tests the entity, not the position: {@code execute as} rebinds only the source's entity, so
     * a position test would search from the console's spot at world origin.
     *
     * <p>Both non-nearest paths fail loudly rather than falling through — a pin whose entity is no
     * longer loaded, and an {@code as} target no longer alive (a body that died mid-chain).
     */
    /**
     * The target for an agent-scoped command, in precedence order: the body this command runs
     * <em>as</em>, else the source's pin, else the nearest body. Reports the reason and returns
     * {@code null} when nothing resolves.
     */
    /** Public: a consuming mod's own subcommands resolve their target the same way. */
    public static @Nullable AgentBody resolveBody(CommandSourceStack source) {
        if (source.getEntity() instanceof AgentBody self) {
            if (!self.entity().isAlive()) {
                source.sendFailure(Component.literal(
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
            source.sendFailure(Component.literal("Selected " + label(source.getServer(), id)
                    + " isn't loaded — /autarkia select clear, or select someone else."));
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

    /** Suggests every registered person's name + short id (loaded or not) for {@code log for},
     *  which (unlike {@code select}) can reach an unloaded person's journal. */
    private static final SuggestionProvider<CommandSourceStack> ALL_PERSON_SUGGESTIONS = (ctx, builder) -> {
        Stream<String> tokens = AgentDirectory.of(ctx.getSource().getServer()).known().entrySet().stream()
                .flatMap(entry -> Stream.of(
                        entry.getValue().name().contains(" ")
                                ? '"' + entry.getValue().name() + '"' : entry.getValue().name(),
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
            source.sendFailure(Component.literal(
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
            source.sendFailure(Component.literal(matches.size() + " agents match '" + token
                    + "' — pick one by id: " + ids));
            return 0;
        }
        AgentId id = matches.get(0).agentId();
        AgentSelection.pin(source, id);
        source.sendSuccess(() -> Component.literal("Selected " + label(server, id))
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** No-argument {@code select}: running <em>as</em> a Person pins that Person
     *  ({@code /execute as @e[…,limit=1] run autarkia select}); a player pins the Person under their
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
            source.sendFailure(Component.literal("That Person isn't identified yet (still spawning)."));
            return 0;
        }
        AgentSelection.pin(source, id);
        source.sendSuccess(() -> Component.literal("Selected " + label(source.getServer(), id))
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int selectClear(CommandSourceStack source) {
        if (AgentSelection.clear(source)) {
            source.sendSuccess(() -> Component.literal("Selection cleared — commands use the nearest Person again.")
                    .withStyle(ChatFormatting.AQUA), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("No Person was selected.").withStyle(ChatFormatting.GRAY), false);
        return 0;
    }

    private static int selectShow(CommandSourceStack source) {
        Optional<AgentId> pin = AgentSelection.pinned(source);
        if (pin.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No selection — commands use the nearest Person.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        AgentId id = pin.get();
        boolean loaded = AgentBodies.findLoaded(source.getServer(), id) != null;
        source.sendSuccess(() -> Component.literal("Selected: " + label(source.getServer(), id)
                + (loaded ? "" : " (not loaded)")).withStyle(loaded ? ChatFormatting.AQUA : ChatFormatting.GRAY), false);
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

    /** The person's name if the directory knows it, else the short id — a stable label for messages. */
    /** Public: the name-or-short-id an operator sees, shared by every surface. */
    public static String label(MinecraftServer server, AgentId id) {
        return AgentDirectory.of(server).nameOf(id).orElse(shortId(id));
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
                source.sendFailure(Component.literal("That Person isn't identified yet (still spawning)."));
            }
            return id;
        }
        if (self instanceof ServerPlayer player) {
            return AgentId.of(player.getUUID());
        }
        source.sendFailure(Component.literal(
                "The console knows everyone and nobody — run this as a player, or "
                        + "/execute as <person> run autarkia contacts …"));
        return null;
    }

    /** Everyone the source can put a name to. */
    private static int contactsList(CommandSourceStack source) {
        AgentId self = sourceIdentity(source);
        return self == null ? 0 : printContacts(source, self, "You know");
    }

    /** Everyone that Person can name — the omniscient view: a dev tool reads any book. */
    private static int contactsOf(CommandSourceStack source, String token) {
        AgentId who = resolveDirectory(source, token);
        return who == null ? 0
                : printContacts(source, who, label(source.getServer(), who) + " knows");
    }

    private static int printContacts(CommandSourceStack source, AgentId who, String heading) {
        MinecraftServer server = source.getServer();
        Set<AgentId> known = ContactData.get(server).contactsOf(who);
        if (known.isEmpty()) {
            source.sendSuccess(() -> Component.literal(heading + " nobody yet.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(heading + " " + known.size()
                + (known.size() == 1 ? " person:" : " people:")).withStyle(ChatFormatting.AQUA), false);
        for (AgentId id : known) {
            String line = "  " + label(server, id) + "  " + shortId(id);
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
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
            source.sendFailure(Component.literal("You have already met yourself."));
            return 0;
        }
        if (!ContactData.get(server).introduce(self, other)) {
            source.sendSuccess(() -> Component.literal("Already acquainted.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        ContactsSync.learned(server, self, other);
        ContactsSync.learned(server, other, self);
        source.sendSuccess(() -> Component.literal(label(server, self) + " and " + label(server, other)
                + " have been introduced.").withStyle(ChatFormatting.AQUA), false);
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
            source.sendSuccess(() -> Component.literal("You never knew who that is.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        resyncIfOnline(server, self);
        source.sendSuccess(() -> Component.literal(label(server, other) + " is a stranger again.")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int contactsClear(CommandSourceStack source) {
        AgentId self = sourceIdentity(source);
        if (self == null) return 0;
        MinecraftServer server = source.getServer();
        if (!ContactData.get(server).clear(self)) {
            source.sendSuccess(() -> Component.literal("You knew nobody to begin with.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        resyncIfOnline(server, self);
        source.sendSuccess(() -> Component.literal("Every name forgotten — everyone is a stranger.")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
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
            source.sendSuccess(() -> Component.literal("Nobody has a mind yet.")
                    .withStyle(ChatFormatting.GRAY), false);
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
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  %s: %s (%s)%s", kind, identity.name(), shortId(id), where)), false);
        });
        source.sendSuccess(() -> Component.literal("  " + known.size() + " known")
                .withStyle(ChatFormatting.GRAY), false);
        return known.size();
    }
}
