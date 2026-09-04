package funmode.curses;

import arc.Events;
import arc.audio.AudioBus;
import arc.math.Mathf;
import funmode.core.Curse;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Sounds;

/**
 * At a random moment, with a 1/40000 chance per tick (~11 minutes on average, but you never know
 * when), a VERY loud gust of wind plays. That's it. That's the curse.
 * <p>
 * Played on this curse's own {@link AudioBus} rather than the shared sound bus: the game's sfx
 * volume slider works by muting the shared bus, and per sonka's spec the wind must reach you even
 * with game sound turned all the way off. Only wind3's playback here is affected - the shared
 * Sound object's bus binding stays untouched.
 */
public class JumpscareCurse implements Curse{
    static final double CHANCE_PER_TICK = 1.0 / 40000.0;

    AudioBus bus;

    @Override
    public String id(){
        return "jumpscare";
    }

    @Override
    public String titleKey(){
        return "fun.curse.jumpscare.title";
    }

    @Override
    public void init(){
        //the constructor self-initializes when Core.audio is already up - true at client load
        bus = new AudioBus();

        Events.run(Trigger.update, () -> {
            if(!isActive()) return;
            if(Mathf.chance(CHANCE_PER_TICK * funmode.core.Chaos.frequencyMult())){
                playNow();
            }
        });
    }

    void playNow(){
        Sounds.wind3.play(20f, 1f, 0f, false, false, bus);
    }

    @Override
    public void buildDebug(arc.scene.ui.layout.Table table){
        table.button("Ветер сейчас", this::playNow).size(220f, 50f);
    }
}
