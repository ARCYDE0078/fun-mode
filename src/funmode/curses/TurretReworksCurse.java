package funmode.curses;

import arc.Core;
import arc.Events;
import arc.audio.Sound;
import arc.func.Cons;
import arc.func.Func;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.IntFloatMap;
import arc.struct.IntSet;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Time;
import funmode.core.Coolant;
import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.content.Liquids;
import mindustry.content.StatusEffects;
import mindustry.entities.Effect;
import mindustry.entities.Lightning;
import mindustry.gen.Fire;
import mindustry.entities.Units;
import mindustry.entities.bullet.BasicBulletType;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.bullet.LaserBulletType;
import mindustry.entities.bullet.LiquidBulletType;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.defense.turrets.ContinuousTurret;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.LiquidTurret;
import mindustry.world.blocks.defense.turrets.PointDefenseTurret;
import mindustry.world.blocks.defense.turrets.PowerTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.defense.turrets.Turret.TurretBuild;

import static mindustry.Vars.net;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;

/**
 * Serpulo turrets, individually cursed - one toggle, a bespoke gag per turret. Static-stat gags are
 * mutated at content load ("next launch"); behavioural gags run live. Host/SP only.
 */
public class TurretReworksCurse implements Curse{
    static final float RUN_INTERVAL = 5f;

    //overheat (arc/spectre)
    static final float HEAT_UP_ARC = 1f / (60f * 9f); //arc heats slower now - the choke was crippling it
    static final float HEAT_UP_SPECTRE = 1f / (60f * 2.5f);
    static final float HEAT_DOWN = 1f / (60f * 4f);
    static final float LIQUID_COOL_BONUS = 3f;
    static final float FIRE_SLOWDOWN = 0.45f; //softer fire-rate penalty while hot
    static final float FIRE_SPEEDUP = 1.2f;

    //spectre: once it's running hot, its shots crackle - each spectre bullet in flight arcs lightning at
    //enemies at a STEADY INTERVAL (staggered by each bullet's own age, so it reads as a rhythmic pulse)
    static final float SPECTRE_ARC_HEAT = 0.55f;     //only above this much heat
    static final float SPECTRE_ARC_INTERVAL = 12f;   //ticks between a bullet's zaps
    static final float SPECTRE_ARC_RANGE = 64f;
    static final float SPECTRE_ARC_DMG = 15f;
    static final int SPECTRE_ARC_LEN = 10;

    //cyclone: builds heat while firing (HEAT_UP_CYCLONE); at full charge it DISCHARGES - a radial storm of
    //its own flak + a shockwave that shoves every unit in range outward - then vents (can't fire) until cool
    static final float HEAT_UP_CYCLONE = 1f / (60f * 5f);
    static final int CYCLONE_BARRAGE = 40;    //bullets in the forward burst (many, at the gun's own spread)
    static final float CYCLONE_BLAST = 6.5f;  //one-shot forward shove on units in the cone
    static final float CYCLONE_CONE = 48f;    //shockwave/gust half-angle (around the aim direction)
    //a big, obvious wind gust blasting down the firing line: a sweeping arc wave + streaks tearing outward
    static final Effect cycloneBlastRing = new Effect(34f, 600f, e -> {
        float range = ((BaseTurret)Blocks.cyclone).range;
        float arcFrac = (CYCLONE_CONE * 2f) / 360f;
        //two sweeping arc waves for a fatter gust
        Draw.color(Color.valueOf("bcd6ff"), Color.white, e.fout());
        Lines.stroke(6f * e.fout());
        Lines.arc(e.x, e.y, e.fin() * range, arcFrac, e.rotation - CYCLONE_CONE);
        Lines.stroke(3f * e.fout());
        Lines.arc(e.x, e.y, e.fin() * range * 0.7f, arcFrac, e.rotation - CYCLONE_CONE);
        //wind streaks tearing outward through the whole cone
        Draw.color(Color.white, e.fout() * 0.8f);
        int n = 11;
        for(int i = 0; i < n; i++){
            float a = e.rotation - CYCLONE_CONE + (i / (n - 1f)) * (CYCLONE_CONE * 2f);
            float r0 = e.fin() * range * 0.5f, r1 = e.fin() * range * 1.02f;
            Lines.stroke(2.2f * e.fout());
            Lines.line(e.x + Angles.trnsx(a, r0), e.y + Angles.trnsy(a, r0),
                       e.x + Angles.trnsx(a, r1), e.y + Angles.trnsy(a, r1));
        }
    });

    //parallax: a repulsor BUBBLE that shoves every nearby FLYER outward (it's AA - ground units ignored)
    static final float PARALLAX_RADIUS = 34f * tilesize;
    static final float PARALLAX_PUSH = 0.6f;

