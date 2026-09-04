package funmode.curses;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.IntFloatMap;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Time;
import funmode.core.Coolant;
import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.content.StatusEffects;
import mindustry.entities.Damage;
import mindustry.entities.Effect;
import mindustry.entities.Lightning;
import mindustry.entities.Units;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Sounds;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.world.Block;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild;

import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;

/**
 * Serpulo crafters, individually cursed, under one toggle. See the settings description for the full
 * roster. Iterates a cached list of the relevant builds every frame for accurate per-craft detection.
 * Host/SP only.
 */
public class FactoryReworksCurse implements Curse{
    static final float REFRESH_TICKS = 60f;
    static final float KILN_DPS = 3f;
    static final float MELTER_DPS = 16f;
    static final float SURGE_LIGHTNING_CHANCE = 0.1f;
    static final float PHASE_HEX_CHANCE = 0.1f;
    static final float MIXER_ACCIDENT_CHANCE = 0.01f;
    static final float COAL_DARK_DIST = 46f * tilesize;
    static final float COAL_DARK_PER = 0.06f;

    /** Small particles that drift UPWARD over their lifetime (spore press = purple haze). */
    static final Effect sporeUp = risingParticles(Color.valueOf("c060ff"));
    /** A big, billowing black smoke cloud that swells as it rises off the coal centrifuge. */
    static final Effect coalCloud = new Effect(150f, 150f, e -> {
        Draw.color(Color.valueOf("3a3a3a"), Color.black, e.fin());
        Draw.alpha(0.85f * e.fout());
        Angles.randLenVectors(e.id, 7, 4f + e.fin() * 13f, (x, y) ->
            Fill.circle(e.x + x, e.y + y + e.fin() * 42f, 2.5f + e.fin() * 7.5f));
    });
    /** A loud, obvious purple phase pulse so the weaver's proc is visible even with no units around. */
    static final Effect phaseBurst = new Effect(45f, e -> {
        Draw.color(Color.valueOf("c874ff"), e.fout());
        Lines.stroke(2.5f * e.fout());
        Lines.circle(e.x, e.y, e.fin() * 42f);
        Angles.randLenVectors(e.id, 9, e.fin() * 34f, (x, y) -> Fill.circle(e.x + x, e.y + y, e.fout() * 3.2f));
    });

    static Effect risingParticles(Color color){
        return new Effect(80f, e -> {
            Draw.color(color, e.fout());
            Angles.randLenVectors(e.id, 3, 5f, (x, y) ->
                Fill.circle(e.x + x, e.y + y + e.fin() * 20f, 2.2f * e.fout() + 0.5f));
        });
    }

    final Seq<Building> factories = new Seq<>();
    final Seq<Building> coalBuilds = new Seq<>();
    final IntFloatMap progress = new IntFloatMap();
    final IntSet frozen = new IntSet();
    final IntSet frostDraw = new IntSet(); //scratch: frost overlay recomputed each frame from synced state
    float refreshTimer = 999f;

    @Override
    public String id(){
        return "factory-reworks";
    }

