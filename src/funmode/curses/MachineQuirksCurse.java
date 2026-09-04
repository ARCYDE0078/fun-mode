package funmode.curses;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.Angles;
import arc.math.Mathf;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.content.Liquids;
import mindustry.content.StatusEffects;
import mindustry.entities.Effect;
import mindustry.entities.Units;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.graphics.Layer;
import mindustry.world.Block;
import mindustry.world.blocks.power.LightBlock;
import mindustry.world.consumers.ConsumeLiquid;

import static mindustry.Vars.content;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;

/**
 * Assorted Serpulo machine quirks under one toggle (per-block gags from the catalog): impact reactors
 * shake the ground around them (unstoppably, harder the closer you get), RTG generators irradiate units,
 * thermal generators cook themselves without coolant, water pumps occasionally drown, and plastanium
 * compressors belch billowing black smoke while running, darkening the screen by proximity the same
 * way the coal centrifuge does. Host/SP only (the smoke darkening itself runs on every client, same
 * as the coal centrifuge's - see {@link #drawSmoke}).
 */
public class MachineQuirksCurse implements Curse{
    static final float RUN_INTERVAL = 10f;

    //impact reactor shake - now PROXIMITY based: calm from afar, the tremor swells the closer the
    //camera drifts to a running reactor (and it's weaker overall than the old always-on quake)
    static final float SHAKE_RADIUS = 30f * tilesize; //past this, no tremor at all
    static final float SHAKE_MAX_PER = 3.2f; //point-blank shake from a single reactor
    static final float SHAKE_CAP = 9f; //a whole reactor farm still can't nauseate you

    //rtg radiation
    static final float RTG_RADIUS = 5f * tilesize;
    static final float RTG_DPS = 9f;

    //thermal overheat
    static final float THERMAL_DPS = 5f;

    //plastanium compressor smoke - same "billows and darkens the screen" gag as the coal centrifuge
    //(FactoryReworksCurse.coalCloud/COAL_DARK_*), independently calibrated here since this curse's
    //update() only runs once per RUN_INTERVAL (10 ticks) rather than every frame. Unlike the coal
    //centrifuge, both the puffing AND the darkening are gated on the PLAYER's own position (not the
    //camera) so a compressor farm off-screen/far away neither puffs nor dims anything, and darkness
    //is the MAX of any single nearby compressor rather than a sum across the whole farm.
    static final float PLAST_SMOKE_CHANCE = 0.5f; //per RUN_INTERVAL scan while running, in range
    static final float PLAST_DARK_RANGE = 40f * tilesize;
    static final float PLAST_DARK_MAX = 0.5f; //darkness from a single compressor right on top of you

    //pump drown - calibrated so one pump drowns on average every ~5 min (once every ~3 min on deep
    //water). The roll runs every ~10 ticks (≈6 rolls/game-second), so 5 min ≈ 1800 rolls → p≈1/1800;
    //here p = DROWN_CHANCE * step(≈10), hence 1/18000. Deep-water ×5/3 turns 5 min into ~3 min.
    static final double DROWN_CHANCE = 1.0 / 18000.0;
    static final double DEEP_WATER_MULT = 5.0 / 3.0;

    //solar vs light: no illuminators = panels run at a LOSS (drain power); a normal illuminator lifts
    //them a little, the mod's MEGA illuminator lifts them enormously
    static final float SOLAR_BASE = -0.5f;
    static final float SOLAR_PER_LIGHT = 0.4f;
    static final float SOLAR_PER_MEGA = 14f;
    static final float SOLAR_MIN = -1f, SOLAR_MAX = 30f;

    /** A big, billowing black smoke cloud rising off a running plastanium compressor. */
    static final Effect plastSmoke = new Effect(150f, 150f, e -> {
        Draw.color(Color.valueOf("35422f"), Color.black, e.fin());
        Draw.alpha(0.85f * e.fout());
        Angles.randLenVectors(e.id, 7, 4f + e.fin() * 13f, (x, y) ->
            Fill.circle(e.x + x, e.y + y + e.fin() * 42f, 2.5f + e.fin() * 7.5f));
    });

    final arc.struct.Seq<Building> activeReactors = new arc.struct.Seq<>();
    boolean solarModified = false;
    /** Up while this curse is actively writing state.rules.solarMultiplier - unit-reworks' eclipse
     * logic checks it and stays off the field, feeding its drain through the formula here instead. */
    public static boolean drivingSolar = false;
    Block megaIlluminator;
    final arc.struct.Seq<Building> drowned = new arc.struct.Seq<>();
    float runTimer = 0f;