    //wave: the water it sprays leaves enemies wet, and wet = conductive -> the turret zaps them.
    //deliberately weak/slow - a cheap turret + wet's own damage bonus makes shock easy to overtune
    static final float WAVE_SHOCK_DMG = 8f;
    static final float WAVE_SHOCK_INTERVAL = 40f; //arcs only ~1.5x/sec, not every beat
    static final int WAVE_SHOCK_MAX = 3;
    static final Color WAVE_ARC_COLOR = Color.valueOf("8fc7ff");
    final IntFloatMap waveTimer = new IntFloatMap();

    //wave: also pulses a radial shove - an actual expanding "wave" that pushes every enemy outward
    static final float WAVE_PUSH_INTERVAL = 75f; //a pulse ~every 1.25s
    static final float WAVE_PUSH_RADIUS = 19f * tilesize;
    static final float WAVE_PUSH = 5f;
    final IntFloatMap wavePushTimer = new IntFloatMap();
    static final Effect wavePushRing = new Effect(30f, e -> {
        Draw.color(Color.valueOf("9fd8ff"), Color.white, e.fout());
        Lines.stroke(3f * e.fout());
        Lines.circle(e.x, e.y, e.fin() * WAVE_PUSH_RADIUS);
    });

    //tsunami: every few seconds it hurls a HUGE fan of its own liquid bullets - a literal wave of water
    static final float TSUNAMI_PERIOD = 60f * 3f;
    static final int TSUNAMI_SHOTS = 55;
    static final float TSUNAMI_SPREAD = 34f; //degrees to either side of the aim - a wide crashing wave
    static final float TSUNAMI_RING_R = 8f * tilesize;
    final IntFloatMap tsunamiTimer = new IntFloatMap();
    static final Effect tsunamiRing = new Effect(40f, e -> {
        Draw.color(Color.valueOf("6fa8ff"), Color.white, e.fout());
        Lines.stroke(4.5f * e.fout());
        Lines.circle(e.x, e.y, e.fin() * TSUNAMI_RING_R);
    });

    //swarmer: seeks the general target but physically veers around it up close, so it misses the
    //locked unit yet its normal collision can still clip whoever's standing next to it
    static final float SWARM_SEEK_RANGE = 220f;
    static final float SWARM_AVOID_RANGE = 30f;
    static final float SWARM_SEEK_TURN = 3f;
    static final float SWARM_AVOID_TURN = 10f;
    final ObjectSet<BulletType> swarmerBullets = new ObjectSet<>();
    final ObjectSet<BulletType> spectreBullets = new ObjectSet<>();

    //static so the hover-popup heat bar (a lambda created at loadContent) and the update loop share it
    static final IntFloatMap heat = new IntFloatMap();
    static final IntSet cooling = new IntSet();
    float runTimer = 0f;
    float clientHeatTimer = 0f;
    final Seq<Fire> fireBuf = new Seq<>();

    static float heatOf(Building b){
        return heat.get(b.pos(), 0f);
    }

    @Override
    public String id(){
        return "turret-reworks";
    }

