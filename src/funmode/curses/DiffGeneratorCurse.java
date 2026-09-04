package funmode.curses;

import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.content.Liquids;
import mindustry.entities.Damage;
import mindustry.entities.Effect;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Sounds;
import mindustry.graphics.Layer;
import mindustry.world.blocks.power.PowerGenerator;

import static mindustry.Vars.net;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;

/**
 * The differential generator gets the full thorium reactor treatment. Destroyed while running, it
 * detonates at reactor scale - except the smoke cloud is ORANGE, per the spec. And like the
 * thorium reactor needs cryofluid, so does this one now REALLY need it: a fueled generator with no
 * cryofluid heats toward detonation (red glow ramps up, then it blows on its own). The
 * starvation boom is done manually - vanilla's built-in generator explosion only triggers while
 * producing, and a cryo-starved generator isn't. Heat drive is host/SP; the on-destroy explosion
 * fields are content mutations applied on launch.
 */
public class DiffGeneratorCurse implements Curse{
    /** Fueled but cryo-starved: cold to boom in ~7 seconds. */
    static final float HEAT_PER_TICK = 1f / (60f * 7f);
    static final float COOL_PER_TICK = 1f / (60f * 5f);
    static final float BOOM_RADIUS_TILES = 19f;
    static final float BOOM_DAMAGE = 5000f;

    /** A reactor-scale boom in warm colors: expanding shockwave ring + orange smoke plume. */
    static final Effect orangeBoom = new Effect(55f, 500f, e -> {
        Draw.color(Color.valueOf("ffb380"));
        Lines.stroke(e.fout() * 5f);
        Lines.circle(e.x, e.y, e.fin() * 110f);

        Draw.color(Color.valueOf("ff9c42"), Color.valueOf("5b5b5b"), e.fin());
        Angles.randLenVectors(e.id, 35, e.finpow() * 100f, (x, y) -> {
            Fill.circle(e.x + x, e.y + y, e.fout() * 13f + 2f);
        });
    });

    /** build pos -> starvation heat 0..1. */
    final IntMap<Float> heat = new IntMap<>();
    final IntSeq stale = new IntSeq();

    @Override
    public String id(){
        return "diff-generator";
    }

    @Override
    public String titleKey(){
        return "fun.curse.diff-generator.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(Blocks.differentialGenerator, "fun.lore.differential-generator");
        }
        Events.on(WorldLoadEvent.class, e -> heat.clear());
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.update, this::clientHeat); //client mirror so the glow shows for everyone
        Events.run(Trigger.draw, this::drawHeat);
    }

    @Override
    public void loadContent(){
        PowerGenerator generator = (PowerGenerator)Blocks.differentialGenerator;
        generator.explosionRadius = (int)BOOM_RADIUS_TILES;
        generator.explosionDamage = (int)BOOM_DAMAGE;
        generator.explodeEffect = orangeBoom;
        generator.explodeSound = Sounds.explosionReactor;
        //MP: sync the generator's items+cryofluid to clients so they can derive the starvation heat below
        generator.sync = true;
    }

    /**
     * CLIENT-side mirror of the starvation heat, so the red glow shows for everyone (the real sim in
     * {@link #update()} is host-only). Same accumulation from the (now-synced) fuel/cryo state, but it
     * NEVER booms - the host stays authoritative for the explosion (which then syncs as the block dies).
     */
    void clientHeat(){
        if(!net.client() || !isEnabled() || !state.isGame() || state.isPaused()) return;
        Groups.build.each(b -> {
            if(b.block != Blocks.differentialGenerator) return;
            boolean fueled = b.items != null && b.items.total() > 0;
            boolean starving = b.liquids == null || b.liquids.get(Liquids.cryofluid) <= 0.01f;
            float current = Mathf.clamp(heat.get(b.pos(), 0f) + (fueled && starving ? HEAT_PER_TICK : -COOL_PER_TICK) * Time.delta);
            if(current > 0f) heat.put(b.pos(), current); else heat.remove(b.pos());
        });
        stale.clear();
        for(IntMap.Entry<Float> entry : heat){
            Building b = world.build(entry.key);
            if(b == null || b.block != Blocks.differentialGenerator) stale.add(entry.key);
        }
        for(int i = 0; i < stale.size; i++) heat.remove(stale.get(i));
    }

    void update(){
        if(!isActive() || state.isPaused()) return;

        Groups.build.each(b -> {
            if(b.block != Blocks.differentialGenerator) return;

            boolean fueled = b.items != null && b.items.total() > 0;
            boolean starving = b.liquids == null || b.liquids.get(Liquids.cryofluid) <= 0.01f;

            float current = heat.get(b.pos(), 0f);
            if(fueled && starving){
                current += HEAT_PER_TICK * Time.delta;
            }else{
                current -= COOL_PER_TICK * Time.delta;
            }
            current = Mathf.clamp(current);

            if(current >= 1f){
                heat.remove(b.pos());
                boom(b);
                return;
            }

            if(current > 0f) heat.put(b.pos(), current);
            else heat.remove(b.pos());
        });

        //forget destroyed/replaced generators
        stale.clear();
        for(IntMap.Entry<Float> entry : heat){
            Building b = world.build(entry.key);
            if(b == null || b.block != Blocks.differentialGenerator) stale.add(entry.key);
        }
        for(int i = 0; i < stale.size; i++){
            heat.remove(stale.get(i));
        }
    }

    void boom(Building b){
        orangeBoom.at(b.x, b.y);
        Sounds.explosionReactor.at(b.x, b.y);
        Effect.shake(20f, 20f, b.x, b.y);
        Damage.damage(b.x, b.y, BOOM_RADIUS_TILES * tilesize, BOOM_DAMAGE);
        b.kill();
    }

    void drawHeat(){
        if(!isEnabled() || !state.isGame() || heat.isEmpty()) return;

        Draw.z(Layer.effect);
        for(IntMap.Entry<Float> entry : heat){
            Building b = world.build(entry.key);
            if(b == null) continue;
            float h = entry.value;
            //steady red glow plus an increasingly frantic flicker as it nears the end
            float alpha = h * 0.45f + (h > 0.7f ? Mathf.absin(Time.time, 3f, 0.25f) * (h - 0.7f) / 0.3f : 0f);
            Draw.color(Color.scarlet, alpha);
            Fill.rect(b.x, b.y, b.block.size * tilesize, b.block.size * tilesize);
        }
        Draw.reset();
    }
}
