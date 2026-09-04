package funmode.curses;

import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.consumers.ConsumeItems;

/**
 * The graphite press and multi-press now work in ENORMOUS batches: every input amount, output amount
 * and the craft time are all multiplied by 100. Same throughput in the end, but instead of trickling
 * one graphite at a time they hoard a hundred coal and then belch out a hundred graphite at once.
 * Content mutation, applies on launch.
 */
public class GraphiteBatchCurse implements Curse{
    static final int MULT = 100;

    @Override
    public String id(){
        return "graphite-batch";
    }

    @Override
    public String titleKey(){
        return "fun.curse.graphite-batch.title";
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(Blocks.graphitePress, "fun.lore.graphite-press");
            funmode.core.Lore.details(Blocks.multiPress, "fun.lore.graphite-press");
        }
    }

    @Override
    public void loadContent(){
        bulk(Blocks.graphitePress);
        bulk(Blocks.multiPress);
    }

    void bulk(Block block){
        if(!(block instanceof GenericCrafter crafter)) return;

        ConsumeItems consume = crafter.findConsumer(c -> c instanceof ConsumeItems);
        if(consume != null){
            for(ItemStack stack : consume.items) stack.amount *= MULT;
        }
        //these presses use the SINGULAR outputItem field (not the outputItems array) - that's why the
        //graphite output wasn't scaling before
        if(crafter.outputItem != null) crafter.outputItem.amount *= MULT;
        if(crafter.outputItems != null){
            for(ItemStack stack : crafter.outputItems) stack.amount *= MULT;
        }
        crafter.craftTime *= MULT;
        //hold a full batch of both the coal going in and the graphite coming out
        crafter.itemCapacity = Math.max(crafter.itemCapacity, MULT * 2 + 100);
    }
}
