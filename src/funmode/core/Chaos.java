package funmode.core;

import arc.math.Mathf;

/**
 * Global CHAOS level (0..1) that scales how often the mod's bad/annoying effects fire. It creeps up
 * on its own; sacrificing whole units at the Sacrifice Altar fills a "calm reserve" that then drains
 * over time, pulling chaos back DOWN gradually (a sacrifice speeds the decline rather than dropping
 * it in one step). Wounded or core-spawn offerings instead anger the altar and spike chaos upward.
 * Shared static state in the spirit of qol-suite's QueueCoordination/UnitClaims.
 */
public final class Chaos{
    /** Chaos starts a match already halfway up the bar. */
    public static final float START_LEVEL = 0.5f;
    /** How fast the calm reserve is spent pulling chaos down (per tick): ~0.1 of the bar per second. */
    static final float DRAIN_PER_TICK = 0.1f / 60f;

    public static float level = START_LEVEL;
    public static float calm = 0f;
    public static boolean active = false;
    /** True while a fully-supplied Chaos Stabilizer stands on the player's team - see ChaosStabilizerCurse.
     * Unlike {@link #active}=false (which only relaxes frequency back to baseline 1x), this forces
     * chaos-driven misbehavior off ENTIRELY, per sonka's ask that the stabilizer "completely eradicate" it. */
    public static boolean suppressed = false;

    private Chaos(){
    }

    public static float frequencyMult(){
        //toned down so events don't fire on top of each other: ~0.85x at the start (level 0.5),
        //up to ~1.4x at maximum chaos (was 1.7x→3.0x, which made the game unplayable)
        return suppressed ? 0f : active ? 0.3f + level * 1.1f : 1f;
    }

    /** The level as a 0..1 "pressure" while active (else 0) - for scaling other curses' intensity/timing. */
    public static float pressure(){
        return suppressed ? 0f : active ? level : 0f;
    }

    /** A whole sacrifice adds to the calm reserve, which then drains chaos down over the next seconds. */
    public static void addCalm(float amount){
        calm = Math.max(0f, calm + amount);
    }

    /** An instant nudge to the level (used for the altar's anger spikes). */
    public static void add(float amount){
        level = Mathf.clamp(level + amount);
    }

    /** Advances one tick: base rise, minus whatever the calm reserve drains this tick. */
    public static void tick(float rise, float delta){
        float drain = Math.min(calm, DRAIN_PER_TICK * delta);
        calm -= drain;
        level = Mathf.clamp(level + rise * delta - drain);
    }

    public static void reset(){
        level = START_LEVEL;
        calm = 0f;
        active = false;
        suppressed = false;
    }
}
