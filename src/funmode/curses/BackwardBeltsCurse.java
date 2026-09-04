package funmode.curses;

import arc.*;
import arc.graphics.g2d.*;
import funmode.core.Curse;
import mindustry.game.EventType.*;
import mindustry.world.blocks.distribution.*;

import static mindustry.Vars.*;

/**
 * Conveyor belts animate BACKWARDS while still moving items forward. Pure gaslighting: the cargo
 * is drawn at its real (forward-moving) positions, so resources visibly ride against the belt's
 * grain the whole way to the core.
 * <p>
 * How: a {@link Conveyor}'s animation is 4 pre-shifted frames per blend shape
 * ({@code regions[7][4]}), and the build's draw() picks a frame by walking {@code Time.time}
 * forward through 0..3 (Conveyor.java:145). Reversing the frame order inside each row makes that
 * same walk play the surface pattern in reverse - nothing else in the draw path knows or cares.
 * Items are drawn from their actual positions on the belt, which this doesn't touch.
 * <p>
 * The swap is its own inverse and touches only texture-region references, so unlike the content
 * curses this one toggles LIVE: a per-tick sync applies/undoes it whenever the checkbox disagrees
 * with the current state - no "next launch" caveat. Applies to every {@link Conveyor}-family block
 * (plain/titanium/armored, plus any modded ones); the plastanium conveyor has no frame animation
 * to reverse (StackConveyor draws a static region per state). Purely visual and client-side, so it
 * works on any server.
 */
public class BackwardBeltsCurse implements Curse{
    /** Whether the frame order is currently reversed - compared against isEnabled() each tick. */
    private boolean applied;

    @Override
    public String id(){
        return "backward-belts";
    }

    @Override
    public String titleKey(){
        return "fun.curse.backward-belts.title";
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(mindustry.content.Blocks.conveyor, "fun.lore.conveyor");
            funmode.core.Lore.details(mindustry.content.Blocks.titaniumConveyor, "fun.lore.conveyor");
            funmode.core.Lore.details(mindustry.content.Blocks.armoredConveyor, "fun.lore.conveyor");
        }
        //sync immediately (init runs at ClientLoadEvent, the atlas and @Load-filled regions are
        //ready by then), then keep the swap state married to the checkbox every tick - Trigger.update
        //also fires in the menu, so toggling the setting there flips the belts right away too
        sync();
        Events.run(Trigger.update, this::sync);
    }

    void sync(){
        if(isEnabled() == applied) return;
        applied = isEnabled();

        content.blocks().each(b -> {
            //regions is null on a headless server (no atlas) - nothing to reverse there anyway
            if(!(b instanceof Conveyor c) || c.regions == null) return;

            for(TextureRegion[] frames : c.regions){
                for(int i = 0, j = frames.length - 1; i < j; i++, j--){
                    TextureRegion tmp = frames[i];
                    frames[i] = frames[j];
                    frames[j] = tmp;
                }
            }
        });
    }
}
