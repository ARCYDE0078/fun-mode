package funmode.curses;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.math.Angles;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.core.World;
import mindustry.entities.Damage;
import mindustry.entities.Fires;
import mindustry.entities.Lightning;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Sounds;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.campaign.LaunchPad;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.ui;
import static mindustry.Vars.world;

/**
 * Enemy launch pads aren't built like they used to be. Every so often a malfunctioning one drops
 * clean out of the sky and CRASHES onto your sector, cargo and all. A shadow gathers, the pad screams
 * down, and what happens on impact depends on what it was hauling:
 * <ul>
 *   <li>BLAST COMPOUND - it goes off like a bomb (a real explosion, damage all around).</li>
 *   <li>PYRATITE / COAL - it scatters FIRE across the impact site.</li>
 *   <li>SURGE ALLOY - the volatile alloy discharges in a burst of LIGHTNING.</li>
 *   <li>anything inert (copper, lead, titanium...) - it just slams down in a cloud of dust.</li>
 * </ul>
 * Either way the wreck's cargo is salvage - whatever survives is dumped straight into your core. A
 * gamble: free resources, but the drop can land on your base, and volatile cargo bites. Host/SP only.
 */
public class CrashPadCurse implements Curse{
    static final float CHECK_INTERVAL = 60f;               //advance the crash clock once a second
    static final float NEXT_MIN = 60f * 90f, NEXT_MAX = 60f * 180f; //1.5-3 min between crashes
    static final float FALL_TIME = 60f * 2.6f;             //telegraph: how long the pad takes to fall
    static final float FALL_HEIGHT = 230f;                 //world units it drops from
    static final float SLANT_MAX = FALL_HEIGHT * 0.55f;    //how far sideways it comes in (the "angle")
    static final int PAD_CAPACITY = 100;                   //a launch pad's hold - cargo never exceeds this

    static final float BLAST_RADIUS = 7f * tilesize, BLAST_DAMAGE = 520f;
    static final int FIRE_TILE_RADIUS = 4;                 //tiles around impact that can catch fire
    static final int LIGHTNING_BOLTS = 9;
    static final float LIGHTNING_DMG = 85f;
    static final float IMPACT_RING = 5.5f * tilesize;      //telegraph ring radius (rough danger zone)

    //hazard kinds, decided by the cargo item
    static final int INERT = 0, EXPLODE = 1, FIRE = 2, LIGHTNING = 3;

    float clock, nextAt = NEXT_MIN, checkTimer;
    /** In-flight crashes: {impactX, impactY, remainingFallTicks, poolIndex, amount, slantX}. */
    final Seq<float[]> falling = new Seq<>();

    //cargo pool + weights, resolved at client load (content refs aren't safe in the constructor)
    Item[] pool;
    int[] weights;
    /** The launch POD sprite (what actually flies/lands), resolved at client load. */
    TextureRegion podRegion;
    /** Research gate: in the campaign, pads don't start falling until "Wreck Recovery" is unlocked. */
    Block padTech;

    @Override
    public void loadContent(){
        //tied to landing pads, not the old plain launch pad - that one dropped out of the tech tree once
        //advancedLaunchPad/landingPad superseded it, so gating on it left this permanently unreachable
        padTech = funmode.core.FunTech.tech("wreck-recovery",
            ItemStack.with(Items.copper, 200, Items.titanium, 130, Items.silicon, 120, Items.thorium, 60),
            Blocks.landingPad);
    }

    @Override
    public String id(){
        return "crash-pads";
    }

