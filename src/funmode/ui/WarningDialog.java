package funmode.ui;

import arc.Core;
import arc.scene.ui.TextButton;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;

/**
 * Shown once, the very first time Fun Mode loads: a heads-up that the mod includes sudden loud
 * sounds, and that plenty of curses only make sense once you've read the affected block/unit's
 * in-game "database" description - stats alone don't tell the whole story once the mod is on.
 * <p>
 * The close button stays disabled - counting down - for {@link #LOCK_SECONDS}, so the warning
 * can't be reflexively clicked away before it's read.
 */
public class WarningDialog extends BaseDialog{
    static final float LOCK_SECONDS = 10f;
    boolean unlocked = false;
    float elapsed = 0f;

    public WarningDialog(){
        super(Core.bundle.get("fun.warning.title", "Fun Mode"));

        cont.add(Core.bundle.get("fun.warning.text",
            "This mod includes sudden loud sounds.\n\nRead block/unit descriptions for clarity."))
            .wrap().width(460f).pad(16f);

        buttons.defaults().size(210f, 64f);
        TextButton close = buttons.button("@back", Icon.left, this::hide).get();
        close.setDisabled(() -> !unlocked);
        close.update(() -> {
            if(unlocked) return;
            elapsed += Core.graphics.getDeltaTime();
            if(elapsed >= LOCK_SECONDS){
                unlocked = true;
                close.setText("@back");
            }else{
                close.setText(Core.bundle.format("fun.warning.wait", (int)Math.ceil(LOCK_SECONDS - elapsed)));
            }
        });
    }

    @Override
    public void hide(){
        //belt-and-braces: the disabled button already blocks clicks, but nothing else (back key,
        //outside-click) is allowed to close this early either
        if(!unlocked) return;
        super.hide();
    }

    public static void open(){
        new WarningDialog().show();
    }
}
