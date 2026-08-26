package dev.luizloyola.anima.mod.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.List;
import java.util.function.Supplier;

/**
 * The two seams of a command root, built from one set of lists.
 *
 * <p>A subcommand belongs to {@code hasSubject} if and only if its behaviour depends on WHICH agent
 * it is about. Those mount twice — at the root, where the ladder finds the subject, and under
 * {@code as <person>}, where it is named. {@code noSubject} mounts once: {@code config},
 * {@code webdebug} and {@code list} answer the same thing whoever asks, so
 * {@code /anima as Cleo webdebug} should not parse at all rather than parse and be refused.
 * {@code asOnly} mounts under {@code as} alone, for a verb that must never fall down the ladder to
 * whoever happens to be nearest.
 *
 * <p><b>Suppliers, not builders.</b> Brigadier parents a builder when it is registered, so a node
 * mounted twice must be BUILT twice; a cached builder can only ever have one parent, and the second
 * mount would silently steal the first.
 *
 * <p>Going through here is what makes the two seams identical by construction. A subcommand added
 * to a root by hand appears at one seam and not the other, and nothing would say so.
 */
public final class CommandSurface {

    private CommandSurface() {
    }

    /** Mounts the three lists onto {@code root} and hands it back, ready to register. */
    public static LiteralArgumentBuilder<CommandSourceStack> mount(
            LiteralArgumentBuilder<CommandSourceStack> root,
            List<Supplier<LiteralArgumentBuilder<CommandSourceStack>>> hasSubject,
            List<Supplier<LiteralArgumentBuilder<CommandSourceStack>>> noSubject,
            List<Supplier<LiteralArgumentBuilder<CommandSourceStack>>> asOnly) {

        for (Supplier<LiteralArgumentBuilder<CommandSourceStack>> node : noSubject) {
            root.then(node.get());
        }
        for (Supplier<LiteralArgumentBuilder<CommandSourceStack>> node : hasSubject) {
            root.then(node.get());
        }
        RequiredArgumentBuilder<CommandSourceStack, String> as = Subject.argument();
        for (Supplier<LiteralArgumentBuilder<CommandSourceStack>> node : hasSubject) {
            as.then(node.get());
        }
        for (Supplier<LiteralArgumentBuilder<CommandSourceStack>> node : asOnly) {
            as.then(node.get());
        }
        return root.then(Commands.literal("as").then(as));
    }
}
