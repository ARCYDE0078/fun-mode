package funmode.core;

import arc.graphics.Color;
import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.gen.Call;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;

import static mindustry.Vars.mobile;
import static mindustry.Vars.net;
import static mindustry.Vars.ui;

/**
 * Sends the mod's chat lines so they reach EVERY player, not just the host. `ui.chatfrag.addMessage`
 * only touches the local client's chat, so a host-run curse (weather, inflation, deserters...) was
 * invisible to everyone else. On the host we broadcast via {@link Call#sendMessage(String)} (a
 * server-called remote that shows on the host AND all clients); in singleplayer, or for a client's own
 * local flavour, we fall back to the local chat fragment.
 * <p>
 * On mobile in singleplayer, `ui.chatfrag` is disabled entirely - that local-echo fallback silently
 * swallowed every message there. Every message is now also kept in {@link #history} regardless of
 * platform, with its own small HUD button ({@link #buildHud()}, mobile only) opening
 * {@link funmode.ui.MessageLogDialog} - a separate window that's a phone player's only reliable view
 * of this mod's messages, instead of the chat line that used to vanish.
 */
public final class Chat{
    private Chat(){
    }

    static final int MAX_HISTORY = 300;
    /** Every message this mod has sent locally, oldest first, capped so a long session can't grow it forever. */
    public static final Seq<String> history = new Seq<>();
    public static boolean unread = false;

    public static void send(String msg){
        if(msg == null) return;
        log(msg);
        if(net.server()){
            Call.sendMessage(msg); //host: broadcast to every player, including ourselves
        }else if(!net.active() && mobile){
            //singleplayer + mobile: ui.chatfrag is disabled here and would eat this silently - the
            //log button from buildHud() is this player's only view of it, see class doc
        }else if(ui != null && ui.chatfrag != null){
            ui.chatfrag.addMessage(msg); //singleplayer, or a client showing its own local line
        }
    }

    /**
     * A transient top-of-screen toast shown to EVERYONE, kept OUT of the chat log - for announcements
     * that shouldn't spam the chat history (challenge prompts, results...). Same host/SP split as
     * {@link #send}: the host broadcasts a networked info-toast (shows on host AND all clients),
     * singleplayer shows it locally.
     */
    public static void toast(String msg, float durationSec){
        if(msg == null) return;
        log(msg);
        if(net.server()){
            Call.infoToast(msg, durationSec); //host: networked toast to every player
        }else if(!net.active() && mobile){
            //see send() above
        }else if(ui != null){
            ui.showInfoToast(msg, durationSec); //singleplayer local toast
        }
    }

    static void log(String msg){
        history.add(msg);
        if(history.size > MAX_HISTORY) history.remove(0);
        unread = true;
    }

    /** Small persistent HUD button, mobile only, opening {@link funmode.ui.MessageLogDialog}. Call
     * once from FunModeMod's ClientLoadEvent handler. Glows accent-colored while {@link #unread}. */
    public static void buildHud(){
        if(!mobile) return;
        ui.hudGroup.fill(t -> {
            t.top().left();
            Table widget = new Table();
            widget.background(mindustry.ui.Styles.black6);
            widget.margin(4f);
            ImageButton logButton = widget.button(Icon.chat, () -> {
                unread = false;
                funmode.ui.MessageLogDialog.open();
            }).size(42f).get();
            logButton.update(() -> logButton.setColor(unread ? Pal.accent : Color.white));
            t.add(widget).padTop(120f).padLeft(8f);
        });
    }
}