    @Override
    public String titleKey(){
        return "fun.curse.factory-reworks.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void loadContent(){
        Blocks.siliconSmelter.itemCapacity = Math.max(Blocks.siliconSmelter.itemCapacity, 30);
        Blocks.siliconCrucible.itemCapacity = Math.max(Blocks.siliconCrucible.itemCapacity, 60);
        //MP: sync the blocks whose visuals (coal blackout / cryo frost) are derived below, so clients see
        //the same working state the host does (their state drives the overlays, recomputed in draw())
        Blocks.coalCentrifuge.sync = true;
        Blocks.cryofluidMixer.sync = true;
        Blocks.kiln.sync = true;
        Blocks.melter.sync = true;
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(Blocks.siliconSmelter, "fun.lore.factory.silicon");
            funmode.core.Lore.details(Blocks.siliconCrucible, "fun.lore.factory.silicon");
            funmode.core.Lore.details(Blocks.surgeSmelter, "fun.lore.factory.surge-smelter");
            funmode.core.Lore.details(Blocks.phaseWeaver, "fun.lore.factory.phase-weaver");
            funmode.core.Lore.details(Blocks.pyratiteMixer, "fun.lore.factory.pyratite-mixer");
            funmode.core.Lore.details(Blocks.blastMixer, "fun.lore.factory.blast-mixer");
            funmode.core.Lore.details(Blocks.cryofluidMixer, "fun.lore.factory.cryofluid-mixer");
            funmode.core.Lore.details(Blocks.melter, "fun.lore.factory.melter");
            funmode.core.Lore.details(Blocks.sporePress, "fun.lore.factory.spore-press");
            funmode.core.Lore.details(Blocks.pulverizer, "fun.lore.factory.pulverizer");
            funmode.core.Lore.details(Blocks.coalCentrifuge, "fun.lore.factory.coal-centrifuge");
            funmode.core.Lore.details(Blocks.separator, "fun.lore.factory.separator");
            funmode.core.Lore.details(Blocks.disassembler, "fun.lore.factory.disassembler");
            funmode.core.Lore.details(Blocks.kiln, "fun.lore.factory.kiln");
        }

