package funmode.curses;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.struct.IntSet;
import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.game.EventType.UnitCreateEvent;
import mindustry.game.EventType.UnitUnloadEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.world.Block;

import static mindustry.Vars.player;
import static mindustry.Vars.ui;

/**
 * Workplace safety is not what it used to be: freshly produced units have a chance to explode
 * right at the factory door - 20% for tier 1, 12%/10% for T2/T3, 6% for T4, 3% for T5 (tier judged by
 * the building that made them). Host/SP only: killing units is a simulation change.
 * <p>
 * Two-stage: the doom roll happens at {@link UnitCreateEvent} (the only moment the SPAWNER is
 * known), but at that point the unit is still a payload INSIDE the factory - not in the world, so
 * killing it there does nothing (found out the fun way). The actual kill waits for
 * {@link UnitUnloadEvent}, when the unit steps out the door.
 */
public class ExplosiveProductionCurse implements Curse{
    final IntSet doomed = new IntSet();
    /** Debug: dooms the very next produced unit regardless of the tier roll. */
    boolean doomNext = false;
    /** Units killed by quality control this session - so ObituariesCurse doesn't ALSO eulogize them. */
    static final IntSet qualityControlVictims = new IntSet();

    /** True (and forgets the id) if this unit just died to quality control rather than honestly. */
    public static boolean consumeQualityControlVictim(int unitId){
        return qualityControlVictims.remove(unitId);
    }

    @Override
    public String id(){
        return "explosive-production";
    }

    @Override
    public String titleKey(){
        return "fun.curse.explosive-production.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(Blocks.groundFactory, "fun.lore.factory-safety");
            funmode.core.Lore.details(Blocks.airFactory, "fun.lore.factory-safety");
            funmode.core.Lore.details(Blocks.navalFactory, "fun.lore.factory-safety");
        }
        Events.on(WorldLoadEvent.class, e -> {
            doomed.clear();
            qualityControlVictims.clear();
        });

        Events.on(UnitCreateEvent.class, e -> {
            if(!isActive() || e.unit == null || e.spawner == null) return;

            if(doomNext){
                doomNext = false;
                doomed.add(e.unit.id);
                return;
            }

            float chance = failureChance(e.spawner.block);
            if(chance > 0f && Mathf.chance(chance)){
                doomed.add(e.unit.id);
            }
        });

        Events.on(UnitUnloadEvent.class, e -> {
            if(!isActive() || e.unit == null) return;
            if(!doomed.remove(e.unit.id)) return;

            //flag BEFORE killing: the destroy event (and the obituary listener) can fire synchronously
            qualityControlVictims.add(e.unit.id);
            e.unit.kill();
            if(player != null && e.unit.team == player.team()){
                funmode.core.Chat.send(Core.bundle.format("fun.explosive-production.message", e.unit.type.localizedName));
            }
        });
    }

    @Override
    public void buildDebug(arc.scene.ui.layout.Table table){
        table.button("Следующий юнит - брак", () -> doomNext = true).size(220f, 50f);
    }

    float failureChance(Block factory){
        if(factory == Blocks.groundFactory || factory == Blocks.airFactory || factory == Blocks.navalFactory) return 0.20f;
        if(factory == Blocks.additiveReconstructor) return 0.12f;
        if(factory == Blocks.multiplicativeReconstructor) return 0.10f;
        if(factory == Blocks.exponentialReconstructor) return 0.06f;
        if(factory == Blocks.tetrativeReconstructor) return 0.03f;
        return 0f;
    }
}