    @Override
    public String titleKey(){
        return "fun.curse.turret-reworks.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    // ---------- content mutations ----------

    @Override
    public void loadContent(){
        ((Turret)Blocks.scatter).inaccuracy = 0f;

        //scorch: it self-ignites while firing (scorchSelfBurn) - double its damage so the self-harm pays off
        eachBullet(Blocks.scorch, b -> b.damage *= 2f);

        Turret hail = (Turret)Blocks.hail;
        hail.range += 170f;
        eachBullet(Blocks.hail, b -> { b.lifetime *= 2f; b.damage *= 0.35f; b.splashDamage *= 0.35f; });

        //ripple: notably shorter range now, but each shell really hurts
        Turret ripple = (Turret)Blocks.ripple;
        ripple.range *= 0.5f;
        eachBullet(Blocks.ripple, b -> { b.lifetime *= 0.5f; b.damage *= 2.4f; b.splashDamage *= 2.4f; });

        //fuse: even more knockback than before
        Turret fuse = (Turret)Blocks.fuse;
        fuse.recoil *= 4f;
        eachBullet(Blocks.fuse, b -> { b.damage *= 0.4f; b.knockback += 22f; });

        //salvo: 20-round burst, minute reload - and the rounds are big and punchy
        Turret salvo = (Turret)Blocks.salvo;
        salvo.shoot.shots = 20;
        salvo.shoot.shotDelay = 3f;
        salvo.reload = 60f * 60f;
        eachBullet(Blocks.salvo, b -> {
            b.damage *= 2.6f;
            b.splashDamage *= 2f;
            if(b instanceof BasicBulletType bb){ bb.width *= 2f; bb.height *= 2f; }
        });

        //swarmer: no vanilla homing (fires straight); the missiles flee live, but panic-detonate with
        //a beefed-up blast if an enemy corners them - so they're skittish proximity mines, not useless
        eachBullet(Blocks.swarmer, b -> {
            b.homingPower = 0f;
            b.homingRange = 0f;
            b.splashDamage *= 2.2f;
            if(b.splashDamageRadius <= 0f) b.splashDamageRadius = 42f;
            swarmerBullets.add(b);
        });

        //lancer -> "impaler": a slow-winding railgun whose beam skewers EVERY enemy in a long line
        //(the old gag - aim ground, hit only air - just made it useless; this makes it a line-clearer)
        Turret lancer = (Turret)Blocks.lancer;
        lancer.range += 90f;
        lancer.reload *= 1.9f; //winds up slowly to pay for the reach
        lancer.rotateSpeed = 1.4f; //heavy railgun - the barrel swings around sluggishly
        BulletType lancerBeam = shootTypeOf(Blocks.lancer);
        if(lancerBeam != null){
            lancerBeam.collidesGround = true; //undo the "collides with nothing on the ground" break
            lancerBeam.collidesAir = false;
            lancerBeam.pierce = true;
            lancerBeam.pierceBuilding = false; //don't chew through your own walls
            lancerBeam.pierceCap = -1; //unlimited: the whole line down-range gets lanced at once
            lancerBeam.damage *= 2.3f;
            if(lancerBeam instanceof LaserBulletType lb){
                lb.length += 98f; //the beam reaches as far as the new range
                lb.width *= 1.7f; //a fat, satisfying lance
            }
        }

        //wave -> "static hose": the spray does little on its own, but water leaves enemies WET, and the
        //turret then arcs lightning into the wet (conductive) ones - see waveShock(). Range nudged up.
        ((Turret)Blocks.wave).range += 30f;

        //tsunami -> "tidal surge": drop the per-shot shove; instead it charges a periodic AoE burst that
        //drenches + damages everything in a wide ring - see tsunamiSurge(). Puddles a touch bigger for looks.
        Turret tsunami = (Turret)Blocks.tsunami;
        tsunami.range += 30f;
        eachBullet(Blocks.tsunami, b -> {
            if(b instanceof LiquidBulletType lb) lb.puddleSize *= 1.6f;
        });

        eachBullet(Blocks.cyclone, b -> b.ammoMultiplier *= 3f);

        BulletType meltBeam = shootTypeOf(Blocks.meltdown);
        if(meltBeam != null) meltBeam.damage *= 3f;

        //remember the spectre's ammo bullets so we can make them crackle when it overheats (spectreArc)
        eachBullet(Blocks.spectre, spectreBullets::add);

        //segment: a bit faster PD than stock, but no longer a runaway buff - it was ×3 speed with only a
        //token downside; now a milder speed-up that its "eats your own shots" gag can actually offset
        ((PointDefenseTurret)Blocks.segment).reload *= 0.55f;
    }

    void eachBullet(Block block, Cons<BulletType> cons){
        if(block instanceof ItemTurret it){
            it.ammoTypes.values().toSeq().each(cons);
        }else if(block instanceof LiquidTurret lt){
            lt.ammoTypes.values().toSeq().each(cons);
        }else{
            BulletType bt = shootTypeOf(block);
            if(bt != null) cons.get(bt);
        }
    }

    BulletType shootTypeOf(Block block){
        if(block instanceof PowerTurret pt) return pt.shootType;
        if(block instanceof ContinuousTurret ct) return ct.shootType;
        return null;
    }

    // ---------- behavioural gags ----------

    @Override
    public void init(){
        //added here (client load), AFTER vanilla setBars() ran during block init, so this bar lands
        //at the BOTTOM of the turret's hover popup instead of the top
        if(isEnabled()){
            //campaign database lore, one line per cursed turret (only while this curse is on)
            funmode.core.Lore.details(Blocks.scatter, "fun.lore.turret.scatter");
            funmode.core.Lore.details(Blocks.scorch, "fun.lore.turret.scorch");
            funmode.core.Lore.details(Blocks.hail, "fun.lore.turret.hail");
            funmode.core.Lore.details(Blocks.ripple, "fun.lore.turret.ripple");
            funmode.core.Lore.details(Blocks.fuse, "fun.lore.turret.fuse");
            funmode.core.Lore.details(Blocks.salvo, "fun.lore.turret.salvo");
            funmode.core.Lore.details(Blocks.swarmer, "fun.lore.turret.swarmer");
            funmode.core.Lore.details(Blocks.lancer, "fun.lore.turret.lancer");
            funmode.core.Lore.details(Blocks.wave, "fun.lore.turret.wave");
            funmode.core.Lore.details(Blocks.tsunami, "fun.lore.turret.tsunami");
            funmode.core.Lore.details(Blocks.cyclone, "fun.lore.turret.cyclone");
            funmode.core.Lore.details(Blocks.meltdown, "fun.lore.turret.meltdown");
            funmode.core.Lore.details(Blocks.spectre, "fun.lore.turret.spectre");
            funmode.core.Lore.details(Blocks.arc, "fun.lore.turret.arc");
            funmode.core.Lore.details(Blocks.segment, "fun.lore.turret.segment");
            funmode.core.Lore.details(Blocks.parallax, "fun.lore.turret.parallax");

            Func<Building, Bar> heatBar = e -> new Bar(
                () -> Core.bundle.get("fun.turret.heat", "Overheat"),
                () -> Color.orange.cpy().lerp(Color.scarlet, heatOf(e)),
                () -> heatOf(e));
            Blocks.arc.addBar("fun-heat", heatBar);
            Blocks.spectre.addBar("fun-heat", heatBar);
            Blocks.cyclone.addBar("fun-heat", heatBar); //cyclone now charges/overheats too
        }

        Events.on(WorldLoadEvent.class, e -> {
            heat.clear(); cooling.clear();
            tsunamiTimer.clear(); waveTimer.clear(); wavePushTimer.clear();
            Coolant.clear();
        });
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.update, this::clientHeat); //display-only heat estimate so CLIENTS' bars/glow fill
        Events.run(Trigger.draw, this::drawHeat);
        Events.run(Trigger.draw, this::drawParallax);
    }

