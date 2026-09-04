package funmode.curses;

import arc.Events;
import arc.math.Mathf;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.Duct;
import mindustry.world.blocks.distribution.StackConveyor;

import static mindustry.Vars.player;

/**
 * Your buildings won't hold still. Every so often a rotatable block already sitting on the map
 * spontaneously spins to face a random new direction - a conveyor turns on itself, a sorter aims at a
 * wall, a turret goes to admire the scenery - and its neighbours re-evaluate around the new facing.
 * It's not the build crew anymore; the buildings themselves fidget as they run. Only your own team's
 * blocks, and the churn worsens as chaos climbs. Host/SP only.
 */
public class CrookedBuildingCurse implements Curse{
    static final float SCAN_INTERVAL = 60f;   //roll each building about once a second
    /** Per rotatable building per scan; a steady trickle of spontaneous turns, not a whirlwind. */
    static final float ROTATE_CHANCE = 1f / 500f;
    /** Researched "Gyroscopic Stabilizers" make buildings hold their facing far better. */
    static final float GYRO_MULT = 0.2f;
    /** Rotations excluding north (1), for conveyor-family blocks - so this never fights the No Northward Conveyors curse. */
    static final int[] NON_NORTH = {0, 2, 3};

    float scanTimer = 0f;
    Block gyroTech;

    @Override
    public String id(){
        return "crooked-building";
    }

    @Override
    public String titleKey(){
        return "fun.curse.crooked-building.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void loadContent(){
        //tied to the plastanium compressor - precision engineering to keep buildings pointed straight
        gyroTech = funmode.core.FunTech.tech("gyro-stabilizers",
            ItemStack.with(Items.copper, 160, Items.lead, 100, Items.titanium, 100, Items.plastanium, 50),
            mindustry.content.Blocks.plastaniumCompressor);
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(mindustry.content.Blocks.plastaniumCompressor, "fun.lore.gyro-stabilizers");
        }

        Events.run(Trigger.update, this::update);
    }

    void update(){
        if(!isActive() || player == null) return;

        scanTimer += Time.delta;
        if(scanTimer < SCAN_INTERVAL) return;
        scanTimer = 0f;

        float base = ROTATE_CHANCE * funmode.core.Chaos.frequencyMult(); //worse as chaos climbs
        //researched Gyroscopic Stabilizers hold facings far better (effectively-final for the lambda)
        float chance = gyroTech != null && gyroTech.unlockedNow() ? base * GYRO_MULT : base;
        Groups.build.each(b -> {
            if(b.team != player.team() || !b.block.rotate) return;
            if(!Mathf.chance(chance)) return;
            spin(b);
        });
    }

    /** Turn a building to a random NEW facing (never the one it already has) and let neighbours react. */
    void spin(Building b){
        Block block = b.block;
        //conveyors/ducts never get spun to face north here - the No Northward Conveyors curse would just
        //blow them up, and one curse undoing another isn't funny, it's broken
        boolean conveyorFamily = block instanceof Conveyor || block instanceof StackConveyor || block instanceof Duct;
        int next;
        if(conveyorFamily){
            next = NON_NORTH[Mathf.random(NON_NORTH.length - 1)];
        }else{
            next = (b.rotation + 1 + Mathf.random(2)) % 4; //any of the other three facings
        }
        if(next == b.rotation) return;
        b.rotation = next;
        b.updateProximity(); //nudge neighbours so conveyors/sorters re-evaluate their connections
        Fx.rotateBlock.at(b.x, b.y, block.size); //the little turn puff, so you notice it happen
    }
}
