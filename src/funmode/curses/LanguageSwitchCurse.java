package funmode.curses;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.util.I18NBundle;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;

import java.util.Locale;

import static mindustry.Vars.state;

/**
 * Every few minutes the interface briefly forgets which language it speaks: the whole UI flips to a
 * random tongue (router included, naturally) for several seconds, then snaps back. Purely a local
 * cosmetic prank - it swaps Core.bundle in place and keeps the original reference to restore, so
 * nothing is permanently broken. Works anywhere, servers included.
 */
public class LanguageSwitchCurse implements Curse{
    static final float MIN_GAP_MIN = 2f, MAX_GAP_MIN = 5f;
    static final float SWAP_SECONDS = 7f;
    static final String[] LOCALES = {"ru", "zh_CN", "ja", "ko", "ar", "fr", "de", "uk_UA", "router", "tr"};

    I18NBundle saved;
    float gap = -1f;
    float restoreLeft = 0f;

    @Override
    public String id(){
        return "language-switch";
    }

    @Override
    public String titleKey(){
        return "fun.curse.language-switch.title";
    }

    @Override
    public void init(){
        Events.on(WorldLoadEvent.class, e -> restore());
        Events.run(Trigger.update, this::update);
    }

    boolean swapped(){
        return saved != null;
    }

    void update(){
        //restore no matter what if we're mid-swap but shouldn't be
        if(swapped() && (!isEnabled() || !state.isGame())){
            restore();
            return;
        }
        if(!isActive()) return;

        if(swapped()){
            restoreLeft -= Time.delta;
            if(restoreLeft <= 0f) restore();
            return;
        }

        if(gap < 0f) gap = Mathf.random(MIN_GAP_MIN, MAX_GAP_MIN) * 60f * 60f;
        gap -= Time.delta * funmode.core.Chaos.frequencyMult();
        if(gap <= 0f) swap();
    }

    void swap(){
        try{
            String loc = LOCALES[Mathf.random(LOCALES.length - 1)];
            Locale locale = loc.contains("_") ? new Locale(loc.split("_")[0], loc.split("_")[1]) : new Locale(loc);
            I18NBundle next = I18NBundle.createBundle(Core.files.internal("bundles/bundle"), locale);
            saved = Core.bundle;
            Core.bundle = next;
            restoreLeft = SWAP_SECONDS * 60f;
        }catch(Throwable t){
            //if anything goes wrong, make sure we're not left in a half-swapped state
            restore();
        }
        gap = -1f;
    }

    void restore(){
        if(saved != null){
            Core.bundle = saved;
            saved = null;
        }
        restoreLeft = 0f;
        gap = -1f;
    }
}
