package funmode.curses;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.util.Tmp;
import funmode.core.Curse;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Unit;

import static mindustry.Vars.player;
import static mindustry.Vars.ui;

/**
 * Your unit has allergies. Every couple of minutes on average it sneezes and lurches a couple of
 * tiles in a random direction. Purely local (it's your own unit), so this one works anywhere -
 * even on servers.
 */
public class SneezeCurse implements Curse{
    /** Per tick; averages out to a sneeze every ~2 minutes. */
    static final double CHANCE_PER_TICK = 1.0 / 7000.0;

    @Override
    public String id(){
        return "sneeze";
    }

    @Override
    public String titleKey(){
        return "fun.curse.sneeze.title";
    }

    @Override
    public void init(){
        Events.run(Trigger.update, () -> {
            if(!isActive() || player == null || player.dead()) return;
            if(player.unit() == null || !Mathf.chance(CHANCE_PER_TICK * funmode.core.Chaos.frequencyMult())) return; //more often as chaos climbs
            sneeze();
        });
    }

    void sneeze(){
        if(player == null || player.dead()) return;
        Unit u = player.unit();
        if(u == null) return;

        u.vel.add(Tmp.v1.rnd(Mathf.random(6f, 10f)));
        ui.showInfoToast(Core.bundle.get("fun.sneeze.toast", "Achoo!"), 2f);
    }

    @Override
    public void buildDebug(arc.scene.ui.layout.Table table){
        table.button("Чихнуть", this::sneeze).size(220f, 50f);
    }
}
