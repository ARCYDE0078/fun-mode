package funmode.curses;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.content.StatusEffects;
import mindustry.content.Weathers;
import mindustry.entities.Damage;
import mindustry.entities.Effect;
import mindustry.entities.Lightning;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Sounds;
import mindustry.gen.Unit;
import mindustry.gen.WeatherState;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.ItemStack;
import mindustry.type.weather.ParticleWeather;
import mindustry.type.weather.RainWeather;
import mindustry.world.Block;
import mindustry.world.blocks.defense.ForceProjector;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.units.Reconstructor;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.meta.Attribute;

import static mindustry.Vars.net;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.ui;
import static mindustry.Vars.world;

/**
 * Custom weather that actually bites, in two flavours:
 * <p>
 * ESCALATIONS ride on the map's OWN vanilla weather and worsen it into a nastier custom form. While a
 * vanilla weather falls (and no random disaster of ours is running) it rolls, on a timer, to grow:
 * <ul>
 *   <li>RAIN → DOWNPOUR (droplets multiply; power nodes short out) → THUNDERSTORM (lightning lashes
 *       units + buildings).</li>
 *   <li>SNOW → BLIZZARD - the snow thickens and whips SIDEWAYS; every unit FREEZES and every block
 *       seizes up unless a nearby building throws off heat (holds slag/oil or a flammable fuel).</li>
 *   <li>SANDSTORM → SAND STORM - the vanilla sandstorm (which does nothing on its own) thickens into
 *       a blinding wall; units bog down (SLOW) and visibility drops to almost nothing.</li>
 *   <li>SPORESTORM → SPORE STORM - far denser spores lay EXHAUSTION (sapped) + SPORE-SLOW on every
 *       unit in the open.</li>
 * </ul>
 * The escalation just mutates the vanilla weather's own fields (density / wind), so it's literally the
 * same storm growing worse; everything is restored when the vanilla weather passes.
 * <p>
 * RANDOM DISASTERS appear on their own clock, one at a time, independent of any vanilla weather:
 * <ul>
 *   <li>ACID RAIN (global) - a GREEN corrosive downpour; corrodes units and eats buildings not under
 *       a same-team FORCE PROJECTOR. Odds climb with your factory count.</li>
 *   <li>ELECTRIC STORM (global) - no rain, just a relentless LIGHTNING barrage under a dark sky.</li>
 *   <li>SOLAR FLARE (global) - blinding radiation pulses that BURN every unit not under a projector.</li>
 *   <li>HEAT WAVE (anywhere but snow) - a sweltering scorch; units overheat and catch fire in the open.</li>
 * </ul>
 * Host/SP only. The custom disaster weathers (acid, electric) are registered in loadContent, so
 * toggling this curse takes effect next launch.
 */
public class CursedWeatherCurse implements Curse{
    //random disaster state (one at a time); escalations are tracked separately, riding vanilla weather
    static final int NONE = 0, ACID = 1, ELECTRIC = 2, SOLAR = 3, HEAT = 4;
    //rain escalation stages
    static final int LIGHT = 0, DOWNPOUR = 1, STORM = 2;

    static final float ESCALATE_INTERVAL = 60f * 35f; //how often a vanilla weather rolls to worsen
    static final float ESCALATE_CHANCE = 0.5f;
    //× MORE droplets/particles. NOTE: particle count is area/density, so density is INVERSE - to get
    //more we DIVIDE the base density by these.
    static final float DOWNPOUR_DROPS = 3f, STORM_DROPS = 5f;
    static final float BLIZZARD_DROPS = 2f, SAND_DROPS = 2f, SPORE_DROPS = 2.4f;
    //blizzard drives the snow SIDEWAYS (vanilla snow falls straight down at xspeed 0.25 / yspeed -2)
    static final float BLIZZARD_XSPEED = 7f, BLIZZARD_YSPEED = -1f;

    //downpour: power nodes short out - a surge that speeds their neighbours, then the node burns out
    static final float POWER_SHORT_CHANCE = 0.12f; //per node per scan
    static final int POWER_MAX_BREAK_PER_SCAN = 2;  //a VISIBLE trickle, not an instant grid-wipe

    //thunderstorm: telegraphed bolts lash random units/buildings (team-neutral, so they hit everyone)
    static final int STORM_MAX_STRIKES = 4;
    static final float STORM_STRIKE_CHANCE = 0.02f;
    static final float LIGHTNING_DAMAGE = 60f;
    static final float CHARGE_TIME = 45f;      //telegraph glow before a bolt lands (~0.75s)
    static final float STRIKE_HEIGHT = 130f;   //the bolt drops from this high above its target
    static final int STRIKE_LENGTH = 34;       //long jagged bolt
    static final float STRIKE_SPLASH = 24f;    //impact damage radius (ensures the marked spot is hit)

    //electric storm: a constant lightning barrage on its own faster clock
    static final float ELECTRIC_INTERVAL = 48f;      //queue a fresh volley this often (~0.8s)
    static final int ELECTRIC_VOLLEY = 3;            //targeted bolts per volley (units/buildings)
    static final int ELECTRIC_SPECTACLE = 1;         //extra bolts flung into open view for spectacle

    static final float DECISION_INTERVAL = 60f * 25f; //how often the sky rolls for a random disaster
    static final float QUIET_AFTER = 60f * 120f;      //guaranteed calm after a disaster ends, so they don't chain
    static final float SCAN_INTERVAL = 30f;           //how often an active hazard applies its bite
    //a disaster lasts several minutes (randomised each time)
    static final float DURATION_MIN = 60f * 150f, DURATION_MAX = 60f * 240f;

    //acid rain: chance = base + per-factory, capped; scales with how much industry you run
    static final float ACID_BASE = 0.05f, ACID_PER_FACTORY = 0.015f, ACID_MAX = 0.6f;
    static final float ACID_BUILD_DPS = 8f;           //buildings can't be statused, so they take flat acid damage
    static final float CORRODE_TICKS = 60f;           //corrosion re-applied to exposed units each scan (its own DoT does the harm)

