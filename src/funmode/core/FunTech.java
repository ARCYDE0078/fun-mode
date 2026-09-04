package funmode.core;

import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.meta.BuildVisibility;

/**
 * The mod's own little branch in the campaign tech tree - "Fun Mode technologies". Any feature that
 * wants a research gate calls {@link #tech}: it gets a hidden, non-buildable "technology" node hung
 * under a shared HUB, and the feature switches on once {@code tech.unlockedNow()} is true (in the
 * campaign that means researched; in custom/sandbox it's always true).
 * <p>
 * The hub is created lazily by the first feature that asks for a tech, and hangs off the very start of
 * the Serpulo tree ({@code coreShard}), so the order curses load in doesn't matter and only the
 * features that are actually enabled add a leaf. Each node needs a sprite at
 * {@code assets/sprites/<name>.png}.
 */
public final class FunTech{
    public static Block hub;

    private FunTech(){
    }

    /** A hidden research-only node under the Fun Mode hub. {@code requires} are vanilla blocks the node
     * demands you research FIRST (shown as "Research: X" objectives) - so each Fun Mode tech is tied to
     * the thematically-related content it builds on. Gate the feature on the returned block's
     * {@code unlockedNow()}. */
    public static Block tech(String name, ItemStack[] cost, Block... requires){
        ensureHub();
        return techUnder(hub, name, cost, requires);
    }

    /** Like {@link #tech} but hangs the new node under an arbitrary Fun Mode tech node instead of
     * the hub - for curses that want a straight chain of research gates (each one only reachable
     * after the last) instead of parallel branches off the hub. */
    public static Block techUnder(Block parent, String name, ItemStack[] cost, Block... requires){
        Block t = hidden(name, cost);
        Research.node(t, parent);
        if(t.techNode != null){
            for(Block req : requires){
                if(req != null) t.techNode.objectives.add(new mindustry.game.Objectives.Research(req));
            }
        }
        return t;
    }

    static void ensureHub(){
        if(hub != null) return;
        //hang the branch off the SECOND core - a mid-game milestone, so it opens only once you've
        //expanded past the opening sectors, not at the very start
        hub = hidden("fun-tech-hub", ItemStack.with(Items.copper, 180, Items.lead, 120, Items.silicon, 90));
        Research.node(hub, Blocks.coreFoundation);
        //opening the whole branch also demands you've CAPTURED a sector - real campaign progress first
        if(hub.techNode != null){
            hub.techNode.objectives.add(new mindustry.game.Objectives.SectorComplete(mindustry.content.SectorPresets.frozenForest));
        }
    }

    /** A block that exists only as a tech-tree token: never in the build menu, researched to unlock. */
    static Block hidden(String name, ItemStack[] cost){
        return new Block(name){{
            buildVisibility = BuildVisibility.hidden;
            alwaysUnlocked = false;
            requirements(Category.effect, cost);
        }};
    }
}
