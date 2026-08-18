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
import dev.luizloyola.anima.mod.identity.Burial;
import dev.luizloyola.anima.mod.identity.Graves;
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
import dev.luizloyola.anima.core.brain.knowledge.PlaceIndex;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.CrescentSampler;
import dev.luizloyola.anima.core.brain.knowledge.HorizonBuffer;
import dev.luizloyola.anima.core.brain.knowledge.HorizonScanner;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.RegionCache;
import dev.luizloyola.anima.compat.sense.LevelProbe;
import dev.luizloyola.anima.core.brain.knowledge.SenseEvent;
import dev.luizloyola.anima.core.brain.knowledge.Sighting;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.knowledge.Survey;
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
import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.agent.need.Binding;
import dev.luizloyola.anima.core.agent.need.Company;
import dev.luizloyola.anima.core.agent.need.Gauge;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.mod.brain.KnowledgeViewer;
import dev.luizloyola.anima.core.brain.board.SiteClaims;
import dev.luizloyola.anima.core.brain.board.WorkLease;
import dev.luizloyola.anima.mod.brain.Claims;
import dev.luizloyola.anima.mod.brain.Knowledges;
import dev.luizloyola.anima.mod.brain.PlaceIndexes;
import dev.luizloyola.anima.mod.brain.RegionCaches;
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
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import dev.luizloyola.anima.mod.identity.Graves;
import dev.luizloyola.anima.mod.nav.Escorts;
import dev.luizloyola.anima.mod.nav.Swimmer;
import dev.luizloyola.anima.core.agent.PrivateIdentity;

/**
 * The command surface that belongs to <em>having a mind</em> rather than to being any
 * particular creature — navigation, the journal, remembered places, perception, the brain's
 * own state, the carried inventory, the selection pin, the contact book, and the grave.
 *
 * <p>Every subcommand is a <b>factory</b>, so more than one root can mount the same tree:
 * Brigadier parents a builder at registration, so a cached node could only ever be mounted once.
 *
 * <p>What is not here is as deliberate: a settlement's work board, a tree chop, an item quota,
 * a person's appearance. Those are a consuming mod's, and mount beside these.
 */
public final class AgentCommands {

    /** How far the bare resolve ladder looks for a body when nothing is pinned. */
    private static final double NEAREST_RADIUS = 32.0;