    void update(){
        if(!isActive()){ Coolant.clear(); return; } //curse off -> no cooling field lingering
        if(state.isPaused()) return;

        //smooth, per-frame: the parallax repulsor, the swarmer flee steering, the spectre's hot-shot arcs
        Groups.build.each(b -> {
            if(b.block == Blocks.parallax) parallaxRepel(b);
        });
        steerSwarmers();
        spectreArc();

        //everything else on a 5-tick beat
        runTimer += Time.delta;
        if(runTimer < RUN_INTERVAL) return;
        float step = runTimer;
        runTimer = 0f;

        //rebuild the cooling field FIRST (a fresh Groups pass) so every gag below reads an up-to-date map
        Coolant.clear();
        Groups.build.each(b -> {
            if(b.block == Blocks.wave) addCoolant(b, ((BaseTurret)Blocks.wave).range);
            else if(b.block == Blocks.tsunami) addCoolant(b, ((BaseTurret)Blocks.tsunami).range);
        });

        Groups.build.each(b -> {
            if(b.block == Blocks.arc || b.block == Blocks.spectre) overheat(b, step);
            else if(b.block == Blocks.cyclone) cycloneOverheat(b, step);
            else if(b.block == Blocks.scorch) scorchSelfBurn(b, step);
            else if(b.block == Blocks.segment) segmentBreakOwn(b);
            else if(b.block == Blocks.meltdown) meltdownBurn(b);
            else if(b.block == Blocks.wave){ waveShock(b, step); wavePush(b, step); extinguish(b, ((BaseTurret)Blocks.wave).range); }
            else if(b.block == Blocks.tsunami){ tsunamiSurge(b, step); extinguish(b, ((BaseTurret)Blocks.tsunami).range); }
        });
    }

    /** A wave/tsunami loaded with water covers fires in its range; loaded with cryo it also covers heat/melt. */
    void addCoolant(Building b, float range){
        if(b.liquids == null) return;
        if(b.liquids.get(Liquids.cryofluid) > 0.1f) Coolant.add(b.x, b.y, range, true);
        else if(b.liquids.get(Liquids.water) > 0.1f) Coolant.add(b.x, b.y, range, false);
    }

    /** Arc & spectre: heat builds while firing (spectre faster), slowing the rate; at max it discharges then cools. */
    void overheat(Building b, float step){
        if(!(b instanceof TurretBuild tb)) return;
        boolean spectre = b.block == Blocks.spectre;
        int pos = b.pos();
        float h = heat.get(pos, 0f);

        //a cryo wave/tsunami field keeps it cold: heat bleeds off fast and it can NEVER reach the break point
        if(Coolant.coversHeat(b.x, b.y)){
            h = Math.max(0f, h - HEAT_DOWN * LIQUID_COOL_BONUS * step);
            cooling.remove(pos);
            heat.put(pos, h);
            if(Mathf.chanceDelta(0.08f)) Fx.steam.at(b.x + Mathf.range(6f), b.y + Mathf.range(6f));
            return;
        }

        float coolRate = HEAT_DOWN;
        if(b.liquids != null && (b.liquids.get(Liquids.water) > 0.1f || b.liquids.get(Liquids.cryofluid) > 0.1f)){
            coolRate *= LIQUID_COOL_BONUS;
        }

        if(cooling.contains(pos)){
            tb.reloadCounter = 0f;
            h -= coolRate * step;
            if(h <= 0.1f){ h = 0f; cooling.remove(pos); }
        }else if(tb.isShooting()){
            h += (spectre ? HEAT_UP_SPECTRE : HEAT_UP_ARC) * step;
            //arc chokes as it heats; spectre goes into a frenzy - faster and faster, until it breaks
            if(spectre) tb.reloadCounter += h * FIRE_SPEEDUP * step;
            else tb.reloadCounter = Math.max(0f, tb.reloadCounter - h * FIRE_SLOWDOWN * step);
            if(h >= 1f){
                h = 1f;
                cooling.add(pos);
                if(spectre){
                    funmode.core.Vfx.at(Fx.blastExplosion, b.x, b.y); //networked: clients see it too
                    b.damage(b.maxHealth * 0.2f);
                }else{
                    //the reward for enduring the choke: a big, wide lightning discharge
                    for(int i = 0; i < 10; i++) Lightning.create(b.team, Pal.lancerLaser, 300f, b.x, b.y, Mathf.random(360f), 46);
                    funmode.core.Vfx.at(Fx.blastExplosion, b.x, b.y); //networked burst so the discharge shows for everyone
                }
            }
        }else{
            h -= coolRate * step;
            if(h < 0f) h = 0f;
        }
        heat.put(pos, h);
        //the red glow (drawHeat) is host-local, so vent networked steam while hot - THAT is what tells a
        //client the turret is overheating
        if(h > 0.35f && Mathf.chance(0.4f)) funmode.core.Vfx.at(Fx.steam, b.x + Mathf.range(b.block.size * 3f), b.y + Mathf.range(b.block.size * 3f));
    }

