package funmode.curses;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Time;
import funmode.core.Chaos;
import funmode.core.Curse;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Sounds;
import mindustry.graphics.Layer;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.blocks.storage.CoreBlock;

import static mindustry.Vars.content;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.ui;

/**
 * The chaos meter and everything that rides on it. A global chaos level creeps upward on its own and
 * cranks how often the mod's bad/annoying effects fire (via {@link Chaos#frequencyMult()}). It's shown
 * as a green→red bar over each Sacrifice Altar (with a cyan sliver = the calm reserve draining it down),
 * and it makes itself FELT: past a storm threshold
 * the view shakes, an ominous howl rises, and at the very top "chaos overloads" periodically break a
 * random building on your base. Feed the altar to keep it in check. This curse only drives the others,
 * so it works anywhere.
 */
public class ChaosCurse implements Curse{
    /** Untended, chaos climbs from 0 to full in roughly 12 minutes. */
    static final float RISE_PER_TICK = 1f / (12f * 60f * 60f);
    /** Researched "Chaos Dampener" slows how fast the chaos bar creeps up. */
    static final float DAMPEN_MULT = 0.5f;
    Block dampenerTech;

    /** Above this the world shakes, the edges pulse, the howl plays, and a siren fires once on entry. */
    static final float STORM_LEVEL = 0.72f;
    /** At/above this, chaos periodically overloads and breaks one of your buildings. */
    static final float MAX_LEVEL = 0.97f;
    static final float STORM_SHAKE_MAX = 3.5f;
    static final float DISASTER_INTERVAL = 60f * 4.5f;

    Block altarBlock;
    float prevLevel = Chaos.START_LEVEL;
    float disasterTimer = 0f;
    float humTimer = 0f;
    /** Chaos is held at half until your team actually has an altar to feed - rescanned periodically. */
    float altarScanTimer = 0f;
    boolean teamHasAltar = false;
    final Seq<Building> disasterBuf = new Seq<>();

    @Override
    public String id(){
        return "chaos";
    }

    @Override
    public String titleKey(){
        return "fun.curse.chaos.title";
    }

    @Override
    public void loadContent(){
        //tied to the phase weaver - phase fabric bends reality, the right tool to damp the madness
        dampenerTech = funmode.core.FunTech.tech("chaos-dampener",
            ItemStack.with(Items.silicon, 200, Items.titanium, 140, Items.thorium, 100, Items.phaseFabric, 40),
            mindustry.content.Blocks.phaseWeaver);
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(mindustry.content.Blocks.phaseWeaver, "fun.lore.chaos-dampener");
        }

