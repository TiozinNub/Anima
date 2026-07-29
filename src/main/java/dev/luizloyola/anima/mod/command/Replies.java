package dev.luizloyola.anima.mod.command;

import dev.luizloyola.anima.mod.body.AgentBody;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * Every line a command prints, stamped with the agent it ran <em>as</em>.
 *
 * <p>{@code execute as} is the one handle that <em>iterates</em>, and its replies are otherwise
 * indistinguishable:
 *
 * <pre>{@code
 * /execute as @e[type=autarkia:person] run anima contacts
 * [as John] You know nobody yet.
 * [as Mary] You know 1 person:
 * }</pre>
 *
 * <p>Per LINE, not per command: a header-only stamp leaves every row of a journal dump anybody's
 * guess.
 *
 * <p>Only when the source's entity is an {@link AgentBody}: a player typing {@code /anima contacts}
 * and {@code execute as @p} arrive as the same source, so stamping that would stamp everything.
 *
 * <p>Commands print through here, never {@link CommandSourceStack#sendSuccess} or
 * {@link CommandSourceStack#sendFailure} directly, so no reply can forget the stamp. Both delegate
 * to the source, leaving the {@code sendCommandFeedback} gamerule and a suppressed-output
 * {@code execute} to vanilla; the stack's own {@code CommandSource} is private, so this cannot
 * decorate it.
 */
public final class Replies {

    private Replies() {
    }

    /** Sends a success line, stamped with the agent this command ran as. */
    public static void send(CommandSourceStack source, Supplier<Component> message) {
        send(source, message, false);
    }

    /** Sends a success line, stamped, with vanilla's "tell the other admins too" flag — set for a
     *  command that CHANGED something (a config write), left off for a readout. The admin copy is
     *  unstamped: it already arrives as "[John: …]", named by the source the command ran as. */
    public static void send(CommandSourceStack source, Supplier<Component> message, boolean logged) {
        source.sendSuccess(() -> stamped(source, message.get()), logged);
    }

    /** Sends a failure line, stamped with the agent this command ran as. */
    public static void fail(CommandSourceStack source, Component message) {
        source.sendFailure(stamped(source, message));
    }

    /**
     * {@code message} behind an {@code [as <name>]} stamp when the source is an agent, unchanged
     * otherwise.
     *
     * <p>Both halves hang off an unstyled parent: a child inherits its parent's style, so nesting
     * would repaint a plain line grey, and the red from {@link CommandSourceStack#sendFailure}
     * still reaches the message.
     */
    private static Component stamped(CommandSourceStack source, Component message) {
        if (!(source.getEntity() instanceof AgentBody body)) return message;
        return Component.empty()
                .append(Component.literal("[as " + body.entity().getName().getString() + "] ")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(message);
    }
}