    /** Thorium-reactor-style heat bar + red glow over arc/spectre. Runs on EVERY machine (not just the
     * host) - clients fill the {@code heat} map via {@link #clientHeat()}, so their glow shows too. */
    void drawHeat(){
        if(!isEnabled() || !state.isGame() || heat.isEmpty()) return;
        Draw.z(Layer.overlayUI);
        for(IntFloatMap.Entry e : heat){
            if(e.value <= 0.02f) continue;
            Building b = world.build(e.key);
            if(b == null || (b.block != Blocks.arc && b.block != Blocks.spectre && b.block != Blocks.cyclone)) continue;
            float h = e.value;
            float size = b.block.size * tilesize;

            //red-hot glow, flickering harder as it nears the top (the numeric bar lives in the hover popup)
            float glow = h * 0.4f + (h > 0.7f ? Mathf.absin(Time.time, 3f, 0.2f) : 0f);
            Draw.color(Color.scarlet, glow);
            Fill.rect(b.x, b.y, size, size);
        }
        Draw.reset();
    }

    /**
     * A DISPLAY-ONLY heat estimate that runs on CLIENTS (the host has the real one). The host's per-turret
     * heat isn't networked, so a client's overheat BAR/glow would sit at zero. Here each client mirrors
     * the same accumulation from the turret's (synced) shooting state - climbing while it fires, cooling
     * when it stops, snapping down when it tops out (the discharge) - so the bar RISES and the glow shows.
     * Purely cosmetic; it never touches the simulation (that stays host-authoritative in {@link #update()}).
     */
    void clientHeat(){
        if(!net.client() || !isEnabled() || !state.isGame() || state.isPaused()) return;
        clientHeatTimer += Time.delta;
        if(clientHeatTimer < RUN_INTERVAL) return;
        float step = clientHeatTimer;
        clientHeatTimer = 0f;

        Groups.build.each(b -> {
            boolean arc = b.block == Blocks.arc, spectre = b.block == Blocks.spectre, cyclone = b.block == Blocks.cyclone;
            if((!arc && !spectre && !cyclone) || !(b instanceof TurretBuild tb)) return;
            int pos = b.pos();
            float h = heat.get(pos, 0f);
            if(tb.isShooting()){
                h += (spectre ? HEAT_UP_SPECTRE : arc ? HEAT_UP_ARC : HEAT_UP_CYCLONE) * step;
                if(h >= 1f) h = 0f; //topped out - it just discharged/vented, drop back down
            }else{
                h = Math.max(0f, h - HEAT_DOWN * step);
            }
            if(h <= 0.001f) heat.remove(pos, 0f); else heat.put(pos, h);
        });
    }

    void scorchSelfBurn(Building b, float step){
        if(Coolant.coversFire(b.x, b.y)){ //a water/cryo turret is dousing it - no self-burn, just steam
            if(Mathf.chanceDelta(0.15f)) Fx.steam.at(b.x + Mathf.range(5f), b.y + Mathf.range(5f));
            return;
        }
        if(!(b instanceof TurretBuild tb) || !tb.isShooting()) return;
        b.damage(b.maxHealth * 0.004f * step);
        if(Mathf.chanceDelta(0.2f)) Fx.fire.at(b.x + Mathf.range(6f), b.y + Mathf.range(6f));
    }

