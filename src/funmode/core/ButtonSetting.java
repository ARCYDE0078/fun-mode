package funmode.core;

import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;

/**
 * A settings-screen row that's just a button opening a dialog (e.g. "configure watched units"), for
 * when a feature needs more than checkPref/sliderPref can express. Registering it through
 * {@link SettingsTable#pref} - the same mechanism {@code checkPref}/{@code sliderPref} use internally -
 * is what actually matters here: a plain {@code table.button(...)} call from a category's builder
 * callback isn't tracked in the table's own {@code Setting} list, and the settings screen detects that
 * mismatch and disables its search bar for the whole category ("Mod added an unexpected row to
 * SettingsTable") - the exact bug this mod hit and fixed elsewhere for its section headers.
 */
public class ButtonSetting extends SettingsTable.Setting{
    final Runnable action;

    public ButtonSetting(String name, Runnable action){
        super(name);
        this.action = action;
    }

    @Override
    public void add(SettingsTable table){
        table.button(title, action).growX().height(50f).pad(4f);
        table.row();
    }
}
