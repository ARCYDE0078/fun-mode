package funmode.curses;

import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.world.Block;
import mindustry.world.meta.BuildVisibility;

/**
 * The sandbox VOIDS - item, liquid and power - are buildable in survival now. Free, as is
 * tradition. At 1 health, so nobody gets clever ideas about using them as walls. Only the voids:
 * the sources stay sandbox-only, per sonka's spec - dumping stuff into nothing is fun, conjuring
 * stuff from nothing is cheating.
 */
public class SurvivalSandboxCurse implements Curse{
    @Override
    public String id(){
        return "survival-sandbox";
    }

    @Override
    public String titleKey(){
        return "fun.curse.survival-sandbox.title";
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(Blocks.itemVoid, "fun.lore.void");
            funmode.core.Lore.details(Blocks.liquidVoid, "fun.lore.void");
            funmode.core.Lore.details(Blocks.powerVoid, "fun.lore.void");
        }
    }

    @Override
    public void loadContent(){
        Block[] voids = {Blocks.itemVoid, Blocks.liquidVoid, Blocks.powerVoid};
        for(Block block : voids){
            block.buildVisibility = BuildVisibility.shown;
            block.health = 1;
        }
    }
}