    /** Segment: occasionally zaps ONE of its own team's shots with a point-defense beam, turning to face it. */
    void segmentBreakOwn(Building b){
        //point defense needs power - but efficiency is 0 whenever it has no PD target, so gate on the
        //actual power status instead (skip only when it has a power module and it's unpowered)
        if(b.power != null && b.power.status <= 0.001f) return;
        if(!Mathf.chance(0.5f)) return; //careless half the time now - the real cost of that fast PD
        float range = ((BaseTurret)Blocks.segment).range;
        int broken = 0;
        for(Bullet bl : Groups.bullet){
            if(broken >= 1) break;
            if(bl.team != b.team || !bl.within(b.x, b.y, range)) continue;
            //aim at the shot it's about to destroy
            if(b instanceof BaseTurret.BaseTurretBuild tbb) tbb.rotation = Angles.angle(b.x, b.y, bl.x, bl.y);
            //the actual point-defense beam + hit, not a lightning bolt. The beam carries a Vec2 endpoint as
            //effect DATA, which Call.effect can't serialise, so it stays host-local; the hit flash networks.
            Fx.pointBeam.at(b.x, b.y, 0f, Pal.accent, new arc.math.geom.Vec2(bl.x, bl.y));
            funmode.core.Vfx.at(Fx.pointHit, bl.x, bl.y, 0f, Pal.accent);
            bl.remove();
            broken++;
        }
    }

    /**
     * Cyclone overheat: heat builds while it fires (it shoots normally meanwhile); at full charge it VENTS -
     * a radial storm of its own flak plus a shockwave that blows every unit in range outward - then it can't
     * fire until it cools back down. Shares the heat map / bar / glow with arc & spectre.
     */
    void cycloneOverheat(Building b, float step){
        if(!(b instanceof TurretBuild tb)) return;
        int pos = b.pos();
        float h = heat.get(pos, 0f);

        //a cryo wave/tsunami field keeps it cold - no charge, no discharge
        if(Coolant.coversHeat(b.x, b.y)){
            h = Math.max(0f, h - HEAT_DOWN * LIQUID_COOL_BONUS * step);
            cooling.remove(pos);
            heat.put(pos, h);
            return;
        }

        float coolRate = HEAT_DOWN;
        if(b.liquids != null && (b.liquids.get(Liquids.water) > 0.1f || b.liquids.get(Liquids.cryofluid) > 0.1f)){
            coolRate *= LIQUID_COOL_BONUS;
        }

        if(cooling.contains(pos)){
            tb.reloadCounter = 0f; //venting - can't fire
            h -= coolRate * step;
            if(h <= 0.1f){ h = 0f; cooling.remove(pos); }
        }else if(tb.isShooting()){
            h += HEAT_UP_CYCLONE * step;
            if(h >= 1f){
                h = 1f;
                cooling.add(pos);
                cycloneDischarge(b, tb);
            }
        }else{
            h -= coolRate * step;
            if(h < 0f) h = 0f;
        }
        heat.put(pos, h);
        //networked venting so clients see the cyclone charging up (the glow is host-local)
        if(h > 0.35f && Mathf.chance(0.4f)) funmode.core.Vfx.at(Fx.steam, b.x + Mathf.range(b.block.size * 3f), b.y + Mathf.range(b.block.size * 3f));
    }

    /** The full-charge vent: a forward FAN barrage of the cyclone's own ammo + a forward shove down its aim line. */
    void cycloneDischarge(Building b, TurretBuild tb){
        float baseAng = tb.rotation; //everything goes where the turret is aiming
        BulletType bt = tb.hasAmmo() ? tb.useAmmo() : null;
        if(bt != null){
            float tip = b.block.size * tilesize / 2f + 2f;
            float spread = ((Turret)Blocks.cyclone).inaccuracy; //just its normal scatter, not a wide fan
            for(int i = 0; i < CYCLONE_BARRAGE; i++){
                float ang = baseAng + Mathf.range(spread);
                float mx = b.x + Angles.trnsx(ang, tip), my = b.y + Angles.trnsy(ang, tip);
                bt.create(b, b.team, mx, my, ang);
            }
        }
        float range = ((BaseTurret)Blocks.cyclone).range;
        funmode.core.Vfx.at(cycloneBlastRing, b.x, b.y, baseAng); //networked gust so clients see the vent
        funmode.core.Vfx.at(Fx.blastExplosion, b.x, b.y);
        //shove units in the FORWARD cone outward, down the firing line (friend and foe alike)
        Units.nearby(b.x - range, b.y - range, range * 2f, range * 2f, u -> {
            if(!u.within(b.x, b.y, range)) return;
            float toAng = Angles.angle(b.x, b.y, u.x, u.y);
            if(Angles.angleDist(toAng, baseAng) > CYCLONE_CONE) return; //only what's in front of the barrel
            float frac = 1f - u.dst(b.x, b.y) / range;
            float power = CYCLONE_BLAST * (0.4f + 0.6f * frac);
            u.vel.add(Angles.trnsx(toAng, power), Angles.trnsy(toAng, power));
        });
    }

