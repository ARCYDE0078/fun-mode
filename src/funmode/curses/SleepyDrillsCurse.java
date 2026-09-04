package funmode.curses;

import arc.Events;
import arc.graphics.g2d.Draw;
import arc.math.Mathf;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.entities.Units;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.graphics.Layer;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.blocks.production.Drill;

import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;

/**
 * Drills (and the oil extractor, built on the same idea) are only human: every once in a while one
 * of them dozes off ({@code enabled = false}) and stops working until it's woken - by a click, or by
 * ANY unit passing within {@link #WAKE_RANGE} of it (units are alarm clocks). A bobbing pause icon
 * marks the sleeper. Host/SP only.
 */
public class SleepyDrillsCurse implements Curse{
    static final float SCAN_INTERVAL_TICKS = 60f;
    /** Per scan (=second) per drill; averages out to one nap per drill every ~10 minutes. */
    static final double SLEEP_CHANCE = 1.0 / 600.0;
    /** Any unit passing this close to a napping drill wakes it - units are alarm clocks. */
    static final float WAKE_RANGE = 6f * tilesize;
    /** Researched "Drill Alarm System" makes drills nod off far less often. */
    static final double ALARM_MULT = 0.25;
    Block drillTech;

    /** oilExtractor is a Fracker, not a Drill, so it needs its own explicit check everywhere. */
    static boolean napper(Block b){
        return b instanceof Drill || b == Blocks.oilExtractor;
    }

    /** Packed positions of currently sleeping drills. */
    final IntSet sleeping = new IntSet();
    final IntSeq stale = new IntSeq();
    float scanTimer = 0f;

    @Override
    public String id(){
        return "sleepy-drills";
    }

    @Override
    public String titleKey(){
        return "fun.curse.sleepy-drills.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void loadContent(){
        //tied to the laser drill - once your mining is serious, it's worth keeping the drills awake
        drillTech = funmode.core.FunTech.tech("drill-alarms",
            ItemStack.with(Items.copper, 160, Items.lead, 120, Items.silicon, 90, Items.titanium, 60),
            mindustry.content.Blocks.laserDrill);

        //MP: a napping drill is just enabled=false, and there's no Call.setEnabled - so mark every drill
        //`sync=true` and the engine periodically syncs its full state (incl. enabled) to clients. Without
        //this the drill only stops on the host; clients keep seeing it run. draw() then derives the Zzz
        //on all machines from the (now-synced) enabled flag.
        for(Block b : mindustry.Vars.content.blocks()){
            if(napper(b)) b.sync = true;
        }
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(mindustry.content.Blocks.laserDrill, "fun.lore.drill-alarms");
        }

        Events.on(WorldLoadEvent.class, e -> sleeping.clear());
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.draw, this::draw);

        Events.on(TapEvent.class, e -> {
            if(e.tile == null || e.tile.build == null) return;
            if(sleeping.remove(e.tile.build.pos())){
                e.tile.build.enabled = true;
            }
        });
    }

    void update(){
        if(!isActive() || player == null) return;

        scanTimer += Time.delta;
        if(scanTimer < SCAN_INTERVAL_TICKS) return;
        scanTimer = 0f;

        //researched Drill Alarms cut the nap rate (campaign only)
        double sleepMult = drillTech != null && drillTech.unlockedNow() ? ALARM_MULT : 1.0;
        //tuck in new sleepers
        Groups.build.each(b -> {
            if(b.team != player.team() || !napper(b.block)) return;
            if(b.enabled && Mathf.chance(SLEEP_CHANCE * funmode.core.Chaos.frequencyMult() * sleepMult)){
                b.enabled = false;
                sleeping.add(b.pos());
            }
        });

        //any unit passing within range wakes a napping drill (any team - a unit is a unit)
        IntSet.IntSetIterator wit = sleeping.iterator();
        while(wit.hasNext){
            Building b = world.build(wit.next());
            if(b != null && !b.enabled && Units.any(b.x - WAKE_RANGE, b.y - WAKE_RANGE, WAKE_RANGE * 2f, WAKE_RANGE * 2f, u -> !u.dead())){
                b.enabled = true; //the cleanup pass below then drops it from the sleeping set
            }
        }

        //forget drills that were destroyed, replaced, or woken (by tap, by a passing unit, by logic...)
        stale.clear();
        IntSet.IntSetIterator it = sleeping.iterator();
        while(it.hasNext){
            int pos = it.next();
            Building b = world.build(pos);
            if(b == null || !napper(b.block) || b.team != player.team() || b.enabled){
                stale.add(pos);
            }
        }
        for(int i = 0; i < stale.size; i++){
            sleeping.remove(stale.get(i));
        }
    }

    @Override
    public void buildDebug(arc.scene.ui.layout.Table table){
        table.button("Усыпить случайный бур", () -> {
            if(!isActive() || player == null) return;
            arc.struct.Seq<Building> drills = new arc.struct.Seq<>();
            Groups.build.each(b -> {
                if(b.team == player.team() && napper(b.block) && b.enabled) drills.add(b);
            });
            Building victim = drills.random();
            if(victim != null){
                victim.enabled = false;
                sleeping.add(victim.pos());
            }
        }).size(220f, 50f);
    }

    //Runs on EVERY machine (not isActive-gated) - clients get `enabled` via the drill's block.sync, so a
    //sleeping drill (enabled=false) is derivable everywhere. Draws the Zzz over any stopped own-team drill.
    void draw(){
        if(!isEnabled() || !state.isGame() || player == null) return;

        Draw.z(Layer.overlayUI);
        for(Building b : Groups.build){
            if(b.enabled || b.team != player.team() || !napper(b.block)) continue;
            float bob = Mathf.absin(Time.time, 8f, 1.5f);
            Draw.rect(Icon.pause.getRegion(), b.x, b.y + b.block.size * 4f + 2f + bob, 6f, 6f);
        }
        Draw.reset();
    }
}