    //blizzard bite (runs while the snow is escalated)
    static final float HEAT_RANGE = 8f * tilesize;    //warmth reach of a slag/flammable building
    static final float FROZEN_SLOW = 0.2f;            //frozen buildings crawl at this fraction speed
    static final float FREEZE_TICKS = SCAN_INTERVAL * 3.5f;
    //sand storm bite
    static final float SAND_TICKS = SCAN_INTERVAL * 2.5f;
    //spore storm bite
    static final float SPORE_TICKS = SCAN_INTERVAL * 2.5f;

    //random global/desert disasters
    static final float ELECTRIC_CHANCE = 0.08f;
    static final float SOLAR_CHANCE = 0.05f;
    static final float SOLAR_PULSE_PERIOD = 60f * 4.5f; //a blinding flare washes the map this often
    static final float SOLAR_BURN_DAMAGE = 40f;         //per-pulse radiation damage to exposed units
    static final float SOLAR_BURN_TICKS = 60f * 3f;
    static final float HEAT_CHANCE = 0.15f;
    static final float HEAT_BURN_TICKS = SCAN_INTERVAL * 2.2f; //units in the open overheat and catch fire

    /** Green sizzle where the acid eats something. */
    static final Effect acidFx = new Effect(24f, e -> {
        Draw.color(Color.valueOf("9dff5e"), 0.75f * e.fout());
        Fill.circle(e.x, e.y, 2.4f * e.fout());
    });

    /** A power node shorting: crackling arcs + a bright flash so it's unmistakable. */
    static final Effect shortSpark = new Effect(30f, 90f, e -> {
        Draw.color(Pal.lancerLaser, Color.white, e.fin());
        Lines.stroke(2f * e.fout());
        for(int i = 0; i < 5; i++){
            float ang = i * 72f + e.id * 30f;
            float len = 6f + e.finpow() * 22f;
            Lines.lineAngle(e.x, e.y, ang, len);
            Lines.lineAngle(e.x + Angles.trnsx(ang, len), e.y + Angles.trnsy(ang, len), ang + Mathf.randomSeed(e.id + i, -60f, 60f), 6f * e.fout());
        }
        Draw.color(Color.white, e.fout());
        Fill.circle(e.x, e.y, 5f * e.fout());
        Drawf.light(e.x, e.y, 40f * e.fout(), Pal.lancerLaser, 0.9f);
    });

    /** Blinding flash at a lightning impact. */
    static final Effect lightningFlash = new Effect(26f, 120f, e -> {
        Draw.color(Color.white, Pal.lancerLaser, e.fin());
        Lines.stroke(3.5f * e.fout());
        Lines.circle(e.x, e.y, 6f + e.finpow() * 46f);
        Draw.color(Color.white, e.fout());
        Fill.circle(e.x, e.y, 9f * e.fout());
        Drawf.light(e.x, e.y, 90f * e.fout(), Color.white, 0.95f);
    });

    /** A sun-hot scorch mark where the solar flare cooks a unit. */
    static final Effect solarBurnFx = new Effect(30f, e -> {
        Draw.color(Color.valueOf("fff2a8"), Color.valueOf("ff7a1c"), e.fin());
        Lines.stroke(1.5f * e.fout());
        Lines.circle(e.x, e.y, 3f + e.finpow() * 14f);
        Draw.color(Color.valueOf("ffd15a"), e.fout());
        Fill.circle(e.x, e.y, 3f * e.fout());
    });

    /** The pre-strike charge-up telegraph, as a self-contained networked effect (fired once per scheduled
     * bolt) so EVERY player sees where lightning is about to land - the old per-frame draw was host-only.
     * Its lifetime = {@link #CHARGE_TIME}, so it grows in lockstep with the countdown to the strike. */
    static final Effect strikeTelegraph = new Effect(CHARGE_TIME, 400f, e -> {
        float f = e.fin(); //0 → 1 as it charges
        Draw.color(Pal.lancerLaser, 0.25f + 0.5f * f);
        Lines.stroke(1f + 2.5f * f);
        Lines.line(e.x, e.y + STRIKE_HEIGHT, e.x, e.y + STRIKE_HEIGHT * (1f - f)); //beam creeps down
        float pulse = Mathf.absin(Time.time, Mathf.lerp(7f, 2f, f), 1f);
        Draw.color(Color.white, Pal.lancerLaser, 0.5f);
        Fill.circle(e.x, e.y, (1.5f + 5f * f) * (0.6f + 0.4f * pulse));
        Draw.color(Pal.lancerLaser, 0.6f * f);
        Lines.circle(e.x, e.y, 4f + 10f * f);
        Drawf.light(e.x, e.y, 22f * f, Pal.lancerLaser, 0.6f);
    });

    //--- random disaster state ---
    int weather = NONE;
    float weatherTimer, scanTimer, decisionTimer;
    float solarPulseTimer, solarFlash, electricTimer;
    boolean snowSector;
    //each random disaster only strikes on the campaign sector where its lore introduces it (so you MEET
    //it where it's described, and early sectors like ground zero stay calm). Custom games allow them all.
    boolean allowAcid = true, allowElectric = true, allowSolar = true, allowHeat = true;
    /** Custom disaster weathers, registered in loadContent: GREEN acid downpour + dark electric storm. */
    RainWeather acidRain;
    ParticleWeather electricStorm;
    /** Invisible MARKER weathers (0 particles) for HEAT & SOLAR - they have no storm entity of their own,
     * so these synced states let clients tell which disaster is running and paint the matching screen tint. */
    ParticleWeather heatMarker, solarMarker;
    /** Research gate: unlocking "Weatherproofing" softens the damage every cursed weather deals. */
    Block weatherTech;
    static final float WEATHERPROOF_MULT = 0.5f;
    /** The live disaster weather instance we spawned (acid/electric), removed when it ends. */
    WeatherState activeState;

    //--- vanilla-weather escalation state (independent of the disaster above) ---
    int rainStage = LIGHT;
    boolean blizzardOn, sandStormOn, sporeStormOn;
    float escalateTimer, rainScanTimer;
    float snowEscalateTimer, snowScanTimer;
    float sandEscalateTimer, sandScanTimer;
    float sporeEscalateTimer, sporeScanTimer;
    //captured vanilla base fields so escalation can scale them and put them back
    float rainBaseDensity = -1f;
    float snowBaseDensity = -1f, snowBaseXspeed = 0.25f, snowBaseYspeed = -2f;
    float sandBaseDensity = -1f, sporeBaseDensity = -1f;

