package funmode.ui;

import arc.Core;
import arc.scene.ui.layout.Table;
import funmode.FunModeMod;
import funmode.core.Curse;
import mindustry.graphics.Pal;
import mindustry.ui.dialogs.BaseDialog;

/**
 * TEMPORARY testing aid: one dialog collecting every curse's instant-trigger buttons
 * ({@link Curse#buildDebug}) so features with long random timers can be tested on demand instead of
 * waiting them out. Opened from the Fun Mode settings category. Rip out once the mod stabilizes.
 */
public class DebugDialog{
    public static void show(){
        BaseDialog dialog = new BaseDialog("Fun Mode: Debug");
        dialog.addCloseButton();
        dialog.cont.pane(t -> {
            t.defaults().left().pad(4f);

            //the first-launch warning only ever shows once (gated by a persistent settings flag
            //that survives mod updates/reinstalls) - this button reopens it on demand for testing
            t.button("Показать окно-предупреждение", WarningDialog::open).left().row();

            for(Curse c : FunModeMod.curses){
                Table row = new Table();
                row.left();
                row.defaults().pad(2f);
                c.buildDebug(row);
                if(row.getChildren().isEmpty()) continue;

                t.add(Core.bundle.get(c.titleKey(), c.id())).color(Pal.accent).row();
                t.add(row).left().row();
            }
        }).width(540f).height(440f);
        dialog.show();
    }
}
