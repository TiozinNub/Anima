package dev.luizloyola.anima.compat.agent;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
//? if >=26.4-alpha {
/*import net.minecraft.world.level.block.sounds.BlockSoundSet;
*///?} else {
import net.minecraft.world.level.block.SoundType;
//?}

/**
 * The sound of a block set down, at the volume and pitch {@code BlockItem.place} uses. 26.4 made a
 * block's sounds data ({@code BlockSoundSet}, read through the level, the place sound optional);
 * older nodes have the {@code SoundType} the block hard-codes.
 */
public final class BlockSounds {

    private BlockSounds() {
    }

    public static void placed(Level level, BlockPos pos, BlockState state) {
        //? if >=26.4-alpha {
        /*BlockSoundSet sound = state.getSounds(level);
        sound.placeSound().ifPresent(event -> level.playSound(null, pos, event, SoundSource.BLOCKS,
                (sound.volume() + 1.0F) / 2.0F, sound.pitch() * 0.8F));
        *///?} else {
        SoundType sound = state.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        //?}
    }
}