    final Seq<float[]> shields = new Seq<>(); //{x, y, radius, teamId}
    final Seq<float[]> heat = new Seq<>();     //{x, y} of heat-source buildings
    final Seq<Building> breakBuf = new Seq<>(); //shorted power nodes, killed after the scan loop
    final Seq<float[]> pending = new Seq<>();   //telegraphed lightning strikes {x, y, remainingChargeTicks}

    @Override
    public void loadContent(){
        //GREEN acid rain (vanilla rain is blue) - RainWeather.color tints the droplets
        acidRain = new RainWeather("acid-rain"){{
            color = Color.valueOf("6cff3aff");
            sound = Sounds.rain;
            soundVol = 0.25f;
        }};
        //electric storm: a dark, fast bank of storm debris; the sky darkens (attrs.light) as it blows
        electricStorm = new ParticleWeather("electric-storm"){{
            color = Color.valueOf("2b3040ff");
            particleRegion = "particle";
            sizeMin = 2f;
            sizeMax = 11f;
            density = 1300f;
            xspeed = 6f;
            yspeed = -3f;
            attrs.set(Attribute.light, -0.25f);
            sound = Sounds.wind;
            soundVol = 0.6f;
        }};
        //invisible marker weathers (no particles, no sound) - HEAT and SOLAR draw no storm of their own,
        //so these synced states are how clients learn which disaster is live (see deriveWeather + draw)
        //drawParticles=false, not density=0f: ParticleWeather.drawParticles divides area BY density
        //(total = area/density), so a zero density is +Infinity particles - a frame-freezing loop the
        //instant this marker goes live, not the "no particles" it looks like at a glance.
        heatMarker = new ParticleWeather("cursed-heat-marker"){{ drawParticles = false; particleRegion = "particle"; }};
        solarMarker = new ParticleWeather("cursed-solar-marker"){{ drawParticles = false; particleRegion = "particle"; }};
        //remember the map's own vanilla weather fields so escalation can scale them and restore them
        if(Weathers.rain instanceof RainWeather rw) rainBaseDensity = rw.density;
        if(Weathers.snow instanceof ParticleWeather sw){ snowBaseDensity = sw.density; snowBaseXspeed = sw.xspeed; snowBaseYspeed = sw.yspeed; }
        if(Weathers.sandstorm instanceof ParticleWeather pw) sandBaseDensity = pw.density;
        if(Weathers.sporestorm instanceof ParticleWeather sp) sporeBaseDensity = sp.density;

        //research gate: "Weatherproofing" softens every cursed weather's bite (campaign only)
        //tied to the force projector - the very shield that shelters you from acid and solar flares
        weatherTech = funmode.core.FunTech.tech("weatherproofing",
            ItemStack.with(Items.titanium, 180, Items.silicon, 150, Items.metaglass, 120, Items.surgeAlloy, 60),
            Blocks.forceProjector);
    }

    /** Damage multiplier for cursed weather - halved once Weatherproofing is researched. */
    float sev(){
        return weatherTech != null && weatherTech.unlockedNow() ? WEATHERPROOF_MULT : 1f;
    }

    @Override
    public String id(){
        return "cursed-weather";
    }