    @Override
    public String id(){
        return "machine-quirks";
    }

    @Override
    public String titleKey(){
        return "fun.curse.machine-quirks.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void loadContent(){
        //let thermal generators take water/cryo as an optional coolant (vanilla ones hold no liquid,
        //which is why "feed it water" did nothing) - now the player can pipe water in to keep it cool
        Blocks.thermalGenerator.hasLiquids = true;
        Blocks.thermalGenerator.liquidCapacity = 30f;
        Blocks.thermalGenerator.consume(new ConsumeLiquid(Liquids.water, 0.1f).boost());
        //drawSmoke reads b.efficiency directly (like FactoryReworksCurse does for coalCentrifuge),
        //so clients need it synced too, not just the host
        Blocks.plastaniumCompressor.sync = true;
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(Blocks.impactReactor, "fun.lore.impact-reactor");
            funmode.core.Lore.details(Blocks.rtgGenerator, "fun.lore.rtg-generator");
            funmode.core.Lore.details(Blocks.thermalGenerator, "fun.lore.thermal-generator");
            funmode.core.Lore.details(Blocks.mechanicalPump, "fun.lore.pump-drown");
            funmode.core.Lore.details(Blocks.rotaryPump, "fun.lore.pump-drown");
            funmode.core.Lore.details(Blocks.impulsePump, "fun.lore.pump-drown");
            funmode.core.Lore.details(Blocks.plastaniumCompressor, "fun.lore.plastanium-smoke");
        }
        //resolved at client load; null if the mega-illuminator curse is disabled (its block won't exist)
        megaIlluminator = content.block("sonka-fun-mode-mega-illuminator");

