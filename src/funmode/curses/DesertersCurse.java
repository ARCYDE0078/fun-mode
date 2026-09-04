package funmode.curses;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.content.Fx;
import mindustry.content.UnitTypes;
import mindustry.entities.Units;
import mindustry.game.EventType.Trigger;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.content.Items;
import mindustry.gen.Unit;
import mindustry.type.ItemStack;
import mindustry.type.UnitType;
import mindustry.world.Block;

import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * Morale is not what it was. Every so often one of your units decides it has seen enough, abandons
 * your cause and wanders off as a neutral derelict - it keeps its name, which you learn from the
 * resignation notice in chat. The unit you're personally controlling stays loyal (it has no choice).
 * <p>
 * The mech tree pushes back on desertion: a dagger, mace or fortress nearby raises MORALE and cuts a
 * unit's desertion odds; while a REIGN lives on your team its authority stops desertion ENTIRELY (no
 * new deserters appear at all) and it executes any that already wandered near it; and a SCEPTER can
 * charm a nearby deserter back into its own service. Host/SP only.
 */
public class DesertersCurse implements Curse{
    static final float SCAN_INTERVAL_TICKS = 60f;
    /** Per scan (~1s) per unit: averages one desertion per unit every ~15 minutes. */
    static final double DESERT_CHANCE = 1.0 / 900.0;

    //morale: dagger/mace/fortress nearby steady the ranks and cut desertion odds
    static final float MORALE_RANGE = 120f;
    static final double MORALE_MULT = 0.25;
    //researched "Morale Doctrine" cuts desertion odds army-wide (stacks with the local morale bonus)
    static final double DOCTRINE_MULT = 0.35;
    //counter-desertion: the reign puts deserters down, a scepter can lure them back
    static final float REIGN_EXECUTE_RANGE = 110f;
    static final float SCEPTER_RECRUIT_RANGE = 110f;
    static final double SCEPTER_RECRUIT_CHANCE = 0.15;

    String[] names;
    /** Research gate: unlocking "Morale Doctrine" cuts desertion odds across the whole army. */
    Block moraleTech;
    final Seq<Unit> candidates = new Seq<>();
    final Seq<Unit> reigns = new Seq<>();
    final Seq<Unit> scepters = new Seq<>();
    final Seq<Unit> derelicts = new Seq<>();
    float scanTimer = 0f;

    @Override
    public String id(){
        return "deserters";
    }

    @Override
    public String titleKey(){
        return "fun.curse.deserters.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void loadContent(){
        //tied to the ground factory - a standing army to instil discipline into
        moraleTech = funmode.core.FunTech.tech("morale-doctrine",
            ItemStack.with(Items.copper, 180, Items.lead, 180, Items.graphite, 120, Items.thorium, 60),
            mindustry.content.Blocks.groundFactory);
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(mindustry.content.Blocks.groundFactory, "fun.lore.morale-doctrine");
        }

        names = Core.bundle.get("fun.obituaries.names", "Bob,Steve,Unit").split("\\s*,\\s*");
        Events.run(Trigger.update, this::update);
    }

    void update(){
        if(!isActive() || player == null || player.team().data() == null || state.isPaused()) return;

        scanTimer += Time.delta;
        if(scanTimer < SCAN_INTERVAL_TICKS) return;
        scanTimer = 0f;

        //while a REIGN lives on your team, its sheer authority stops ALL desertion outright - no new
        //deserters appear anywhere while the king reigns (it still executes any stragglers below)
        boolean teamHasReign = player.team().data().units.contains(u -> !u.dead() && u.type == UnitTypes.reign);

        if(!teamHasReign){
            Unit controlled = player.unit();
            candidates.clear();
            player.team().data().units.each(u -> {
                if(u.isCommandable() && u != controlled) candidates.add(u);
            });

            for(Unit u : candidates){
                //dagger/mace/fortress nearby raise morale and cut the desertion odds
                double chance = DESERT_CHANCE * funmode.core.Chaos.frequencyMult();
                if(Units.closest(u.team, u.x, u.y, MORALE_RANGE, a -> a != u && isMorale(a.type)) != null) chance *= MORALE_MULT;
                if(moraleTech != null && moraleTech.unlockedNow()) chance *= DOCTRINE_MULT; //researched doctrine
                if(chance <= 0.0) continue;
                if(!Mathf.chance(chance)) continue;
                String name = names[Math.floorMod(u.id * 31, names.length)];
                funmode.core.Vfx.at(Fx.unitDrop, u.x, u.y);
                funmode.core.Chat.send(Core.bundle.format("fun.deserters.message", u.type.localizedName, name));
                u.team(Team.derelict);
            }
        }

        counterDesertion();
    }

    static boolean isMorale(UnitType t){
        return t == UnitTypes.dagger || t == UnitTypes.mace || t == UnitTypes.fortress;
    }

    /**
     * The two royal answers to desertion: a REIGN puts down any deserter that wanders near it (the
     * king suffers no traitors), while a SCEPTER can charm one back into its own service. Collect the
     * relevant units once, then act on a snapshot of derelicts (kill/team-change mutates the derelict
     * team's unit list, so never iterate it live).
     */
    void counterDesertion(){
        reigns.clear();
        scepters.clear();
        derelicts.clear();
        for(Unit u : Groups.unit){
            if(u.dead()) continue;
            if(u.team == Team.derelict){
                if(u.isCommandable()) derelicts.add(u);
            }else if(u.type == UnitTypes.reign){
                reigns.add(u);
            }else if(u.type == UnitTypes.scepter){
                scepters.add(u);
            }
        }
        if(derelicts.isEmpty() || (reigns.isEmpty() && scepters.isEmpty())) return;

        for(Unit d : derelicts){
            boolean executed = false;
            for(Unit r : reigns){
                if(d.within(r.x, r.y, REIGN_EXECUTE_RANGE)){
                    funmode.core.Vfx.at(Fx.blastExplosion, d.x, d.y);
                    d.kill();
                    executed = true;
                    break;
                }
            }
            if(executed) continue;

            for(Unit s : scepters){
                if(d.within(s.x, s.y, SCEPTER_RECRUIT_RANGE) && Mathf.chance(SCEPTER_RECRUIT_CHANCE)){
                    d.team(s.team());
                    funmode.core.Vfx.at(Fx.heal, d.x, d.y);
                    if(ui != null && ui.chatfrag != null && player != null && s.team() == player.team()){
                        String name = names[Math.floorMod(d.id * 31, names.length)];
                        String msg = Core.bundle.has("fun.deserters.recruited")
                            ? Core.bundle.format("fun.deserters.recruited", name)
                            : "[lime]A scepter lures " + name + " back into service.[]";
                        funmode.core.Chat.send(msg);
                    }
                    break;
                }
            }
        }
    }
}
