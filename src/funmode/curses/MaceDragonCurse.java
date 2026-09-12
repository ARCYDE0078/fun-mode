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
 * clone of vanilla Fx.shootSmallFlame with the particle travel stretched from 60 world units to the
 * full 7.5 tiles, a longer lifetime, and a few more particles so the stream doesn't look sparse.
 * <p>
 * Nerfed down from an original 20 tiles - that reach made the mace outrange most early turrets, at
 * sonka's request.
 */
public class MaceDragonCurse implements Curse{
    static final float RANGE_TILES = 7.5f;

    /** Fx.shootSmallFlame at dragon scale: same colors and shape, ~2.7x the reach, 2x the lifetime. */
    static final Effect longFlame = new Effect(64f, 220f, e -> {
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
