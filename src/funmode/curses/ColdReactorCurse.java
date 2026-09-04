package funmode.curses;

import arc.graphics.Color;
import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.world.blocks.power.NuclearReactor;

/**
 * The thorium reactor's heat gauge lies. Just that. Vanilla draws the heat overlay as a plain
 * cool-to-hot color lerp by heat (NuclearReactor draw()), so swapping the block's two color fields
 * inverts the whole display: a stone-cold safe reactor glows scorching orange, and one genuinely
 * about to detonate looks perfectly calm. The actual reactor mechanics are untouched - it heats,
 * cools and explodes exactly like vanilla. You just can't trust your eyes anymore.
 */
public class ColdReactorCurse implements Curse{
    @Override
    public String id(){
        return "cold-reactor";
    }

    @Override
    public String titleKey(){
        return "fun.curse.cold-reactor.title";
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(Blocks.thoriumReactor, "fun.lore.thorium-reactor");
        }
    }

    @Override
    public void loadContent(){
        NuclearReactor reactor = (NuclearReactor)Blocks.thoriumReactor;
        Color cool = reactor.coolColor;
        reactor.coolColor = reactor.hotColor;
        reactor.hotColor = cool;
    }
}
