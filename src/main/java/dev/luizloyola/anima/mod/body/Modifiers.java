package dev.luizloyola.anima.mod.body;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.core.agent.AspectModifier;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import java.util.List;

/**
 * The codec for a body's {@link AspectModifier}s, so a consumer can carry them across a restart
 * without writing one.
 *
 * <p>Durability still belongs to the consumer ({@link AspectModifier} is explicit): it persists the
 * JOB and re-applies the modifier on load. This is for a modifier with no source anywhere else,
 * today the one {@code /anima profile debug} sets by hand.
 *
 * <p>The two compose because re-applying an id replaces rather than stacks, so a saved copy and the
 * consumer's own re-derivation land on the same value.
 *
 * <p>In {@code mod} rather than {@code core} because {@code core} does not name DataFixerUpper.
 */
public final class Modifiers {

    private Modifiers() {
    }

    /**
     * Aspects round-trip by their stable key. An unknown one — a build that no longer declares that
     * aspect — errors rather than guessing, and the list codec drops that one modifier rather than
     * failing the body's whole NBT: losing a shift beats losing the settler.
     */
    private static final Codec<ProfileAspect> ASPECT = Codec.STRING.comapFlatMap(
            key -> ProfileAspect.byKey(key)
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "no aspect is registered as \"" + key
                            + "\" — was it renamed or removed?")),
            ProfileAspect::key);

    private static final Codec<AspectModifier.Op> OP =
            Codec.STRING.xmap(AspectModifier.Op::valueOf, AspectModifier.Op::name);

    /** One modifier: what shifted it, which aspect, how it combines, and by how much. */
    public static final Codec<AspectModifier> CODEC = RecordCodecBuilder.create(m -> m.group(
            Codec.STRING.fieldOf("id").forGetter(AspectModifier::id),
            ASPECT.fieldOf("aspect").forGetter(AspectModifier::aspect),
            OP.fieldOf("op").forGetter(AspectModifier::op),
            Codec.DOUBLE.fieldOf("amount").forGetter(AspectModifier::amount)
    ).apply(m, AspectModifier::new));

    /** Every modifier on one body, in application order. */
    public static final Codec<List<AspectModifier>> LIST = CODEC.listOf();
}