    @Override
    public String titleKey(){
        return "fun.curse.cursed-weather.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void init(){
        if(isEnabled()){
            //ONE signature sector per weather phenomenon, so each is introduced (and can be met) on its
            //own briefing. Escalations sit on the biome that actually carries their vanilla weather.
            funmode.core.Lore.description(mindustry.content.SectorPresets.frozenForest, "fun.lore.sector.frozen-forest");          //BLIZZARD (snow)
            funmode.core.Lore.description(mindustry.content.SectorPresets.saltFlats, "fun.lore.sector.salt-flats");               //SAND STORM (sandstorm)
            funmode.core.Lore.description(mindustry.content.SectorPresets.overgrowth, "fun.lore.sector.overgrowth");              //SPORE STORM (sporestorm)
            funmode.core.Lore.description(mindustry.content.SectorPresets.ruinousShores, "fun.lore.sector.ruinous-shores");       //DOWNPOUR (rain; earlier sector = milder stage)
            funmode.core.Lore.description(mindustry.content.SectorPresets.windsweptIslands, "fun.lore.sector.windswept-islands"); //THUNDERSTORM (rain; later sector = worse stage)
            funmode.core.Lore.description(mindustry.content.SectorPresets.tarFields, "fun.lore.sector.tar-fields");               //ACID RAIN (disaster)
            funmode.core.Lore.description(mindustry.content.SectorPresets.stainedMountains, "fun.lore.sector.stained-mountains"); //ELECTRIC STORM (disaster)
            funmode.core.Lore.description(mindustry.content.SectorPresets.nuclearComplex, "fun.lore.sector.nuclear-complex");     //SOLAR FLARE (disaster)
            funmode.core.Lore.description(mindustry.content.SectorPresets.desolateRift, "fun.lore.sector.desolate-rift");         //HEAT WAVE (disaster)
        }
        Events.on(WorldLoadEvent.class, e -> {
            weather = NONE;
            weatherTimer = scanTimer = decisionTimer = 0f;
            solarPulseTimer = solarFlash = electricTimer = 0f;
            activeState = null;
            resetEscalations();
            pending.clear();
            snowSector = state.rules != null && state.rules.weather.contains(w -> w.weather == Weathers.snow);
            //gate the random disasters to their signature campaign sectors (see the field comment)
            boolean campaign = state.rules != null && state.rules.sector != null;
            var preset = campaign ? state.rules.sector.preset : null;
            allowAcid = !campaign || preset == mindustry.content.SectorPresets.tarFields;
            allowElectric = !campaign || preset == mindustry.content.SectorPresets.stainedMountains;
            allowSolar = !campaign || preset == mindustry.content.SectorPresets.nuclearComplex;
            allowHeat = !campaign || preset == mindustry.content.SectorPresets.desolateRift;
        });
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.draw, this::draw);
        Events.run(Trigger.draw, this::drawFrost); //runs on EVERY machine, incl. clients
    }

    void update(){
        if(!isActive() || player == null || state.isPaused()) return;

        if(weather != NONE){
            weatherTimer -= Time.delta;
            scanTimer += Time.delta;
            if(scanTimer >= SCAN_INTERVAL){
                float step = scanTimer;
                scanTimer = 0f;
                if(weather == ACID) applyAcid(step);
                else if(weather == HEAT) applyHeat(step);
            }
            //solar flare + electric storm run on their own faster clocks (pulses / lightning volleys)
            if(weather == SOLAR){
                solarPulseTimer += Time.delta;
                if(solarPulseTimer >= SOLAR_PULSE_PERIOD){ solarPulseTimer = 0f; solarFlarePulse(); }
            }else if(weather == ELECTRIC){
                electricTimer += Time.delta;
                if(electricTimer >= ELECTRIC_INTERVAL){ electricTimer = 0f; electricStrikes(); }
            }
            if(weatherTimer <= 0f) endWeather();
        }else{
            decisionTimer += Time.delta;
            if(decisionTimer >= DECISION_INTERVAL){
                decisionTimer = 0f;
                rollEvent();
            }
        }

        if(solarFlash > 0f) solarFlash = Math.max(0f, solarFlash - Time.delta / 26f);

        //escalations ride the map's own vanilla weather, but only while NO random disaster is running
        boolean idle = weather == NONE;
        updateRain(idle);
        updateSnow(idle);
        updateSand(idle);
        updateSpore(idle);
        updatePending(); //resolve telegraphed lightning strikes (even as a storm winds down)
    }

    /** Rolls the sky for a random disaster (acid / electric / solar / heat). Each only strikes on its
     * signature campaign sector (all allowed in custom games). Never stacks on an escalation; acid +
     * heat are also barred from snow sectors. */
    void rollEvent(){
        if(escalationBusy()) return; //an escalating vanilla weather counts as active - don't pile on
        //acid odds climb with the size of your industry (but not amid the snow)
        float acidChance = Mathf.clamp(ACID_BASE + countFactories() * ACID_PER_FACTORY, 0f, ACID_MAX);
        if(allowAcid && !snowSector && Mathf.chance(acidChance)){ startWeather(ACID); return; }
        if(allowElectric && Mathf.chance(ELECTRIC_CHANCE)){ startWeather(ELECTRIC); return; }
        if(allowSolar && Mathf.chance(SOLAR_CHANCE)){ startWeather(SOLAR); return; }
        //heat waves strike anywhere but the snow (a scorch in a blizzard biome makes no sense)
        if(allowHeat && !snowSector && Mathf.chance(HEAT_CHANCE)){ startWeather(HEAT); }
    }

    /** True while any vanilla weather is mid-escalation (a downpour/storm, blizzard, sand or spore
     * storm). Used to keep exactly ONE hazard running at a time - the only allowed "layering" is a
     * weather's own gradation (rain → downpour → thunderstorm). */
    boolean escalationBusy(){
        return blizzardOn || sandStormOn || sporeStormOn || rainStage != LIGHT;
    }

    // ---------------------------------------------------------------------------------------------
    // Vanilla-weather escalations
    // ---------------------------------------------------------------------------------------------

    /** RAIN → DOWNPOUR (power nodes short) → THUNDERSTORM (lightning). Mutates the vanilla rain's own
     * droplet density; restored when the rain passes. */
    void updateRain(boolean idle){
        boolean active = idle && Weathers.rain.isActive();
        if(!active){
            if(rainStage != LIGHT){ rainStage = LIGHT; restoreRain(); announce("fun.weather.rain.end", "[lightgray]The storm passes.[]"); }
            escalateTimer = rainScanTimer = 0f;
            return;
        }
        RainWeather rw = Weathers.rain instanceof RainWeather r ? r : null;
        if(rw == null) return;
        if(rainStage < STORM){
            escalateTimer += Time.delta;
            if(escalateTimer >= ESCALATE_INTERVAL){
                escalateTimer = 0f;
                //continuing a downpour into a storm is gradation (always ok); STARTING one must not
                //stack on another escalation already running
                boolean canStart = rainStage > LIGHT || !escalationBusy();
                if(canStart && Mathf.chance(ESCALATE_CHANCE)) escalateRain(rw);
            }
        }
        if(rainStage >= DOWNPOUR){
            rainScanTimer += Time.delta;
            if(rainScanTimer >= SCAN_INTERVAL){
                rainScanTimer = 0f;
                powerShorts();
                if(rainStage >= STORM) lightningStrikes();
            }
        }
    }

    void escalateRain(RainWeather rw){
        rainStage++;
        if(rw != null && rainBaseDensity > 0f) rw.density = rainBaseDensity / (rainStage == DOWNPOUR ? DOWNPOUR_DROPS : STORM_DROPS);
        if(rainStage == DOWNPOUR){
            funmode.core.Vfx.sound(Sounds.windHowl, 0.5f, 1.1f);
            announce("fun.weather.downpour", "[cyan]The rain thickens into a DOWNPOUR - power nodes start shorting out![]");
        }else{
            funmode.core.Vfx.sound(Sounds.windHowl, 0.6f, 0.85f);
            announce("fun.weather.storm", "[yellow]A THUNDERSTORM breaks - lightning lashes the field![]");
        }
    }

    void restoreRain(){
        if(rainBaseDensity > 0f && Weathers.rain instanceof RainWeather rw) rw.density = rainBaseDensity;
    }

    /** SNOW → BLIZZARD: the snow thickens and whips sideways, freezing units + seizing buildings. */
    void updateSnow(boolean idle){
        boolean active = idle && Weathers.snow.isActive();
        if(!active){
            if(blizzardOn){ blizzardOn = false; restoreSnow(); announce("fun.weather.blizzard.end", "[lightgray]The blizzard blows over.[]"); }
            snowEscalateTimer = snowScanTimer = 0f;
            return;
        }
        if(!blizzardOn){
            snowEscalateTimer += Time.delta;
            if(snowEscalateTimer >= ESCALATE_INTERVAL){
                snowEscalateTimer = 0f;
                if(!escalationBusy() && Mathf.chance(ESCALATE_CHANCE)) escalateSnow();
            }
        }else{
            snowScanTimer += Time.delta;
            if(snowScanTimer >= SCAN_INTERVAL){ snowScanTimer = 0f; applyBlizzard(); }
        }
    }

    void escalateSnow(){
        blizzardOn = true;
        if(Weathers.snow instanceof ParticleWeather sw){
            if(snowBaseDensity > 0f) sw.density = snowBaseDensity / BLIZZARD_DROPS; //thicker snow
            sw.xspeed = BLIZZARD_XSPEED; //driven sideways
            sw.yspeed = BLIZZARD_YSPEED;
        }
        funmode.core.Vfx.sound(Sounds.windHowl, 0.6f, 0.4f);
        announce("fun.weather.blizzard.start", "[cyan]The snowfall whips up into a BLIZZARD - everything freezes without a heat source nearby.[]");
    }

    void restoreSnow(){
        if(Weathers.snow instanceof ParticleWeather sw){
            if(snowBaseDensity > 0f) sw.density = snowBaseDensity;
            sw.xspeed = snowBaseXspeed;
            sw.yspeed = snowBaseYspeed;
        }
    }

    /** SANDSTORM → SAND STORM: the vanilla sandstorm (harmless on its own) thickens into a blinding
     * wall; units bog down (slow), visibility drops (drawn as a thick haze). */
    void updateSand(boolean idle){
        boolean active = idle && Weathers.sandstorm.isActive();
        if(!active){
            if(sandStormOn){ sandStormOn = false; restoreSand(); announce("fun.weather.sandstorm.end", "[lightgray]The sandstorm settles.[]"); }
            sandEscalateTimer = sandScanTimer = 0f;
            return;
        }
        if(!sandStormOn){
            sandEscalateTimer += Time.delta;
            if(sandEscalateTimer >= ESCALATE_INTERVAL){
                sandEscalateTimer = 0f;
                if(!escalationBusy() && Mathf.chance(ESCALATE_CHANCE)) escalateSand();
            }
        }else{
            sandScanTimer += Time.delta;
            if(sandScanTimer >= SCAN_INTERVAL){ sandScanTimer = 0f; applySandstorm(); }
        }
    }

    void escalateSand(){
        sandStormOn = true;
        if(sandBaseDensity > 0f && Weathers.sandstorm instanceof ParticleWeather pw) pw.density = sandBaseDensity / SAND_DROPS;
        funmode.core.Vfx.sound(Sounds.wind, 0.8f, 1f);
        announce("fun.weather.sandstorm.start", "[orange]The sandstorm thickens into a SAND STORM - units bog down and can barely see.[]");
    }

    void restoreSand(){
        if(sandBaseDensity > 0f && Weathers.sandstorm instanceof ParticleWeather pw) pw.density = sandBaseDensity;
    }

    /** SPORESTORM → SPORE STORM: far denser spores lay EXHAUSTION (sapped) + SPORE-SLOW on units. */
    void updateSpore(boolean idle){
        boolean active = idle && Weathers.sporestorm.isActive();
        if(!active){
            if(sporeStormOn){ sporeStormOn = false; restoreSpore(); announce("fun.weather.spore.end", "[lightgray]The spore storm thins out.[]"); }
            sporeEscalateTimer = sporeScanTimer = 0f;
            return;
        }
        if(!sporeStormOn){
            sporeEscalateTimer += Time.delta;
            if(sporeEscalateTimer >= ESCALATE_INTERVAL){
                sporeEscalateTimer = 0f;
                if(!escalationBusy() && Mathf.chance(ESCALATE_CHANCE)) escalateSpore();
            }
        }else{
            sporeScanTimer += Time.delta;
            if(sporeScanTimer >= SCAN_INTERVAL){ sporeScanTimer = 0f; applySporeStorm(); }
        }
    }

    void escalateSpore(){
        sporeStormOn = true;
        if(sporeBaseDensity > 0f && Weathers.sporestorm instanceof ParticleWeather sw) sw.density = sporeBaseDensity / SPORE_DROPS;
        funmode.core.Vfx.sound(Sounds.wind, 0.7f, 0.7f);
        announce("fun.weather.spore", "[purple]The spores thicken into a SPORE STORM - exhaustion and spore-slow choke the field![]");
    }

    void restoreSpore(){
        if(sporeBaseDensity > 0f && Weathers.sporestorm instanceof ParticleWeather sw) sw.density = sporeBaseDensity;
    }

    /** Wipe every escalation back to stock (undo all vanilla-field mutations). */
    void resetEscalations(){
        rainStage = LIGHT;
        blizzardOn = sandStormOn = sporeStormOn = false;
        escalateTimer = rainScanTimer = 0f;
        snowEscalateTimer = snowScanTimer = 0f;
        sandEscalateTimer = sandScanTimer = 0f;
        sporeEscalateTimer = sporeScanTimer = 0f;
        restoreRain();
        restoreSnow();
        restoreSand();
        restoreSpore();
    }

    // ---------------------------------------------------------------------------------------------
    // Random disasters
    // ---------------------------------------------------------------------------------------------

    void startWeather(int w){
        endWeatherSilent(); //never stack two disasters
        weather = w;
        scanTimer = solarPulseTimer = electricTimer = 0f;
        solarFlash = 0f;
        float dur = Mathf.random(DURATION_MIN, DURATION_MAX); //several minutes, varied
        weatherTimer = dur;
        switch(w){
            case ACID -> {
                if(acidRain != null) activeState = acidRain.create(0.9f, dur + 120f); //the visible green downpour
                funmode.core.Vfx.sound(Sounds.windHowl, 0.35f, 1.3f);
                announce("fun.weather.acid.start", "[lime]Acid rain! Corrosion eats anything not under a force projector.[]");
            }
            case ELECTRIC -> {
                if(electricStorm != null) activeState = electricStorm.create(1f, dur + 120f);
                funmode.core.Vfx.sound(Sounds.windHowl, 0.6f, 0.7f);
                announce("fun.weather.electric.start", "[violet]An electric storm rolls in - lightning hammers the field without pause![]");
            }
            case SOLAR -> {
                if(solarMarker != null) activeState = solarMarker.create(1f, dur + 120f); //invisible marker → clients tint
                funmode.core.Vfx.sound(Sounds.windHowl, 0.4f, 1.5f);
                announce("fun.weather.solar.start", "[gold]A solar flare erupts - radiation burns anything not under a force projector.[]");
            }
            case HEAT -> {
                if(heatMarker != null) activeState = heatMarker.create(1f, dur + 120f); //invisible marker → clients tint
                funmode.core.Vfx.sound(Sounds.wind, 0.5f, 0.6f);
                announce("fun.weather.heat.start", "[orange]A scorching heat wave settles in - units overheat and catch fire in the open.[]");
            }
        }
    }

    void endWeather(){
        int prev = weather;
        endWeatherSilent();
        switch(prev){
            case ACID -> announce("fun.weather.acid.end", "[lightgray]The acid rain lets up.[]");
            case ELECTRIC -> announce("fun.weather.electric.end", "[lightgray]The electric storm passes.[]");
            case SOLAR -> announce("fun.weather.solar.end", "[lightgray]The solar flare fades.[]");
            case HEAT -> announce("fun.weather.heat.end", "[lightgray]The heat wave breaks.[]");
        }
    }

    /** Tear the current disaster down without an announcement (used when swapping or clearing). */
    void endWeatherSilent(){
        if(activeState != null){ activeState.remove(); activeState = null; }
        weather = NONE;
        weatherTimer = 0f;
        decisionTimer = -QUIET_AFTER; //a long breather before the sky can roll another disaster
    }

    /** Acid lays CORROSION on every exposed unit (the status is a heavy DoT) and eats every exposed
     * building - unless it's sheltered under a force projector of its own team. */
    void applyAcid(float step){
        collectShields();
        for(Unit u : Groups.unit){
            if(u.dead() || shielded(u.x, u.y, u.team.id)) continue;
            u.apply(StatusEffects.corroded, CORRODE_TICKS);
            if(Mathf.chanceDelta(0.03f)) acidFx.at(u.x + Mathf.range(u.hitSize), u.y + Mathf.range(u.hitSize));
        }
        for(Building b : Groups.build){
            if(b.block instanceof CoreBlock || shielded(b.x, b.y, b.team.id)) continue;
            b.damage(ACID_BUILD_DPS / 60f * step * sev());
        }
    }

    /** Heat wave: units in the open slowly overheat and catch fire (buildings tolerate the heat). */
    void applyHeat(float step){
        for(Unit u : Groups.unit){
            if(u.dead()) continue;
            u.apply(StatusEffects.burning, HEAT_BURN_TICKS);
        }
    }

    /** Solar flare pulse: a blinding wash of radiation. Every unit not tucked under a force projector
     * catches fire and takes a chunk of burn damage; buildings ride it out. Marks a screen-wide flash. */
    void solarFlarePulse(){
        collectShields();
        for(Unit u : Groups.unit){
            if(u.dead() || shielded(u.x, u.y, u.team.id)) continue;
            u.apply(StatusEffects.burning, SOLAR_BURN_TICKS);
            u.damage(SOLAR_BURN_DAMAGE * sev());
            if(Mathf.chance(0.5f)) solarBurnFx.at(u.x + Mathf.range(u.hitSize), u.y + Mathf.range(u.hitSize));
        }
        solarFlash = 1f; //draw() blooms the screen from this
        funmode.core.Vfx.sound(Sounds.explosionArtilleryShock, 0.7f, 0.7f);
    }

    boolean isPowerNode(mindustry.world.Block block){
        return block == Blocks.powerNode || block == Blocks.powerNodeLarge || block == Blocks.surgeTower;
    }

    /** Downpour: a power node shorts out - crackling arcs + electric bang, a surge that overclocks its
     * neighbours, then the node burns out (capped per scan so a grid isn't wiped in one go). */
    void powerShorts(){
        breakBuf.clear();
        int left = POWER_MAX_BREAK_PER_SCAN;
        for(Building b : Groups.build){
            if(left <= 0) break;
            if(isPowerNode(b.block) && Mathf.chance(POWER_SHORT_CHANCE)){
                for(Building n : b.proximity) n.applyBoost(1.5f, 60f); //the surge accelerates them...
                funmode.core.Vfx.at(shortSpark, b.x, b.y);
                Lightning.create(Team.derelict, Pal.lancerLaser, 0f, b.x, b.y, Mathf.random(360f), 10);
                funmode.core.Vfx.at(Fx.blastExplosion, b.x, b.y);
                funmode.core.Vfx.soundAt(Sounds.blockExplodeElectric, b.x, b.y, 1f, 1.1f);
                breakBuf.add(b); //...then it burns out (killed after the loop)
                left--;
            }
        }
        for(int i = 0; i < breakBuf.size; i++) breakBuf.get(i).kill();
    }

    /** Schedule a telegraphed strike: tracked for the host's countdown AND fires the networked telegraph
     * effect so every player sees the charge-up where the bolt will land, not just the host. */
    void addStrike(float x, float y, float charge){
        pending.add(new float[]{x, y, charge});
        funmode.core.Vfx.at(strikeTelegraph, x, y);
    }

    /** Thunderstorm: a few bolts per scan are AIMED at random units/buildings - each one telegraphs a
     * charge-up glow first (see draw + updatePending), then a long, loud bolt lands. */
    void lightningStrikes(){
        int left = STORM_MAX_STRIKES;
        for(Unit u : Groups.unit){
            if(left <= 0) break;
            if(!u.dead() && Mathf.chance(STORM_STRIKE_CHANCE)){ addStrike(u.x, u.y, CHARGE_TIME); left--; }
        }
        for(Building b : Groups.build){
            if(left <= 0) break;
            if(!(b.block instanceof CoreBlock) && Mathf.chance(STORM_STRIKE_CHANCE)){ addStrike(b.x, b.y, CHARGE_TIME); left--; }
        }
    }

    /** Electric storm: a steady volley of telegraphed bolts - some aimed at scattered units/buildings,
     * a couple flung into open view so the sky is never quiet. */
    void electricStrikes(){
        int left = ELECTRIC_VOLLEY;
        for(Unit u : Groups.unit){
            if(left <= 0) break;
            if(!u.dead() && Mathf.chance(0.05f)){ addStrike(u.x, u.y, CHARGE_TIME); left--; }
        }
        for(Building b : Groups.build){
            if(left <= 0) break;
            if(!(b.block instanceof CoreBlock) && Mathf.chance(0.04f)){ addStrike(b.x, b.y, CHARGE_TIME); left--; }
        }
        //always drop a bolt or two into view so it reads as a relentless storm even over empty ground
        for(int i = 0; i < ELECTRIC_SPECTACLE; i++){
            float x = Core.camera.position.x + Mathf.range(Core.camera.width * 0.45f);
            float y = Core.camera.position.y + Mathf.range(Core.camera.height * 0.45f);
            addStrike(x, y, CHARGE_TIME * Mathf.random(0.5f, 1f));
        }
    }

    /** Counts down each telegraphed strike; when its charge is spent, the bolt lands. Runs every frame
     * so strikes still resolve even as the storm is ending. */
    void updatePending(){
        for(int i = pending.size - 1; i >= 0; i--){
            float[] p = pending.get(i);
            p[2] -= Time.delta;
            if(p[2] <= 0f){
                executeStrike(p[0], p[1]);
                pending.remove(i);
            }
        }
    }

    /** The bolt itself: a long jagged strike dropping from the sky onto the marked spot, guaranteed
     * splash damage there, a blinding flash and LOUD thunder. */
    void executeStrike(float x, float y){
        float dmg = LIGHTNING_DAMAGE * sev();
        Lightning.create(Team.derelict, Pal.lancerLaser, dmg, x, y + STRIKE_HEIGHT, 270f + Mathf.range(12f), STRIKE_LENGTH);
        Damage.damage(Team.derelict, x, y, STRIKE_SPLASH, dmg);
        //networked so every player sees the flash + explosion and hears the thunder, not just the host
        funmode.core.Vfx.at(lightningFlash, x, y);
        funmode.core.Vfx.at(Fx.blastExplosion, x, y);
        funmode.core.Vfx.sound(Sounds.explosionArtilleryShockBig, 0.9f, Mathf.random(0.9f, 1.1f)); //loud thunder, everywhere
        funmode.core.Vfx.soundAt(Sounds.shockBullet, x, y, 1f, 1.3f);
    }

    /** Sand storm: units bog down (heavy slow status). Visibility loss is drawn as a thick sand haze. */
    void applySandstorm(){
        for(Unit u : Groups.unit){
            if(u.dead()) continue;
            u.apply(StatusEffects.slow, SAND_TICKS);
        }
    }

    /** Heavy spores drain and mire every unit in the open. */
    void applySporeStorm(){
        for(Unit u : Groups.unit){
            if(u.dead()) continue;
            u.apply(StatusEffects.sapped, SPORE_TICKS);
            u.apply(StatusEffects.sporeSlowed, SPORE_TICKS);
        }
    }

    /** Blizzard freezes units and seizes buildings, except anything kept warm by a nearby heat source;
     * a frozen building's dragged-down timeScale is what drawFrost renders (on every machine). */
    void applyBlizzard(){
        collectHeat();
        for(Unit u : Groups.unit){
            if(u.dead() || nearHeat(u.x, u.y)) continue;
            u.apply(StatusEffects.freezing, FREEZE_TICKS);
        }
        for(Building b : Groups.build){
            if(b.block instanceof CoreBlock || nearHeat(b.x, b.y)) continue;
            b.applySlowdown(FROZEN_SLOW, FREEZE_TICKS);
        }
    }

    void collectShields(){
        shields.clear();
        for(Building b : Groups.build){
            if(b instanceof ForceProjector.ForceBuild fb && !fb.broken){
                float r = fb.realRadius();
                if(r > 1f) shields.add(new float[]{b.x, b.y, r, b.team.id});
            }
        }
    }

    boolean shielded(float x, float y, int teamId){
        for(int i = 0; i < shields.size; i++){
            float[] s = shields.get(i);
            if((int)s[3] == teamId && Mathf.dst(x, y, s[0], s[1]) <= s[2]) return true;
        }
        return false;
    }

    void collectHeat(){
        heat.clear();
        for(Building b : Groups.build){
            if(isHeatSource(b)) heat.add(new float[]{b.x, b.y});
        }
    }

    /** A building throws off heat if it currently holds slag or a flammable fuel. */
    boolean isHeatSource(Building b){
        if(b.liquids != null && (b.liquids.get(Liquids.slag) > 0.05f || b.liquids.get(Liquids.oil) > 0.05f)) return true;
        return b.items != null && (b.items.get(Items.coal) > 0 || b.items.get(Items.pyratite) > 0 || b.items.get(Items.blastCompound) > 0);
    }

    boolean nearHeat(float x, float y){
        for(int i = 0; i < heat.size; i++){
            float[] h = heat.get(i);
            if(Mathf.dst(x, y, h[0], h[1]) <= HEAT_RANGE) return true;
        }
        return false;
    }

    int countFactories(){
        var team = player.team();
        int c = 0;
        for(Building b : Groups.build){
            if(b.team == team && (b.block instanceof GenericCrafter || b.block instanceof UnitFactory || b.block instanceof Reconstructor)) c++;
        }
        return c;
    }

    /**
     * Frost on blizzard-frozen buildings, drawn on EVERY machine (unlike the host-only {@link #draw()}).
     * A frozen building is one the blizzard has dragged to a crawl (its {@code timeScale} pinned down by
     * applySlowdown) while the map's snow falls - both SYNCED values - so a client can render the frost
     * from state it actually has, without knowing the host's internal blizzard flags. If anything is
     * frozen it also lays the cyan blizzard wash. Gated on the curse being ENABLED, not isActive (which
     * is false on clients).
     */
    void drawFrost(){
        if(!isEnabled() || !state.isGame() || !Weathers.snow.isActive()) return;
        boolean any = false;
        Draw.z(Layer.blockOver);
        for(Building b : Groups.build){
            //timeScale isn't public, but delta() = Time.delta * timeScale is - so this recovers the
            //(synced) timeScale: a frozen building crawls at FROZEN_SLOW, well under 1
            float ts = Time.delta > 0.0001f ? b.delta() / Time.delta : 1f;
            if(b.block instanceof CoreBlock || ts > FROZEN_SLOW + 0.15f) continue; //only the blizzard-slowed
            any = true;
            float s = b.block.size * tilesize;
            Draw.color(Color.valueOf("9fe8ff"), 0.35f);
            Fill.rect(b.x, b.y, s, s);
            Draw.color(Color.white, 0.5f);
            Lines.stroke(1f);
            Lines.square(b.x, b.y, s / 2f);
        }
        Draw.reset();
        if(any) tintScreen(Color.valueOf("cfe7ff"), 0.10f); //the cyan blizzard wash, shown to everyone too
    }

    /** CLIENT: which disaster is running, read from the SYNCED weather states (the host-only {@link #weather}
     * int is 0 on clients). Acid/electric have visible storms; heat/solar use the invisible marker weathers. */
    int deriveWeather(){
        for(WeatherState ws : Groups.weather){
            if(ws.weather == acidRain) return ACID;
            if(ws.weather == electricStorm) return ELECTRIC;
            if(ws.weather == heatMarker) return HEAT;
            if(ws.weather == solarMarker) return SOLAR;
        }
        return NONE;
    }

    //Runs on EVERY machine (not isActive-gated). The telegraphs now fire as a networked effect at schedule
    //time (addStrike/strikeTelegraph), and the disaster/escalation is derived from synced weather on clients,
    //so all the mood tints show for everyone.
    void draw(){
        if(!isEnabled() || !state.isGame()) return;

        //escalation visuals ride the vanilla (synced) weather; clients read "escalated" as "that vanilla
        //weather is active" (the host-only thickening itself isn't synced, but the mood tint is the shared cue)
        boolean spore = net.client() ? Weathers.sporestorm.isActive() : sporeStormOn;
        boolean sand = net.client() ? Weathers.sandstorm.isActive() : sandStormOn;
        if(spore) tintScreen(Color.valueOf("7457ce"), 0.08f + 0.03f * Mathf.absin(Time.time, 30f, 1f));
        if(sand) tintScreen(Color.valueOf("e7c08a"), 0.24f + 0.03f * Mathf.absin(Time.time, 40f, 1f)); //thick haze = low visibility
        //blizzard frost is drawn separately (drawFrost) so it shows for EVERY player, not just the host

        //which disaster is live: the host knows directly, clients read it off the synced weather markers
        int w = net.client() ? deriveWeather() : weather;
        if(w == NONE) return;

        //screen-wide mood tint per active disaster
        switch(w){
            case ACID -> tintScreen(Color.valueOf("84ff5a"), 0.055f + 0.02f * Mathf.absin(Time.time, 22f, 1f));
            case HEAT -> tintScreen(Color.valueOf("ff8a2c"), 0.10f + 0.04f * Mathf.absin(Time.time, 18f, 1f));      //shimmering heat
            case ELECTRIC -> tintScreen(Color.valueOf("1e2130"), 0.14f + (Mathf.chance(0.05f) ? Mathf.random(0.15f, 0.4f) : 0f)); //dark, flickering
            //host blooms hard on each flare pulse (solarFlash); clients lack that timing, so a gentle steady shimmer
            case SOLAR -> tintScreen(Color.valueOf("fff0c0"), 0.10f + 0.55f * (net.client() ? 0.35f + 0.15f * Mathf.absin(Time.time, 9f, 1f) : solarFlash));
        }
    }

    /** Fill the whole camera view with a translucent colour wash. */
    void tintScreen(Color color, float alpha){
        float w = Core.camera.width, h = Core.camera.height;
        float bx = Core.camera.position.x - w / 2f, by = Core.camera.position.y - h / 2f;
        Draw.z(Layer.overlayUI - 2f);
        Draw.color(color, alpha);
        Fill.crect(bx, by, w, h);
        Draw.reset();
    }

    static void announce(String key, String fallback){
        String out = Core.bundle.has(key) ? Core.bundle.get(key) : fallback;
        if(ui != null && ui.chatfrag != null) funmode.core.Chat.send(out);
    }

    /** Debug only: wipe every hazard AND remove the underlying vanilla weather instances, so a test
     * button starts from a clean sky (one weather at a time). Not used in normal play. */
    void clearAllWeatherForDebug(){
        endWeatherSilent();
        resetEscalations();
        Weathers.rain.remove();
        Weathers.snow.remove();
        Weathers.sandstorm.remove();
        Weathers.sporestorm.remove();
    }

    @Override
    public void buildDebug(Table table){
        //random disasters
        table.button("Погода: кислотный дождь", () -> { clearAllWeatherForDebug(); startWeather(ACID); }).size(240f, 50f);
        table.button("Погода: электрошторм", () -> { clearAllWeatherForDebug(); startWeather(ELECTRIC); }).size(240f, 50f);
        table.button("Погода: солнечная вспышка", () -> { clearAllWeatherForDebug(); startWeather(SOLAR); }).size(240f, 50f);
        table.button("Погода: зной", () -> { clearAllWeatherForDebug(); startWeather(HEAT); }).size(240f, 50f);
        table.row();
        //escalations - spin up the matching vanilla weather, then grow it into ours right away
        table.button("Погода: буран", () -> {
            clearAllWeatherForDebug();
            Weathers.snow.create(1f, 60f * 120f);
            escalateSnow();
        }).size(240f, 50f);
        table.button("Погода: песчаный шторм", () -> {
            clearAllWeatherForDebug();
            Weathers.sandstorm.create(1f, 60f * 120f);
            escalateSand();
        }).size(240f, 50f);
        table.button("Погода: споровой шторм", () -> {
            clearAllWeatherForDebug();
            Weathers.sporestorm.create(1f, 60f * 120f);
            escalateSpore();
        }).size(240f, 50f);
        table.button("Погода: усилить дождь", () -> {
            //escalate the CURRENT rain if it's already going; otherwise start a fresh rain first
            if(!Weathers.rain.isActive()){ clearAllWeatherForDebug(); Weathers.rain.create(1f, 60f * 120f); }
            if(rainStage < STORM && Weathers.rain instanceof RainWeather rw) escalateRain(rw);
        }).size(240f, 50f);
        table.row();
        table.button("Погода: очистить", this::clearAllWeatherForDebug).size(240f, 50f);
    }
}
