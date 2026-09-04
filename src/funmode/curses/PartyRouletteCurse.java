package funmode.curses;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.Tmp;
import funmode.core.Chaos;
import funmode.core.Curse;
import mindustry.content.Fx;
import mindustry.content.StatusEffects;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;

import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * The multiplayer curse: a roulette wheel that spins on a timer and lands on ONE random player in
 * the game, hitting their unit with a short, everyone-sees-it effect and calling their name out in
 * chat. Because the host owns the simulation, applying a status or an impulse to any player's unit
 * (even a remote one) syncs back to their client - so in co-op the whole lobby watches whoever the
 * wheel picks get jammed, frozen, sneezed across the map, dazed... or, once in a while, blessed with
 * a speed surge. In singleplayer it just keeps picking you.
 * <p>
 * Host/SP only (it moves the sim). The spin cadence rides {@link Chaos#frequencyMult()}, so a chaotic
 * game spins the wheel far more often.
 */
public class PartyRouletteCurse implements Curse{
    /** Base seconds between spins (chaos shortens this - down to ~a third at full chaos). */
    static final float INTERVAL = 60f * 90f;

    static final float DISARM_TICKS = 60f * 4f;
    static final float FREEZE_TICKS = 60f * 3f;
    static final float DIZZY_TICKS = 60f * 5f;
    static final float LUCKY_TICKS = 60f * 6f;

    float timer = 0f;
    final Seq<Player> candidates = new Seq<>();

    @Override
    public String id(){
        return "party-roulette";
    }

    @Override
    public String titleKey(){
        return "fun.curse.party-roulette.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void init(){
        Events.on(WorldLoadEvent.class, e -> timer = 0f);
        Events.run(Trigger.update, this::update);
    }

    void update(){
        if(!isActive() || state.isPaused()) return;
        //chaos winds the wheel faster; frequencyMult is 1 when the chaos curse is off
        timer += Time.delta * Chaos.frequencyMult();
        if(timer < INTERVAL) return;
        timer = 0f;
        spin();
    }

    /** One spin: pick a random player who's actually piloting a unit, hit them, announce it. */
    void spin(){
        candidates.clear();
        for(Player p : Groups.player){
            if(p != null && !p.dead() && p.unit() != null) candidates.add(p);
        }
        if(candidates.isEmpty()) return;

        Player target = candidates.random();
        Unit u = target.unit();
        String who = target.name;

        switch(Mathf.random(4)){
            case 0 -> {
                u.vel.add(Tmp.v1.rnd(Mathf.random(8f, 12f)));
                funmode.core.Vfx.at(Fx.smeltsmoke, u.x, u.y + u.hitSize * 0.4f);
                announce("fun.roulette.fling", "[accent]🎲 The wheel lands on {0}[accent] - a violent sneeze sends them flying![]", who);
            }
            case 1 -> {
                u.apply(StatusEffects.disarmed, DISARM_TICKS);
                funmode.core.Vfx.at(Fx.smeltsmoke, u.x, u.y);
                announce("fun.roulette.jam", "[accent]🎲 The wheel lands on {0}[accent] - weapons JAMMED![]", who);
            }
            case 2 -> {
                u.apply(StatusEffects.unmoving, FREEZE_TICKS);
                funmode.core.Vfx.at(Fx.freezing, u.x, u.y);
                announce("fun.roulette.freeze", "[accent]🎲 The wheel lands on {0}[accent] - frozen stiff![]", who);
            }
            case 3 -> {
                u.apply(StatusEffects.sapped, DIZZY_TICKS);
                u.apply(StatusEffects.sporeSlowed, DIZZY_TICKS);
                funmode.core.Vfx.at(Fx.smeltsmoke, u.x, u.y);
                announce("fun.roulette.dizzy", "[accent]🎲 The wheel lands on {0}[accent] - a dizzy spell![]", who);
            }
            default -> {
                u.apply(StatusEffects.overclock, LUCKY_TICKS);
                funmode.core.Vfx.at(Fx.overdriven, u.x, u.y);
                announce("fun.roulette.lucky", "[lime]🎲 The wheel lands on {0}[lime] - LUCKY! A surge of speed![]", who);
            }
        }
    }

    /** Chat line, localized if the key resolves, else an inline fallback (never a raw ??? placeholder). */
    static void announce(String key, String fallback, Object... args){
        String out;
        if(Core.bundle.has(key)){
            out = Core.bundle.format(key, args);
        }else{
            out = fallback;
            for(int i = 0; i < args.length; i++) out = out.replace("{" + i + "}", String.valueOf(args[i]));
        }
        if(ui != null && ui.chatfrag != null) funmode.core.Chat.send(out);
    }

    @Override
    public void buildDebug(Table table){
        table.button("Рулетка: крутить", this::spin).size(220f, 50f);
    }
}