    /** Wave & tsunami: water/cryo loaded means they hose out any fires in range (the mod sets a lot alight). */
    void extinguish(Building b, float range){
        if(Groups.fire.size() == 0 || b.liquids == null) return;
        //water or cryo loaded (slag/oil wouldn't put anything out)
        if(b.liquids.get(Liquids.water) <= 0.05f && b.liquids.get(Liquids.cryofluid) <= 0.05f) return;
        fireBuf.clear();
        for(Fire f : Groups.fire){
            if(Mathf.dst(f.x, f.y, b.x, b.y) <= range) fireBuf.add(f);
        }
        //collect-then-remove: killing mid-iteration corrupts the group. Steam puff so you SEE it get doused.
        for(Fire f : fireBuf){ funmode.core.Vfx.at(Fx.steam, f.x, f.y); f.remove(); }
    }

    /** Parallax: a repulsor bubble - every enemy inside the ring is shoved outward, harder up close. */
    void parallaxRepel(Building b){
        if(b.power != null && b.power.status <= 0.001f) return; //needs power, like its vanilla beam
        float r = PARALLAX_RADIUS;
        Units.nearby(b.x - r, b.y - r, r * 2f, r * 2f, u -> {
            if(u.team == b.team || !u.isFlying() || !u.within(b.x, b.y, r)) return; //AA only: ignore ground
            float ang = Angles.angle(b.x, b.y, u.x, u.y); //push directly away from the turret
            float frac = 1f - u.dst(b.x, b.y) / r; //full shove at the centre, nothing at the edge
            float power = PARALLAX_PUSH * frac * Time.delta;
            u.vel.add(Angles.trnsx(ang, power), Angles.trnsy(ang, power));
        });
    }

    /** Faint pulsing ring so the parallax repulsor bubble is readable. */
    void drawParallax(){
        if(!isActive()) return;
        Draw.z(Layer.overlayUI);
        for(Building b : Groups.build){
            if(b.block != Blocks.parallax) continue;
            if(b.power != null && b.power.status <= 0.001f) continue;
            float pulse = Mathf.absin(Time.time, 22f, 1f);
            Draw.color(Pal.accent, 0.12f + 0.13f * pulse);
            Lines.stroke(1.6f);
            Lines.circle(b.x, b.y, PARALLAX_RADIUS * (0.86f + 0.14f * pulse));
        }
        Draw.reset();
    }

    /** Wave: water leaves enemies wet; wet = conductive, so the turret arcs shocking lightning into them. */
    void waveShock(Building b, float step){
        if(!(b instanceof TurretBuild tb) || !tb.hasAmmo()) return;
        //own cooldown - without it, an arc every 5-tick beat (×wet's damage bonus) melts everything
        float wt = waveTimer.get(b.pos(), 0f) + step;
        if(wt < WAVE_SHOCK_INTERVAL){ waveTimer.put(b.pos(), wt); return; }
        waveTimer.put(b.pos(), 0f);

        float range = ((BaseTurret)Blocks.wave).range;
        int zapped = 0;
        for(Unit u : Groups.unit){
            if(zapped >= WAVE_SHOCK_MAX) break;
            if(u.team == b.team) continue;
            //water and cryo both soak/chill the target into conducting
            if(!u.hasEffect(StatusEffects.wet) && !u.hasEffect(StatusEffects.freezing)) continue;
            if(!u.within(b.x, b.y, range)) continue;
            Lightning.create(b.team, WAVE_ARC_COLOR, WAVE_SHOCK_DMG, u.x, u.y, Mathf.random(360f), 10);
            funmode.core.Vfx.at(Fx.hitLancer, u.x, u.y); //networked zap flash so clients see the shock land
            zapped++;
        }
    }

    /** Wave: pulses an expanding ring that shoves every nearby enemy outward, harder up close. */
    void wavePush(Building b, float step){
        if(!(b instanceof TurretBuild tb) || !tb.hasAmmo()) return;
        float pt = wavePushTimer.get(b.pos(), 0f) + step;
        if(pt < WAVE_PUSH_INTERVAL){ wavePushTimer.put(b.pos(), pt); return; }
        wavePushTimer.put(b.pos(), 0f);

        float r = WAVE_PUSH_RADIUS;
        funmode.core.Vfx.at(wavePushRing, b.x, b.y);
        Units.nearby(b.x - r, b.y - r, r * 2f, r * 2f, u -> {
            if(u.team == b.team || !u.within(b.x, b.y, r)) return;
            float ang = Angles.angle(b.x, b.y, u.x, u.y); //straight out from the turret
            float frac = 1f - u.dst(b.x, b.y) / r; //a firmer shove near the centre
            float power = WAVE_PUSH * (0.4f + 0.6f * frac);
            u.vel.add(Angles.trnsx(ang, power), Angles.trnsy(ang, power));
        });
    }

    /** Tsunami: every ~3s it hurls a huge fan of its own liquid bullets at the target - a wall of water. */
    void tsunamiSurge(Building b, float step){
        if(!(b instanceof TurretBuild tb) || !tb.hasAmmo()) return;
        float range = ((BaseTurret)Blocks.tsunami).range;
        //only wind up when there's actually something in reach, so it reacts instead of firing into empty water
        Unit target = Units.closestEnemy(b.team, b.x, b.y, range, u -> true);
        if(target == null){ tsunamiTimer.put(b.pos(), 0f); return; }

        float t = tsunamiTimer.get(b.pos(), 0f) + step;
        if(t < TSUNAMI_PERIOD){ tsunamiTimer.put(b.pos(), t); return; }
        tsunamiTimer.put(b.pos(), 0f);
        fireTsunami(b, Angles.angle(b.x, b.y, target.x, target.y));
    }

