package funmode.curses;

import arc.Events;
import funmode.core.Curse;
import mindustry.ai.types.GroundAI;
import mindustry.ai.types.SuicideAI;
import mindustry.content.Fx;
import mindustry.content.UnitTypes;
import mindustry.entities.Damage;
import mindustry.game.EventType.UnitDestroyEvent;
import mindustry.gen.Sounds;
import mindustry.type.Weapon;

import static mindustry.Vars.tilesize;

/**
 * Cursed spider genetics - the two spiders swap lifestyles. The toxopid loses ALL its weapons and
 * becomes the world's largest crawler: it charges its target and detonates like a thorium reactor
 * (on attack via the crawler's old suicide bomb, or on plain death; either way the reactor blast
 * hits EVERYONE nearby, its own team included). The crawler stops exploding entirely: its bomb is
 * gone, replaced by a heavily watered-down copy of the toxopid's web cannon, and it fights like a
 * regular ground shooter. Toxopid explosion is host/SP; the weapon swaps are content mutations
 * applied on launch.
 */
public class SpiderGenesCurse implements Curse{
    //vanilla thorium reactor blast: explosionRadius = 19 tiles, explosionDamage = 5000
    static final float BOOM_RADIUS_TILES = 19f;
    static final float BOOM_DAMAGE = 5000f;
    static final float CRAWLER_DAMAGE_SCALE = 0.15f;

    @Override
    public String id(){
        return "spider-genes";
    }

    @Override
    public String titleKey(){
        return "fun.curse.spider-genes.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void init(){
        //campaign database lore, one line per swapped spider (only while this curse is on)
        if(isEnabled()){
            funmode.core.Lore.details(UnitTypes.toxopid, "fun.lore.unit.toxopid");
            funmode.core.Lore.details(UnitTypes.crawler, "fun.lore.unit.crawler");
        }

        funmode.core.PoisonClouds.init();
        Events.on(UnitDestroyEvent.class, e -> {
            if(!isActive() || e.unit == null || e.unit.type != UnitTypes.toxopid) return;

            funmode.core.Vfx.at(Fx.reactorExplosion, e.unit.x, e.unit.y);
            funmode.core.Vfx.soundAt(Sounds.explosionReactor, e.unit.x, e.unit.y, 1f, 1f);
            Damage.damage(e.unit.x, e.unit.y, BOOM_RADIUS_TILES * tilesize, BOOM_DAMAGE);
            //the fallout: a big lingering poison cloud over the crater (per sonka, catalog units
            //round) - damages and saps EVERYTHING inside, both teams, like the blast itself
            funmode.core.PoisonClouds.spawn(e.unit.x, e.unit.y, CLOUD_RADIUS, CLOUD_LIFE, CLOUD_DPS);
        });
    }

    static final float CLOUD_RADIUS = 60f;
    static final float CLOUD_LIFE = 60f * 9f;
    static final float CLOUD_DPS = 10f;

    @Override
    public void loadContent(){
        //captured BEFORE the crawler gets its new cannon, so this is the plain suicide bomb
        Weapon suicideBomb = UnitTypes.crawler.weapons.first().copy();

        Weapon cannon = UnitTypes.toxopid.weapons.find(w -> w.name.contains("toxopid-cannon"));
        if(cannon != null && cannon.bullet != null){
            //deep-ish copy: the bullet must be cloned too, or weakening it would ALSO weaken the original
            Weapon copy = cannon.copy();
            copy.bullet = cannon.bullet.copy();
            copy.bullet.damage *= CRAWLER_DAMAGE_SCALE;
            copy.bullet.splashDamage *= CRAWLER_DAMAGE_SCALE;
            copy.reload = cannon.reload * 2f;
            copy.x = 0f;
            copy.y = 0f;
            copy.mirror = false;
            //40%-scale sprite shipped with the mod, so the crawler stays visible under its new gun
            copy.name = "sonka-fun-mode-crawler-web";
            //the web cannon is the crawler's ONLY weapon now - the suicide bomb is gone entirely,
            //so it's a normal aimed weapon and the crawler fights like a regular ground shooter
            UnitTypes.crawler.weapons.clear();
            UnitTypes.crawler.weapons.add(copy);
            UnitTypes.crawler.aiController = GroundAI::new;
        }

        //the toxopid: no weapons, just commitment - a crawler-style suicide charge (the bomb's
        //killShooter triggers death on attack, and death triggers the reactor blast above)
        UnitTypes.toxopid.weapons.clear();
        UnitTypes.toxopid.weapons.add(suicideBomb);
        UnitTypes.toxopid.aiController = SuicideAI::new;
    }
}
