package funmode.curses;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.IntSet;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Time;
import funmode.core.Chaos;
import funmode.core.Curse;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.entities.Effect;
import mindustry.entities.Units;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Sounds;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.type.Category;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import static mindustry.Vars.content;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.ui;

/**
 * A 3x3 sacrifice altar. Any of your own units that steps onto it (except the one you're
 * controlling - the altar isn't suicidal on your behalf) is consumed, and HALF of what it cost to
 * build is refunded into your core. A grim little exchange rate, but sometimes an army is worth
 * more melted down than marched. Host/SP only.
 * <p>
 * The altar never names its demand outright: no icon, no unit name - just a vague, trait-based
 * riddle ({@link #hint}) built from what the unit actually IS (flies/swims/hovers/walks, roughly how
 * big it is, whether it digs/builds/fights). The riddle's phrasing is seeded per re-demand so asking
 * for the same unit type twice doesn't read identically ({@link #lastHint}).
 */
public class SacrificeAltarCurse implements Curse{
    /** Units consumed by the altar - so ObituariesCurse doesn't eulogize a willing sacrifice. */
    static final IntSet sacrificed = new IntSet();

    /** True (and forgets the id) if this unit was just sacrificed rather than dying in battle. */
    public static boolean consumeSacrificed(int unitId){
        return sacrificed.remove(unitId);
    }

    static final Effect sacrificeEffect = new Effect(40f, e -> {
        Draw.color(Color.valueOf("c878ff"), Color.valueOf("6a3fb0"), e.fin());
        Angles.randLenVectors(e.id, 14, 4f + e.finpow() * 26f, (x, y) -> {
            Fill.circle(e.x + x, e.y + y, e.fout() * 4f + 0.6f);
        });
    });

    @Override
    public String id(){
        return "sacrifice-altar";
    }

    @Override
    public String titleKey(){
        return "fun.curse.sacrifice-altar.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    /** Core spawn units (alpha/beta/gamma and any modded core unit) - sacrificing these angers the altar. Static so the nested block build can read it. */
    static final ObjectSet<UnitType> coreUnits = new ObjectSet<>();

    /** 3 phrasings each for move/size/role = 27 distinct riddles per unit type. */
    static final int HINT_VARIANTS = 27;
    /** Last riddle variant rolled for a given unit type, so re-demanding it doesn't read identically. */
    static final ObjectIntMap<UnitType> lastHint = new ObjectIntMap<>();

    /** A vague, trait-based riddle instead of the unit's name/icon. The move/size/role BUCKET always
     * matches the real unit (never lies about what it is) - only which of the 3 phrasings within that
     * bucket gets used is seeded, so the same unit type reads differently demand to demand. */
    static String hint(UnitType t, int seed){
        seed = Math.floorMod(seed, HINT_VARIANTS);
        int a = seed % 3, b = (seed / 3) % 3, c = (seed / 9) % 3;
        String move = t.flying ? hintKey("move.fly", a) : t.naval ? hintKey("move.naval", a) : t.hovering ? hintKey("move.hover", a) : hintKey("move.ground", a);
        String size = t.hitSize < 8f ? hintKey("size.small", b) : t.hitSize < 20f ? hintKey("size.med", b) : hintKey("size.large", b);
        String role = t.mineTier >= 1 ? hintKey("role.miner", c) : t.buildSpeed > 0f ? hintKey("role.builder", c) : t.weapons.isEmpty() ? hintKey("role.unarmed", c) : hintKey("role.armed", c);
        return Core.bundle.has("fun.altar.hint") ? Core.bundle.format("fun.altar.hint", move, size, role)
            : "something that " + move + ", is " + size + ", and " + role;
    }

    static String hintKey(String bucket, int idx){
        String k = "fun.altar.hint." + bucket + "." + idx;
        return Core.bundle.has(k) ? Core.bundle.get(k) : bucket;
    }

    /** When you have NO units to offer, the altar drinks RESOURCES from your core instead. */
    static final int RESOURCE_AMOUNT = 60;            //taken per resource offering
    static final float RESOURCE_CALM = 0.12f;         //weaker relief than a matching unit
    static final float RESOURCE_COOLDOWN_MULT = 0.4f; //resources are a faster, smaller trickle
    /** With NO altar on your team at all (e.g. you haven't researched it yet), the world self-calms
     * GENTLY - ~60% of chaos's natural rise, so chaos still climbs (at ~40% speed) but early game is
     * survivable and you're not doomed before you can build one. */
    static final float NO_ALTAR_RELIEF = 0.6f / (12f * 60f * 60f);

    float altarScanTimer = 0f;
    boolean teamHasAltar = false;

    @Override
    public void init(){
        for(Block b : content.blocks()){
            if(b instanceof CoreBlock c && c.unitType != null) coreUnits.add(c.unitType);
        }
        Events.on(WorldLoadEvent.class, e -> {
            sacrificed.clear();
            lastHint.clear();
            teamHasAltar = false;
            altarScanTimer = 0f;
        });
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.draw, this::drawRequests);
    }

