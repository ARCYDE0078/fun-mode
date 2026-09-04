package funmode.curses;

import arc.Core;
import arc.Events;
import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import funmode.core.Chaos;
import funmode.core.Curse;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.gen.Sounds;
import mindustry.entities.Damage;
import mindustry.game.EventType.Trigger;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.Tile;

import static mindustry.Vars.net;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.ui;
import static mindustry.Vars.world;

/**
 * An endgame block: the Chaos Stabilizer. Expensive to build, and it can only be PLACED on a sector
 * that currently has no enemy units standing anywhere on the map - it's a victory monument, not a
 * panic button. Once built and fully fed (power + a steady item feed + cryofluid coolant), it doesn't
 * just calm chaos like the altar does - it completely eradicates it: {@link Chaos#suppressed} zeroes
 * {@link Chaos#frequencyMult()} outright (rather than merely relaxing it back to the un-scaled 1x that
 * {@code Chaos.active=false} alone would give) and the level itself is pinned to zero. Starve any one
 * of its three inputs and chaos resumes climbing from wherever it left off.
 * <p>
 * Detection/suppression runs identically on every machine (no needsHost gate): it only reads the
 * block's own synced {@code fullySupplied} state and writes to the local {@link Chaos} statics, the
 * same way {@link ChaosCurse} itself keeps every client's chaos meter in step without RPCs.
 */
public class ChaosStabilizerCurse implements Curse{
    static final float ITEM_PERIOD = 300f; //5s per feed cycle
    //NOT "final" and NOT built here: this class loads (as a static field initializer would run) inside
    //FunModeMod's constructor, well before ContentLoader has populated Items.thorium etc. - building the
    //stack eagerly bakes in null items forever, which is exactly what crashed ConsumeItems.apply() at
    //content-init time. Assigned instead in loadContent(), which vanilla guarantees runs after Items loads.
    static ItemStack[] ITEM_COST;
    static final float POWER_USE = 20f;
    static final float LIQUID_USE = 0.3f;
    static final float EXPLODE_RADIUS_TILES = 9f;
    static final float EXPLODE_DAMAGE = 4000f;

    //host/SP only (see the explode() gate below) - one collect-then-kill buffer reused every tick,
    //avoids mutating Groups.build mid-iteration
    final Seq<Building> explodeBuf = new Seq<>();

    Block stabilizerBlock;

    @Override
    public String id(){
        return "chaos-stabilizer";
    }

    @Override
    public String titleKey(){
        return "fun.curse.chaos-stabilizer.title";
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(stabilizerBlock, "fun.lore.chaos-stabilizer");
        }

