package funmode.curses;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import funmode.core.Curse;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.type.ItemStack;
import mindustry.world.Block;

import static mindustry.Vars.content;
import static mindustry.Vars.net;
import static mindustry.Vars.player;
import static mindustry.Vars.state;

/**
 * The market has a mind of its own: a price multiplier creeps up the FASTER your own team builds,
 * and settles back down the moment you stop. Every 10-second window it compares your team's current
 * building count against last window's, and every block placed (or under construction) nudges the
 * multiplier up a notch; an idle window lets it decay back toward normal. Build in a frenzy and
 * prices spiral; sit still and the market calms.
 * <p>
 * Crucially the multiplier is DETERMINISTIC - a function of the synced game clock ({@code state.tick})
 * and the (synced) per-team building count ({@code Groups.build} filtered to your own team) - so the
 * host and every teammate compute the exact same prices from data they already have, and apply them
 * locally. That means every player SEES the changed costs in their own build menu, and the amount
 * actually charged matches what's shown. Two different teams naturally drift apart, each stoked only
 * by its own industry. Only the host narrates the swings in chat.
 */
public class VolatileEconomyCurse implements Curse{
    static final float WINDOW_TICKS = 10f * 60f;   //re-samples the team's build count every 10s
    static final float GROWTH_PER_BLOCK = 0.006f;  //each block your team completed/placed this window
    static final float DECAY_PER_WINDOW = 0.03f;   //cooldown when nothing new got built this window
    static final float MIN_MULT = 0.2f, MAX_MULT = 3.2f;

    final ObjectMap<Block, int[]> baseAmounts = new ObjectMap<>();
    float mult = 1f;
    int lastWindow = -1;
    int lastTeamBlocks = -1; //-1 = not sampled yet; the first window only baselines, never grows off it
    int lastState = 0; //-1 fire sale, 0 normal, 1 inflation - so the host only announces real shifts

    @Override
    public String id(){
        return "volatile-economy";
    }

    @Override
    public String titleKey(){
        return "fun.curse.volatile-economy.title";
    }

    @Override
    public void init(){
        //content exists by ClientLoadEvent - snapshot every block's base requirement amounts
        for(Block b : content.blocks()){
            if(b.requirements == null || b.requirements.length == 0) continue;
            int[] base = new int[b.requirements.length];
            for(int i = 0; i < base.length; i++) base[i] = b.requirements[i].amount;
            baseAmounts.put(b, base);
        }

        Events.on(WorldLoadEvent.class, e -> {
            mult = 1f;
            lastWindow = -1;
            lastTeamBlocks = -1;
            lastState = 0;
            apply();
        });
        Events.run(Trigger.update, this::update);
    }

    void update(){
        if(!isActive() || state.isPaused() || player == null) return;

        int w = (int)(state.tick / WINDOW_TICKS);
        if(w == lastWindow) return; //same 10s window - prices hold steady
        lastWindow = w;

        recompute(player.team());
        if(!net.client()) announce(); //only the host narrates; clients just apply the identical prices
    }

    /** Deterministic price for this window - same result for every player on this team. */
    void recompute(Team team){
        int count = teamBlockCount(team);
        if(lastTeamBlocks < 0){
            lastTeamBlocks = count; //first sample - just a baseline, no growth off a base that was never "built" by us
            return;
        }
        int built = Math.max(0, count - lastTeamBlocks);
        lastTeamBlocks = count;
        mult = Mathf.clamp(mult + built * GROWTH_PER_BLOCK - DECAY_PER_WINDOW, MIN_MULT, MAX_MULT);
        apply();
    }

    /** Host only: speaks up when the market shifts GEAR (inflation / fire sale / back to normal). */
    void announce(){
        int pct = Math.round(mult * 100f);
        int now = mult > 1.2f ? 1 : mult < 0.8f ? -1 : 0;
        if(now == lastState) return;
        lastState = now;
        String msg = now > 0 ? econMsg("fun.economy.up", "[scarlet]Prices spike - build costs up to {0}% of normal.[]", pct)
            : now < 0 ? econMsg("fun.economy.down", "[lime]Fire sale! Build costs down to {0}% of normal.[]", pct)
            : econMsg("fun.economy.normal", "[gray]The market settles - costs back near normal ({0}%).[]", pct);
        funmode.core.Chat.send(msg);
    }

    static String econMsg(String key, String fallback, int pct){
        return Core.bundle.has(key) ? Core.bundle.format(key, pct) : fallback.replace("{0}", String.valueOf(pct));
    }

    /** Every tile your team currently occupies with a building (finished or still under construction) -
     * a fresh burst of these between two samples IS the "how fast are you building" signal. */
    int teamBlockCount(Team team){
        int[] n = {0};
        mindustry.gen.Groups.build.each(b -> {
            if(b.team == team) n[0]++;
        });
        return n[0];
    }

    void apply(){
        for(ObjectMap.Entry<Block, int[]> entry : baseAmounts){
            ItemStack[] req = entry.key.requirements;
            int[] base = entry.value;
            for(int i = 0; i < req.length && i < base.length; i++){
                req[i].amount = Math.max(1, Math.round(base[i] * mult));
            }
        }
    }

    @Override
    public void buildDebug(arc.scene.ui.layout.Table table){
        //previews a swing until the next 30s window recomputes it
        table.button("Экономика: подорожание", () -> {
            if(!isActive()) return;
            mult = Math.min(MAX_MULT, mult * 1.6f);
            apply();
            announce();
        }).size(220f, 45f);
        table.button("Экономика: скидки", () -> {
            if(!isActive()) return;
            mult = Math.max(MIN_MULT, mult * 0.6f);
            apply();
            announce();
        }).size(220f, 45f);
    }
}
