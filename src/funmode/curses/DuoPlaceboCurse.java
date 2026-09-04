package funmode.curses;

import arc.graphics.Color;
import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.entities.bullet.BasicBulletType;
import mindustry.world.blocks.defense.turrets.ItemTurret;

/**
 * The duo now fires an absolutely devastating-looking projectile - huge, glowing, trailing fire,
 * exploding on impact. It deals 5 damage. Mutates the existing ammo BulletTypes in place before
 * content init, so no new content registration (and no net-id concerns) is involved.
 */
public class DuoPlaceboCurse implements Curse{
    @Override
    public String id(){
        return "duo-placebo";
    }

    @Override
    public String titleKey(){
        return "fun.curse.duo-placebo.title";
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(Blocks.duo, "fun.lore.duo");
        }
    }

    @Override
    public void loadContent(){
        ((ItemTurret)Blocks.duo).ammoTypes.each((item, bullet) -> {
            bullet.damage = 5f;
            bullet.splashDamage = 0f;
            bullet.trailWidth = 3.5f;
            bullet.trailLength = 12;
            bullet.trailColor = Color.valueOf("d4816b");
            bullet.shootEffect = Fx.shootBig;
            bullet.smokeEffect = Fx.shootBigSmoke;
            bullet.hitEffect = Fx.blastExplosion;
            bullet.despawnEffect = Fx.blastExplosion;
            bullet.hitShake = 3f;
            //sprite size/tint fields live on BasicBulletType; duo's standard/graphite ammo are that,
            //but stay safe against any other type
            if(bullet instanceof BasicBulletType basic){
                basic.width *= 7f;
                basic.height *= 7f;
                basic.shrinkY = 0f;
                basic.frontColor = Color.valueOf("ffaa5f");
                basic.backColor = Color.valueOf("d4816b");
            }
        });
    }
}