        Events.run(Trigger.update, this::update);
    }

    void update(){
        if(!isEnabled() || !state.isGame() || state.isPaused() || player == null){
            Chaos.suppressed = false;
            return;
        }

        boolean stabilizing = false;
        for(Building b : Groups.build){
            if(b.team == player.team() && b instanceof ChaosStabilizer.ChaosStabilizerBuild sb && sb.fullySupplied){
                stabilizing = true;
                break;
            }
        }

        boolean was = Chaos.suppressed;
        Chaos.suppressed = stabilizing;
        if(stabilizing){
            Chaos.level = 0f;
            Chaos.calm = 0f;
        }
        if(stabilizing && !was){
            announce("fun.chaos-stabilizer.online", "[lime]The Chaos Stabilizer hums to life - chaos is silenced.[]");
        }else if(!stabilizing && was){
            announce("fun.chaos-stabilizer.offline", "[orange]The Chaos Stabilizer falls silent - chaos resumes.[]");
        }

        checkOverrun();
    }

    /**
     * The stabilizer is a "sector is clear" victory monument - it was never built to withstand an
     * actual attack. If enemy units ever set foot back on its sector, it can't take the strain and
     * detonates violently instead of just switching off like a starved supply would.
     * <p>
     * Host/SP only: kill() + Damage.damage() are real gameplay mutations (unlike the suppression
     * logic above, which just mirrors synced building state), so running this on a client too would
     * double-apply the damage.
     */
    void checkOverrun(){
        if(net.client()) return;

        explodeBuf.clear();
        for(Building b : Groups.build){
            if(b instanceof ChaosStabilizer.ChaosStabilizerBuild && !ChaosStabilizer.noEnemiesPresent(b.team)){
                explodeBuf.add(b); //collect-then-kill (no mid-iteration removal)
            }
        }
        if(explodeBuf.isEmpty()) return;

        for(int i = 0; i < explodeBuf.size; i++){
            Building b = explodeBuf.get(i);
            funmode.core.Vfx.at(Fx.reactorExplosion, b.x, b.y);
            funmode.core.Vfx.soundAt(Sounds.explosionReactor, b.x, b.y, 1f, 1f);
            Damage.damage(b.x, b.y, EXPLODE_RADIUS_TILES * tilesize, EXPLODE_DAMAGE);
            b.kill();
        }
        announce("fun.chaos-stabilizer.overrun", "[scarlet]Enemies have returned to the sector - the Chaos Stabilizer couldn't hold and blew apart.[]");
    }

    static void announce(String key, String fallback){
        String out = Core.bundle.has(key) ? Core.bundle.get(key) : fallback;
        if(ui != null && ui.chatfrag != null) funmode.core.Chat.send(out);
    }

    @Override
    public void loadContent(){
        ITEM_COST = ItemStack.with(Items.thorium, 30, Items.phaseFabric, 15);
        stabilizerBlock = new ChaosStabilizer("chaos-stabilizer");
        //hung under the impact reactor - itself the "resources + power + cryofluid" endgame reactor
        //this block mirrors, so unlocking one naturally leads into the other
        funmode.core.Research.node(stabilizerBlock, mindustry.content.Blocks.impactReactor);
    }

    public static class ChaosStabilizer extends Block{
        public final int timerFeed = timers++;

        public ChaosStabilizer(String name){
            super(name);
            requirements(Category.effect, ItemStack.with(
                Items.thorium, 3000, Items.phaseFabric, 2000, Items.surgeAlloy, 3000,
                Items.silicon, 4000, Items.metaglass, 2000
            ));
            size = 4;
            health = 1600;
            update = true;
            solid = true;
            destructible = true;
            //MP: sync so the fed/starved state (and thus whether it's suppressing chaos) reaches clients
            sync = true;
            hasPower = true;
            hasLiquids = true;
            hasItems = true;
            liquidCapacity = 40f;
            itemCapacity = 60;
            consumePower(POWER_USE);
            consumeLiquid(Liquids.cryofluid, LIQUID_USE);
            //registering this is what lets belts actually deposit items into the block at all - Block.itemFilter
            //is only populated from registered consumers, and Building.acceptItem() rejects everything otherwise,
            //so a purely hand-rolled items.has()/remove() check (the old approach) could never receive anything
            consumeItems(ITEM_COST);
        }

        /** Only buildable once the sector is clear - not a panic button mid-swarm. */
        @Override
        public boolean canPlaceOn(Tile tile, Team team, int rotation){
            return super.canPlaceOn(tile, team, rotation) && noEnemiesPresent(team);
        }

        @Override
        public void drawPlace(int x, int y, int rotation, boolean valid){
            super.drawPlace(x, y, rotation, valid);
            Tile tile = world.tile(x, y);
            if(tile != null && player != null && !noEnemiesPresent(player.team())){
                drawPlaceText(Core.bundle.get("fun.chaos-stabilizer.enemies-present", "Sector must be clear of enemies"), x, y, valid);
            }
        }

        static boolean noEnemiesPresent(Team team){
            for(Unit u : Groups.unit){
                //derelict is the neutral/scrap team (erekir ruins), never a real threat
                if(u.team() != team && u.team() != Team.derelict) return false;
            }
            return true;
        }

        public class ChaosStabilizerBuild extends Building{
            public boolean fullySupplied = false;

            @Override
            public void updateTile(){
                //efficiency already folds in power+liquid+item availability (Building.updateConsumption()
                //takes the min of every registered consumer's efficiency()), so gating on it here and then
                //actually deducting the items via consume() on a fixed cycle matches vanilla's own
                //ImpactReactor pattern - see ImpactReactorBuild.updateTile()
                if(efficiency >= 0.999f){
                    if(timer(timerFeed, ITEM_PERIOD)) consume();
                    fullySupplied = true;
                }else{
                    fullySupplied = false;
                }
            }

            @Override
            public void write(Writes write){
                super.write(write);
                write.bool(fullySupplied);
            }

            @Override
            public void read(Reads read, byte revision){
                super.read(read, revision);
                fullySupplied = read.bool();
            }
        }
    }
}