        Events.on(WorldLoadEvent.class, e -> restoreSolar());
        Events.run(Trigger.update, this::update);
        //unstoppable shake: offset the camera directly, ignoring the game's screen-shake setting
        Events.run(Trigger.preDraw, this::shake);
        Events.run(Trigger.draw, this::drawSmoke);
    }

    void restoreSolar(){
        drivingSolar = false;
        if(solarModified && state.rules != null){
            state.rules.solarMultiplier = 1f;
            solarModified = false;
        }
    }

    boolean pump(Building b){
        return b.block == Blocks.mechanicalPump || b.block == Blocks.rotaryPump || b.block == Blocks.impulsePump;
    }

    boolean onDeepWater(Building b){
        return b.tile != null && b.tile.floor() != null && b.tile.floor().isDeep();
    }

    boolean hasCoolant(Building b){
        return b.liquids != null && (b.liquids.get(Liquids.water) > 0.1f || b.liquids.get(Liquids.cryofluid) > 0.1f);
    }

    void update(){
        //restore vanilla solar if the curse was switched off after we'd meddled
        if(!isEnabled()){ restoreSolar(); return; }
        if(!isActive() || state.isPaused()) return;

        runTimer += Time.delta;
        if(runTimer < RUN_INTERVAL) return;
        float step = runTimer;
        runTimer = 0f;

        float solarWeight = 0f;
        drowned.clear();
        activeReactors.clear();
        for(Building b : Groups.build){
            if(b.block == Blocks.impactReactor){
                if(b.efficiency > 0.4f) activeReactors.add(b);
            }else if(b.block instanceof LightBlock && b.efficiency > 0.2f){
                solarWeight += (b.block == megaIlluminator ? SOLAR_PER_MEGA : SOLAR_PER_LIGHT);
            }else if(b.block == Blocks.rtgGenerator && b.efficiency > 0.2f){
                irradiate(b, step);
            }else if(b.block == Blocks.thermalGenerator && !hasCoolant(b) && !funmode.core.Coolant.coversFire(b.x, b.y)){
                //cooks itself ONLY if it has no water of its own AND no water/cryo turret dousing it - so
                //piping water straight in keeps it running regardless of any turret nearby
                b.damage(THERMAL_DPS / 60f * step);
                if(Mathf.chanceDelta(0.05f)) funmode.core.Vfx.at(Fx.fire, b.x + Mathf.range(6f), b.y + Mathf.range(6f));
            }else if(b.block == Blocks.plastaniumCompressor && b.efficiency > 0.5f){
                if(player != null && b.within(player.x, player.y, PLAST_DARK_RANGE) && Mathf.chance(PLAST_SMOKE_CHANCE)){
                    plastSmoke.at(b.x + Mathf.range(5f), b.y + Mathf.range(4f));
                }
            }else if(pump(b)){
                double chance = DROWN_CHANCE * step * (onDeepWater(b) ? DEEP_WATER_MULT : 1.0) * funmode.core.Chaos.frequencyMult();
                if(Mathf.chance(chance)) drowned.add(b); //kill AFTER the loop - killing mid-iteration breaks it
            }
        }
        for(Building b : drowned){
            funmode.core.Vfx.at(Fx.bubble, b.x, b.y);
            if(player != null && b.team == player.team()){
                funmode.core.Chat.send(Core.bundle.get("fun.machine.drowned", "A pump has drowned."));
            }
            b.kill();
        }

        //solar output rides on how much light is around: none → negative (drains), a normal illuminator
        //nudges it up, the mega illuminator rockets it. Live eclipses (unit-reworks curse) blot a
        //chunk back out - subtracted HERE so the two curses never fight over the same field: while
        //this flag is up, unit-reworks leaves solarMultiplier entirely to us
        if(state.rules != null){
            state.rules.solarMultiplier = Mathf.clamp(SOLAR_BASE + solarWeight - UnitReworksCurse.eclipseSolarDrain(), SOLAR_MIN, SOLAR_MAX);
            solarModified = true;
            drivingSolar = true;
        }
    }

    void irradiate(Building b, float step){
        Units.nearby(b.x - RTG_RADIUS, b.y - RTG_RADIUS, RTG_RADIUS * 2f, RTG_RADIUS * 2f, u -> {
            if(!u.within(b.x, b.y, RTG_RADIUS)) return;
            u.damage(RTG_DPS / 60f * step);
            u.apply(StatusEffects.sapped, 90f);
        });
    }

    //Runs on EVERY machine (not isActive-gated): a running impact reactor is synced (Groups.build +
    //efficiency), so clients feel the quake too - derive the reactor set here rather than from the
    //host-only `activeReactors`.
    void shake(){
        if(!isEnabled() || !state.isGame() || state.isPaused() || Core.camera == null) return;
        float cx = Core.camera.position.x, cy = Core.camera.position.y;
        float mag = 0f;
        for(Building b : Groups.build){
            if(b.block != Blocks.impactReactor || b.efficiency <= 0.4f) continue;
            float d = Mathf.dst(cx, cy, b.x, b.y);
            if(d >= SHAKE_RADIUS) continue;
            float frac = (SHAKE_RADIUS - d) / SHAKE_RADIUS; //1 on top of it, 0 at the edge
            mag += SHAKE_MAX_PER * frac * frac; //squared falloff - the quake hugs the reactor
        }
        if(mag <= 0.01f) return;
        mag = Math.min(SHAKE_CAP, mag);
        Core.camera.position.add(Mathf.range(mag), Mathf.range(mag));
    }

    //Runs on EVERY machine (not isActive-gated): plastaniumCompressor.sync = true (set in loadContent)
    //means b.efficiency reads the same on clients as on host, so the darkening matches for everyone -
    //same pattern as FactoryReworksCurse's coalCentrifuge darkening, but keyed off the PLAYER's own
    //position (not the camera) and MAX'd rather than summed, so a whole compressor farm doesn't blow
    //the screen to full black and a farm far from the player doesn't dim anything at all.
    void drawSmoke(){
        if(!isEnabled() || !state.isGame() || player == null || Core.camera == null) return;
        float darkness = 0f;
        for(Building b : Groups.build){
            if(b.block != Blocks.plastaniumCompressor || b.efficiency <= 0.5f) continue;
            float d = player.dst(b.x, b.y);
            if(d >= PLAST_DARK_RANGE) continue;
            darkness = Math.max(darkness, (1f - d / PLAST_DARK_RANGE) * PLAST_DARK_MAX);
        }
        if(darkness <= 0.01f) return;
        Draw.z(Layer.overlayUI - 1f);
        Draw.color(Color.black, darkness);
        float w = Core.camera.width, h = Core.camera.height;
        Fill.crect(Core.camera.position.x - w / 2f, Core.camera.position.y - h / 2f, w, h);
        Draw.reset();
    }
}