    void fireTsunami(Building b, float baseAng){
        Liquid liq = b.liquids == null ? null : b.liquids.current();
        if(liq == null) return;
        BulletType bt = ((LiquidTurret)Blocks.tsunami).ammoTypes.get(liq);
        if(bt == null) return;

        float tip = b.block.size * tilesize / 2f + 2f;
        for(int i = 0; i < TSUNAMI_SHOTS; i++){
            float ang = baseAng + Mathf.range(TSUNAMI_SPREAD);
            float vel = Mathf.random(0.7f, 1.3f); //mixed speeds so it churns like a real wave
            float mx = b.x + Angles.trnsx(ang, tip), my = b.y + Angles.trnsy(ang, tip);
            //networked so the wall of water appears for every player, not just the host (loses the owner
            //ref, but a team-tagged spray doesn't need it)
            mindustry.gen.Call.createBullet(bt, b.team, mx, my, ang, bt.damage, vel, 1f);
        }
        funmode.core.Vfx.at(tsunamiRing, b.x, b.y);
        Sound s = ((Turret)Blocks.tsunami).shootSound;
        if(s != null) funmode.core.Vfx.soundAt(s, b.x, b.y, 1f, Mathf.random(0.85f, 1.1f));
        //drain some liquid so the flood isn't literally free
        b.liquids.remove(liq, Math.min(b.liquids.get(liq), TSUNAMI_SHOTS * 0.08f));
    }

    /**
     * Swarmer missiles home toward the nearest enemy, but veer sharply AWAY at a FIXED distance from
     * its centre. Small units (tiny hitbox) end up outside that ring, so the missile slides past and
     * misses; big units (fat hitbox) still overlap the ring, so they get clipped anyway. Emergent
     * result: swarmer whiffs on small enemies, connects on the big ones.
     */
    void steerSwarmers(){
        if(swarmerBullets.isEmpty()) return;
        for(Bullet bl : Groups.bullet){
            if(!swarmerBullets.contains(bl.type)) continue;
            Unit target = Units.closestEnemy(bl.team, bl.x, bl.y, SWARM_SEEK_RANGE, u -> true);
            if(target == null) continue;

            if(bl.within(target, SWARM_AVOID_RANGE)){
                //too close to the locked unit - swerve off so the missile slides past it
                float away = Angles.angle(target.x, target.y, bl.x, bl.y);
                bl.vel.setAngle(Angles.moveToward(bl.rotation(), away, SWARM_AVOID_TURN * Time.delta));
            }else{
                float toward = Angles.angle(bl.x, bl.y, target.x, target.y);
                bl.vel.setAngle(Angles.moveToward(bl.rotation(), toward, SWARM_SEEK_TURN * Time.delta));
            }
        }
    }

    /** Spectre: while the firing turret is running hot, its shots in flight crackle lightning into nearby enemies. */
    void spectreArc(){
        if(spectreBullets.isEmpty()) return;
        for(Bullet bl : Groups.bullet){
            if(!spectreBullets.contains(bl.type)) continue;
            if(!(bl.owner instanceof Building ob)) continue; //fired by a spectre turret
            if(heatOf(ob) < SPECTRE_ARC_HEAT) continue;
            //zap on the interval boundary of this bullet's own age - a steady pulse, staggered per bullet
            if(bl.time() % SPECTRE_ARC_INTERVAL >= Time.delta) continue;
            Unit target = Units.closestEnemy(bl.team, bl.x, bl.y, SPECTRE_ARC_RANGE, u -> true);
            if(target == null) continue;
            Lightning.create(bl.team, Pal.lancerLaser, SPECTRE_ARC_DMG, bl.x, bl.y,
                Angles.angle(bl.x, bl.y, target.x, target.y), SPECTRE_ARC_LEN);
        }
    }

    void meltdownBurn(Building b){
        if(Coolant.coversHeat(b.x, b.y)) return; //cryo field: nothing melts
        if(!(b instanceof TurretBuild tb) || !tb.isShooting()) return;
        float range = ((BaseTurret)Blocks.meltdown).range;
        float ang = tb.rotation;
        for(float d = tilesize; d <= range; d += tilesize * 2f){
            float px = b.x + Angles.trnsx(ang, d), py = b.y + Angles.trnsy(ang, d);
            Building hit = world.buildWorld(px, py);
            if(hit != null && hit != b){
                hit.damage(18f);
                if(Mathf.chanceDelta(0.1f)) Fx.fire.at(px, py);
            }
        }
    }
}
