package funmode;

import arc.Core;
import arc.Events;
import arc.struct.Seq;
import funmode.core.ButtonSetting;
import funmode.core.Curse;
import funmode.core.LabelSetting;
import funmode.ui.DebugDialog;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.Icon;
import mindustry.mod.Mod;

import funmode.curses.BackwardBeltsCurse;
import funmode.curses.BigRoutersCurse;
import funmode.curses.BlastGeneratorsCurse;
import funmode.curses.ChallengesCurse;
import funmode.curses.ChaosCurse;
import funmode.curses.ChaosStabilizerCurse;
import funmode.curses.CrookedBuildingCurse;
import funmode.curses.DesertersCurse;
import funmode.curses.DrunkCameraCurse;
import funmode.curses.LanguageSwitchCurse;
import funmode.curses.VolatileEconomyCurse;
import funmode.curses.ColdReactorCurse;
import funmode.curses.DiffGeneratorCurse;
import funmode.curses.DuoPlaceboCurse;
import funmode.curses.ExplosiveProductionCurse;
import funmode.curses.FactoryReworksCurse;
import funmode.curses.ForeshadowNukeCurse;
import funmode.curses.GraphiteBatchCurse;
import funmode.curses.JumpscareCurse;
import funmode.curses.MaceDragonCurse;
import funmode.curses.MachineQuirksCurse;
import funmode.curses.MegaIlluminatorCurse;
import funmode.curses.MysteryMessagesCurse;
import funmode.curses.NoNorthCurse;
import funmode.curses.ObituariesCurse;
import funmode.curses.SacrificeAltarCurse;
import funmode.curses.SafetyRegulationsCurse;
import funmode.curses.RouterLanguageCurse;
import funmode.curses.SleepyDrillsCurse;
import funmode.curses.SlowProjectorCurse;
import funmode.curses.SneezeCurse;
import funmode.curses.SpiderGenesCurse;
import funmode.curses.SurvivalSandboxCurse;
import funmode.curses.TurretReworksCurse;
import funmode.curses.UnitReworksCurse;
import funmode.curses.PartyRouletteCurse;
import funmode.curses.CursedWeatherCurse;
import funmode.curses.CrashPadCurse;
import funmode.curses.EnemyLootCurse;

import static mindustry.Vars.ui;

/**
 * A meme overhaul in the spirit of Factorio's Fun Mode (mods.factorio.com/mod/fun_mode): a growing
 * pile of cursed-but-playable gameplay changes. Structure copied from qol-suite: every curse is a
 * self-contained module registered here, with its own enable pref in one shared settings category
 * (headers via LabelSetting, prefs via table.pref/checkPref only - raw rows break the settings
 * search bar, see qol-suite's QolSuiteMod for the full story).
 */
public class FunModeMod extends Mod{
    public static final Seq<Curse> curses = new Seq<>();

    public FunModeMod(){
        curses.add(new MysteryMessagesCurse());
        curses.add(new ObituariesCurse());
        curses.add(new GraphiteBatchCurse());
        curses.add(new DuoPlaceboCurse());
        curses.add(new MaceDragonCurse());
        curses.add(new JumpscareCurse());
        curses.add(new ExplosiveProductionCurse());
        curses.add(new NoNorthCurse());
        curses.add(new SleepyDrillsCurse());
        curses.add(new SneezeCurse());
        curses.add(new ChallengesCurse());
        curses.add(new BigRoutersCurse());
        curses.add(new MegaIlluminatorCurse());
        curses.add(new SurvivalSandboxCurse());
        curses.add(new BlastGeneratorsCurse());
        curses.add(new ColdReactorCurse());
        curses.add(new SpiderGenesCurse());
        curses.add(new SacrificeAltarCurse());
        curses.add(new LanguageSwitchCurse());
        curses.add(new DrunkCameraCurse());
        curses.add(new VolatileEconomyCurse());
        curses.add(new CrookedBuildingCurse());
        curses.add(new DesertersCurse());
        curses.add(new TurretReworksCurse());
        curses.add(new MachineQuirksCurse());
        curses.add(new FactoryReworksCurse());
        curses.add(new ChaosCurse());
        curses.add(new ChaosStabilizerCurse());

        curses.add(new SlowProjectorCurse());
        curses.add(new ForeshadowNukeCurse());
        curses.add(new DiffGeneratorCurse());
        curses.add(new RouterLanguageCurse());
        curses.add(new BackwardBeltsCurse());
        curses.add(new UnitReworksCurse());
        curses.add(new PartyRouletteCurse());
        curses.add(new CursedWeatherCurse());
        curses.add(new CrashPadCurse());
        curses.add(new EnemyLootCurse());
        curses.add(new SafetyRegulationsCurse());

        Events.on(ClientLoadEvent.class, e -> {
            for(Curse c : curses) c.init();
            buildSettings();
            showFirstLaunchWarning();
        });
    }

    /** Once ever, the first time this mod loads: warn about sudden loud sounds and point players
     * at block/unit descriptions, since plenty of curses aren't obvious from stats alone. */
    void showFirstLaunchWarning(){
        if(Core.settings.getBool("fun-mode-seen-warning", false)) return;
        Core.settings.put("fun-mode-seen-warning", true);
        funmode.ui.WarningDialog.open();
    }

    @Override
    public void loadContent(){
        //content-mutating curses only apply when enabled at launch - see Curse.loadContent()
        for(Curse c : curses){
            if(c.isEnabled()) c.loadContent();
        }
    }

    void buildSettings(){
        ui.settings.addCategory(Core.bundle.get("fun.settings.category", "Fun Mode"), Icon.effect, table -> {
            //TEMPORARY, remove once the mod stabilizes - see DebugDialog
            table.pref(new ButtonSetting("fun-debug-menu", DebugDialog::show));

            for(Curse c : curses){
                table.pref(new LabelSetting(c.settingsKey() + "-header", Core.bundle.get(c.titleKey(), c.titleKey())));
                table.checkPref(c.settingsKey(), true);
                c.buildSettings(table);
            }
        });
    }
}