    /** The pre-altar grace: while chaos is running and your team fields no altar, chaos self-calms
     * gently so an un-researched, un-built world isn't a death sentence. Altar presence is rescanned
     * every 30 ticks (cheap) and cached. */
    void update(){
        if(!isActive() || !Chaos.active || state.isPaused() || player == null) return;
        altarScanTimer += Time.delta;
        if(altarScanTimer >= 30f){
            altarScanTimer = 0f;
            teamHasAltar = false;
            for(Building b : Groups.build){
                if(b.team == player.team() && b instanceof SacrificeAltar.SacrificeAltarBuild){ teamHasAltar = true; break; }
            }
        }
        if(!teamHasAltar) Chaos.add(-NO_ALTAR_RELIEF * Time.delta);
    }

    /** At most one sacrifice per altar per minute (shortens as chaos climbs - see sacrifice()). */
    static final float COOLDOWN = 60f * 60f;
    /** Chaos relief for a matching, healthy offering (scaled down by how hurt it is). */
    static final float CALM_BASE = 0.35f;
    /** Chaos spike for a WRONG offering. */
    static final float ANGER = 0.18f;
    /** Chance a fresh demand is a GOLDEN one - double the relief, but you must find that exact unit. */
    static final float GOLDEN_CHANCE = 0.18f;

    /** A shower of gold when a golden offering is devoured. */
    static final Effect goldEffect = new Effect(60f, e -> {
        Draw.color(Color.gold, Color.orange, e.fin());
        Angles.randLenVectors(e.id, 18, 6f + e.finpow() * 34f, (x, y) -> Fill.circle(e.x + x, e.y + y, e.fout() * 4f + 0.7f));
    });

    /** Chat line, localized if the key resolves, else an inline fallback (never a raw ??? placeholder). */
    static void announce(String key, String fallback, Object... args){
        String out;
        if(Core.bundle.has(key)){
            out = Core.bundle.format(key, args);
        }else{
            out = fallback;
            for(int i = 0; i < args.length; i++) out = out.replace("{" + i + "}", String.valueOf(args[i]));
        }
        if(ui != null && ui.chatfrag != null) funmode.core.Chat.send(out);
    }

    /** Draws what each altar currently demands - a MYSTERY icon for a unit demand (the riddle in
     * chat is the only clue - no icon giveaway), or the real item icon when it's reduced to drinking
     * resources (items were never part of the guessing game). Greyed while on cooldown, with a
     * fill-up timer bar. */
    void drawRequests(){
        if(!isEnabled() || !state.isGame()) return; //the altar's demand is synced, so draw it on every machine
        Draw.z(Layer.overlayUI);
        for(Building b : Groups.build){
            if(!(b instanceof SacrificeAltar.SacrificeAltarBuild ab)) continue;
            float iy = b.y + b.block.size * tilesize / 2f + 15f;
            if(ab.requested != null){
                Draw.color(ab.cooldown > 0f ? Color.gray : (ab.golden ? Color.gold : Color.white));
                float s = ab.golden ? 10.5f : 9f;
                Draw.rect(Icon.eyeOff.getRegion(), b.x, iy, s, s);
            }else if(ab.requestedItem != null){
                Draw.color(ab.cooldown > 0f ? Color.gray : Color.white);
                Draw.rect(ab.requestedItem.uiIcon, b.x, iy, 8f, 8f);
            }else{
                continue;
            }
            if(ab.cooldown > 0f){
                Draw.color(Color.orange);
                Fill.crect(b.x - 5f, iy - 7f, 10f * (1f - ab.cooldown / ab.cooldownMax), 1.5f);
            }
        }
        Draw.reset();
    }

    @Override
    public void loadContent(){
        SacrificeAltar altar = new SacrificeAltar("sacrifice-altar");
        //campaign research: hung directly under the CORE (not deep behind the overdrive-projector
        //chain), so it unlocks the moment you can afford its cost - as sonka wants. Its own build
        //requirements (copper/lead/graphite/silicon) are the only real gate. Pre-research grace =
        //the passive self-calm in update(). Custom/sandbox keep it free.
        funmode.core.Research.node(altar, mindustry.content.Blocks.coreShard);
    }

