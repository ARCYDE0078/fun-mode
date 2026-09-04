package funmode.curses;

import arc.Events;
import arc.math.Mathf;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.world.blocks.power.ConsumeGenerator;
import mindustry.world.consumers.ConsumeItemExplode;

import static mindustry.Vars.state;

/**
 * The combustion and steam generators now also run JUST FINE on blast compound - the little detail
 * where explosive fuel explodes has been removed as a design oversight. Coal still works (it's what
 * they were built for) and is a solid EARLY power source - ~480 power/s combustion, ~1320/s steam -
 * down from the old absurd 2700. The catch: coal now very slowly WEARS THE GENERATOR DOWN (~2 dps,
 * minutes to break one), so you can lean on coal at the start but will want cleaner fuel eventually.
 * Content mutation applies on launch; the coal wear is live (host/SP).
 */
public class BlastGeneratorsCurse implements Curse{
    static final float RUN_INTERVAL = 15f;
    //coal is the fuel they were BUILT for and works fine early on - it just very slowly wears the
    //generator down (minutes), so eventually you'll want cleaner fuel, but you can lean on coal at first
    static final float COAL_DPS = 2f;
    float runTimer = 0f;

    @Override
    public String id(){
        return "blast-generators";
    }

    @Override
    public String titleKey(){
        return "fun.curse.blast-generators.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(Blocks.combustionGenerator, "fun.lore.combustion-generator");
            funmode.core.Lore.details(Blocks.steamGenerator, "fun.lore.steam-generator");
        }
        Events.run(Trigger.update, () -> {
            if(!isActive() || state.isPaused()) return;
            runTimer += Time.delta;
            if(runTimer < RUN_INTERVAL) return;
            float step = runTimer;
            runTimer = 0f;

            for(Building b : Groups.build){
                if((b.block != Blocks.combustionGenerator && b.block != Blocks.steamGenerator)) continue;
                if(b.items != null && b.items.get(Items.coal) > 0){
                    b.damage(COAL_DPS / 60f * step);
                    if(Mathf.chanceDelta(0.06f)) funmode.core.Vfx.at(Fx.smoke, b.x + Mathf.range(6f), b.y + Mathf.range(6f));
                }
            }
        });
    }

    @Override
    public void loadContent(){
        ConsumeGenerator combustion = (ConsumeGenerator)Blocks.combustionGenerator;
        ConsumeGenerator steam = (ConsumeGenerator)Blocks.steamGenerator;

        //the "explosive items detonate the generator" consumer - a design oversight, clearly
        combustion.removeConsumers(c -> c instanceof ConsumeItemExplode);
        steam.removeConsumers(c -> c instanceof ConsumeItemExplode);

        //production scales by fuel flammability (blast compound = 0.4): these bases put blast at
        //~1080/s and ~3200/s respectively, per the spec
        //toned way down from the old cheat numbers (power/s = powerProduction × fuel flammability × 60):
        //coal (flammability 1.0) now gives ~480/s combustion / ~1320/s steam - a solid early power source,
        //not the old 2700. Blast compound (0.4) gives less here, but it doesn't wear the generator down.
        combustion.powerProduction = 8f;
        steam.powerProduction = 22f;
    }
}
