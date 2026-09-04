package funmode.core;

import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.content.StatusEffects;
import mindustry.entities.Effect;
import mindustry.entities.Units;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.graphics.Layer;

import static mindustry.Vars.state;

/**
 * Shared lingering poison-gas engine (static global, the Chaos/Coolant precedent): a cloud sits at
 * a point for a while, periodically damaging + sapping + slowing units inside. By default it's
 * team-blind (toxopid's death cloud hits everyone), but a cloud can be given a {@code friendly}
 * team to SPARE, so spiroct's venom doesn't corrode the spider that spat it or its packmates. Used
 * by spiroct's attack clouds (UnitReworksCurse) and toxopid's post-explosion cloud
 * (SpiderGenesCurse); it lives outside both so either curse works with the other disabled.
 * <p>
 * {@link #init()} is idempotent and self-registers the update/draw/world-load hooks exactly once -
 * every curse that spawns clouds calls it from its own init(), whichever runs first wins. The
 * engine always ticks (a no-op when empty); gating stays with the curses, which simply don't
 * spawn clouds while inactive.
 */
public class PoisonClouds{
    static final float DAMAGE_INTERVAL = 15f;
    static final Color POISON = Color.valueOf("bf92f9");

    /** Rising toxic wisp, tinted to match whichever cloud spawned it. */
    static final Effect wisp = new Effect(55f, e -> {
        Draw.color(e.color, 0.45f * e.fout());
        Fill.circle(e.x, e.y + e.finpow() * 9f, 1.6f + 2.6f * e.fin());
    });

    static class Cloud{
        float x, y, radius, life, maxLife, dps;
        Team friendly; //null = hits everyone; otherwise this team is spared
        Color color = POISON;
    }

    static final Seq<Cloud> clouds = new Seq<>();
    static boolean registered;
    static float damageTimer;

    /** Idempotent hook registration - call from any curse that spawns clouds. */
    public static void init(){
        if(registered) return;
        registered = true;
        Events.run(Trigger.update, PoisonClouds::update);
        Events.run(Trigger.draw, PoisonClouds::draw);
        Events.on(WorldLoadEvent.class, e -> clouds.clear());
    }

    public static void spawn(float x, float y, float radius, float lifeTicks, float dps){
        spawn(x, y, radius, lifeTicks, dps, null);
    }

    /** As above, but units of {@code friendly} are spared (pass the caster's team). */
    public static void spawn(float x, float y, float radius, float lifeTicks, float dps, Team friendly){
        spawn(x, y, radius, lifeTicks, dps, friendly, POISON);
    }

    /** As above, but with a custom cloud colour instead of the shared default purple - lets a
     * specific gag (e.g. cyerce's sting) read as visually distinct from the shared poison gas. */
    public static void spawn(float x, float y, float radius, float lifeTicks, float dps, Team friendly, Color color){
        Cloud c = new Cloud();
        c.x = x;
        c.y = y;
        c.radius = radius;
        c.life = c.maxLife = lifeTicks;
        c.dps = dps;
        c.friendly = friendly;
        c.color = color;
        clouds.add(c);
    }

    static void update(){
        if(clouds.isEmpty() || !state.isGame() || state.isPaused()) return;

        damageTimer += Time.delta;
        float step = damageTimer >= DAMAGE_INTERVAL ? damageTimer : 0f;
        if(step > 0) damageTimer = 0f;

        for(int i = clouds.size - 1; i >= 0; i--){
            Cloud c = clouds.get(i);
            c.life -= Time.delta;
            if(c.life <= 0){
                clouds.remove(i);
                continue;
            }

            if(Mathf.chanceDelta(0.10f * (c.radius / 24f))){
                wisp.at(c.x + Mathf.range(c.radius * 0.8f), c.y + Mathf.range(c.radius * 0.8f), c.color);
            }

            if(step > 0){
                Units.nearby(c.x - c.radius, c.y - c.radius, c.radius * 2f, c.radius * 2f, u -> {
                    if(u.dead() || !u.within(c.x, c.y, c.radius)) return;
                    if(c.friendly != null && u.team == c.friendly) return; //spare the caster's own team
                    u.damage(c.dps / 60f * step);
                    u.apply(StatusEffects.sapped, 60f);
                    u.apply(StatusEffects.sporeSlowed, 60f);
                });
            }
        }
    }

    static void draw(){
        if(clouds.isEmpty()) return;
        float pz = Draw.z();
        Draw.z(Layer.effect);
        for(Cloud c : clouds){
            //fades in fast, lingers, fades out over the last third of its life
            float alpha = 0.14f * Mathf.clamp(c.life / (c.maxLife * 0.34f));
            Draw.color(c.color, alpha);
            Fill.circle(c.x, c.y, c.radius);
            Lines.stroke(1f);
            Draw.color(c.color, alpha * 1.6f);
            Lines.circle(c.x, c.y, c.radius * (0.92f + Mathf.absin(Time.time, 9f, 0.06f)));
        }
        Draw.z(pz);
        Draw.color();
    }
}
