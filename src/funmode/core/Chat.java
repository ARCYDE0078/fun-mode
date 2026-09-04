package funmode.core;

import mindustry.gen.Call;

import static mindustry.Vars.net;
import static mindustry.Vars.ui;

/**
 * Sends the mod's chat lines so they reach EVERY player, not just the host. `ui.chatfrag.addMessage`
 * only touches the local client's chat, so a host-run curse (weather, inflation, deserters...) was
 * invisible to everyone else. On the host we broadcast via {@link Call#sendMessage(String)} (a
 * server-called remote that shows on the host AND all clients); in singleplayer, or for a client's own
 * local flavour, we fall back to the local chat fragment.
 */
public final class Chat{
    private Chat(){
    }

    public static void send(String msg){
        if(msg == null) return;
        if(net.server()){
            Call.sendMessage(msg); //host: broadcast to every player, including ourselves
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
        if(net.server()){
            Call.infoToast(msg, durationSec); //host: networked toast to every player
        }else if(ui != null){
            ui.showInfoToast(msg, durationSec); //singleplayer local toast
        }
    }
}
