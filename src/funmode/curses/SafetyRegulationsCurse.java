package funmode.curses;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.content.SectorPresets;
import mindustry.game.EventType.SectorCaptureEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.Objectives;
import mindustry.gen.Unit;
import mindustry.type.ItemStack;
import mindustry.type.Sector;
import mindustry.type.SectorPreset;
import mindustry.world.Block;

import static mindustry.Vars.player;
import static mindustry.Vars.state;

/**
 * A chain of ten "Safety Training" research nodes, unlocked one at a time as the campaign clears
 * progressively harder sectors. Whoever printed the certificates ran out of Roman numerals halfway
 * through, so tier six reads "6" instead of "VI". None of them are optional: the moment a tier
 * opens up, you have until the NEXT sector capture (anywhere - not just the one that unlocked it)
 * to go research it, or the "overdue" course stands and your own units start deciding the chain of
 * command isn't worth respecting - defecting to the enemy team outright. Host/SP only (team-
 * switching mutates the live sim).
 */
public class SafetyRegulationsCurse implements Curse{
    static final float SCAN_INTERVAL_TICKS = 60f;
    /** Per scan (~1s) per uncertified unit, while at least one course is overdue: averages one
     * mutiny per unit every ~30 minutes. */
    static final double MUTINY_CHANCE = 1.0 / 1800.0;

    static final String[] NAMES = {"safety-1", "safety-2", "safety-3", "safety-4", "safety-5",
        "safety-6", "safety-7", "safety-8", "safety-9", "safety-10"};
    static final SectorPreset[] GATES = {SectorPresets.groundZero, SectorPresets.crateredBattleground,
        SectorPresets.biomassFacility, SectorPresets.perilousHarbor, SectorPresets.saltFlats,
        SectorPresets.testingGrounds, SectorPresets.navalFortress, SectorPresets.weatheredChannels,
        SectorPresets.littoralShipyard, SectorPresets.planetaryTerminal};

    final Block[] techs = new Block[NAMES.length];
    /** Set once a tier's gate sector is captured AND a DIFFERENT sector gets captured afterward
     * while that tier is still unresearched - i.e. the player was given one sector's grace and blew
     * through it anyway. Cleared implicitly the moment the tier gets researched (see {@link #overdue()}). */
    final boolean[] overdueFlag = new boolean[NAMES.length];
    final Seq<Unit> candidates = new Seq<>();
    float scanTimer = 0f;

    @Override
    public String id(){
        return "safety-regs";
    }

    @Override
    public String titleKey(){
        return "fun.curse.safety-regs.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void loadContent(){
        Block prev = funmode.core.FunTech.tech(NAMES[0], cost(0));
        techs[0] = prev;
        gate(prev, GATES[0]);
        for(int i = 1; i < NAMES.length; i++){
            prev = funmode.core.FunTech.techUnder(prev, NAMES[i], cost(i));
            techs[i] = prev;
            gate(prev, GATES[i]);
        }
    }

    /** Escalating research cost per tier, cheap early game items scaling up to end-game ones. */
    static ItemStack[] cost(int tier){
        return switch(tier){
            case 0 -> ItemStack.with(Items.copper, 100, Items.lead, 60);
            case 1 -> ItemStack.with(Items.copper, 140, Items.lead, 100, Items.graphite, 60);
            case 2 -> ItemStack.with(Items.copper, 160, Items.lead, 120, Items.graphite, 90, Items.titanium, 60);
            case 3 -> ItemStack.with(Items.copper, 180, Items.lead, 140, Items.titanium, 90, Items.silicon, 80);
            case 4 -> ItemStack.with(Items.titanium, 150, Items.silicon, 130, Items.thorium, 70);
            case 5 -> ItemStack.with(Items.titanium, 200, Items.silicon, 180, Items.thorium, 120, Items.plastanium, 60);
            case 6 -> ItemStack.with(Items.thorium, 180, Items.silicon, 200, Items.plastanium, 100);
            case 7 -> ItemStack.with(Items.thorium, 200, Items.plastanium, 140, Items.phaseFabric, 60);
            case 8 -> ItemStack.with(Items.plastanium, 180, Items.phaseFabric, 100, Items.surgeAlloy, 60);
            default -> ItemStack.with(Items.phaseFabric, 160, Items.surgeAlloy, 140, Items.silicon, 200);
        };
    }

    static void gate(Block tech, SectorPreset preset){
        if(tech != null && tech.techNode != null && preset != null){
            tech.techNode.objectives.add(new Objectives.SectorComplete(preset));
        }
    }

    @Override
    public void init(){
        Events.on(SectorCaptureEvent.class, e -> onCapture(e.sector));
        Events.run(Trigger.update, this::update);
    }

    /** Whenever ANY sector is captured: every tier whose own gate sector is already captured (so
     * its course has been available a while) - other than the tier this very capture just opened,
     * which gets its one sector of grace - is now overdue if it's still unresearched. */
    void onCapture(Sector justCaptured){
        for(int i = 0; i < GATES.length; i++){
            SectorPreset gatePreset = GATES[i];
            if(gatePreset == null || gatePreset.sector == null || gatePreset.sector == justCaptured) continue;
            if(techs[i] == null || techs[i].unlockedNow()) continue;
            if(gatePreset.sector.isCaptured()) overdueFlag[i] = true;
        }
    }

    /** True while at least one opened tier remains overdue and unresearched - self-clears the
     * moment that tier is researched, no need to reset the flag. */
    boolean overdue(){
        for(int i = 0; i < techs.length; i++){
            if(overdueFlag[i] && techs[i] != null && !techs[i].unlockedNow()) return true;
        }
        return false;
    }

    void update(){
        if(!isActive() || player == null || player.team().data() == null || state.isPaused()) return;
        if(!overdue()) return;

        scanTimer += Time.delta;
        if(scanTimer < SCAN_INTERVAL_TICKS) return;
        scanTimer = 0f;

        Unit controlled = player.unit();
        candidates.clear();
        player.team().data().units.each(u -> {
            if(u.isCommandable() && u != controlled) candidates.add(u);
        });

        for(Unit u : candidates){
            double chance = MUTINY_CHANCE * funmode.core.Chaos.frequencyMult();
            if(chance <= 0.0 || !Mathf.chance(chance)) continue;
            mutiny(u);
        }
    }

    void mutiny(Unit u){
        funmode.core.Vfx.at(Fx.unitDrop, u.x, u.y);
        funmode.core.Chat.send(Core.bundle.format("fun.safety.mutiny", u.type.localizedName));
        u.team(state.rules.waveTeam);
    }

    @Override
    public void buildDebug(arc.scene.ui.layout.Table table){
        table.button("Взбунтовать юнита (тест)", () -> {
            if(player == null || player.team().data() == null) return;
            Unit controlled = player.unit();
            candidates.clear();
            player.team().data().units.each(u -> {
                if(u.isCommandable() && u != controlled) candidates.add(u);
            });
            Unit victim = candidates.random();
            if(victim != null) mutiny(victim);
        }).size(220f, 50f);
    }
}
