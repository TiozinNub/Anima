package dev.luizloyola.anima.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.ObjectContents;
import net.minecraft.network.chat.contents.objects.PlayerSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * Chat components whose Minecraft API drifts across versions — today, the player-head glyph.
 *
 * <p>See {@code docs/chat-sprites.md} for what was verified in-world: a texture-only profile is
 * enough (no name, no UUID, so nothing is left for the client to resolve), and {@code hat} must be
 * true or a skin that keeps its hair on the overlay layer renders bald.
 */
public final class Chats {
    private Chats() {}

    /**
     * A player-head glyph wearing {@code skinAsset} — a font glyph, so it renders no text of its
     * own and the name goes beside it as an ordinary component.
     *
     * <p>The profile is built by parsing rather than by constructor: {@code ResolvableProfile}'s
     * texture/model shape is a codec detail with no public builder, and the two fields below are
     * {@code PlayerSkin.Patch}'s, inlined into the profile object exactly as the {@code /tellraw}
     * JSON spells them.
     */
    public static Component head(Identifier skinAsset, boolean slim) {
        CompoundTag tag = new CompoundTag();
        tag.putString("texture", skinAsset.toString());
        tag.putString("model", slim ? "slim" : "wide");
        ResolvableProfile profile = ResolvableProfile.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        PlayerSprite sprite = new PlayerSprite(profile, true);
        // 26.1 gave ObjectContents a second component — the fallback shown when the object cannot
        // render at all. 1.21.11 has the record with one. The docs note claiming the construction is
        // byte-identical across nodes was wrong on exactly this.
        //? if >=26.1 {
        return MutableComponent.create(new ObjectContents(sprite, java.util.Optional.empty()));
        //?} else {
        /*return MutableComponent.create(new ObjectContents(sprite));
        *///?}
    }
}