    /**
     * One line nested under the one above it. The gutter stays in Java rather than in every
     * translation of the line, where it is one edit away from being trimmed off.
     */
    private static MutableComponent indent(Component line) {
        return Component.literal("  ").append(line);
    }

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
     * "Follow me" — a standing order to walk after somebody until it is called off. Bare it means
     * follow ME; a target can be a player or anything an entity selector picks out (another agent,
     * a cow), because the escort only ever asks a target where it is.
     *
     * <p><b>{@code follow <target> <near> <far>}</b> reads as one sentence: get this close, once
     * they are this far. Omitted, they are {@value Escorts#DEFAULT_NEAR} and
     * {@value Escorts#DEFAULT_FAR}; given, both are required, since {@code near} alone would leave
     * the leash to a default nobody typed.
     *
     * <p><b>Installing one takes the legs</b>: the running task is cancelled and autonomy switched
     * off, because the arbiter and the escort cannot both own the navigator. {@code brain auto
     * true} therefore ENDS the follow — the escort stands down the moment anything else drives
     * (see {@link Escorts}) — and {@code follow stop} does not re-enable autonomy, matching every
     * other manual order here.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> follow() {
        return Commands.literal("follow")
                                .executes(ctx -> followMe(ctx.getSource()))
                                // Literals, so they beat a player named stop/list — reach one of
                                // those with a selector (@p[name=stop]), the way `select` does.
                                .then(Commands.literal("stop")
                                        .executes(ctx -> followStop(ctx.getSource())))
                                .then(Commands.literal("list")
                                        .executes(ctx -> followList(ctx.getSource())))
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(ctx -> followTarget(ctx.getSource(),
                                                EntityArgument.getEntity(ctx, "target"),
                                                Escorts.DEFAULT_NEAR, Escorts.DEFAULT_FAR))
                                        .then(Commands.argument("near", DoubleArgumentType.doubleArg(0.0))
                                                .then(Commands.argument("far", DoubleArgumentType.doubleArg(0.0))
                                                        .executes(ctx -> followTarget(ctx.getSource(),
                                                                EntityArgument.getEntity(ctx, "target"),
                                                                DoubleArgumentType.getDouble(ctx, "near"),
                                                                DoubleArgumentType.getDouble(ctx, "far"))))));
    }

    /**
     * What perception makes of the blocks themselves — a dump, for diffing across builds.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    /**
     * {@code recipes <what>} — what the registered {@link dev.luizloyola.anima.core.craft.Recipes
     * recipe sources} know how to make, as bills of materials. Read-only, no agent involved: the
     * craftbook is world knowledge, not body state.
     *
     * <p>{@code what} resolves in order: a registered {@link
     * dev.luizloyola.anima.core.inv.ItemSpec} name, then an exact item id (bare names get
     * {@code minecraft:}), then a contains-match over every output. The ad-hoc lookups build an
     * UNREGISTERED spec — registration exists so persisted plans can find their spec again, and a
     * readout persists nothing.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> recipes() {
        return Commands.literal("recipes")
                .then(Commands.argument("what", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                craftableNames(), builder))
                        .executes(ctx -> listRecipes(ctx.getSource(),
                                StringArgumentType.getString(ctx, "what"))));
    }

    /** Everything worth tab-completing: registered spec names plus every craftable output. */
    private static java.util.Collection<String> craftableNames() {
        java.util.TreeSet<String> names =
                new java.util.TreeSet<>(dev.luizloyola.anima.core.inv.ItemSpec.names());
        for (dev.luizloyola.anima.core.craft.CraftRecipe recipe : allRecipes()) {
            names.add(shortItem(recipe.outputId()));
        }
        return names;
    }

    /** The whole book, through the same path a real query takes — a spec that matches all. */
    private static List<dev.luizloyola.anima.core.craft.CraftRecipe> allRecipes() {
        return dev.luizloyola.anima.core.craft.Recipes.producing(
                new dev.luizloyola.anima.core.inv.ItemSpec("(everything)", id -> true));
    }

    private static int listRecipes(CommandSourceStack source, String name) {
        var registered = dev.luizloyola.anima.core.inv.ItemSpec.byName(name);
        String label = "\"" + name + "\"";
        List<dev.luizloyola.anima.core.craft.CraftRecipe> known;
        if (registered.isPresent()) {
            known = dev.luizloyola.anima.core.craft.Recipes.producing(registered.get());
        } else {
            String qualified = name.contains(":") ? name : "minecraft:" + name;
            known = dev.luizloyola.anima.core.craft.Recipes.producing(
                    new dev.luizloyola.anima.core.inv.ItemSpec(qualified,
                            id -> id.equals(qualified)));
            if (known.isEmpty() && !name.isBlank()) {
                // Not a spec, not an exact id: show everything whose output mentions it.
                known = dev.luizloyola.anima.core.craft.Recipes.producing(
                        new dev.luizloyola.anima.core.inv.ItemSpec(name,
                                id -> id.contains(name)));
                if (!known.isEmpty()) {
                    label = "*" + name + "*";
                }
            } else {
                label = shortItem(qualified);
            }
        }
        if (known.isEmpty()) {
            String asked = label;
            Replies.send(source, () -> Component.translatable("anima.command.recipes.none", asked)
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        int count = known.size();
        String header = label;
        Replies.send(source, () -> Component.translatable(count == 1
                        ? "anima.command.recipes.header_one" : "anima.command.recipes.header",
                header, count).withStyle(ChatFormatting.AQUA));
        int shown = 0;
        for (dev.luizloyola.anima.core.craft.CraftRecipe recipe : known) {
            if (shown++ == RECIPE_ROWS_CAP) {
                int rest = known.size() - RECIPE_ROWS_CAP;
                Replies.send(source, () -> indent(Component.translatable(
                        "anima.command.recipes.more", rest).withStyle(ChatFormatting.GRAY)));
                break;
            }
            StringBuilder bill = new StringBuilder();
            for (dev.luizloyola.anima.core.craft.CraftRecipe.Ingredient line : recipe.ingredients()) {
                if (bill.length() > 0) {
                    bill.append(" + ");
                }
                bill.append(line.count()).append("×").append(billLabel(line.acceptedIds()));
            }
            String row = shortItem(recipe.outputId())
                    + (recipe.outputCount() > 1 ? " x" + recipe.outputCount() : "")
                    + " ← " + bill;
            boolean table = recipe.needsTable();
            Replies.send(source, () -> indent(Component.literal(row)
                    .append(table ? Component.translatable("anima.command.recipes.needs_table")
                            : Component.empty())
                    .withStyle(ChatFormatting.GRAY)));
        }
        return known.size();
    }

    /** Rows before a broad contains-match stops flooding the chat. */
    private static final int RECIPE_ROWS_CAP = 20;

    /** One bill line's alternatives: the first id plainly, the rest as a count. */
    private static String billLabel(java.util.Set<String> acceptedIds) {
        String first = shortItem(acceptedIds.iterator().next());
        int alternatives = acceptedIds.size() - 1;
        return alternatives == 0 ? first : first + "(+" + alternatives + " alts)";
    }

    /** {@code minecraft:} dropped, everything else kept — the readout stays one line wide. */
    private static String shortItem(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> probe() {
        return Commands.literal("probe").then(ProbeDump.node());
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
     * The far sense as a line of text: how much of the skyline is swept, and what it is topped by.
     *
     * <p><b>No {@code view} here.</b> Drawing it is {@code debug horizon}, beside the other four
     * layers, so there is no second notion of "who am I looking at" to fall out of step with the
     * selection. This half works with no client at all. That is what the headless harness has.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> horizon() {
        return Commands.literal("horizon")
                                .executes(ctx -> horizonShow(ctx.getSource()));
    }

    /**
     * Stop and look all the way round — the active tier, driven by hand before anything decides
     * to do it on its own.
     *
     * <p>Runs to completion inside the call rather than trickling on the sense's wallet: roughly
     * fifty thousand block reads for a Person, a real pause on the server thread, acceptable only
     * because an operator asked for it and is waiting. A body doing this by itself will have to
     * spread the work.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> survey() {
        return Commands.literal("survey")
                .executes(ctx -> surveyNow(ctx.getSource()));
    }

    /** How many reads one hand-driven survey may spend before it gives up and says so. */
    private static final int SURVEY_READ_CAP = 400_000;

    private static int surveyNow(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        String name = person.entity().getName().getString();
        Pos feet = new Pos(person.entity().blockPosition().getX(),
                person.entity().blockPosition().getY(), person.entity().blockPosition().getZ());
        Survey survey = new Survey(person.profile(), feet);
        if (!survey.possible()) {
            Replies.send(source, () -> Component.translatable("anima.command.survey.nothing", name)
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        LevelProbe probe = new LevelProbe(person.entity());
        List<SenseEvent> events = new ArrayList<>();
        int reads = 0;
        while (!survey.done() && reads < SURVEY_READ_CAP) {
            reads += survey.step(probe, 4096, events);
        }
        AgentKnowledge knowledge = Knowledges.of(source.getServer()).forPerson(person.agentId());
        long now = person.entity().level().getGameTime();
        int kept = 0;
        for (SenseEvent event : events) {
            if (event.type() != SenseEvent.Type.GLIMPSED) continue;
            knowledge.glimpse(new Sighting(event.kind(), event.anchor(), feet, now,
                    Sighting.Provenance.SURVEY), AgentKnowledge.maxPerKind(person.profile()));
            kept++;
        }
        int finalReads = reads;
        int finalKept = kept;
        boolean finished = survey.done();
        Replies.send(source, () -> (finished
                        ? Component.translatable("anima.command.survey.done", name,
                                feet.x(), feet.y(), feet.z(), finalKept, finalReads)
                        : Component.translatable("anima.command.survey.gave_up", name,
                                feet.x(), feet.y(), feet.z(), finalKept, finalReads,
                                survey.progress()))
                .withStyle(finished ? ChatFormatting.AQUA : ChatFormatting.YELLOW));
        return kept;
    }

    /** The skyline as a line of text — how much of it is swept, and what it is topped by. */
    private static int horizonShow(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        String name = person.entity().getName().getString();
        int radius = HorizonScanner.radius(person.profile());
        if (radius <= CrescentSampler.radius(person.profile())) {
            Replies.send(source, () -> Component.translatable("anima.command.horizon.none", name)
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        HorizonBuffer buffer = person.poiSensor() == null ? null : person.poiSensor().horizon();
        if (buffer == null) {
            Replies.fail(source, Component.translatable("anima.command.horizon.nothing_seen", name));
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
        // Two tails rather than two keys per combination: either can be absent, and four whole
        // sentences would be four chances for one of them to drift.
        Replies.send(source, () -> Component.translatable("anima.command.horizon.header", name,
                        radius, CrescentSampler.coneDegrees(person.profile()), finalSwept,
                        HorizonBuffer.BINS, knowledge.glimpseCount())
                .append(finalCutShort > 0
                        ? Component.translatable("anima.command.horizon.cut_short", finalCutShort)
                        : Component.empty())
                .append(finalHighest > Double.NEGATIVE_INFINITY
                        ? Component.translatable("anima.command.horizon.steepest",
                                Math.round(Math.toDegrees(Math.atan(finalHighest))))
                        : Component.empty())
                .withStyle(ChatFormatting.AQUA));
        return swept;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> claims() {
        return Commands.literal("claims")
                                .executes(ctx -> claimsShow(ctx.getSource()));
    }

    /**
     * What the resolved agent feels — one line per gauge, in the order its body declared them,
     * plus the dev setters for staging a mood.
     *
     * <p><b>Written against the roster, not against a list of needs</b>: it walks
     * {@code needs().all()}, so a gauge some other mod registers appears the day it exists. The
     * setters are the exception — moving a gauge is its own typed business, and {@code food} moves
     * an organ rather than a level.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered,
     * so a shared subcommand must be built once per root that mounts it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> needs() {
        return Commands.literal("needs")
                                .executes(ctx -> needsShow(ctx.getSource()))
                                .then(Commands.literal("food")
                                        .then(Commands.argument("food",
                                                        IntegerArgumentType.integer(0, Metabolism.MAX_FOOD))
                                                .executes(ctx -> setFood(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "food"), 0.0F))
                                                .then(Commands.argument("saturation",
                                                                FloatArgumentType.floatArg(0.0F, Metabolism.MAX_FOOD))
                                                        .executes(ctx -> setFood(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "food"),
                                                                FloatArgumentType.getFloat(ctx, "saturation"))))))
                                .then(Commands.literal("company")
                                        .then(Commands.argument("level",
                                                        DoubleArgumentType.doubleArg(0.0, 1.0))
                                                .executes(ctx -> setCompany(ctx.getSource(),
                                                        DoubleArgumentType.getDouble(ctx, "level")))));
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
                        ProfileAspect.all().stream()
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
            Replies.fail(source, Component.translatable("anima.command.profile.no_such_aspect",
                    aspectKey));
            return 0;
        }
        if (person.modifiers() == AgentModifiers.NONE) {
            Replies.fail(source, Component.translatable("anima.command.profile.no_modifiers",
                    person.entity().getName()));
            return 0;
        }
        person.modifiers().apply(AspectModifier.add(DEBUG_MODIFIER, aspect, amount));
        for (Component line : explain(person.profile(), aspect, true)) {
            Replies.send(source, () -> indent(line.copy().withStyle(ChatFormatting.YELLOW)), true);
        }
        return 1;
    }

    /** Drops every {@code profile debug} shift on the resolved agent. */
    private static int profileDebugClear(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean removed = person.modifiers() != AgentModifiers.NONE
                && person.modifiers().remove(DEBUG_MODIFIER);
        Replies.send(source, () -> (removed
                        ? Component.translatable("anima.command.profile.cleared",
                                person.entity().getName(), person.profile().species())
                        : Component.translatable("anima.command.profile.nothing_to_clear"))
                .withStyle(ChatFormatting.GREEN), true);
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
                Replies.fail(source, Component.translatable(
                        "anima.command.profile.no_such_aspect_hint", aspectKey));
                return 0;
            }
            Replies.send(source, () -> Component.translatable("anima.command.profile.species",
                    name, profile.species()).withStyle(ChatFormatting.AQUA));
            for (Component line : explain(profile, aspect, true)) {
                Replies.send(source, () -> indent(line));
            }
            return 1;
        }

        List<ProfileAspect> shifted = ProfileAspect.all().stream()
                .filter(aspect -> !profile.modifiers(aspect).isEmpty())
                .toList();
        Replies.send(source, () -> (shifted.isEmpty()
                        ? Component.translatable("anima.command.profile.is_a",
                                name, profile.species())
                        : Component.translatable("anima.command.profile.is_a_shifted",
                                name, profile.species(), shifted.size()))
                .withStyle(ChatFormatting.AQUA));
        if (shifted.isEmpty()) {
            // No root in the hint: this subcommand is mounted by every consumer as well as by
            // /anima, so naming one would be wrong under all the others.
            Replies.send(source, () -> indent(Component.translatable(
                    "anima.command.profile.nothing_modifying", person.pronouns().object())
                    .withStyle(ChatFormatting.GRAY)));
            return 0;
        }
        for (ProfileAspect aspect : shifted) {
            for (Component line : explain(profile, aspect, true)) {
                Replies.send(source, () -> indent(line));
            }
        }
        return shifted.size();
    }

    /** Every aspect, grouped by section — "what is this agent running", in full. */
    private static int profileAll(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        AgentProfile profile = person.profile();
        Replies.send(source, () -> Component.translatable("anima.command.profile.species",
                person.entity().getName(), profile.species()).withStyle(ChatFormatting.AQUA));
        String section = null;
        for (ProfileAspect aspect : ProfileAspect.all()) {
            if (!aspect.section().equals(section)) {
                section = aspect.section();
                // The GUI's own tab names — one word per section, already translated for the
                // config screen, and a second set would be the same words disagreeing.
                String heading = section;
                Replies.send(source, () -> indent(Component.translatableWithFallback(
                        "anima.config.category." + heading, heading)
                        .withStyle(ChatFormatting.DARK_AQUA)));
            }
            for (Component line : explain(profile, aspect, false)) {
                Replies.send(source, () -> indent(indent(line.copy()
                        .withStyle(profile.modifiers(aspect).isEmpty()
                                ? ChatFormatting.GRAY : ChatFormatting.YELLOW))));
            }
        }
        return ProfileAspect.count();
    }

    /**
     * One aspect's derivation: species value, each contribution, effective. Only the aspects with
     * something to derive get more than a line — an unmodified aspect is its species value, and
     * printing "24 -> 24" thirty times would bury the two that matter.
     */
    private static List<Component> explain(AgentProfile profile, ProfileAspect aspect,
            boolean withKey) {
        List<AspectModifier> applied = profile.modifiers(aspect);
        String label = withKey ? aspect.key() : aspect.key().substring(aspect.key().indexOf('.') + 1);
        String effective = format(aspect, profile.raw(aspect));
        if (applied.isEmpty()) {
            return List.of(Component.translatable("anima.command.profile.aspect", label, effective));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("anima.command.profile.aspect_shifted", label, effective,
                format(aspect, profile.base(aspect))));
        for (AspectModifier modifier : applied) {
            lines.add(indent(indent(Component.translatable("anima.command.profile.modifier",
                    modifier.describe(), modifier.id()))));
        }
        return lines;
    }

    private static String format(ProfileAspect aspect, double value) {
        return switch (aspect.kind()) {
            case BOOL -> value != 0.0 ? "true" : "false";
            case INT -> Long.toString((long) value);
            case DOUBLE -> String.format(Locale.ROOT, "%s", value);
            // Unreachable: ProfileAspect.register refuses STRING, an aspect being a numeric dial.
            case STRING, LIST -> throw new IllegalStateException(aspect.key() + " holds text");
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
        Replies.send(source, () -> Component.translatable("anima.command.nav.goto",
                person.entity().getName(), pos.toShortString()).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int navStop(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.navigator().stop();
        Replies.send(source, () -> Component.translatable("anima.command.nav.stopped",
                person.entity().getName()).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int navStatus(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        // The swimmer's state rides along with the follower's, and only when it has something to
        // say: every water bug this pair produced was the two disagreeing about what the body was
        // doing, unseen.
        String water = person.swimmer().state() == Swimmer.State.DRY
                ? "" : "  [" + person.swimmer().describe() + "]";
        // A standing follow order explains a navigator that keeps taking goals nobody typed, so it
        // rides on this line rather than behind `follow`.
        AgentId id = person.agentId();
        Entity leading = id == null ? null : Escorts.following(source.getServer(), id);
        String escort = leading == null ? "" : "  following " + leading.getName().getString();
        // Where the eyes are, on the same line and for the same reason as the swimmer: a head
        // aimed somewhere reasonable and a head aimed nowhere look identical for the first second.
        String looking = "  [" + person.gaze().describe() + "]";
        Replies.send(source, () -> Component.translatable("anima.command.state",
                person.entity().getName(),
                person.navigator().describe() + water + escort + looking)
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** Bare {@code follow}: the player who typed it is the one to follow. */
    private static int followMe(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            Replies.fail(source, Component.translatable("anima.command.follow.whom"));
            return 0;
        }
        return followTarget(source, player, Escorts.DEFAULT_NEAR, Escorts.DEFAULT_FAR);
    }

    /**
     * Installs the standing order that the resolved agent walks after {@code target}, ending up
     * {@code near} blocks from them and setting off again once they are {@code far} away. Takes the
     * legs first (see {@link AgentCommands#follow()}): a running task is cancelled and autonomy
     * switched off, or the arbiter would drive over the escort within the half-second.
     */
    private static int followTarget(CommandSourceStack source, Entity target,
            double near, double far) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        AgentId id = person.agentId();
        if (id == null) {
            Replies.fail(source, Component.translatable("anima.command.not_identified"));
            return 0;
        }
        String name = person.entity().getName().getString();
        if (target == person.entity()) {
            Replies.fail(source, Component.translatable("anima.command.follow.self", name));
            return 0;
        }
        if (target.level() != person.entity().level()) {
            Replies.fail(source, Component.translatable("anima.command.follow.elsewhere",
                    target.getName()));
            return 0;
        }
        // A leash shorter than the distance it aims for is a body that arrives and is immediately
        // told to set off again. Rejected rather than silently widened: a number quietly not being
        // yours is worse than a retype.
        if (far < near) {
            Replies.fail(source, Component.translatable("anima.command.follow.bad_leash",
                    String.format(Locale.ROOT, "%.1f", far),
                    String.format(Locale.ROOT, "%.1f", near)));
            return 0;
        }
        person.brain().cancel();
        boolean autoDisabled = person.brain().isAuto();
        if (autoDisabled) {
            person.brain().setAuto(false);
        }
        ServerPlayer asked = source.getPlayer();
        Escorts.follow(source.getServer(), id, target, near, far,
                asked == null ? null : asked.getUUID());
        Replies.send(source, () -> Component.translatable("anima.command.follow.started",
                        name, target.getName(),
                        String.format(Locale.ROOT, "%.1f", near),
                        String.format(Locale.ROOT, "%.1f", far))
                .append(autoDisabledNote(autoDisabled))
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** Calls the resolved agent's follow order off — the legs stop where they are. */
    private static int followStop(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        AgentId id = person.agentId();
        if (id == null) {
            Replies.fail(source, Component.translatable("anima.command.not_identified"));
            return 0;
        }
        String name = person.entity().getName().getString();
        if (!Escorts.stop(source.getServer(), id)) {
            Replies.send(source, () -> Component.translatable("anima.command.follow.not_following",
                    name).withStyle(ChatFormatting.GRAY));
            return 0;
        }
        // Autonomy stays where the order left it, like every other manual override here — but say
        // so, because a body standing perfectly still is what a stuck brain looks like.
        Component manual = person.brain().isAuto() ? Component.empty()
                : Component.translatable("anima.command.follow.still_manual");
        Replies.send(source, () -> Component.translatable("anima.command.follow.stopped", name)
                .append(manual).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** Every standing follow order on the server — the readout for "who is trailing whom". */
    private static int followList(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Map<AgentId, Escorts.Order> orders = Escorts.all(server);
        if (orders.isEmpty()) {
            Replies.send(source, () -> Component.translatable("anima.command.follow.nobody")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.translatable("anima.command.follow.list_header",
                orders.size()).withStyle(ChatFormatting.AQUA));
        orders.forEach((agent, order) -> {
            AgentBody body = AgentBodies.findLoaded(server, agent);
            Entity target = Escorts.following(server, agent);
            String who = body == null ? label(server, agent) + " (unloaded)"
                    : body.entity().getName().getString();
            String whom = target == null ? "somebody gone" : target.getName().getString();
            String gap = body == null || target == null
                    || target.level() != body.entity().level() ? ""
                    : String.format(Locale.ROOT, "  %.1fm",
                            Math.sqrt(body.entity().distanceToSqr(target)));
            Replies.send(source, () -> indent(Component.literal(String.format(Locale.ROOT,
                    "%s -> %s%s  [%.1f–%.1f]", who, whom, gap, order.near(), order.far()))));
        });
        return orders.size();
    }

    /** Runs a {@link GoTo} task on the resolved Person through the brain's executor — same walk
     *  as {@link #navGoto}, but through the task machinery, so the whole pipeline is exercised. */
    private static int brainGoto(CommandSourceStack source, BlockPos pos) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new GoTo(pos.getX(), pos.getY(), pos.getZ()));
        Component suffix = autoDisabledNote(autoDisabled);
        Replies.send(source, () -> Component.translatable("anima.command.state",
                person.entity().getName(), person.brain().describe())
                .append(suffix).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** Runs a {@link BreakBlock} on the resolved Person — the working arm's debug leaf (slice-2
     *  ladder step 1): reach-checked, vanilla break time for the held stack, real drops. */
    private static int brainBreak(CommandSourceStack source, BlockPos pos) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean autoDisabled = person.brain().run(new BreakBlock(pos.getX(), pos.getY(), pos.getZ()));
        Component suffix = autoDisabledNote(autoDisabled);
        Replies.send(source, () -> Component.translatable("anima.command.state",
                person.entity().getName(), person.brain().describe())
                .append(suffix).withStyle(ChatFormatting.AQUA));
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
            Replies.fail(source, Component.translatable("anima.command.not_identified"));
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
            Replies.fail(source, Component.translatable("anima.command.not_identified"));
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

    /**
     * Every gauge the resolved body has, one line each, its declared {@link Binding}s under it, and
     * — for a gauge whose number has parts — the {@code because:} block itemising them. All of it
     * without knowing what any of them are.
     */
    private static int needsShow(CommandSourceStack source) {
        AgentBody body = resolveBody(source);
        if (body == null) return 0;
        String name = body.entity().getName().getString();
        Collection<Gauge> gauges = body.needs().all();
        if (gauges.isEmpty()) {
            Replies.send(source, () -> Component.translatable("anima.command.needs.none", name)
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.translatable("anima.command.needs.header",
                name, gauges.size()).withStyle(ChatFormatting.AQUA));
        for (Gauge gauge : gauges) {
            // No key prefix: every gauge's describe() already names itself, and the alternative
            // reads "food: food 14/20".
            Replies.send(source, () -> indent(Component.translatable("anima.command.needs.gauge",
                    gauge.describe(), String.format(Locale.ROOT, "%.2f", gauge.pressure()))
                    .withStyle(gauge.pressure() > 0.0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY)));
            // What this need DOES, from its own declaration — so "why is he walking over there?"
            // is answerable without reading whichever instinct happens to mention the gauge.
            for (Binding binding : gauge.kind().bindings()) {
                Replies.send(source, () -> indent(indent(Component.translatable(binding.nameKey(),
                        binding.needKey(), binding.key(),
                        Component.translatable(binding.sideKey()))
                        .withStyle(ChatFormatting.DARK_GRAY))));
            }
            for (Component because : Because.lines(name, gauge)) {
                Replies.send(source, () -> because);
            }
        }
        return 1;
    }

    /**
     * Sets the resolved body's food level (0..20) and saturation (0.0 when omitted) — the dev knob
     * for exercising starvation, regen and the Eat instinct without waiting out the natural burn.
     * Food is set before saturation because saturation clamps against the current food level;
     * exhaustion is zeroed so behavior afterwards is deterministic.
     *
     * <p>Reaches for the ORGAN, not the gauge, as every need setter will: {@code need.food} is a
     * view, so there is no level here to write.
     */
    private static int setFood(CommandSourceStack source, int food, float saturation) {
        AgentBody body = resolveBody(source);
        if (body == null) return 0;
        Metabolism metabolism = body.metabolism();
        metabolism.setFoodLevel(food);
        metabolism.setSaturation(saturation);
        metabolism.setExhaustion(0.0F);
        // LOGGED: needs persist on the body and drive the arbiter — a hand-set hunger explains an
        // eat that would otherwise read as the brain deciding something inexplicable.
        Replies.send(source, () -> Component.translatable("anima.command.state",
                body.entity().getName(), metabolism.describe())
                .withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    /**
     * Sets the resolved body's company level directly — the dev knob for staging a lonely settler
     * without leaving one alone for two in-game days. LOGGED for the same reason as the food one.
     */
    private static int setCompany(CommandSourceStack source, double level) {
        AgentBody body = resolveBody(source);
        if (body == null) return 0;
        Optional<Company> gauge = body.needs().gauge(NeedKind.COMPANY, Company.class);
        if (gauge.isEmpty()) {
            Replies.fail(source, Component.translatable("anima.command.needs.no_company",
                    body.entity().getName()));
            return 0;
        }
        Company company = gauge.get();
        company.setValue(level);
        Replies.send(source, () -> Component.translatable("anima.command.state",
                body.entity().getName(), company.describe())
                .withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    private static int peersList(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        List<Being> beings = person.brain().percepts().beings();
        String name = person.entity().getName().getString();
        if (beings.isEmpty()) {
            Replies.send(source, () -> Component.translatable("anima.command.peers.none", name)
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.translatable("anima.command.peers.header",
                name, beings.size()).withStyle(ChatFormatting.AQUA));
        String pronoun = person.pronouns().object();
        for (Being being : beings) {
            String kind = being.kind() == Being.Kind.AGENT || being.kind() == Being.Kind.UNKNOWN
                    ? "" : " [" + being.kind().key()
                            + (being.aggressive() ? "!" : "") + "]";
            String awareness = being.awareness() == Being.Awareness.SEEN
                    ? "" : " [" + being.awareness().name().toLowerCase(Locale.ROOT) + "]";
            Replies.send(source, () -> Component.translatable("anima.command.peers.row",
                    being.knownAs() + kind, being.pos().x(), being.pos().y(), being.pos().z(),
                    String.format(Locale.ROOT, "%.1f", being.distance()),
                    being.tell(pronoun) + awareness)
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
        Component suffix = autoDisabledNote(autoDisabled);
        Replies.send(source, () -> Component.translatable("anima.command.state",
                person.entity().getName(), person.brain().describe())
                .append(suffix).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int brainStatus(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        Replies.send(source, () -> Component.translatable("anima.command.state",
                person.entity().getName(), person.brain().describe())
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int brainCancel(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.brain().cancel();
        Replies.send(source, () -> Component.translatable("anima.command.brain.cancelled",
                person.entity().getName(), person.brain().describe())
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** Flips the resolved Person's autonomy switch and echoes the new describe() line (now
     *  reporting auto|manual up front). */
    private static int brainAuto(CommandSourceStack source, boolean auto) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.brain().setAuto(auto);
        Replies.send(source, () -> Component.translatable("anima.command.state",
                person.entity().getName(), person.brain().describe())
                .withStyle(ChatFormatting.AQUA));
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
        Replies.send(source, () -> Component.translatable(auto
                        ? "anima.command.brain.auto_on" : "anima.command.brain.auto_off",
                person.entity().getName())
                .withStyle(auto ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        return auto ? 1 : 0;
    }

    /** Mutes or unmutes the resolved agent's idle wander drive and echoes the new brain readout —
     *  the pressure line then reads {@code wander (muted) 0.00}. */
    private static int brainWander(CommandSourceStack source, boolean wander) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.brain().setWander(wander);
        Replies.send(source, () -> Component.translatable("anima.command.state",
                person.entity().getName(), person.brain().describe())
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    /** {@code brain wander} with no {@code true|false}: reads the mute instead of flipping it. */
    private static int brainWanderShow(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        boolean wander = person.brain().isWander();
        Replies.send(source, () -> Component.translatable(wander
                        ? "anima.command.brain.wander_on" : "anima.command.brain.wander_off",
                person.entity().getName())
                .withStyle(wander ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        return wander ? 1 : 0;
    }

    /**
     * The note appended to a manual {@code brain goto}/{@code brain eat} reply when that very call
     * is what took the wheel from the arbiter — empty when it did not, so it always appends.
     *
     * <p>Public: a consumer's own brain verbs report the autonomy switch the same way.
     */
    public static Component autoDisabledNote(boolean autoDisabled) {
        return autoDisabled
                ? Component.translatable("anima.command.brain.auto_disabled")
                : Component.empty();
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
            Replies.fail(source, Component.translatable("anima.command.not_identified"));
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
        // The category is a journal filter, and the journal is filed under these words — the
        // scope tag names the filter, not a thing to be renamed per language.
        String scope = category == null ? "" : " (" + category.name().toLowerCase(Locale.ROOT) + ")";
        Component tag = loaded ? Component.empty()
                : Component.translatable("anima.command.not_loaded_tag");
        if (lines.isEmpty()) {
            Replies.send(source, () -> Component.translatable("anima.command.log.empty", name, scope)
                    .append(tag).withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.translatable("anima.command.log.header",
                        name, lines.size(), scope)
                .append(tag).withStyle(ChatFormatting.AQUA));
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
            Replies.fail(source, Component.translatable("anima.command.no_match", token));
            return null;
        }
        if (matches.size() > 1) {
            String ids = matches.stream().map(AgentCommands::shortId)
                    .collect(java.util.stream.Collectors.joining(", "));
            Replies.fail(source, Component.translatable("anima.command.ambiguous_name",
                    matches.size(), token, ids));
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
            Replies.send(source, () -> Component.translatable("anima.command.claims.none")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            Replies.send(source, () -> Component.translatable("anima.command.claims.header",
                    sites.size()).withStyle(ChatFormatting.AQUA));
            for (SiteClaims.Held held : sites) {
                String what = held.kind().key().toUpperCase(java.util.Locale.ROOT)
                        + " (" + held.anchor().x() + ", " + held.anchor().y() + ", "
                        + held.anchor().z() + ")";
                Replies.send(source, () -> indent(Component.translatable("anima.command.claims.row",
                        what, label(server, held.who()), held.remaining())
                        .withStyle(ChatFormatting.GRAY)));
            }
        }
        AgentBody person = resolveBody(source);
        if (person == null) return 1; // the site half stands on its own; no agent, no lease half
        String name = person.entity().getName().getString();
        List<WorkLease> leases = person.brain().leases();
        if (leases.isEmpty()) {
            Replies.send(source, () -> Component.translatable("anima.command.leases.none", name)
                    .withStyle(ChatFormatting.GRAY));
            return 1;
        }
        Replies.send(source, () -> Component.translatable("anima.command.leases.header",
                name, leases.size()).withStyle(ChatFormatting.AQUA));
        for (WorkLease lease : leases) {
            Replies.send(source, () -> indent(Component.translatable("anima.command.claims.row",
                    lease.board() + " · " + lease.item(), label(server, lease.who()),
                    lease.remaining()).withStyle(ChatFormatting.GRAY)));
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
            Replies.fail(source, Component.translatable("anima.command.not_identified"));
            return 0;
        }
        AgentKnowledge knowledge = Knowledges.of(source.getServer()).forPerson(id);
        String name = person.entity().getName().getString();
        if (knowledge.size() == 0 && knowledge.glimpseCount() == 0) {
            Replies.send(source, () -> Component.translatable("anima.command.knowledge.none", name)
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        long now = source.getServer().overworld().getGameTime();
        Replies.send(source, () -> Component.translatable("anima.command.knowledge.header",
                        name, knowledge.size(), knowledge.glimpseCount(),
                        person.poiSensor().claimCount())
                .withStyle(ChatFormatting.AQUA));
        // The world's line, not theirs — but this is the command somebody reads when they are
        // asking why perception costs what it does, and the hit rate is that answer.
        RegionCache shapes = RegionCaches.of((ServerLevel) person.level());
        long looks = shapes.hits() + shapes.misses();
        Replies.send(source, () -> Component.literal("  world: " + shapes.size()
                        + " shape(s) remembered, " + shapes.cells() + "/" + RegionCache.maxCells()
                        + " cells, " + shapes.hits() + " of " + looks + " scans saved, "
                        + shapes.drops() + " forgotten (ground moved), "
                        + shapes.evictions() + " (no room)")
                .withStyle(ChatFormatting.DARK_GRAY));
        // why the rest were not saved: a bare miss count says the cache is not earning its keep;
        // these three say which rule refused.
        Replies.send(source, () -> Component.literal("  re-grown: "
                        + shapes.unknownGround() + " new ground, "
                        + shapes.refusedPartial() + " mass cut short, "
                        + shapes.refusedOutOfReach() + " out of reach")
                .withStyle(ChatFormatting.DARK_GRAY));
        // The line that should make the two above shrink: things the level knows, answered
        // without walking anything at all.
        PlaceIndex places = PlaceIndexes.of((ServerLevel) person.level());
        long asked = places.hits() + places.misses();
        Replies.send(source, () -> Component.literal("  things: " + places.size()
                        + " known, " + places.cells() + "/" + PlaceIndex.maxCells() + " cells, "
                        + places.hits() + " of " + asked + " recognised on sight, "
                        + places.drops() + " forgotten (ground moved), "
                        + places.replaced() + " re-read, "
                        + places.evictions() + " (no room)")
                .withStyle(ChatFormatting.DARK_GRAY));
        for (PoiKind kind : PoiKind.all()) {
            for (PoiMemory memory : knowledge.all(kind)) {
                Replies.send(source, () -> formatPoi(person, memory, now).copy()
                        .withStyle(ChatFormatting.GREEN));
            }
        }
        // The gist tier last and dimmer: these are not things they know, they are places worth
        // going to look at.
        for (PoiKind kind : PoiKind.all()) {
            for (Sighting sighting : knowledge.glimpses(kind)) {
                Replies.send(source, () -> formatGlimpse(person, sighting, now).copy()
                        .withStyle(ChatFormatting.AQUA));
            }
        }
        return knowledge.size() + knowledge.glimpseCount();
    }

    /** One rumour line: {@code ~TREE (0, 68, 30) - 30 blocks away, made out from 42 off, 12s ago}. */
    private static Component formatGlimpse(AgentBody person, Sighting sighting, long now) {
        double distance = Math.sqrt(person.entity().distanceToSqr(
                sighting.at().x() + 0.5, sighting.at().y() + 0.5, sighting.at().z() + 0.5));
        // PoiLabels' spans stay as they are: "32s" is a unit, and the in-world labels it also
        // feeds have no room for a sentence.
        return Component.translatable("anima.command.knowledge.glimpse",
                "~" + sighting.kind().key().toUpperCase(java.util.Locale.ROOT),
                sighting.at().x(), sighting.at().y(), sighting.at().z(),
                Math.round(distance), sighting.range(), PoiLabels.ticks(sighting.age(now)))
                .append(sighting.provenance() == Sighting.Provenance.PASSIVE
                        ? Component.empty()
                        : Component.literal(", " + sighting.provenance().name()
                                .toLowerCase(java.util.Locale.ROOT)));
    }

    /** One belief line: {@code TREE (10, 64, 8) - 14 blocks away, 4 logs, seen 32s ago, partial}. */
    private static Component formatPoi(AgentBody person, PoiMemory memory, long now) {
        double distance = Math.sqrt(person.entity().distanceToSqr(
                memory.anchor().x() + 0.5, memory.anchor().y() + 0.5, memory.anchor().z() + 0.5));
        String age = PoiLabels.age(memory, now);
        int lifetime = memory.kind().lifetimeTicks();
        String what = memory.kind().key().toUpperCase(java.util.Locale.ROOT)
                + (memory.detail().isEmpty() ? "" : " " + memory.detail());
        Component amount = memory.kind().unit().isEmpty()
                ? Component.translatable("anima.command.knowledge.cells", memory.units())
                : Component.literal(memory.units() + memory.kind().unit());
        // PoiLabels' spans stay as they are: "32s" is a unit, and the in-world labels it also
        // feeds have no room for a sentence.
        MutableComponent line = Component.translatable("anima.command.knowledge.poi",
                what, memory.anchor().x(), memory.anchor().y(), memory.anchor().z(),
                Math.round(distance), amount,
                age.equals("now") ? Component.translatable("anima.command.knowledge.just_now")
                        : Component.translatable("anima.command.knowledge.seen_ago", age));
        if (lifetime > 0) {
            line.append(Component.literal(", " + PoiLabels.when(memory, now)));
        }
        if (memory.partial()) {
            line.append(Component.translatable("anima.command.knowledge.partial"));
        }
        return line;
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
            Replies.fail(source, Component.translatable("anima.command.not_identified"));
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
            Replies.fail(source, Component.translatable("anima.command.not_identified"));
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
            Replies.send(source, () -> Component.translatable("anima.command.inv.empty",
                    person.entity().getName()).withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.translatable("anima.command.inv.header",
                person.entity().getName()).withStyle(ChatFormatting.AQUA));
        for (Inventory.Entry entry : occupied) {
            String line = slotLabel(entry.slot()) + "  " + entry.stack().id()
                    + " x" + entry.stack().count();
            Replies.send(source, () -> indent(Component.literal(line)
                    .withStyle(ChatFormatting.GRAY)));
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
        Replies.send(source, () -> (remainder.isEmpty()
                        ? Component.translatable("anima.command.inv.gave",
                                person.entity().getName(), placed, template.id())
                        : Component.translatable("anima.command.inv.gave_partial",
                                person.entity().getName(), placed, template.id(),
                                remainder.count()))
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
            Replies.fail(source, Component.translatable("anima.command.inv.not_equippable",
                    want.id()));
            return 0;
        }
        if (!PERSON_EQUIP_SLOTS.contains(slot)) { // e.g. BODY/SADDLE — no such slot on a Person
            Replies.fail(source, Component.translatable("anima.command.inv.wrong_slot",
                    want.id(), slot.getName()));
            return 0;
        }
        Inventory inv = person.inventory();
        dev.luizloyola.anima.core.inv.ItemStack piece = inv.takeOne(want.id());
        if (piece.isEmpty()) {
            Replies.fail(source, Component.translatable("anima.command.inv.not_carried",
                    person.entity().getName(), want.id()));
            return 0;
        }
        dev.luizloyola.anima.core.inv.ItemStack displaced = placeEquipment(inv, slot, piece);
        if (!displaced.isEmpty()) inv.add(displaced); // whatever was worn there goes back to storage
        Replies.send(source, () -> Component.translatable("anima.command.inv.equipped",
                person.entity().getName(), want.id(), slot.getName())
                .withStyle(ChatFormatting.AQUA), true); // LOGGED: persisted, unjournalled
        return 1;
    }

    private static int invClear(CommandSourceStack source) {
        AgentBody person = resolveBody(source);
        if (person == null) return 0;
        person.inventory().clear();
        Replies.send(source, () -> Component.translatable("anima.command.inv.cleared",
                person.entity().getName())
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
            Replies.fail(source, Component.translatable("anima.command.select.nobody_near",
                    (int) NEAREST_RADIUS));
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
                Replies.fail(source, Component.translatable("anima.command.select.dead",
                        self.entity().getName()));
                return null;
            }
            return self;
        }
        Optional<AgentId> pin = AgentSelection.pinned(source);
        if (pin.isEmpty()) return nearestBody(source);
        AgentId id = pin.get();
        AgentBody live = AgentBodies.findLoaded(source.getServer(), id);
        if (live == null) {
            Replies.fail(source, Component.translatable("anima.command.select.not_loaded",
                    label(source.getServer(), id)));
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
            Replies.fail(source, Component.translatable("anima.command.select.no_match", token));
            return 0;
        }
        // Ambiguity FAILS rather than guessing (decision: Luiz): a name collides across kinds once
        // several mods share a world, and picking the closer of a settler and a wolf is a worse
        // answer than asking. Ids are always unambiguous.
        if (matches.size() > 1) {
            String ids = matches.stream()
                    .map(b -> shortId(b.agentId()))
                    .collect(java.util.stream.Collectors.joining(", "));
            Replies.fail(source, Component.translatable("anima.command.select.ambiguous",
                    matches.size(), token, ids));
            return 0;
        }
        AgentId id = matches.get(0).agentId();
        AgentSelection.pin(source, id);
        Replies.send(source, () -> Component.translatable("anima.command.select.selected",
                label(server, id)).withStyle(ChatFormatting.AQUA));
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
            Replies.fail(source, Component.translatable("anima.command.not_identified"));
            return 0;
        }
        AgentSelection.pin(source, id);
        Replies.send(source, () -> Component.translatable("anima.command.select.selected",
                label(source.getServer(), id)).withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int selectClear(CommandSourceStack source) {
        if (AgentSelection.clear(source)) {
            Replies.send(source, () -> Component.translatable("anima.command.select.cleared")
                    .withStyle(ChatFormatting.AQUA));
            return 1;
        }
        Replies.send(source, () -> Component.translatable("anima.command.select.none_was")
                .withStyle(ChatFormatting.GRAY));
        return 0;
    }

    private static int selectShow(CommandSourceStack source) {
        Optional<AgentId> pin = AgentSelection.pinned(source);
        if (pin.isEmpty()) {
            Replies.send(source, () -> Component.translatable("anima.command.select.none")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        AgentId id = pin.get();
        boolean loaded = AgentBodies.findLoaded(source.getServer(), id) != null;
        Replies.send(source, () -> Component.translatable("anima.command.select.show",
                        label(source.getServer(), id))
                .append(loaded ? Component.empty()
                        : Component.translatable("anima.command.not_loaded_tag"))
                .withStyle(loaded ? ChatFormatting.AQUA : ChatFormatting.GRAY));
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
                Replies.fail(source, Component.translatable("anima.command.not_identified"));
            }
            return id;
        }
        if (self instanceof ServerPlayer player) {
            return AgentId.of(player.getUUID());
        }
        Replies.fail(source, Component.translatable("anima.command.contacts.console"));
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
            return printNames(source, AgentDirectory.of(server).living(server).keySet(),
                    Component.translatable(player.isSpectator()
                            ? "anima.command.contacts.knows_spectator"
                            : "anima.command.contacts.knows_creative"));
        }
        // The living, by default: the entry is not deleted — "I knew Alice" stays true after Alice
        // dies — but the dead are not people to deal with. `contacts of <name>` shows the book as
        // it is.
        return printNames(source, Set.copyOf(Graves.get(server)
                .living(ContactData.get(server).contactsOf(self))),
                Component.translatable("anima.command.contacts.you_know"));
    }

    /** Everyone that Person can name — the omniscient view: a dev tool reads any book. */
    private static int contactsOf(CommandSourceStack source, String token) {
        AgentId who = resolveDirectory(source, token);
        return who == null ? 0
                : printContacts(source, who, Component.translatable(
                        "anima.command.contacts.agent_knows", label(source.getServer(), who)));
    }

    private static int printContacts(CommandSourceStack source, AgentId who, Component heading) {
        return printNames(source, ContactData.get(source.getServer()).contactsOf(who), heading);
    }

    private static int printNames(CommandSourceStack source, Set<AgentId> known, Component heading) {
        MinecraftServer server = source.getServer();
        if (known.isEmpty()) {
            Replies.send(source, () -> heading.copy()
                    .append(Component.translatable("anima.command.contacts.nobody"))
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> heading.copy()
                .append(Component.translatable(known.size() == 1
                                ? "anima.command.contacts.count_one"
                                : "anima.command.contacts.count", known.size()))
                .withStyle(ChatFormatting.AQUA));
        for (AgentId id : known) {
            String line = label(server, id) + "  " + shortId(id);
            Replies.send(source, () -> indent(Component.literal(line)
                    .withStyle(ChatFormatting.GRAY)));
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
            Replies.fail(source, Component.translatable("anima.command.contacts.met_self"));
            return 0;
        }
        if (!ContactData.get(server).introduce(self, other)) {
            Replies.send(source, () -> Component.translatable("anima.command.contacts.already")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        ContactsSync.learned(server, self, other);
        ContactsSync.learned(server, other, self);
        // LOGGED: the contact book is persisted SavedData and nothing journals a change to it —
        // the journal is the agent's own, and an agent does not narrate what was done TO it.
        Replies.send(source, () -> Component.translatable("anima.command.contacts.introduced",
                label(server, self), label(server, other))
                .withStyle(ChatFormatting.AQUA), true);
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
            Replies.send(source, () -> Component.translatable("anima.command.contacts.never_knew")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        resyncIfOnline(server, self);
        Replies.send(source, () -> Component.translatable("anima.command.contacts.forgotten",
                label(server, other))
                .withStyle(ChatFormatting.AQUA), true); // LOGGED: persisted, unjournalled
        return 1;
    }

    private static int contactsClear(CommandSourceStack source) {
        AgentId self = sourceIdentity(source);
        if (self == null) return 0;
        MinecraftServer server = source.getServer();
        if (!ContactData.get(server).clear(self)) {
            Replies.send(source, () -> Component.translatable("anima.command.contacts.knew_nobody")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        resyncIfOnline(server, self);
        Replies.send(source, () -> Component.translatable("anima.command.contacts.cleared")
                .withStyle(ChatFormatting.AQUA), true); // LOGGED: persisted, unjournalled
        return 1;
    }

    /** The source's own party — who they belong with, themselves included. */
    private static int partyList(CommandSourceStack source) {
        AgentId self = sourceIdentity(source);
        if (self == null) return 0;
        MinecraftServer server = source.getServer();
        return printParty(source, PartyData.get(server).partyOf(self),
                Component.translatable("anima.command.party.yours"));
    }

    /** That agent's party — the omniscient view: a dev tool reads any roster. */
    private static int partyOfAgent(CommandSourceStack source, String token) {
        AgentId who = resolveDirectory(source, token);
        if (who == null) return 0;
        MinecraftServer server = source.getServer();
        return printParty(source, PartyData.get(server).partyOf(who),
                Component.translatable("anima.command.party.theirs", label(server, who)));
    }

    private static int printParty(CommandSourceStack source, PartyId party, Component heading) {
        MinecraftServer server = source.getServer();
        List<AgentId> members = PartyData.get(server).members(party);
        Component tagged = heading.copy().append(Component.literal(" (" + shortId(party) + ")"));
        if (members.size() == 1) {
            Replies.send(source, () -> tagged.copy()
                    .append(Component.translatable("anima.command.party.of_one"))
                    .withStyle(ChatFormatting.GRAY));
            return 1;
        }
        Replies.send(source, () -> tagged.copy()
                .append(Component.translatable("anima.command.party.members", members.size()))
                .withStyle(ChatFormatting.AQUA));
        for (AgentId member : members) {
            String line = label(server, member) + "  " + shortId(member);
            Replies.send(source, () -> indent(Component.literal(line)
                    .withStyle(ChatFormatting.GRAY)));
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
            Replies.fail(source, Component.translatable("anima.command.party.own"));
            return 0;
        }
        PartyData parties = PartyData.get(server);
        PartyId theirs = parties.partyOf(other);
        if (!parties.join(self, theirs)) {
            Replies.send(source, () -> Component.translatable("anima.command.party.same")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        // LOGGED: membership is persisted and it is what layer 3 scopes a board to — a party
        // moved out from under someone silently is a board's worth of work changing hands.
        Replies.send(source, () -> Component.translatable("anima.command.party.joined",
                label(server, self), label(server, other), parties.members(theirs).size())
                .withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    /** The source strikes out on their own; their next ask mints a fresh party of one. */
    private static int partyLeave(CommandSourceStack source) {
        AgentId self = sourceIdentity(source);
        if (self == null) return 0;
        MinecraftServer server = source.getServer();
        if (!PartyData.get(server).leave(self)) {
            Replies.send(source, () -> Component.translatable("anima.command.party.already_alone")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Replies.send(source, () -> Component.translatable("anima.command.party.left")
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
        return id.shortText();
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
        return Commands.literal("list")
                .executes(ctx -> listAgents(ctx.getSource(), false))
                // Explicit, because a listing that quietly mixed the dead in would be the
                // `purge graveyard` confusion again, wearing a readout instead of a command.
                .then(Commands.literal("all").executes(ctx -> listAgents(ctx.getSource(), true)));
    }

    private static int listAgents(CommandSourceStack source, boolean includeDead) {
        MinecraftServer server = source.getServer();
        AgentDirectory directory = AgentDirectory.of(server);
        Graves graves = Graves.get(server);
        Map<AgentId, PrivateIdentity> known =
                includeDead ? directory.known() : directory.living(server);
        if (known.isEmpty()) {
            Replies.send(source, () -> Component.translatable(includeDead
                    ? "anima.command.list.none_ever" : "anima.command.list.none")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        Vec3 origin = source.getPosition();
        long now = server.overworld().getGameTime();
        known.forEach((id, identity) -> {
            AgentBody body = AgentBodies.findLoaded(server, id);
            // "dead" before "unloaded" — those two used to be the same word here. The live row
            // borrows the entity type's own name rather than flattening it: getString() would
            // resolve it against the SERVER's language and hand every client that one.
            Component kind = graves.isDead(id)
                    ? Component.translatable("anima.command.list.dead")
                    : body == null ? Component.translatable("anima.command.list.unloaded")
                    : body.entity().getType().getDescription();
            // A dead row says when and where it happened instead of how far away it is standing —
            // the grave has held that all along.
            Component where = graves.isDead(id)
                    ? graves.deathOf(id).<Component>map(death -> summarise(death, now))
                            .orElse(Component.empty())
                    : body == null ? Component.empty()
                    : Component.literal(String.format(Locale.ROOT, "  %s  %.1fm",
                            body.entity().level().dimension().identifier().getPath(),
                            Math.sqrt(body.entity().distanceToSqr(origin))));
            Replies.send(source, () -> indent(Component.translatable("anima.command.list.row",
                    kind, identity.name(), shortId(id)).append(where)));
        });
        int buried = graves.size();
        Replies.send(source, () -> indent(Component.translatable(includeDead
                        ? "anima.command.list.total_known" : "anima.command.list.total_living",
                        known.size())
                .append(!includeDead && buried > 0
                        ? Component.translatable("anima.command.list.also_buried", buried)
                        : Component.empty())
                .withStyle(ChatFormatting.GRAY)));
        return known.size();
    }

    /**
     * What is written on somebody's grave — bare, the roll of everyone buried here; with a name,
     * the whole black box.
     *
     * <p>A factory, not a cached node: Brigadier parents a builder when it is registered.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> grave() {
        return Commands.literal("grave")
                .executes(ctx -> graveRoll(ctx.getSource()))
                .then(Commands.argument("person", StringArgumentType.string())
                        .suggests(ALL_PERSON_SUGGESTIONS)
                        .executes(ctx -> graveOf(ctx.getSource(),
                                StringArgumentType.getString(ctx, "person"))));
    }

    /** Everyone buried here, oldest death first. */
    private static int graveRoll(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Graves graves = Graves.get(server);
        if (graves.size() == 0) {
            Replies.send(source, () -> Component.translatable("anima.command.grave.none")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        long now = server.overworld().getGameTime();
        Replies.send(source, () -> Component.translatable("anima.command.grave.header",
                graves.size()).withStyle(ChatFormatting.AQUA));
        for (AgentId id : graves.all()) {
            Component where = graves.deathOf(id).<Component>map(death -> summarise(death, now))
                    .orElse(Component.empty());
            Replies.send(source, () -> indent(Component.literal(
                    label(server, id) + " (" + shortId(id) + ")").append(where))
                    .withStyle(ChatFormatting.GRAY));
        }
        return graves.size();
    }

    /**
     * One grave in full: when and where, what killed them, the state of the mind at the end and
     * their last words.
     *
     * <p><b>Resolved through the DIRECTORY, never through a body</b> — the point of a grave is that
     * there is nothing standing there. {@link #resolveBody} would answer only for the freshly dead
     * whose entity has not finished despawning.
     */
    private static int graveOf(CommandSourceStack source, String token) {
        AgentId who = resolveDirectory(source, token);
        if (who == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        String name = label(server, who);
        Optional<Graves.Death> found = Graves.get(server).deathOf(who);
        if (found.isEmpty()) {
            Replies.send(source, () -> Component.translatable("anima.command.grave.alive", name)
                    .withStyle(ChatFormatting.GREEN));
            return 0;
        }
        Graves.Death death = found.orElseThrow();
        long now = server.overworld().getGameTime();
        Replies.send(source, () -> Component.translatable("anima.command.grave.died",
                        name, shortId(who), ago(now - death.diedAtTick()))
                .withStyle(ChatFormatting.AQUA));
        Replies.send(source, () -> indent(Component.translatable("anima.command.grave.where",
                dimensionName(death.dimension()) + " " + death.where(), death.diedAtTick())));
        if (!death.cause().isBlank()) {
            Replies.send(source, () -> indent(Component.translatable("anima.command.grave.cause",
                    death.cause()).withStyle(ChatFormatting.RED)));
        }
        if (!death.killer().isBlank()) {
            // The id only when there is one: a zombie has no handle, and printing an empty pair of
            // brackets after every mob would make the ones that DO matter harder to spot.
            String killer = death.killer()
                    + death.killerId().map(id -> " (" + shortId(id) + ")").orElse("")
                    + (death.damageType().isBlank() ? "" : "  [" + death.damageType() + "]");
            Replies.send(source, () -> indent(Component.translatable("anima.command.grave.killer",
                    killer).withStyle(ChatFormatting.RED)));
        } else if (!death.damageType().isBlank()) {
            Replies.send(source, () -> indent(Component.translatable("anima.command.grave.damage",
                    death.damageType()).withStyle(ChatFormatting.RED)));
        }
        if (!death.mind().isEmpty()) {
            Replies.send(source, () -> indent(Component.translatable("anima.command.grave.mind")
                    .withStyle(ChatFormatting.AQUA)));
            for (String line : death.mind()) {
                Replies.send(source, () -> Component.literal("    " + line)
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        List<Entry> words = death.lastWords();
        if (words.isEmpty()) {
            // Says which of the two it is: a grave dug with the tail switched off is not the same
            // as one whose owner never got a line written about them.
            Replies.send(source, () -> indent(Component.translatable(Burial.tailEntries() == 0
                            ? "anima.command.grave.no_words_disabled"
                            : "anima.command.grave.no_words")
                    .withStyle(ChatFormatting.DARK_GRAY)));
            return 1;
        }
        Replies.send(source, () -> indent(Component.translatable("anima.command.grave.last_lines",
                words.size()).withStyle(ChatFormatting.AQUA)));
        for (Entry entry : words) {
            Replies.send(source, () -> Component.literal("    " + formatLine(name, entry))
                    .withStyle(colorFor(entry.category())));
        }
        return 1;
    }

    /** One death as a listing's tail: where it happened, how long ago, and what the story was. */
    private static Component summarise(Graves.Death death, long now) {
        return indent(Component.translatable("anima.command.grave.summary",
                dimensionName(death.dimension()) + " " + death.where(),
                ago(now - death.diedAtTick()))
                .append(death.cause().isBlank() ? Component.empty()
                        : Component.translatable("anima.command.grave.summary_cause",
                                death.cause())));
    }

    /** {@code minecraft:overworld} as {@code overworld} — the listing's own shorthand. */
    private static String dimensionName(String dimension) {
        int colon = dimension.indexOf(':');
        return colon < 0 ? dimension : dimension.substring(colon + 1);
    }

    /**
     * A span of ticks as something a person can hold in their head, coarse on purpose: minutes or
     * days ago, not 4 minutes versus 5.
     *
     * <p>Clamped at zero: a negative span means the world's clock is behind the grave (a backup
     * restored over a newer save), and "0s ago" confuses less than a negative age does.
     */
    private static Component ago(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        if (seconds < 60) {
            return Component.translatable("anima.time.seconds_ago", seconds);
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return Component.translatable("anima.time.minutes_ago", minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return Component.translatable("anima.time.hours_ago", hours, minutes % 60);
        }
        return Component.translatable("anima.time.days_ago", hours / 24, hours % 24);
    }

    /**
     * The in-world gizmo view over the selected agent — path, brain, memory, peers and horizon.
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
