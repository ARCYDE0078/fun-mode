package funmode.core;

import mindustry.graphics.Pal;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;

/**
 * A non-interactive section-header row for a shared settings category, e.g. splitting "QoL Suite" back
 * into one category with a bold title per feature instead of six separate categories. Registering it
 * through {@link SettingsTable#pref} - same as {@link ButtonSetting} - keeps it tracked in the table's
 * own {@code Setting} list; a raw {@code table.add(...)} row isn't, and the settings screen disables its
 * search bar for the whole category the moment it spots the mismatch ("Mod added an unexpected row to
 * SettingsTable").
 * <p>
 * Takes the display text directly instead of resolving it from {@code "setting." + name + ".name"} like
 * the base {@link SettingsTable.Setting} constructor does - a header just repeats each feature's already
 * bundled title (e.g. {@code qol.feature.bridge-to-core.title}), so there's no need for a second,
 * near-duplicate bundle key per feature just for this row.
 */
public class LabelSetting extends SettingsTable.Setting{
    final String text;

    public LabelSetting(String name, String text){
        super(name);
        this.text = text;
    }

    @Override
    public void add(SettingsTable table){
        table.row();
        table.add(text).color(Pal.accent).padTop(8f).left().row();
    }
}
