package funmode.curses;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import funmode.core.Curse;
import mindustry.content.Items;
import mindustry.entities.Effect;
import mindustry.game.EventType.UnitDestroyEvent;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import static mindustry.Vars.player;
import static mindustry.Vars.state;

/**
 * A little battlefield economy, borrowed from Factorio's combat mods: every enemy unit that dies
 * leaves SALVAGE. A random slice of the materials it was built from is recovered straight into your
 * core - so bigger, costlier units pay out more, and holding the line quietly funds your factory.
 * <p>
 * In the campaign you only ever recover resources you've already RESEARCHED (a boss made of phase
 * fabric drops nothing exotic until you've unlocked it). A dropped-item icon pops off each kill for
 * feedback; the deposit is silent so a big wave doesn't spam the chat.
 * <p>
 * The whole feature is itself a campaign UNLOCK: a hidden "Salvage Protocol" technology sits in the
 * tech tree (under the duo), and enemy salvage stays off until it's researched. Outside the campaign
 * ({@code unlockedNow()}) it's always on. Host/SP only.
 */
public class EnemyLootCurse implements Curse{
    static final float DROP_FRAC_MIN = 0.10f, DROP_FRAC_MAX = 0.25f; //share of build cost recovered
    static final int LOOT_CAP_PER_ITEM = 50; //per item per kill, so a boss is a jackpot, not a flood

    /** A research-only "technology" node: in the campaign, enemy salvage stays off until this is unlocked. */
    Block salvageTech;

    /** A dropped-item icon popping up off a kill (item region passed as effect data). */
    static final Effect lootFx = new Effect(40f, e -> {
        if(!(e.data instanceof TextureRegion region)) return;
        Draw.color(Color.white, e.fout());
        Draw.rect(region, e.x, e.y + e.fin() * 11f, 7f, 7f); //rises and fades
        Draw.reset();
    });

    @Override
    public String id(){
        return "enemy-loot";
    }

    @Override
    public String titleKey(){
        return "fun.curse.enemy-loot.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void loadContent(){
        //a research node in the Fun Mode branch; enemy salvage stays off until it's unlocked (campaign)
        //tied to the separator - reprocessing scrap into materials is exactly salvaging wreckage
        salvageTech = funmode.core.FunTech.tech("salvage-protocol",
            ItemStack.with(Items.copper, 200, Items.lead, 150, Items.silicon, 120, Items.titanium, 80),
            mindustry.content.Blocks.separator);
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(mindustry.content.Blocks.separator, "fun.lore.salvage-protocol");
        }

        Events.on(UnitDestroyEvent.class, e -> onKill(e.unit));
    }

    void onKill(Unit unit){
        if(!isActive() || player == null || state.isPaused()) return;
        if(unit == null || unit.type == null || unit.team() == player.team()) return; //enemies only
        if(salvageTech != null && !salvageTech.unlockedNow()) return; //campaign: needs Salvage Protocol researched
        if(player.team().core() == null) return;

        float frac = Mathf.random(DROP_FRAC_MIN, DROP_FRAC_MAX);
        Item shown = null;
        int shownAmt = 0, total = 0;

        //recover a slice of what the unit was built from (auto-scales with its tier/cost)
        ItemStack[] cost = unit.type.getTotalRequirements();
        if(cost != null){
            for(ItemStack s : cost){
                int amt = Math.min(LOOT_CAP_PER_ITEM, Mathf.round(s.amount * frac));
                int added = deposit(s.item, amt);
                if(added > 0){
                    total += added;
                    if(added >= shownAmt){ shown = s.item; shownAmt = added; } //show the biggest drop
                }
            }
        }

        //units with no build recipe (or nothing researched yet) still shed a little scrap by size
        if(total == 0){
            int added = deposit(Items.copper, Math.max(1, (int)(unit.maxHealth / 140f)));
            if(added > 0){ total = added; shown = Items.copper; }
        }

        if(total > 0 && shown != null) lootFx.at(unit.x, unit.y, 0f, shown.uiIcon);
    }

    /** Dump loot into the team core, skipping resources not yet researched (campaign), clamped to space. */
    int deposit(Item item, int amount){
        if(amount <= 0 || item == null || !item.unlockedNow()) return 0;
        CoreBuild core = player.team().core();
        if(core == null) return 0;
        int space = Math.max(0, core.storageCapacity - core.items.get(item));
        int add = Math.min(amount, space);
        if(add > 0) core.items.add(item, add);
        return add;
    }

    @Override
    public void buildDebug(Table table){
        //no live enemies on the test path - drop a sample of loot at the core so the effect + deposit show
        table.button("Трофей: пример (в ядро)", () -> {
            if(!isActive() || player == null) return;
            CoreBuild core = player.team().core();
            if(core == null) return;
            deposit(Items.copper, 40);
            deposit(Items.silicon, 20);
            lootFx.at(core.x, core.y, 0f, Items.silicon.uiIcon);
        }).size(240f, 50f);
    }
}