    public static class SacrificeAltar extends Block{
        public SacrificeAltar(String name){
            super(name);
            requirements(Category.effect, ItemStack.with(Items.copper, 80, Items.lead, 80, Items.graphite, 40, Items.silicon, 40));
            size = 3;
            health = 400;
            update = true;
            solid = true;
            destructible = true;
            alwaysUnlocked = true;
            //MP: sync so the demanded unit/item icon (custom build state below) reaches clients, not just the host
            sync = true;
            consumePower(1f);
        }

        public class SacrificeAltarBuild extends Building{
            /** The unit type the altar is currently demanding (shown floating above it). */
            public UnitType requested;
            /** The resource the altar demands when you have no units to offer (mutually exclusive with {@link #requested}). */
            public Item requestedItem;
            /** A golden demand: worth double the relief, but you must bring that exact unit. */
            public boolean golden = false;
            /** Ticks until it will accept another offering - it takes at most one per minute. */
            public float cooldown = 0f;
            /** The cooldown value it was last set to (for the on-screen timer bar - the cap shrinks with chaos). */
            public float cooldownMax = COOLDOWN;
            /** How many correct offerings in a row - each raises the relief a little (breaks on a wrong one). */
            public int streak = 0;
            final Seq<Unit> onAltar = new Seq<>();

            //serialise the demand so it syncs to clients (block is sync=true) and survives save/load -
            //writeSync/save both flow through here. Content is stored by id and looked back up on read.
            @Override
            public void write(Writes write){
                super.write(write);
                write.s(requested == null ? -1 : requested.id);
                write.s(requestedItem == null ? -1 : requestedItem.id);
                write.bool(golden);
                write.f(cooldown);
                write.f(cooldownMax);
            }

            @Override
            public void read(Reads read, byte revision){
                super.read(read, revision);
                short rid = read.s();
                requested = rid < 0 ? null : content.unit(rid);
                short iid = read.s();
                requestedItem = iid < 0 ? null : content.item(iid);
                golden = read.bool();
                cooldown = read.f();
                cooldownMax = read.f();
            }

            @Override
            public void updateTile(){
                if(efficiency <= 0f) return;

                CoreBuild core = team.core();
                if(core == null) return;

                //pick something to crave: a unit if you field any, otherwise a resource from your core
                if(requested == null && requestedItem == null) pickAnyRequest(core, null);

                if(cooldown > 0f){ cooldown -= Time.delta; return; } //sated - won't take anything yet

                if(requested != null){
                    Unit controlled = player == null ? null : player.unit();
                    float span = size * tilesize;
                    onAltar.clear();
                    Units.nearby(x - span / 2f, y - span / 2f, span, span, u -> {
                        if(u.team == team && u != controlled) onAltar.add(u);
                    });
                    //take exactly ONE, then go on cooldown (collect-first so the kill doesn't corrupt the query)
                    if(onAltar.size > 0) sacrifice(onAltar.first(), core);
                }else if(requestedItem != null){
                    sacrificeResource(core);
                }
            }

            /** Prefer a unit demand; fall back to a resource demand when you field no units at all. */
            void pickAnyRequest(CoreBuild core, UnitType avoid){
                UnitType u = pickRequest(avoid);
                if(u != null){
                    requestedItem = null;
                    setRequest(u);
                }else{
                    pickItemRequest(core);
                }
            }

            /** Craves a resource the core is well-stocked in (keeps a 2× buffer so it never starves your base). */
            void pickItemRequest(CoreBuild core){
                requested = null;
                golden = false;
                Seq<Item> avail = new Seq<>();
                for(Item it : content.items()){
                    if(core.items.get(it) >= RESOURCE_AMOUNT * 2) avail.add(it);
                }
                if(avail.isEmpty()){ requestedItem = null; return; } //nothing plentiful - stays idle
                requestedItem = avail.random();
                announce("fun.altar.demand.item", "The altar, denied flesh, demands {1}x {0}.", requestedItem.localizedName, RESOURCE_AMOUNT);
            }

            /** Drinks the demanded resource straight from the core (leaving a buffer), banks a little calm. */
            void sacrificeResource(CoreBuild core){
                if(requestedItem == null || core.items.get(requestedItem) < RESOURCE_AMOUNT * 2){
                    pickItemRequest(core); //ran low - drink something else, or idle if nothing's plentiful
                    return;
                }
                core.items.remove(requestedItem, RESOURCE_AMOUNT);
                Chaos.addCalm(RESOURCE_CALM);
                sacrificeEffect.at(x, y);
                Sounds.healWave.at(x, y);
                announce("fun.altar.accept.item", "[lime]The altar drinks {1}x {0}. Chaos recedes.[]", requestedItem.localizedName, RESOURCE_AMOUNT);

                cooldownMax = COOLDOWN * RESOURCE_COOLDOWN_MULT * (1f - 0.5f * Chaos.pressure());
                cooldown = cooldownMax;
                pickAnyRequest(core, null); //prefer units again if any showed up
            }

