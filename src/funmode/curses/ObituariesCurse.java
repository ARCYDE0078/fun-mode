package funmode.curses;

import arc.Core;
import arc.Events;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.UnitDestroyEvent;
import mindustry.gen.Unit;

import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * Every unit on your team has a name. You find out what it was when they die: a chat obituary with
 * the name, the unit type and where it happened. Mass casualties are folded into a single grim
 * summary instead of flooding the chat (a wave wiping thirty daggers is a tragedy, not thirty).
 * Names are derived from the unit id, so a unit keeps its name for life - you just never knew it.
 * <p>
 * Respects are paid with a dedicated REBINDABLE key (default G - NOT F, which vanilla has bound to
 * schematic copying; rebindable in Settings/Controls like qol-suite's binds): press it within a few
 * seconds of an obituary and the fallen is honored. Units that died to Explosive Production's
 * quality control get no obituary - they never truly lived.
 */
public class ObituariesCurse implements Curse{
    static final KeyBind payRespects = KeyBind.add("fun-pay-respects", KeyCode.g, "fun-mode");

    static final int MAX_OBITS_PER_WINDOW = 1;
    static final long WINDOW_MS = 10_000L;
    static final long RESPECTS_WINDOW_MS = 10_000L;

    String[] names;
    long windowStart = 0L;
    int shownThisWindow = 0;
    int suppressed = 0;
    String lastFallenName = null;
    long lastObitAt = 0L;

    @Override
    public String id(){
        return "obituaries";
    }

    @Override
    public String titleKey(){
        return "fun.curse.obituaries.title";
    }

    @Override
    public void init(){
        names = Core.bundle.get("fun.obituaries.names", "Bob,Steve,Unit").split("\\s*,\\s*");
        Events.on(UnitDestroyEvent.class, this::onDestroy);
        Events.run(Trigger.update, this::pollRespects);
    }

    void pollRespects(){
        if(!isActive() || lastFallenName == null || ui.chatfrag.shown()) return;
        if(Time.timeSinceMillis(lastObitAt) > RESPECTS_WINDOW_MS){
            lastFallenName = null;
            return;
        }
        if(Core.input.keyTap(payRespects)){
            funmode.core.Chat.send(Core.bundle.format("fun.obituaries.respects", lastFallenName));
            lastFallenName = null;
        }
    }

    void onDestroy(UnitDestroyEvent e){
        if(!isActive() || player == null) return;
        Unit u = e.unit;
        if(u == null || u.team != player.team()) return;
        //the player respawning shouldn't eulogize themselves
        if(u.isPlayer()) return;
        //quality control rejects don't get obituaries - they never truly lived
        if(ExplosiveProductionCurse.consumeQualityControlVictim(u.id)) return;
        //neither do willing sacrifices - the altar is not a funeral
        if(SacrificeAltarCurse.consumeSacrificed(u.id)) return;

        long now = Time.millis();
        if(now - windowStart > WINDOW_MS){
            if(suppressed > 0){
                funmode.core.Chat.send(Core.bundle.format("fun.obituaries.summary", suppressed));
            }
            windowStart = now;
            shownThisWindow = 0;
            suppressed = 0;
        }

        if(shownThisWindow >= MAX_OBITS_PER_WINDOW){
            suppressed++;
            return;
        }
        shownThisWindow++;

        String name = names[Math.floorMod(u.id * 31, names.length)];
        funmode.core.Chat.send(Core.bundle.format("fun.obituaries.message",
            u.type.localizedName, name, (int)(u.x / 8f), (int)(u.y / 8f)));
        lastFallenName = name;
        lastObitAt = now;
    }
}
