package funmode.core;

import arc.audio.Sound;
import arc.graphics.Color;
import mindustry.entities.Effect;
import mindustry.gen.Call;

/**
 * Plays the mod's one-shot EFFECTS and SOUNDS so every player sees/hears them, not just the host.
 * A host-run curse that calls {@code someFx.at(...)} or {@code sound.play(...)} only shows it on the
 * host - those are client-local. {@link Call#effect}/{@link Call#soundAt}/{@link Call#sound} are
 * server-broadcast (and effects serialise by id, which matches across machines since everyone runs
 * the same mod), so routing through here makes explosions, fire, lightning and thunder reach the
 * whole lobby. In singleplayer these just run locally. Only for FINITE one-shot fx/sounds - continuous
 * per-frame drawing (screen tints, telegraph beams) is a separate problem.
 */
public final class Vfx{
    private Vfx(){
    }

    public static void at(Effect effect, float x, float y){
        Call.effect(effect, x, y, 0f, Color.white);
    }

    public static void at(Effect effect, float x, float y, float rotation){
        Call.effect(effect, x, y, rotation, Color.white);
    }

    public static void at(Effect effect, float x, float y, float rotation, Color color){
        Call.effect(effect, x, y, rotation, color);
    }

    /** A positional sound everyone hears (volume falls off with distance for each player). */
    public static void soundAt(Sound sound, float x, float y, float volume, float pitch){
        Call.soundAt(sound, x, y, volume, pitch);
    }

    /** A non-positional sound everyone hears at full volume (e.g. thunder across the whole map). */
    public static void sound(Sound sound, float volume, float pitch){
        Call.sound(sound, volume, pitch, 0f);
    }
}
