package funmode.curses;

import funmode.core.Curse;
import mindustry.content.Items;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.world.blocks.distribution.Router;

/**
 * ROUTER. New routers in sizes 3x3, 5x5, 7x7, 9x9 and 11x11 - each one exactly the vanilla router
 * texture, upscaled, as the community intended. Costs and health scale with the footprint. They
 * work precisely as well as a normal router, which is to say: it's a router.
 */
public class BigRoutersCurse implements Curse{
    static final int[] SIZES = {3, 5, 7, 9, 11};

    final mindustry.world.Block[] routers = new mindustry.world.Block[SIZES.length];

    @Override
    public String id(){
        return "big-routers";
    }

    @Override
    public String titleKey(){
        return "fun.curse.big-routers.title";
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            for(mindustry.world.Block r : routers){
                funmode.core.Lore.details(r, "fun.lore.big-router");
            }
        }
    }

    @Override
    public void loadContent(){
        //campaign research: chain the big routers BY SIZE after the vanilla router (router → 3 → 5
        //→ 7 → …), each unlocked from the previous size (custom/sandbox keep them all free)
        mindustry.world.Block prev = mindustry.content.Blocks.router;
        for(int i = 0; i < SIZES.length; i++){
            int routerSize = SIZES[i];
            Router r = new Router("router" + routerSize){{
                requirements(Category.distribution, ItemStack.with(Items.copper, 3 * routerSize * routerSize));
                size = routerSize;
                health = 90 * routerSize * routerSize;
                alwaysUnlocked = true;
            }};
            funmode.core.Research.node(r, prev);
            routers[i] = r;
            prev = r;
        }
    }
}
