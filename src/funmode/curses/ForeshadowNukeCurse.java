package funmode.curses;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.IntFloatMap;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.content.StatusEffects;
import mindustry.entities.Effect;
import mindustry.entities.Units;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Sounds;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.world.blocks.defense.turrets.Turret.TurretBuild;

import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;
import static mindustry.Vars.world;

/**
 * The foreshadow keeps its day job: normal shots go out like any other turret, at any target in
 * range. Alongside that, given power and ammo, it ALSO charges for 5 minutes - a clock face above
 * the turret fills CLOCKWISE as it does - then discharges across HALF THE MAP, killing every unit
 * in the radius: every team, yours included; an omen makes no exceptions. Cut the power or the
 * ammo and the charge simply pauses where it was (normal firing draws from the same ammo pool, so
 * trigger-happy use of the turret visibly slows its own doomsday clock). The final ten seconds get
 * a chat warning, the corvus charge sound at indecent volume, and the collapsing ring animation.
 * <p>
 * True to the name: while charging, the omen FORETELLS. Every few seconds it marks the nearest
 * enemy in range with a portent - a jolt of damage plus a lingering weakness - foreshadowing that
 * unit's end before the turret's own gun ever gets to it.
 * <p>
 * There can be only one: finishing a second foreshadow while one stands anywhere on the map
 * destroys the newcomer on the spot. Host/SP only.
 */
public class ForeshadowNukeCurse implements Curse{
    static final float PERIOD_TICKS = 5f * 60f * 60f;
    static final float WARN_TICKS = 10f * 60f;
    static final float WARN_FRACTION = WARN_TICKS / PERIOD_TICKS;
    static final float SCAN_INTERVAL_TICKS = 30f;

    //omen marking: the charging turret periodically foretells a nearby foe's end
    static final float OMEN_MARK_INTERVAL = 4f * 60f;
    static final float OMEN_MARK_RADIUS = 60f * 8f;
    static final float OMEN_MARK_DAMAGE_FRAC = 0.06f; //instant toll, a fraction of the marked unit's max health
    static final float OMEN_MARK_SAP_DURATION = 6f * 60f;

    /** Fx.instTrail at triple scale - the omen's rays, drawn chunky enough to read across the map. */
    /**
     * The ENTIRE discharge - a 30-tile shock ring plus 32 fat rays out to ~175 tiles - as ONE effect, so
     * it costs a single networked packet ({@link funmode.core.Vfx#at}) instead of ~450. The whole radial
     * storm is drawn from the effect's own centre, so every player sees the omen speak, not just the host.
     */
    static final Effect omenDischarge = new Effect(40f, 2900f, e -> {
        //the shock ring
        Draw.color(Color.white, Pal.bulletYellowBack, e.fin());
        Lines.stroke(e.fout() * 9f + 0.5f);
        Lines.circle(e.x, e.y, e.fin() * 240f);
        Draw.color(Pal.bulletYellowBack);
        for(int s : Mathf.signs){
            Drawf.tri(e.x, e.y, 40f * e.fout(), 260f, 90f * s);
            Drawf.tri(e.x, e.y, 40f * e.fout(), 150f, 20f * s);
        }
        //32 fat rays radiating out to ~175 tiles
        for(int i = 0; i < 32; i++){
            float angle = i * 11.25f;
            for(float d = 60f; d <= 1400f; d += 100f){
                float rx = e.x + Angles.trnsx(angle, d), ry = e.y + Angles.trnsy(angle, d);
                for(int k = 0; k < 2; k++){
                    Draw.color(k == 0 ? Pal.bulletYellowBack : Pal.bulletYellow);
                    float m = k == 0 ? 1f : 0.5f;
                    float rot = angle + 180f;
                    float w = 45f * e.fout() * m;
                    Drawf.tri(rx, ry, w, (90f + Mathf.randomSeedRange(e.id + i * 97 + (int)d, 45f)) * m, rot);
                    Drawf.tri(rx, ry, w, 30f * m, rot + 180f);
                }
            }
        }
        Drawf.light(e.x, e.y, 400f, Pal.bulletYellowBack, 0.6f * e.fout());
    });

