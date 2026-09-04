package funmode.core;

import arc.Core;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;

/**
 * One cursed gameplay change, mirroring qol-suite's Feature contract: hooks its listeners once from
 * {@link #init()} (called on client load regardless of enabled state) and gates itself every tick
 * with {@link #isActive()}. Each curse gets its own on/off pref so the player assembles their own
 * blend of pain.
 * <p>
 * Curses that alter the actual game simulation (breaking buildings, stealing items, spawning
 * things) only work where this game instance is the authority - singleplayer or hosting. Those
 * return true from {@link #needsHost()} and are automatically inert while connected to someone
 * else's server; purely cosmetic/local curses (messages, obituaries, own-unit antics) work anywhere.
 */
public interface Curse{
    /** Stable id used as the settings-key suffix. */
    String id();

    /** Bundle key for the display name in settings. */
    String titleKey();

    /** Hooks events; called once on client load regardless of enabled state. */
    void init();

    /**
     * Called during content loading, BEFORE content init - the right moment to mutate vanilla
     * content stats (consume amounts, bullet fields, unit weapons), since init() then recomputes
     * derived values (ranges etc.) from the mutated fields. Unlike init(), this is only called when
     * the curse is enabled: content mutation can't be undone at runtime, so toggling these curses
     * takes effect on the next game launch (said so in their setting descriptions).
     */
    default void loadContent(){
    }

    /** Adds this curse's extra preferences (if any) to the shared settings category. */
    default void buildSettings(SettingsTable table){
    }

    default String settingsKey(){
        return "fun-" + id() + "-enabled";
    }

    default boolean isEnabled(){
        return Core.settings.getBool(settingsKey(), true);
    }

    /** True when this curse changes the game simulation and therefore only works in SP/hosting. */
    default boolean needsHost(){
        return false;
    }

    /** TEMPORARY debug hook: adds instant-trigger buttons to the debug dialog for testing without waiting on timers. */
    default void buildDebug(Table table){
    }

    /** The per-tick gate: enabled, in game, and (for simulation curses) this instance is the authority. */
    default boolean isActive(){
        return isEnabled() && Vars.state.isGame() && !(needsHost() && Vars.net.client());
    }
}
