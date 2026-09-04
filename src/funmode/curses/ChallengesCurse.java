package funmode.curses;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.util.Time;
import arc.util.Tmp;
import funmode.core.Curse;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.game.EventType.BlockBuildBeginEvent;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.UnitCreateEvent;
import mindustry.game.EventType.UnitDestroyEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.type.Item;
import mindustry.ui.Bar;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.defense.Wall;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import static mindustry.Vars.player;
import static mindustry.Vars.spawner;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * Every few minutes the factory administration issues a timed challenge. Some are "don't do X until
 * the timer runs out" (survive the clock to win); some are "do X before the timer runs out" (beat
 * the clock to win). Success is rewarded with resources in your core; failure is punished with a
 * confiscation and an inspection party of crawlers. Host/SP only.
 * <p>
 * Fixes over the first version: "no building" only trips on the PLAYER'S OWN construction, not on
 * units auto-rebuilding (which made it near-unwinnable on any live base); the win reward now always
 * picks an item the core has room for, so you never get a "+0" prize.
 * <p>
 * A persistent top-right HUD widget ({@link #widget}) shows the active challenge and its live
 * progress, and flashes the start/win/lose line for a few seconds - not just a toast in the chat log,
 * which on mobile is off entirely in singleplayer (sonka's "на телефоне чат в одиночной игре выключен").
 * The toasts stay too (still useful in a windowed desktop game), the widget is the extra channel that
 * works everywhere.
 */
public class ChallengesCurse implements Curse{
    static final float MIN_GAP_MIN = 4f, MAX_GAP_MIN = 8f;
    static final float DURATION_TICKS = 60f * 60f;
    static final int INSPECTION_CRAWLERS = 5;

    /** Populated in init() (not static): content is null when this class loads in the mod ctor - see the item-null crash. */
    Item[] rewardItems;

    /** avoid = win by outlasting the timer; achieve = win by hitting the goal before it. */
    enum Kind{avoid, achieve}

    enum Challenge{
        noBuild(Kind.avoid),
        noLosses(Kind.avoid),
        breakWall(Kind.achieve),
        makeUnit(Kind.achieve),
        killEnemies(Kind.achieve),
        hoard(Kind.achieve);

        final Kind kind;
        Challenge(Kind kind){ this.kind = kind; }
    }

    Challenge active = null;
    float gapCountdown = -1f;
    float challengeLeft = 0f;

    //per-challenge working state
    int killsNeeded = 0, killsSoFar = 0;
    Item hoardItem = null;
    int hoardTarget = 0, hoardBaseline = 0;

    //persistent HUD widget
    static final float FLASH_TICKS = 4f * 60f;
    Table widget;
    String flashText = null;
    float flashTimer = 0f;

    @Override
    public String id(){
        return "challenges";
    }

    @Override
    public String titleKey(){
        return "fun.curse.challenges.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void init(){
        rewardItems = new Item[]{Items.copper, Items.lead, Items.graphite, Items.silicon, Items.titanium, Items.metaglass};

        Events.on(WorldLoadEvent.class, e -> {
            active = null;
            gapCountdown = -1f;
        });

        Events.run(Trigger.update, this::update);
        Events.run(Trigger.update, this::updateWidgetVisibility);
        buildWidget();

        Events.on(BlockBuildBeginEvent.class, e -> {
            if(!running() || e.team != player.team()) return;
            //only the PLAYER'S OWN building counts - auto-rebuilding units don't fail the challenge
            if(active == Challenge.noBuild && !e.breaking && e.unit != null && e.unit == player.unit()){
                lose();
            }
        });

        Events.on(BlockBuildEndEvent.class, e -> {
            if(!running() || active != Challenge.breakWall || e.team != player.team()) return;
            //detect the wall right here: deconstructFinish fires this event BEFORE tile.remove(), so the
            //ConstructBuild is still on the tile and its `previous` is the wall that was just removed.
            //(Doing it at END avoids the fragile BEGIN/END pos pairing that wasn't registering.)
            if(e.breaking && e.tile != null && e.tile.build instanceof ConstructBuild cb && cb.previous instanceof Wall){
                win();
            }
        });

        Events.on(UnitCreateEvent.class, e -> {
            if(!running() || active != Challenge.makeUnit || e.unit == null || e.unit.team != player.team()) return;
            win();
        });

        Events.on(UnitDestroyEvent.class, e -> {
            if(!running() || e.unit == null) return;
            if(active == Challenge.noLosses && e.unit.team == player.team() && !e.unit.isPlayer()){
                lose();
            }else if(active == Challenge.killEnemies && e.unit.team != player.team()){
                killsSoFar++;
                if(killsSoFar >= killsNeeded) win();
            }
        });
    }

    boolean running(){
        return isActive() && active != null && player != null;
    }

    /** Top-right progress readout, built once at load: a Bar whose name/color/fraction are pulled
     * live off this curse's own state. Hidden whenever there's nothing to show. */
    void buildWidget(){
        ui.hudGroup.fill(t -> {
            t.top().right();
            widget = new Table();
            widget.background(mindustry.ui.Styles.black6);
            widget.margin(6f);
            widget.add(new Bar(this::widgetTitle, this::widgetColor, this::widgetFraction)).width(230f).height(38f);
            t.add(widget).padTop(120f).padRight(8f);
            widget.visible = false;
        });
    }

    CharSequence widgetTitle(){
        if(flashText != null) return flashText;
        if(active == null) return "";
        String key = "fun.challenge.name." + active.name().toLowerCase();
        return Core.bundle.has(key) ? Core.bundle.get(key) : active.name();
    }

    Color widgetColor(){
        float frac = active == null ? 1f : Mathf.clamp(challengeLeft / DURATION_TICKS);
        return Tmp.c1.set(Color.scarlet).lerp(Color.lime, frac);
    }

    float widgetFraction(){
        if(active == null) return flashTimer > 0f ? 1f : 0f;
        switch(active){
            case killEnemies: return killsNeeded <= 0 ? 0f : Mathf.clamp((float)killsSoFar / killsNeeded);
            case hoard: {
                CoreBuild core = player == null ? null : player.team().core();
                if(core == null || hoardTarget <= 0) return 0f;
                return Mathf.clamp((float)(core.items.get(hoardItem) - hoardBaseline) / hoardTarget);
            }
            default: return Mathf.clamp(1f - challengeLeft / DURATION_TICKS); //time elapsed so far
        }
    }

    /** Runs unconditionally (unlike {@link #update()}) so the widget hides the instant the curse is
     * toggled off, rather than freezing on whatever it last showed. */
    void updateWidgetVisibility(){
        if(widget == null) return;
        widget.visible = isEnabled() && (active != null || flashTimer > 0f);
    }

    void update(){
        if(!isActive() || player == null || player.team().core() == null) return;

        if(flashTimer > 0f){
            flashTimer -= Time.delta;
            if(flashTimer <= 0f) flashText = null;
        }

        if(active == null){
            if(gapCountdown < 0f) gapCountdown = Mathf.random(MIN_GAP_MIN, MAX_GAP_MIN) * 60f * 60f;
            gapCountdown -= Time.delta;
            if(gapCountdown <= 0f) start();
            return;
        }

        //hoard is polled rather than event-driven
        if(active == Challenge.hoard){
            CoreBuild core = player.team().core();
            if(core != null && core.items.get(hoardItem) - hoardBaseline >= hoardTarget){
                win();
                return;
            }
        }

        challengeLeft -= Time.delta;
        if(challengeLeft <= 0f){
            //avoid-kind challenges are won by surviving the clock; achieve-kind are lost by it
            if(active.kind == Kind.avoid) win();
            else lose();
        }
    }

    void start(){
        Challenge[] all = Challenge.values();
        Challenge pick = all[Mathf.random(all.length - 1)];
        for(int tries = 0; tries < all.length && !feasible(pick); tries++){
            pick = all[Mathf.random(all.length - 1)];
        }
        //every attempt landed on an infeasible challenge (extremely unlucky, or killEnemies is the
        //only unfeasible one and RNG kept hitting it) - noBuild always works, fall back to it
        forceStart(feasible(pick) ? pick : Challenge.noBuild);
    }

    /** killEnemies is unwinnable once the enemy team has nothing left to ever spawn (sector fully
     * captured: no cores, no spawn points, nothing currently alive) - mirrors the same "sector done"
     * check Logic uses to auto-complete a mission (spawner.countSpawns() + waveTeam's core count). */
    boolean feasible(Challenge type){
        if(type != Challenge.killEnemies) return true;
        return state.enemies > 0 || (state.rules.waves && (spawner.countSpawns() > 0 || state.teams.cores(state.rules.waveTeam).size > 0));
    }

    void forceStart(Challenge type){
        active = type;
        challengeLeft = DURATION_TICKS;
        killsSoFar = 0;

        CoreBuild core = player.team().core();
        String detail = "";
        if(type == Challenge.killEnemies){
            killsNeeded = Mathf.random(3, 6);
            detail = String.valueOf(killsNeeded);
        }else if(type == Challenge.hoard){
            hoardItem = pickHoardItem(core);
            hoardTarget = Mathf.random(4, 8) * 50;
            hoardBaseline = core == null ? 0 : core.items.get(hoardItem);
            detail = hoardTarget + " " + hoardItem.localizedName;
        }

        String text = Core.bundle.format("fun.challenge.start." + type.name().toLowerCase(), detail);
        funmode.core.Chat.toast(text, 8f); //shown separately (top toast), not in the chat log
        flashText = text;
        flashTimer = FLASH_TICKS;
    }

    /** Only ever asks for an item the core already has some of, so an early copper-only run never
     * gets handed a "hoard silicon" that isn't produced yet. Falls back to copper - the one item
     * guaranteed producible from turn one - if the core is completely empty. */
    Item pickHoardItem(CoreBuild core){
        if(core != null){
            for(int tries = 0; tries < rewardItems.length; tries++){
                Item candidate = rewardItems[Mathf.random(rewardItems.length - 1)];
                if(core.items.get(candidate) > 0) return candidate;
            }
        }
        return Items.copper;
    }

    void win(){
        active = null;
        gapCountdown = -1f;

        CoreBuild core = player.team().core();
        if(core == null) return;

        //always pick an item the core has room for, so the prize is never "+0"
        Item item = null;
        int amount = 0;
        for(int tries = 0; tries < rewardItems.length; tries++){
            Item candidate = rewardItems[Mathf.random(rewardItems.length - 1)];
            int room = core.getMaximumAccepted(candidate) - core.items.get(candidate);
            if(room > 0){
                item = candidate;
                amount = Math.min(500, room);
                break;
            }
        }
        if(item == null){
            String full = Core.bundle.get("fun.challenge.win.full");
            funmode.core.Chat.toast(full, 6f);
            flashText = full;
            flashTimer = FLASH_TICKS;
            return;
        }
        core.items.add(item, amount);
        String text = Core.bundle.format("fun.challenge.win", amount, item.localizedName);
        funmode.core.Chat.toast(text, 6f);
        flashText = text;
        flashTimer = FLASH_TICKS;
    }

    void lose(){
        active = null;
        gapCountdown = -1f;

        CoreBuild core = player.team().core();
        if(core == null) return;

        Item first = rewardItems[Mathf.random(rewardItems.length - 1)];
        Item second = rewardItems[Mathf.random(rewardItems.length - 1)];
        int firstTaken = Math.min(1000, core.items.get(first));
        core.items.remove(first, firstTaken);
        int secondTaken = Math.min(1000, core.items.get(second));
        core.items.remove(second, secondTaken);

        for(int i = 0; i < INSPECTION_CRAWLERS; i++){
            float angle = Mathf.random(360f);
            float dst = Mathf.random(10f, 14f) * 8f;
            UnitTypes.crawler.spawn(state.rules.waveTeam, core.x + Mathf.cosDeg(angle) * dst, core.y + Mathf.sinDeg(angle) * dst);
        }

        String text = Core.bundle.format("fun.challenge.lose", firstTaken, first.localizedName, secondTaken, second.localizedName);
        funmode.core.Chat.toast(text, 6f);
        flashText = text;
        flashTimer = FLASH_TICKS;
    }

    @Override
    public void buildDebug(arc.scene.ui.layout.Table table){
        for(Challenge c : Challenge.values()){
            Challenge cc = c;
            table.button(c.name(), () -> {
                if(isActive() && player != null && player.team().core() != null) forceStart(cc);
            }).size(150f, 45f);
        }
    }
}