        Events.on(WorldLoadEvent.class, e -> { factories.clear(); progress.clear(); refreshTimer = 999f; });
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.draw, this::draw);
    }

    boolean relevant(Block b){
        return b == Blocks.siliconSmelter || b == Blocks.siliconCrucible || b == Blocks.kiln
            || b == Blocks.cryofluidMixer || b == Blocks.melter || b == Blocks.surgeSmelter
            || b == Blocks.phaseWeaver || b == Blocks.sporePress || b == Blocks.pyratiteMixer
            || b == Blocks.blastMixer || b == Blocks.pulverizer || b == Blocks.coalCentrifuge
            || b == Blocks.separator || b == Blocks.disassembler;
    }

    boolean working(Building b){
        return b.efficiency > 0.1f;
    }

    boolean adjacent(Building b, Block block){
        for(Building n : b.proximity) if(n.block == block) return true;
        return false;
    }

    boolean crafted(Building b){
        if(!(b instanceof GenericCrafterBuild gb)) return false;
        float last = progress.get(b.pos(), 0f);
        progress.put(b.pos(), gb.progress);
        return gb.progress < last - 0.1f;
    }

    int siliconOutput(Building b){
        return b.block instanceof GenericCrafter c && c.outputItem != null ? c.outputItem.amount : 1;
    }

    void update(){
        if(!isActive() || state.isPaused()) return;

        refreshTimer += Time.delta;
        if(refreshTimer >= REFRESH_TICKS){
            refreshTimer = 0f;
            factories.clear();
            Groups.build.each(b -> { if(relevant(b.block)) factories.add(b); });
        }

        frozen.clear();
        coalBuilds.clear();

        for(Building b : factories){
            if(b == null || !b.isValid()) continue;
            Block bl = b.block;

            //--- craft-triggered gags ---
            if(bl == Blocks.siliconSmelter || bl == Blocks.siliconCrucible){
                if(crafted(b)) siliconGamble(b);
                continue;
            }
            if(bl == Blocks.surgeSmelter){
                if(crafted(b) && Mathf.chance(SURGE_LIGHTNING_CHANCE)){
                    for(int i = 0; i < 3; i++) Lightning.create(b.team, Pal.surge, 26f, b.x, b.y, Mathf.random(360f), 10);
                    Damage.damage(b.x, b.y, 5f * tilesize, 20f);
                }
                continue;
            }
            if(bl == Blocks.phaseWeaver){
                //phase shockwave: SHOVE nearby units outward (physics keeps them out of walls, unlike a
                //teleport). 10% per phase craft, per sonka.
                if(crafted(b) && Mathf.chance(PHASE_HEX_CHANCE)){
                    funmode.core.Vfx.at(phaseBurst, b.x, b.y);
                    phasePush(b);
                }
                continue;
            }
            if(bl == Blocks.pyratiteMixer){
                //crafted() first so its progress tracking always runs; water/cryo cover stops the ignition
                if(crafted(b) && !Coolant.coversFire(b.x, b.y) && Mathf.chance(MIXER_ACCIDENT_CHANCE)){
                    funmode.core.Vfx.at(Fx.fire, b.x, b.y); funmode.core.Vfx.at(Fx.smoke, b.x, b.y); b.damage(b.maxHealth * 0.3f);
                }
                continue;
            }
            if(bl == Blocks.blastMixer){
                if(crafted(b) && Mathf.chance(MIXER_ACCIDENT_CHANCE)){
                    funmode.core.Vfx.at(Fx.blastExplosion, b.x, b.y);
                    Damage.damage(b.x, b.y, 7f * tilesize, 320f);
                    b.kill();
                }
                continue;
            }

            //--- continuous gags (only while working) ---
            if(!working(b)) continue;

            if(bl == Blocks.cryofluidMixer){
                //a kiln or melter right next to it cancels the freeze (heat vs cold)
                if(adjacent(b, Blocks.kiln) || adjacent(b, Blocks.melter)){
                    if(Mathf.chanceDelta(0.15f)) Fx.steam.at(b.x, b.y);
                }else{
                    for(Building n : b.proximity){ n.applySlowdown(0.4f, 30f); frozen.add(n.pos()); }
                    if(Mathf.chanceDelta(0.15f)) Fx.smeltsmoke.at(b.x, b.y, 0f, Color.valueOf("9fe8ff"));
                }
            }else if(bl == Blocks.melter){
                //cold cancels the melt - an adjacent cryo mixer OR a cryo turret field (melting is heat)
                if(Coolant.coversHeat(b.x, b.y) || adjacent(b, Blocks.cryofluidMixer)){
                    if(Mathf.chanceDelta(0.15f)) Fx.steam.at(b.x, b.y);
                }else{
                    for(Building n : b.proximity) n.damage(MELTER_DPS / 60f * Time.delta);
                }
            }else if(bl == Blocks.sporePress){
                float r = 6f * tilesize;
                Units.nearby(b.x - r, b.y - r, r * 2f, r * 2f, u -> { if(u.within(b.x, b.y, r)) u.apply(StatusEffects.sporeSlowed, 120f); });
                if(Mathf.chanceDelta(0.25f)) sporeUp.at(b.x + Mathf.range(7f), b.y + Mathf.range(5f));
            }else if(bl == Blocks.pulverizer){
                //global, loud, low-pitched - "deafening" (positional playback was inaudible)
                if(Mathf.chanceDelta(0.05f)) Sounds.explosion.play(1.6f, Mathf.random(0.45f, 0.6f), 0f);
            }else if(bl == Blocks.coalCentrifuge){
                coalBuilds.add(b);
                if(Mathf.chanceDelta(0.14f)) coalCloud.at(b.x + Mathf.range(5f), b.y + Mathf.range(4f));
            }else if(bl == Blocks.separator){
                if(b.items != null && Mathf.chanceDelta(0.03f)) b.items.add(Items.scrap, 1);
            }else if(bl == Blocks.disassembler){
                if(b.items != null){
                    if(Mathf.chanceDelta(0.03f)) b.items.add(Items.scrap, 1);
                    if(Mathf.chanceDelta(0.004f)) b.items.add(Mathf.chance(0.5) ? Items.plastanium : Items.phaseFabric, 1);
                }
            }
        }

        //kilns last: thaw & un-frost neighbours + slow scorch, UNLESS a cryo mixer is adjacent (cancel)
        for(Building b : factories){
            if(b == null || !b.isValid() || b.block != Blocks.kiln || !working(b)) continue;
            //an adjacent cryo mixer OR a water/cryo turret field puts out the kiln's fire - no scorch, just steam
            if(Coolant.coversFire(b.x, b.y) || adjacent(b, Blocks.cryofluidMixer)){
                if(Mathf.chanceDelta(0.15f)) Fx.steam.at(b.x, b.y);
                continue;
            }
            for(Building n : b.proximity){
                n.applyBoost(1.3f, 30f);
                frozen.remove(n.pos());
                n.damage(KILN_DPS / 60f * Time.delta);
            }
            if(Mathf.chanceDelta(0.08f)) Fx.fire.at(b.x + Mathf.range(8f), b.y + Mathf.range(8f));
        }
    }

    void siliconGamble(Building b){
        if(b.items == null) return;
        float roll = Mathf.random();
        if(roll < 0.3f){
            b.items.add(Items.silicon, siliconOutput(b)); //a second helping of this craft's output
            funmode.core.Vfx.at(Fx.smeltsmoke, b.x, b.y, 0f, Pal.accent);
        }else if(roll < 0.6f){
            //instead of eating the output, the furnace strains itself and takes a little damage
            b.damage(b.maxHealth * 0.04f);
            funmode.core.Vfx.at(Fx.smoke, b.x, b.y);
        }
    }

    void phasePush(Building b){
        float r = 10f * tilesize;
        Units.nearby(b.x - r, b.y - r, r * 2f, r * 2f, u -> {
            if(!u.within(b.x, b.y, r)) return;
            //fling the unit away from the weaver (physics keeps it out of walls)
            float ang = Angles.angle(b.x, b.y, u.x, u.y);
            float power = Mathf.random(7f, 12f);
            u.vel.add(Angles.trnsx(ang, power), Angles.trnsy(ang, power));
            funmode.core.Vfx.at(Fx.teleportOut, u.x, u.y);
        });
    }

    //Runs on EVERY machine (not isActive-gated): the frost + coal-blackout overlays are recomputed here
    //from SYNCED state (Groups.build + the blocks' synced working state), so every player sees them - the
    //host-only `frozen`/`coalBuilds` sets drive the SIM, this drives the DRAW.
    void draw(){
        if(!isEnabled() || !state.isGame() || Core.camera == null) return;

        //frost = neighbours of a working cryo mixer (unless a kiln/melter beside it cancels the chill),
        //minus the neighbours of a working, un-doused kiln (heat thaws them) - mirrors the sim in update()
        frostDraw.clear();
        for(Building b : Groups.build){
            if(b.block == Blocks.cryofluidMixer && working(b) && !adjacent(b, Blocks.kiln) && !adjacent(b, Blocks.melter)){
                for(Building n : b.proximity) frostDraw.add(n.pos());
            }
        }
        for(Building b : Groups.build){
            if(b.block == Blocks.kiln && working(b) && !(Coolant.coversFire(b.x, b.y) || adjacent(b, Blocks.cryofluidMixer))){
                for(Building n : b.proximity) frostDraw.remove(n.pos());
            }
        }
        if(!frostDraw.isEmpty()){
            Draw.z(Layer.blockOver);
            IntSet.IntSetIterator it = frostDraw.iterator();
            while(it.hasNext){
                Building b = world.build(it.next());
                if(b == null) continue;
                float s = b.block.size * tilesize;
                Draw.color(Color.valueOf("9fe8ff"), 0.35f);
                Fill.rect(b.x, b.y, s, s);
                Draw.color(Color.white, 0.5f);
                Lines.stroke(1f);
                Lines.square(b.x, b.y, s / 2f);
            }
            Draw.reset();
        }

        //coal smoke darkens the screen by PROXIMITY: clean far away, up to pitch black among many
        float darkness = 0f;
        for(Building b : Groups.build){
            if(b.block != Blocks.coalCentrifuge || !working(b)) continue;
            float d = Core.camera.position.dst(b.x, b.y);
            darkness += Mathf.clamp((COAL_DARK_DIST - d) / COAL_DARK_DIST) * COAL_DARK_PER;
        }
        darkness = Math.min(1f, darkness);
        if(darkness > 0.01f){
            Draw.z(Layer.overlayUI - 1f);
            Draw.color(Color.black, darkness);
            float w = Core.camera.width, h = Core.camera.height;
            Fill.crect(Core.camera.position.x - w / 2f, Core.camera.position.y - h / 2f, w, h);
            Draw.reset();
        }
    }
}
