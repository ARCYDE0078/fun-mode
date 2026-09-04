package funmode.curses;

import arc.Events;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.world.blocks.distribution.Router;

/**
 * Router is a language, and every router built here speaks it: finish a router - the big ones
 * included, they are routers too - and its icon is broadcast to the chat, for everyone to admire.
 * That's the whole curse now: routers still build normally, they just announce themselves. Rate-limited
 * so a wall of routers doesn't flood the chat. Host/SP only (the host posts for every build source).
 */
public class RouterLanguageCurse implements Curse{
    static final long MESSAGE_COOLDOWN_MS = 2500L;

    long lastMessage = 0L;

    @Override
    public String id(){
        return "router-language";
    }

    @Override
    public String titleKey(){
        return "fun.curse.router-language.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(Blocks.router, "fun.lore.router-language");
        }

        Events.on(BlockBuildEndEvent.class, e -> {
            if(!isActive() || e.breaking || e.tile == null) return;
            if(!(e.tile.block() instanceof Router)) return;
            if(Time.timeSinceMillis(lastMessage) <= MESSAGE_COOLDOWN_MS) return;
            lastMessage = Time.millis();
            funmode.core.Chat.send(routerLine());
        });
    }

    /** The vanilla router's chat emoji, a few of them, so it reads as a little "router router router". */
    String routerLine(){
        if(!Blocks.router.hasEmoji()) return "router";
        String icon = Blocks.router.emoji();
        return icon + " " + icon + " " + icon;
    }
}
