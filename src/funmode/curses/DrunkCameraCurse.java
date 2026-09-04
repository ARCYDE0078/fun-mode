package funmode.curses;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.game.EventType.Trigger;

import static mindustry.Vars.state;

/**
 * At random moments the camera has had a few too many: for several seconds the view lists and
 * sways in slow circles, listing worse in the middle of the episode and settling as it ends. A
 * true world rotation isn't something a mod can do in Mindustry (the camera has no roll and there's
 * no hook between its matrix rebuild and the world draw), so this is the next best thing: a
 * transient position sway, applied in preDraw so the whole world moves with it. Purely local -
 * works anywhere.
 */
public class DrunkCameraCurse implements Curse{
    static final float MIN_GAP_SEC = 120f, MAX_GAP_SEC = 300f;
    static final float EPISODE_SEC = 5f;
    static final float AMPLITUDE = 70f;

    float gap = -1f;
    float episodeLeft = 0f;

    @Override
    public String id(){
        return "drunk-camera";
    }

    @Override
    public String titleKey(){
        return "fun.curse.drunk-camera.title";
    }

    @Override
    public void init(){
        //preDraw runs before the camera rebuilds its matrix, so offsetting position here moves the
        //entire world for this frame (same window the mod's foreshadow shake uses)
        Events.run(Trigger.preDraw, this::sway);
    }

    void sway(){
        if(!isActive()) return;

        if(episodeLeft > 0f){
            episodeLeft -= Time.delta;
            float phase = 1f - episodeLeft / (EPISODE_SEC * 60f);
            //envelope: rises to full mid-episode, eases back out
            float env = Mathf.sin(phase * Mathf.PI);
            float slow = Time.time / 26f;
            float fast = Time.time / 9f;
            float mag = AMPLITUDE * env;
            float ox = Mathf.cos(slow) * mag + Mathf.cos(fast) * mag * 0.25f;
            float oy = Mathf.sin(slow) * mag + Mathf.sin(fast) * mag * 0.25f;
            Core.camera.position.add(ox, oy);
            return;
        }

        if(!state.isGame()) return;
        if(gap < 0f) gap = Mathf.random(MIN_GAP_SEC, MAX_GAP_SEC) * 60f;
        gap -= Time.delta * funmode.core.Chaos.frequencyMult();
        if(gap <= 0f){
            episodeLeft = EPISODE_SEC * 60f;
            gap = -1f;
        }
    }

    @Override
    public void buildDebug(arc.scene.ui.layout.Table table){
        table.button("Пьяная камера сейчас", () -> episodeLeft = EPISODE_SEC * 60f).size(220f, 50f);
    }
}
