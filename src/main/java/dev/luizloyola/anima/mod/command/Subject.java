package dev.luizloyola.anima.mod.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.AgentLookup;
import dev.luizloyola.anima.mod.body.AgentBodies;
import dev.luizloyola.anima.mod.body.AgentBody;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Who a command is about.
 *
 * <p>{@code as <person>} binds an {@link AgentId} as a Brigadier ARGUMENT rather than rebinding the
 * command source, and that is the whole design. Rebinding the source — the obvious build, and what
 * vanilla's {@code /execute as} does — would conflate the subject with the caller:
 * {@link AgentSelection} picks its slot by asking whether the source's entity is a player, and the
 * per-player viewers all call {@code getPlayer()}. Under a redirect a player's
 * {@code /anima as Cleo select} would write into the shared console slot, and
 * {@code as Cleo knowledge view true} — subject Cleo, viewer ME — could not be expressed at all.
 *
 * <p>Because the subject is an id rather than an entity, {@code as} reaches an agent whose body is
 * unloaded, or dead. {@link #id} answers for those; {@link #body} is the one that insists on a live
 * body and says so when there is not one.
 *
 * <p>{@link #ARG} is RESERVED. No other node in either mod may name an argument {@code person}, or
 * it silently captures the subject.
 */
public final class Subject {

    private Subject() {
    }

    /** The reserved argument name {@code as} binds, and the only use of it in either tree. */
    public static final String ARG = "person";

    /** How far the ladder's last rung looks for a body. */
    static final double NEAREST_RADIUS = 32.0;

    /** Every name and short id the directory can put a token to — loaded or not, plus players. */
    private static final SuggestionProvider<CommandSourceStack> SUGGESTIONS = (ctx, builder) -> {
        Stream<String> tokens = AgentCommands.nameable(ctx.getSource().getServer()).entrySet().stream()
                .flatMap(entry -> Stream.of(
                        entry.getValue().contains(" ") ? '"' + entry.getValue() + '"' : entry.getValue(),
                        AgentCommands.shortId(entry.getKey())));
        return SharedSuggestionProvider.suggest(tokens, builder);
    };

    /**
     * The suggester alone, for a node naming an agent as a verb's OBJECT rather than as the
     * subject — {@code contacts meet <whom>}, {@code party join <whom>}.
     *
     * <p><b>Such a node must not be called {@code person}.</b> Brigadier keys a command's arguments
     * by NAME, so a second one of that name overwrites the first: in
     * {@code as Ada contacts meet Bram} the subject would silently read back as Bram.
     */
    public static SuggestionProvider<CommandSourceStack> suggestions() {
        return SUGGESTIONS;
    }

    /** The subject argument, for {@code as} and for any leaf that names an agent positionally. */
    public static RequiredArgumentBuilder<CommandSourceStack, String> argument() {
        return Commands.argument(ARG, StringArgumentType.string()).suggests(SUGGESTIONS);
    }

    /**
     * Whether this command carries an {@code as <person>} subject.
     *
     * <p>Walks the parsed nodes rather than reaching for the argument: Brigadier 1.3.10 exposes no
     * argument map, and {@code getArgument} THROWS on a name that is not there — which "no subject"
     * is, on almost every command typed.
     *
     * <p>The nodes of one context are the whole path that reached the leaf, which is the assumption
     * the whole design rests on: {@code as} re-mounts its subtree rather than redirecting, so
     * {@code anima as Ada contacts} parses into ONE context carrying {@code as}, {@code person} and
     * {@code contacts} together. A redirect would have started a fresh context at the seam and lost
     * the subject.
     */
    public static boolean bound(CommandContext<CommandSourceStack> ctx) {
        for (ParsedCommandNode<CommandSourceStack> node : ctx.getNodes()) {
            if (ARG.equals(node.getNode().getName())) return true;
        }
        return false;
    }

    /**
     * The agent this command is about, whether or not a body is loaded: the {@code as} subject,
     * else the body it runs AS ({@code /execute as …}), else the source's selection, else the
     * nearest body within {@value #NEAREST_RADIUS} blocks.
     *
     * <p>Reports why and answers {@code null} when nothing resolves.
     */
    public static @Nullable AgentId id(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (bound(ctx)) return directoryId(source, StringArgumentType.getString(ctx, ARG));
        if (source.getEntity() instanceof AgentBody self) return self.agentId();
        Optional<AgentId> selection = AgentSelection.selected(source);
        if (selection.isPresent()) return selection.get();
        AgentBody near = nearest(source);
        return near == null ? null : near.agentId();
    }

    /**
     * The subject as a LIVE body, or {@code null} having said why.
     *
     * <p>"Nobody resolved" and "they resolved, and are not loaded" are different answers, and the
     * second is phrased against the rung that produced it: a stale <em>selection</em> wants
     * {@code select clear}, while a named {@code as} subject wants no such advice.
     */
    public static @Nullable AgentBody body(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        // The as-body rung whole and first: it is already a live body, and the only rung that can
        // be standing there DEAD.
        if (!bound(ctx) && source.getEntity() instanceof AgentBody self) {
            if (!self.entity().isAlive()) {
                Replies.fail(source, Component.translatable("anima.command.select.dead",
                        self.entity().getName()));
                return null;
            }
            return self;
        }
        AgentId id = id(ctx);
        if (id == null) return null; // already reported
        AgentBody live = AgentBodies.findLoaded(source.getServer(), id);
        if (live == null) {
            // Only two rungs can hand back an id with no body: a named subject and a stale
            // selection. The nearest rung returns a live body or nothing at all.
            Replies.fail(source, Component.translatable(
                    bound(ctx) ? "anima.command.subject.not_loaded"
                            : "anima.command.select.not_loaded",
                    AgentCommands.label(source.getServer(), id)));
        }
        return live;
    }

    /** A token against the whole directory, reporting the reason it did not land. */
    public static @Nullable AgentId directoryId(CommandSourceStack source, String token) {
        return switch (AgentLookup.match(AgentCommands.nameable(source.getServer()), token)) {
            case AgentLookup.Found found -> found.id();
            case AgentLookup.None ignored -> {
                Replies.fail(source, Component.translatable("anima.command.no_match", token));
                yield null;
            }
            case AgentLookup.Ambiguous ambiguous -> {
                String ids = ambiguous.candidates().stream()
                        .map(AgentCommands::shortId)
                        .collect(Collectors.joining(", "));
                Replies.fail(source, Component.translatable("anima.command.ambiguous_name",
                        ambiguous.candidates().size(), token, ids));
                yield null;
            }
        };
    }

    /** The nearest live agent body of any kind — the ladder's last rung, and {@code select}'s. */
    static @Nullable AgentBody nearest(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();
        AABB box = AABB.ofSize(origin, NEAREST_RADIUS * 2, NEAREST_RADIUS * 2, NEAREST_RADIUS * 2);
        List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e instanceof AgentBody);
        AgentBody nearest = near.stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(origin), b.distanceToSqr(origin)))
                .map(AgentBody.class::cast)
                .orElse(null);
        if (nearest == null) {
            Replies.fail(source, Component.translatable("anima.command.select.nobody_near",
                    (int) NEAREST_RADIUS));
        }
        return nearest;
    }
}
