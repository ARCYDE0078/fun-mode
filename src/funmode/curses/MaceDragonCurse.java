package funmode.curses;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.Angles;
import funmode.core.Curse;
import mindustry.content.UnitTypes;
import mindustry.entities.Effect;
import mindustry.entities.bullet.BulletType;
import mindustry.graphics.Pal;
import mindustry.type.Weapon;

import static mindustry.Vars.tilesize;

/**
 * The mace's flamethrower now reaches 7.5 tiles - and LOOKS it, too. Flame range is speed*lifetime
 * (stretched before content init so the unit's attack range recomputes), and the shoot effect is a
 * clone of vanilla Fx.shootSmallFlame with the particle travel driven by the same {@link #RANGE_TILES}
 * constant, so visual and mechanical range can never drift apart.
 * <p>
 * Nerfed down from an original 20 tiles - that reach made the mace outrange most early turrets, at
 * sonka's request. At 20 tiles the burst's own life/clip (64/220) were stretched well past vanilla's
 * 32/80 to give particles time to cross the much longer distance; now that 7.5 tiles works out to the
 * same 60 world units as vanilla's flame, life/clip went back down to vanilla's own values too - left
 * at the old stretched numbers, the burst lingered on screen far longer than the mace's 22-tick reload,
 * so 2-3 shots' flames overlapped at once and visually smeared past the real 7.5-tile edge.
 */
public class MaceDragonCurse implements Curse{
    static final float RANGE_TILES = 7.5f;

    /** Fx.shootSmallFlame at dragon scale: same colors, shape and timing, just a few more particles
     * so the stream doesn't look sparse. */
    static final Effect longFlame = new Effect(32f, 80f, e -> {
        Draw.color(Pal.lightFlame, Pal.darkFlame, Color.gray, e.fin());

        Angles.randLenVectors(e.id, 18, e.finpow() * RANGE_TILES * tilesize, e.rotation, 10f, (x, y) -> {
            Fill.circle(e.x + x, e.y + y, 0.65f + e.fout() * 1.6f);
        });
    }).followParent(false);

    @Override
    public String id(){
        return "mace-dragon";
    }

    @Override
    public String titleKey(){
        return "fun.curse.mace-dragon.title";
    }

    @Override
    public void init(){
        //campaign database lore, layered onto whatever UnitReworksCurse already wrote for mace
        if(isEnabled()) funmode.core.Lore.details(UnitTypes.mace, "fun.lore.unit.mace-dragon");
    }

    @Override
    public void loadContent(){
        for(Weapon weapon : UnitTypes.mace.weapons){
            BulletType bullet = weapon.bullet;
            if(bullet == null || bullet.speed <= 0f) continue;
            bullet.lifetime = RANGE_TILES * tilesize / bullet.speed;
            bullet.shootEffect = longFlame;
        }
    }
}