        altarBlock = content.block("sonka-fun-mode-sacrifice-altar");
        Events.on(WorldLoadEvent.class, e -> {
            Chaos.reset();
            prevLevel = Chaos.START_LEVEL;
            disasterTimer = 0f;
            humTimer = 0f;
            altarScanTimer = 0f;
            teamHasAltar = false;
        });
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.preDraw, this::shake);
        Events.run(Trigger.draw, this::draw);
    }

    void update(){
        Chaos.active = isEnabled() && state.isGame();
        if(!Chaos.active || state.isPaused()) return;

        //researched Chaos Dampener slows the creep (campaign only; always full-speed in custom/sandbox)
        float rise = RISE_PER_TICK * (dampenerTech != null && dampenerTech.unlockedNow() ? DAMPEN_MULT : 1f);
        Chaos.tick(rise, Time.delta);
        //mercy: while your team has NO altar built, chaos can't climb past halfway - there's nothing to
        //push it back down yet, so it mustn't run away before you can get one going. Only applies when
        //the altar curse is on at all (altarBlock != null); rescanned every ~half second.
        altarScanTimer += Time.delta;
        if(altarScanTimer >= 30f){ altarScanTimer = 0f; teamHasAltar = scanForAltar(); }
        if(altarBlock != null && !teamHasAltar && Chaos.level > 0.5f) Chaos.level = 0.5f;
        float lvl = Chaos.level;

        //siren, once, as it tips into storm territory
        if(prevLevel <= STORM_LEVEL && lvl > STORM_LEVEL){
            Sounds.acceleratorCharge.play(0.7f, 1f, 0f);
            announce("fun.chaos.storm", "[orange]The chaos swells - brace yourself.[]");
        }
        prevLevel = lvl;

        //a rising ominous howl - more often and louder the worse it gets
        if(lvl > 0.45f){
            humTimer -= Time.delta;
            if(humTimer <= 0f){
                Sounds.windHowl.play(0.12f + 0.5f * lvl, 0.6f + 0.3f * lvl, 0f);
                humTimer = Mathf.lerp(120f, 30f, lvl);
            }
        }

        //at the very top, chaos starts breaking your base
        if(lvl >= MAX_LEVEL){
            disasterTimer -= Time.delta;
            if(disasterTimer <= 0f){ disaster(); disasterTimer = DISASTER_INTERVAL; }
        }else{
            disasterTimer = DISASTER_INTERVAL; //full grace after it drops back down
        }
    }

    /** Does the player's team have a Sacrifice Altar standing? (What lets chaos climb past half.) */
    boolean scanForAltar(){
        if(altarBlock == null || player == null) return false;
        for(Building b : Groups.build){
            if(b.block == altarBlock && b.team == player.team()) return true;
        }
        return false;
    }

    /** Chaos overload: something on your base gives way. */
    void disaster(){
        disasterBuf.clear();
        for(Building b : Groups.build){
            if(player != null && b.team == player.team() && !(b.block instanceof CoreBlock)) disasterBuf.add(b);
        }
        if(disasterBuf.isEmpty()) return;
        Building victim = disasterBuf.random();
        victim.damage(victim.maxHealth * 0.18f);
        Fx.blastExplosion.at(victim.x, victim.y);
        Sounds.explosionReactor.at(victim.x, victim.y);
        announce("fun.chaos.overload", "[scarlet]CHAOS OVERLOAD - something in your base gives way![]");
    }

    /** Unstoppable tremor above the storm threshold, worsening toward max (ignores the shake setting). */
    void shake(){
        if(!Chaos.active || !state.isGame() || Chaos.level <= STORM_LEVEL) return;
        float f = (Chaos.level - STORM_LEVEL) / (1f - STORM_LEVEL);
        float mag = STORM_SHAKE_MAX * f * f;
        Core.camera.position.add(Mathf.range(mag), Mathf.range(mag));
    }

    void draw(){
        if(!Chaos.active) return;
        drawAltars();
    }

    /** Green→red chaos bar over each altar, plus a cyan sliver for the calm reserve stacked on top. */
    void drawAltars(){
        if(altarBlock == null) return;
        Draw.z(Layer.overlayUI);
        float level = Chaos.level;
        float calm = Math.min(1f, Chaos.calm);
        for(Building b : Groups.build){
            if(b.block != altarBlock) continue;

            float bw = b.block.size * tilesize + 6f, bh = 4f;
            float bx = b.x - bw / 2f;
            float by = b.y + b.block.size * tilesize / 2f + 5f;

            Draw.color(Color.black, 0.6f);
            Fill.crect(bx, by, bw, bh);
            Draw.color(Color.lime.cpy().lerp(Color.scarlet, level), 0.95f);
            Fill.crect(bx, by, bw * level, bh);
            //the calm reserve you've banked - a cyan sliver that will drag chaos down over the next seconds
            if(calm > 0.01f){
                Draw.color(Color.cyan, 0.9f);
                Fill.crect(bx, by + bh, bw * calm, 1.4f);
            }
            Draw.color(Color.white, 0.5f);
            Lines.stroke(0.8f);
            Lines.rect(bx, by, bw, bh);
        }
        Draw.reset();
    }

    /** Chat line, localized if the key resolves, else an inline fallback (never a raw ??? placeholder). */
    static void announce(String key, String fallback){
        String out = Core.bundle.has(key) ? Core.bundle.get(key) : fallback;
        if(ui != null && ui.chatfrag != null) funmode.core.Chat.send(out);
    }
}