    @Override
    public String titleKey(){
        return "fun.curse.crash-pads.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void init(){
        //common freight is likely; volatile freight is rarer but frequent enough that you SEE its gags
        pool = new Item[]{
            Items.copper, Items.lead, Items.sand, Items.scrap, Items.graphite, Items.titanium,
            Items.metaglass, Items.silicon, Items.coal, Items.thorium, Items.plastanium,
            Items.surgeAlloy, Items.blastCompound, Items.pyratite, Items.phaseFabric, Items.sporePod
        };
        weights = new int[]{ 10, 10, 8, 8, 7, 7, 6, 6, 7, 4, 4, 5, 5, 5, 2, 3 };

        //the pod sprite the launch pad actually flies (not the ground plate) - matches the current texture
        podRegion = Blocks.launchPad instanceof LaunchPad p ? p.podRegion : Core.atlas.find("launchpod");

        //a note about the crashing pads fits the extraction outpost's launch-traffic briefing (curse-gated)
        if(isEnabled()){
            funmode.core.Lore.description(mindustry.content.SectorPresets.extractionOutpost, "fun.lore.crashpad.sector");
            funmode.core.Lore.details(Blocks.landingPad, "fun.lore.landing-pad");
        }

        Events.on(WorldLoadEvent.class, e -> {
            falling.clear();
            clock = checkTimer = 0f;
            nextAt = Mathf.random(NEXT_MIN, NEXT_MAX);
        });
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.draw, this::draw);
    }

    void update(){
        if(!isActive() || player == null || state.isPaused()) return;

        //schedule the next crash on a slow clock (faster as chaos climbs) - but only once the campaign
        //has unlocked Wreck Recovery (always available outside the campaign)
        if(padTech == null || padTech.unlockedNow()){
            checkTimer += Time.delta;
            if(checkTimer >= CHECK_INTERVAL){
                checkTimer = 0f;
                clock += CHECK_INTERVAL * funmode.core.Chaos.frequencyMult();
                if(clock >= nextAt){
                    clock = 0f;
                    nextAt = Mathf.random(NEXT_MIN, NEXT_MAX);
                    spawnCrash();
                }
            }
        }

        //advance in-flight pads; resolve the ones that have landed
        for(int i = falling.size - 1; i >= 0; i--){
            float[] f = falling.get(i);
            f[2] -= Time.delta;
            if(f[2] <= 0f){
                impact(f);
                falling.remove(i);
                continue;
            }
            //a smoke trail streaming off the pod as it screams down
            float p = 1f - Mathf.clamp(f[2] / FALL_TIME);
            if(Mathf.chanceDelta(0.7f)) Fx.fallSmoke.at(padX(f, p) + Mathf.range(3f), padY(f, p) + Mathf.range(3f));
        }
    }

    /** Airborne x/y of a falling pad at progress p (0 spawned .. 1 landing) - a diagonal descent. */
    float padX(float[] f, float p){ return f[0] - f[5] * (1f - p); }
    float padY(float[] f, float p){ return f[1] + FALL_HEIGHT * (1f - p); }
    /** Sprite rotation aligned to the slanted descent (pod points up at 0). */
    float padRotation(float[] f){ return Angles.angle(0f, 0f, f[5], -FALL_HEIGHT) + 90f; }

    /** Aim a fresh crash at the player's sector - biased onto their base so it actually matters. */
    void spawnCrash(){
        float[] at = pickTarget();
        if(at == null) return;
        int idx = weightedItem();
        int amount = Mathf.random(25, PAD_CAPACITY); //a launch pad holds at most PAD_CAPACITY
        falling.add(new float[]{at[0], at[1], FALL_TIME, idx, amount, Mathf.range(SLANT_MAX)});
        funmode.core.Vfx.sound(Sounds.shootMissileLarge, 0.7f, 0.6f); //a distant scream from above, heard by all
        announce("fun.crashpad.incoming", "[orange]A malfunctioning enemy launch pad is dropping onto your sector - take cover![]");
    }

    /** Reservoir-pick a random player building to land on; fall back to the core, then the camera. */
    float[] pickTarget(){
        Building chosen = null;
        int seen = 0;
        var team = player.team();
        for(Building b : Groups.build){
            if(b.team != team) continue;
            seen++;
            if(Mathf.random(seen - 1) == 0) chosen = b; //uniform reservoir sample
        }
        if(chosen != null){
            //nudge a few tiles off the exact building so it doesn't always bullseye the same spot
            return new float[]{chosen.x + Mathf.range(4f * tilesize), chosen.y + Mathf.range(4f * tilesize)};
        }
        CoreBuild core = team.core();
        if(core != null) return new float[]{core.x + Mathf.range(6f * tilesize), core.y + Mathf.range(6f * tilesize)};
        if(Core.camera != null) return new float[]{Core.camera.position.x, Core.camera.position.y};
        return null;
    }

    /** Pick a weighted cargo item - but only from resources currently available (in the campaign that
     * means already RESEARCHED; in custom/sandbox unlockedNow() is always true so nothing is filtered). */
    int weightedItem(){
        int sum = 0;
        for(int i = 0; i < pool.length; i++) if(pool[i].unlockedNow()) sum += weights[i];
        if(sum <= 0) return indexOf(Items.copper); //nothing researched yet - copper is always available
        int roll = Mathf.random(sum - 1);
        for(int i = 0; i < pool.length; i++){
            if(!pool[i].unlockedNow()) continue;
            roll -= weights[i];
            if(roll < 0) return i;
        }
        return indexOf(Items.copper);
    }

    int hazardOf(Item item){
        if(item == Items.blastCompound) return EXPLODE;
        if(item == Items.pyratite || item == Items.coal) return FIRE;
        if(item == Items.surgeAlloy) return LIGHTNING;
        return INERT;
    }

    /** The pad lands: dust + a cargo-dependent hazard, then whatever survives is dumped into your core. */
    void impact(float[] f){
        float x = f[0], y = f[1];
        Item item = pool[(int)f[3]];
        int cargo = (int)f[4];
        int hazard = hazardOf(item);

        //the crash itself - a heavy slam of debris + kicked-up dust, regardless of cargo.
        //All one-shot fx/sounds go through Vfx so every player (not just the host) sees/hears the crash.
        funmode.core.Vfx.at(Fx.smokeCloud, x, y);
        funmode.core.Vfx.at(Fx.coreLandDust, x, y, Mathf.random(360f));
        funmode.core.Vfx.at(Fx.dynamicExplosion, x, y, 0.6f);

        switch(hazard){
            case EXPLODE -> {
                funmode.core.Vfx.at(Fx.titanExplosion, x, y);
                funmode.core.Vfx.at(Fx.bigShockwave, x, y);
                Damage.damage(Team.derelict, x, y, BLAST_RADIUS, BLAST_DAMAGE);
                funmode.core.Vfx.soundAt(Sounds.explosionTitan, x, y, 0.9f, Mathf.random(0.9f, 1.1f));
            }
            case FIRE -> {
                int tx = World.toTile(x), ty = World.toTile(y);
                for(int dx = -FIRE_TILE_RADIUS; dx <= FIRE_TILE_RADIUS; dx++){
                    for(int dy = -FIRE_TILE_RADIUS; dy <= FIRE_TILE_RADIUS; dy++){
                        if(dx * dx + dy * dy > FIRE_TILE_RADIUS * FIRE_TILE_RADIUS) continue;
                        if(!Mathf.chance(0.45f)) continue;
                        Tile t = world.tile(tx + dx, ty + dy);
                        if(t != null) Fires.create(t);
                    }
                }
                funmode.core.Vfx.at(Fx.fireHit, x, y);
                funmode.core.Vfx.soundAt(Sounds.explosionDull, x, y, 0.8f, 1f);
            }
            case LIGHTNING -> {
                for(int i = 0; i < LIGHTNING_BOLTS; i++){
                    Lightning.create(Team.derelict, Pal.lancerLaser, LIGHTNING_DMG, x, y, Mathf.random(360f), 18);
                }
                funmode.core.Vfx.soundAt(Sounds.shockBullet, x, y, 1f, 1.2f);
                funmode.core.Vfx.soundAt(Sounds.explosionDull, x, y, 0.7f, 0.9f);
            }
            default -> funmode.core.Vfx.soundAt(Sounds.explosionDull, x, y, 0.85f, 0.85f); //just a thud
        }

        //volatile cargo is consumed by its own reaction - blast compound blows up, pyratite/coal burns -
        //so there's nothing left to salvage. Otherwise a RANDOM share of the hold survives the crash.
        boolean consumed = hazard == EXPLODE || hazard == FIRE;
        int survivors = consumed ? 0 : Mathf.random(Math.max(1, cargo / 4), cargo);
        int salvaged = deposit(item, survivors);

        if(consumed){
            announce("fun.crashpad.burned", "[lightgray]The volatile cargo went up on impact - nothing left to salvage.[]");
        }else if(salvaged > 0){
            announce("fun.crashpad.loot", "[lime]Salvaged from the wreck: +{0} {1}.[]", salvaged, item.localizedName);
        }else{
            announce("fun.crashpad.lost", "[lightgray]The wreck's {0} is lost - your core is full.[]", item.localizedName);
        }
    }

    /** Dump the cargo into the team core, clamped to remaining storage; returns what actually fit. */
    int deposit(Item item, int amount){
        CoreBuild core = player.team().core();
        if(core == null) return 0;
        int space = Math.max(0, core.storageCapacity - core.items.get(item));
        int add = Math.min(amount, space);
        if(add > 0) core.items.add(item, add);
        return add;
    }

    void draw(){
        if(!isActive() || falling.isEmpty()) return;

        for(int i = 0; i < falling.size; i++){
            float[] f = falling.get(i);
            float x = f[0], y = f[1];
            float prog = 1f - Mathf.clamp(f[2] / FALL_TIME); //0 (just spawned) -> 1 (landing)
            Item item = pool[(int)f[3]];

            //growing ground shadow + a red danger ring pulsing faster as it nears
            Draw.z(Layer.blockOver);
            Draw.color(Color.black, 0.28f * prog);
            Fill.circle(x, y, 5f + 11f * prog);
            float pulse = Mathf.absin(Time.time, Mathf.lerp(9f, 2.5f, prog), 1f);
            Draw.color(Pal.remove, 0.35f + 0.45f * prog);
            Lines.stroke(1.5f + 1.5f * prog);
            Lines.circle(x, y, IMPACT_RING * (0.9f + 0.1f * pulse));
            Draw.reset();

            if(podRegion == null) continue;
            //the POD itself, coming in on a slant and swelling to size as it nears the ground
            float px = padX(f, prog), py = padY(f, prog);
            float rot = padRotation(f);
            float scale = (2.6f - 1.6f * prog) * podRegion.scl(); //big up high, natural on impact
            float rw = podRegion.width * scale, rh = podRegion.height * scale;
            Draw.z(Layer.flyingUnit);
            Draw.color(Color.white, tintFor(item), 0.3f); //a hint of the cargo's colour
            Draw.rect(podRegion, px, py, rw, rh, rot);
            Draw.reset();
        }
    }

    /** A faint cargo-coloured tint on the falling pad, hinting what it's hauling. */
    Color tintFor(Item item){
        int h = hazardOf(item);
        if(h == EXPLODE) return Pal.remove;
        if(h == FIRE) return Color.valueOf("ffad4d");
        if(h == LIGHTNING) return Pal.lancerLaser;
        return item.color;
    }

    static void announce(String key, String fallback, Object... args){
        String out;
        if(Core.bundle.has(key)) out = args.length == 0 ? Core.bundle.get(key) : Core.bundle.format(key, args);
        else out = args.length == 0 ? fallback : formatFallback(fallback, args);
        if(ui != null && ui.chatfrag != null) funmode.core.Chat.send(out);
    }

    /** Minimal {0}/{1} substitution for the hard-coded English fallbacks. */
    static String formatFallback(String s, Object... args){
        for(int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    @Override
    public void buildDebug(Table table){
        table.button("Уронить площадку (случайно)", () -> forceCrash(-1)).size(260f, 50f);
        table.row();
        table.button("Площадка: взрывчатка", () -> forceCrash(indexOf(Items.blastCompound))).size(260f, 50f);
        table.button("Площадка: пиратит (огонь)", () -> forceCrash(indexOf(Items.pyratite))).size(260f, 50f);
        table.row();
        table.button("Площадка: сплав (молнии)", () -> forceCrash(indexOf(Items.surgeAlloy))).size(260f, 50f);
        table.button("Площадка: медь (просто упадёт)", () -> forceCrash(indexOf(Items.copper))).size(260f, 50f);
    }

    void forceCrash(int poolIdx){
        if(!isActive() || player == null) return;
        float[] at = pickTarget();
        if(at == null) return;
        int idx = poolIdx >= 0 ? poolIdx : weightedItem();
        falling.add(new float[]{at[0], at[1], FALL_TIME, idx, Mathf.random(25, PAD_CAPACITY), Mathf.range(SLANT_MAX)});
        funmode.core.Vfx.sound(Sounds.shootMissileLarge, 0.7f, 0.6f);
    }

    int indexOf(Item item){
        for(int i = 0; i < pool.length; i++) if(pool[i] == item) return i;
        return 0;
    }
}
