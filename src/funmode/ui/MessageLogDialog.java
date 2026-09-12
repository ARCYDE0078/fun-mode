package funmode.ui;

import arc.Core;
import arc.graphics.Color;
import funmode.core.Chat;
import mindustry.ui.dialogs.BaseDialog;

/**
 * Mobile-only history of every message this mod has sent locally - see {@link Chat#buildHud}. The
 * chat fragment is disabled on mobile in singleplayer, so this window is where those messages
 * actually live instead of vanishing. Newest message first, opened from the small HUD button.
 */
public class MessageLogDialog extends BaseDialog{
    public MessageLogDialog(){
        super(Core.bundle.get("fun.chatlog.title", "Fun Mode Messages"));
        addCloseButton();

        cont.pane(t -> {
            t.top();
            t.defaults().left().pad(4f).width(460f);
            if(Chat.history.isEmpty()){
                t.add(Core.bundle.get("fun.chatlog.empty", "Nothing yet.")).color(Color.lightGray);
            }else{
                for(int i = Chat.history.size - 1; i >= 0; i--){
                    t.add(Chat.history.get(i)).wrap().row();
                }
            }
        }).width(500f).height(420f).grow();
    }

    public static void open(){
        new MessageLogDialog().show();
    }
}
