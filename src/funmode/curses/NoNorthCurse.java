package funmode.curses;

import arc.Core;
import arc.Events;
import arc.struct.Seq;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.Duct;
import mindustry.world.blocks.distribution.StackConveyor;

import static mindustry.Vars.state;

/**
 * Conveyors don't go north here. Nobody knows why. A conveyor (or stack conveyor, or duct) facing up
 * promptly explodes - an homage to Factorio Fun Mode's "no northward belts".
 * <p>
 * Enforced by a periodic HOST-side sweep of every building on the map (not an instant build-event
 * kill, which would race clients' build prediction and leave phantom belts). The sweep sees everyone's
 * builds and its kills sync cleanly, so in multiplayer ALL players' belts obey, not just the host's.
 * Host/SP only.
 */
public class NoNorthCurse implements Curse{
    static final int ROTATION_UP = 1;
    static final long MESSAGE_COOLDOWN_MS = 5000L;
    static final float SCAN_INTERVAL = 20f; //host sweep, ~3x/sec

    long lastMessage = 0L;
    float scanTimer = 0f;
    final Seq<Building> killBuf = new Seq<>();

    @Override
    public String id(){
        return "no-north";
    }

    @Override
    public String titleKey(){
        return "fun.curse.no-north.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(mindustry.content.Blocks.conveyor, "fun.lore.no-north");
            funmode.core.Lore.details(mindustry.content.Blocks.titaniumConveyor, "fun.lore.no-north");
            funmode.core.Lore.details(mindustry.content.Blocks.armoredConveyor, "fun.lore.no-north");
        }

        //ONLY a periodic host-side sweep - no instant kill on build-complete. Killing a belt the very
        //frame it finishes races a client's build PREDICTION (the client finishes its own copy just as
        //the host destroys it, leaving a phantom the host can't reach). A ~third-of-a-second delay lets
        //the client settle to "built" first, so the sweep's kill then syncs cleanly to everyone.
        Events.run(Trigger.update, this::scan);
    }

    void scan(){
        if(!isActive() || state.isPaused()) return;
        scanTimer += Time.delta;
        if(scanTimer < SCAN_INTERVAL) return;
        scanTimer = 0f;

        killBuf.clear();
        for(Building b : Groups.build){
            if(isNorthConveyor(b.block, b.rotation)) killBuf.add(b); //collect-then-kill (no mid-iteration removal)
        }
        if(killBuf.isEmpty()) return;
        for(int i = 0; i < killBuf.size; i++) killBuf.get(i).kill();
        announce();
    }

    boolean isNorthConveyor(Block block, int rotation){
        return rotation == ROTATION_UP && (block instanceof Conveyor || block instanceof StackConveyor || block instanceof Duct);
    }

    void announce(){
        if(Time.timeSinceMillis(lastMessage) > MESSAGE_COOLDOWN_MS){
            lastMessage = Time.millis();
            funmode.core.Chat.send(Core.bundle.get("fun.no-north.message"));
        }
    }
}
