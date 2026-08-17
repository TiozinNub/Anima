package dev.luizloyola.anima.mod.command;

import dev.luizloyola.anima.core.agent.need.Gauge;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.agent.need.NeedLevel;
import dev.luizloyola.anima.core.agent.need.Reason;
import dev.luizloyola.anima.core.agent.need.ReasonGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Turns a gauge's itemisation into the readout the needs design was written for:
 *
 * <pre>
 *   John is healthy because:
 *   - Health is 16
 *   - No debuffs
 *   - Has Strength applied to them
 * </pre>
 *
 * <p><b>Assembled from lang, not written here.</b> Core hands over keys — the header, the level's
 * own {@code person_is} word, each line and the thing it names — and every one is resolved against
 * the reader's language. That is what lets a consumer's need read like a sentence in a language
 * Anima has never heard of.
 *
 * <p><b>An empty group still prints.</b> {@code No debuffs} is a fact about the body; a missing
 * line would be a fact about the readout, and nothing tells a reader which they are looking at.
 */
public final class Because {

    private Because() {
    }

    /**
     * The block for one gauge, or empty for a gauge whose number has no parts — which is most of
     * them, and why this is safe to call for every gauge on a roster.
     *
     * @param who what to call the body, already resolved: it is a name, not a translation
     */
    public static List<Component> lines(String who, Gauge gauge) {
        List<ReasonGroup> groups = gauge.reasons();
        if (groups.isEmpty()) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(NeedKind.REASON_HEADER, who, personIs(gauge))
                .withStyle(ChatFormatting.WHITE));
        for (ReasonGroup group : groups) {
            if (group.isEmpty()) {
                lines.add(bullet(Component.translatable(group.emptyKey())));
                continue;
            }
            for (Reason reason : group.reasons()) {
                lines.add(bullet(Component.translatable(reason.key(),
                        reason.arg().isEmpty() ? Component.empty() : Component.translatable(reason.arg()),
                        amount(reason.amount()))));
            }
        }
        return lines;
    }

    /** How this body's level says a body <em>is</em> it, or the gauge's own key for a level-less need. */
    private static Component personIs(Gauge gauge) {
        NeedLevel level = gauge.level();
        return level == null
                ? Component.literal(gauge.kind().key())
                : Component.translatable(level.personIsKey());
    }

    private static Component bullet(Component line) {
        return Component.literal("    - ").withStyle(ChatFormatting.GRAY).append(line);
    }

    /** A whole number loses its {@code .0}: "Health is 16", never "Health is 16.0". */
    private static String amount(double value) {
        return value == Math.rint(value)
                ? String.format(Locale.ROOT, "%d", (long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