            void sacrifice(Unit u, CoreBuild core){
                for(ItemStack stack : u.type.getTotalRequirements()){
                    int refund = stack.amount / 2;
                    if(refund <= 0) continue;
                    int room = core.getMaximumAccepted(stack.item) - core.items.get(stack.item);
                    core.items.add(stack.item, Math.max(0, Math.min(refund, room)));
                }

                //the altar wants the SPECIFIC unit it asked for:
                // - the right offering fills the calm reserve (chaos then drains down); healthier = more
                //   relief, a running streak adds a bonus, and a GOLDEN demand pays double
                // - anything else insults it, breaks your streak, and spikes chaos upward
                if(u.type == requested){
                    streak++;
                    float hpFrac = u.maxHealth <= 0f ? 1f : u.health / u.maxHealth;
                    float comboMult = 1f + Math.min(streak - 1, 5) * 0.12f; //up to +60% at a 6-streak
                    float relief = CALM_BASE * (0.4f + 0.6f * hpFrac) * comboMult;
                    if(golden){
                        relief *= 2f;
                        goldEffect.at(u.x, u.y);
                        announce("fun.altar.golden", "[gold]The altar DEVOURS your {0}! Chaos plummets.[]", u.type.localizedName);
                    }else if(streak >= 2){
                        announce("fun.altar.combo", "[lime]The altar accepts your {0}. Chaos recedes. (combo x{1})[]",
                            u.type.localizedName, streak);
                    }else{
                        announce("fun.altar.accept", "[lime]The altar accepts your {0}. Chaos recedes.[]", u.type.localizedName);
                    }
                    Chaos.addCalm(relief);
                    sacrificeEffect.at(u.x, u.y);
                    Sounds.healWave.at(x, y);
                }else{
                    streak = 0;
                    Chaos.add(ANGER);
                    Fx.blastExplosion.at(u.x, u.y);
                    Sounds.door.at(x, y);
                    announce("fun.altar.reject", "[scarlet]The altar wanted {0}, got {1}. Chaos rises.[]",
                        requested == null ? "?" : requested.localizedName, u.type.localizedName);
                }

                //flag BEFORE killing: the destroy event fires synchronously (obituary listener too)
                sacrificed.add(u.id);
                u.kill();

                //the hungrier the world (higher chaos), the sooner it demands the next one - down to ~30s
                cooldownMax = COOLDOWN * (1f - 0.5f * Chaos.pressure());
                cooldown = cooldownMax;
                pickAnyRequest(core, u.type); //craves a DIFFERENT unit - or a resource if the field is now empty
            }

            void setRequest(UnitType t){
                requested = t;
                if(t == null){ golden = false; return; }
                golden = Mathf.chance(GOLDEN_CHANCE); //some demands are golden - double relief for the exact unit

                //never repeat the same phrasing back-to-back for this unit type
                int prev = lastHint.get(t, -1);
                int seed = Mathf.random(HINT_VARIANTS - 1);
                if(HINT_VARIANTS > 1) while(seed == prev) seed = Mathf.random(HINT_VARIANTS - 1);
                lastHint.put(t, seed);
                String desc = hint(t, seed);

                if(golden){
                    announce("fun.altar.demand.golden", "[gold]The altar craves a GOLDEN sacrifice: {0}![]", desc);
                }else{
                    announce("fun.altar.demand", "The altar demands a sacrifice: {0}.", desc);
                }
            }

            /** A random unit type the player currently fields (never a core unit), avoiding the last one asked. */
            UnitType pickRequest(UnitType avoid){
                Unit controlled = player == null ? null : player.unit();
                ObjectSet<UnitType> types = new ObjectSet<>();
                for(Unit u : Groups.unit){
                    if(u.team == team && u != controlled && !coreUnits.contains(u.type)) types.add(u.type);
                }
                if(avoid != null) types.remove(avoid); //arc's ObjectSet.remove throws on a null key
                if(types.size > 0) return types.toSeq().random();
                //no variety on the field - keep whatever it had (falls back to `avoid`/current)
                for(Unit u : Groups.unit){
                    if(u.team == team && u != controlled && !coreUnits.contains(u.type)) return u.type;
                }
                return requested;
            }
        }
    }
}