    /** A small omen-yellow ring flash marking whoever the turret has just foretold. */
    static final Effect omenMarkFx = new Effect(50f, e -> {
        Draw.color(Pal.bulletYellowBack, Color.white, e.fin());
        Lines.stroke(e.fout() * 2f + 0.4f);
        Lines.circle(e.x, e.y, 6f + e.fin() * 10f);
        Drawf.light(e.x, e.y, 50f, Pal.bulletYellowBack, 0.5f * e.fout());
    });

    /** build pos -> charge progress 0..1. Advances only while the turret is powered and fed. */
    final IntMap<Float> charge = new IntMap<>();
    final IntSet warned = new IntSet();
    /** build pos -> ticks until this turret can foretell (mark) its next victim. */
    final IntFloatMap omenMarkTimer = new IntFloatMap();
    final IntSeq stale = new IntSeq();
    final Seq<Unit> victims = new Seq<>();
    float scanTimer = 0f;

    /** Remaining ticks of the discharge shake, applied straight to the camera. */
    float shakeTime = 0f;
    static final float SHAKE_DURATION_TICKS = 90f;
    static final float SHAKE_POWER = 30f;

    @Override
    public String id(){
        return "foreshadow-nuke";
    }

    @Override
    public String titleKey(){
        return "fun.curse.foreshadow-nuke.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(Blocks.foreshadow, "fun.lore.foreshadow");
        }
        Events.on(WorldLoadEvent.class, e -> {
            charge.clear();
            warned.clear();
            scanTimer = 0f;
        });
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.draw, this::drawCharge);
        Events.run(Trigger.preDraw, this::applyShake);

        //there can be only one: a second foreshadow completed anywhere = instant demolition
        Events.on(BlockBuildEndEvent.class, e -> {
            if(!isActive() || e.breaking || e.tile == null || e.tile.build == null) return;
            if(e.tile.block() != Blocks.foreshadow) return;

            int count = Groups.build.count(b -> b.block == Blocks.foreshadow);
            if(count > 1){
                //no announcement - the omen's opinion of rivals speaks for itself
                e.tile.build.kill();
            }
        });
    }

    void update(){
        if(!isActive() || state.isPaused()) return;

        scanTimer += Time.delta;
        if(scanTimer < SCAN_INTERVAL_TICKS) return;
        float step = scanTimer;
        scanTimer = 0f;

        Groups.build.each(b -> {
            if(b.block != Blocks.foreshadow || !(b instanceof TurretBuild turret)) return;

            //charging requires power and ammo; starved turrets just hold their progress. Normal
            //firing is left entirely alone now - it draws from the same ammo pool, so hosing down
            //small fry with the regular gun visibly starves the doomsday charge.
            if(turret.efficiency <= 0f || !turret.hasAmmo()) return;

            float progress = charge.get(b.pos(), 0f) + step / PERIOD_TICKS;

            //the omen foretells: while charging, it periodically marks the nearest foe in range
            if(progress > 0.02f){
                float markT = omenMarkTimer.get(b.pos(), 0f) - step;
                if(markT <= 0f){
                    markVictim(turret);
                    markT = OMEN_MARK_INTERVAL;
                }
                omenMarkTimer.put(b.pos(), markT);
            }

            if(progress >= 1f - WARN_FRACTION && !warned.contains(b.pos())){
                warned.add(b.pos());
                funmode.core.Chat.send(Core.bundle.get("fun.foreshadow.warning"));
                //the corvus charge-up, pitched way down so it drones across the whole ten seconds (all players)
                funmode.core.Vfx.sound(Sounds.chargeCorvus, 15f, 0.2f);
            }

            if(progress >= 1f){
                discharge(b);
                charge.put(b.pos(), 0f);
                warned.remove(b.pos());
            }else{
                charge.put(b.pos(), progress);
            }
        });

        //forget destroyed/replaced foreshadows
        stale.clear();
        for(IntMap.Entry<Float> entry : charge){
            Building b = world.build(entry.key);
            if(b == null || b.block != Blocks.foreshadow) stale.add(entry.key);
        }
        for(int i = 0; i < stale.size; i++){
            charge.remove(stale.get(i));
            warned.remove(stale.get(i));
            omenMarkTimer.remove(stale.get(i), 0f);
        }
    }

    /** The omen's signature trick: mark the nearest enemy in range with a portent of its own end -
     * an instant jolt of damage, plus a lingering weakness that foreshadows what's coming. */
    void markVictim(Building turret){
        Unit[] best = {null};
        float[] bestDst = {OMEN_MARK_RADIUS};
        Units.nearbyEnemies(turret.team, turret.x, turret.y, OMEN_MARK_RADIUS, u -> {
            float d = u.dst(turret);
            if(d < bestDst[0]){ bestDst[0] = d; best[0] = u; }
        });
        Unit u = best[0];
        if(u == null) return;
        u.damage(u.maxHealth * OMEN_MARK_DAMAGE_FRAC);
        u.apply(StatusEffects.sapped, OMEN_MARK_SAP_DURATION);
        funmode.core.Vfx.at(omenMarkFx, u.x, u.y);
    }

    void discharge(Building turret){
        float radius = Math.max(world.unitWidth(), world.unitHeight()) / 2f;

        //collect first, kill after - no mutating the unit group mid-iteration
        victims.clear();
        Groups.unit.each(u -> {
            if(u.within(turret, radius)) victims.add(u);
        });
        for(Unit u : victims){
            u.kill();
        }
        victims.clear();

        //the foreshadow's railgun visuals at omen scale - one networked effect so all players see it
        funmode.core.Vfx.at(Fx.instHit, turret.x, turret.y);
        funmode.core.Vfx.at(omenDischarge, turret.x, turret.y);
        //heard from anywhere, at a volume the administration considers appropriate
        funmode.core.Vfx.sound(Sounds.shootForeshadow, 25f, 1f);
        funmode.core.Vfx.sound(Sounds.explosionReactor, 15f, 1f);
        shakeTime = SHAKE_DURATION_TICKS;
        if(player != null){
            funmode.core.Chat.send(Core.bundle.get("fun.foreshadow.discharge"));
        }
    }

    /**
     * The discharge shake, applied by offsetting the camera directly right before the world
     * renders - so it hits at full force even with screen shake disabled in the game settings.
     * The omen is not interested in your accessibility preferences.
     */
    void applyShake(){
        if(shakeTime <= 0f || !state.isGame()) return;
        shakeTime -= Time.delta;
        float falloff = shakeTime / SHAKE_DURATION_TICKS;
        Core.camera.position.add(Mathf.range(SHAKE_POWER * falloff), Mathf.range(SHAKE_POWER * falloff));
    }

    /**
     * The whole 5 minutes, visibly: a clock face above the turret filling CLOCKWISE with the
     * charge, then - in the final ten seconds - the collapsing ring and the frantic core.
     */
    void drawCharge(){
        if(!isActive() || charge.isEmpty()) return;

        Draw.z(Layer.effect);
        for(IntMap.Entry<Float> entry : charge){
            Building b = world.build(entry.key);
            if(b == null) continue;
            float progress = entry.value;
            //drawn on the turret itself, sized just past its footprint
            float clockRadius = b.block.size * 4f + 4f;

            //clock face: faint full circle + bright clockwise fill (arc end pinned at 12 o'clock,
            //start receding clockwise as the charge grows)
            Draw.color(Pal.bulletYellowBack, 0.25f);
            Lines.stroke(2f);
            Lines.circle(b.x, b.y, clockRadius);
            Draw.color(Pal.bulletYellow, 0.9f);
            Lines.stroke(3f);
            Lines.arc(b.x, b.y, clockRadius, progress, 90f - progress * 360f);

            if(progress >= 1f - WARN_FRACTION){
                //the final ten seconds
                float panic = (progress - (1f - WARN_FRACTION)) / WARN_FRACTION;

                Draw.color(Pal.bulletYellowBack, 0.35f + 0.5f * panic);
                Lines.stroke(2f + 4f * panic);
                Lines.circle(b.x, b.y, (1f - panic) * 30f * 8f + 10f);

                Draw.color(Pal.bulletYellow, 0.4f + 0.4f * Mathf.absin(Time.time, 4f - 3f * panic, 1f));
                Fill.circle(b.x, b.y, 6f + 12f * panic);

                Drawf.light(b.x, b.y, 120f + 260f * panic, Pal.bulletYellowBack, 0.8f);
            }
        }
        Draw.reset();
    }

    @Override
    public void buildDebug(arc.scene.ui.layout.Table table){
        table.button("Знамение: залп через 15с", () -> {
            float target = 1f - (15f * 60f) / PERIOD_TICKS;
            for(IntMap.Entry<Float> entry : charge){
                charge.put(entry.key, Math.max(entry.value, target));
            }
        }).size(220f, 50f);
    }
}
