package funmode.curses;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.type.Item;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import static mindustry.Vars.content;
import static mindustry.Vars.net;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * Every once in a while, a friendly message informs you that something new and exciting has
 * happened. It doesn't say what. Half the time something actually did happen - a small random
 * amount of one random item quietly leaves the core (host/SP only; as a pure client the message
 * still appears, which is arguably worse).
 */
public class MysteryMessagesCurse implements Curse{
    static final float MIN_INTERVAL_MIN = 3f, MAX_INTERVAL_MIN = 7f;
    static final int MESSAGE_COUNT = 8;

    float countdownTicks = -1f;

    @Override
    public String id(){
        return "mystery";
    }

    @Override
    public String titleKey(){
        return "fun.curse.mystery.title";
    }

    @Override
    public void init(){
        Events.on(WorldLoadEvent.class, e -> reroll());
        Events.run(Trigger.update, this::update);
    }

    void reroll(){
        countdownTicks = Mathf.random(MIN_INTERVAL_MIN, MAX_INTERVAL_MIN) * 60f * 60f;
    }

    @Override
    public void buildDebug(arc.scene.ui.layout.Table table){
        table.button("Сообщение сейчас", () -> countdownTicks = 0.5f).size(220f, 50f);
    }

    void update(){
        if(!isActive() || player == null) return;

        if(countdownTicks < 0f) reroll();
        countdownTicks -= Time.delta * funmode.core.Chaos.frequencyMult();
        if(countdownTicks > 0f) return;
        reroll();

        funmode.core.Chat.send(Core.bundle.get("fun.mystery." + Mathf.random(1, MESSAGE_COUNT)));

        //half the time, something new and exciting really did just happen
        if(Mathf.chance(0.5) && !net.client() && !state.rules.infiniteResources){
            CoreBuild core = player.team().core();
            if(core == null) return;
            Item item = content.items().select(i -> core.items.get(i) > 0).random();
            if(item == null) return;
            core.items.remove(item, Math.min(Mathf.random(5, 25), core.items.get(item)));
        }
    }
}
