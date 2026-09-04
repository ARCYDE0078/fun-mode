package funmode.core;

import mindustry.content.TechTree;
import mindustry.world.Block;

/**
 * Slots the mod's custom blocks into the campaign tech tree. Vanilla's tree is built
 * ({@code SerpuloTechTree.load()}) BEFORE mods load content ({@code ContentLoader.createBaseContent}
 * runs before {@code createModContent}), so a parent vanilla block's {@code techNode} already exists
 * when a curse's loadContent calls this. Flips the child off always-unlocked and hangs it under the
 * given parent with default research costs derived from its build requirements.
 * <p>
 * Gating is campaign-only by design: {@code UnlockableContent.unlockedNow() = unlocked() ||
 * !state.isCampaign()}, so custom / multiplayer / sandbox games keep every block available - only
 * the actual campaign requires researching them. No-ops safely if the parent has no node.
 */
public final class Research{
    private Research(){
    }

    public static void node(Block child, Block parent){
        if(child == null || parent == null || parent.techNode == null) return;
        child.alwaysUnlocked = false;
        if(child.techNode == null){
            new TechTree.TechNode(parent.techNode, child, child.researchRequirements());
        }
    }

    /**
     * Slots {@code child} into the tree in {@code target}'s place, then re-hangs {@code target} (with
     * its whole subtree) underneath it - so the campaign must research {@code child} BEFORE it can
     * reach {@code target}. Used to gate the vanilla overdrive projector/dome behind their slow
     * counterparts. Safe no-op if the target has no node or is a root (no parent to graft onto).
     */
    public static void insertBefore(Block child, Block target){
        if(child == null || target == null || target.techNode == null) return;
        TechTree.TechNode tnode = target.techNode;
        TechTree.TechNode grandparent = tnode.parent;
        if(grandparent == null) return; //target is a root - nothing to insert before

        child.alwaysUnlocked = false;
        TechTree.TechNode newNode = child.techNode != null ? child.techNode
            : new TechTree.TechNode(grandparent, child, child.researchRequirements());

        //detach the target from its old parent and re-hang it (and everything below it) under child
        grandparent.children.remove(tnode);
        tnode.parent = newNode;
        if(!newNode.children.contains(tnode, true)) newNode.children.add(tnode);
        fixDepth(newNode);
    }

    /** Re-derive tree depth for a moved node and its subtree (depth is set once at construction). */
    private static void fixDepth(TechTree.TechNode n){
        if(n.parent != null) n.depth = n.parent.depth + 1;
        for(TechTree.TechNode c : n.children) fixDepth(c);
    }
}
