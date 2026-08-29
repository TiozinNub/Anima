package dev.luizloyola.anima.mod.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.tree.CommandNode;
import dev.luizloyola.anima.core.log.Category;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every {@link Category} is reachable from {@code /anima log}.
 *
 * <p>The leaves are enumerated by hand, next to a {@code colorFor} switch over the same enum that
 * the compiler DOES check — so a new category compiles clean with its filter silently missing.
 * That is exactly how {@code mind} and {@code op} landed unreachable: coloured everywhere, typeable
 * nowhere.
 */
class LogCommandTreeTest {

    /**
     * {@code Commands}' own static init reaches a registry, so it needs the bootstrap even to build
     * a bare literal. Without this the test still passes in a full run — some earlier class has
     * already bootstrapped — and dies on {@code --tests}, which is the ordering dependency that
     * makes a test worthless exactly when it is being trusted.
     */
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyCategoryHasItsOwnLogLeaf() {
        Set<String> leaves = AgentCommands.log().build().getChildren().stream()
                .map(CommandNode::getName)
                .collect(Collectors.toSet());

        for (Category category : Category.values()) {
            String leaf = category.name().toLowerCase(Locale.ROOT);
            assertTrue(leaves.contains(leaf),
                    "/anima log " + leaf + " does not exist — the tree offers " + leaves);
        }
    }
}
