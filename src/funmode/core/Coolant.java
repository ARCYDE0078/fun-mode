package funmode.core;

import arc.struct.Seq;

/**
 * The cooling coverage of deployed Wave/Tsunami turrets, rebuilt each tick by TurretReworksCurse. Two
 * strengths, both radius-based (only within a turret's reach, not the whole map):
 * <ul>
 *   <li>WATER or CRYO in range → {@link #coversFire} true: fires are put out here (scorch, kiln, pyratite,
 *       thermal all stop burning).</li>
 *   <li>CRYO in range → {@link #coversHeat} true: nothing may heat OR melt here either (melter, arc/spectre
 *       overheat, meltdown, thermal), on top of the fire suppression.</li>
 * </ul>
 * Shared static state in the spirit of {@link Chaos}.
 */
public final class Coolant{
    /** Each source is {x, y, radius, cryo?1:0}. */
    static final Seq<float[]> sources = new Seq<>();

    private Coolant(){
    }

    public static void clear(){
        sources.clear();
    }

    public static void add(float x, float y, float radius, boolean cryo){
        sources.add(new float[]{x, y, radius, cryo ? 1f : 0f});
    }

    /** Any water OR cryo turret covering this point (fires are doused here). */
    public static boolean coversFire(float x, float y){
        for(float[] s : sources){
            float dx = x - s[0], dy = y - s[1];
            if(dx * dx + dy * dy <= s[2] * s[2]) return true;
        }
        return false;
    }

    /** Only a CRYO turret covering this point (nothing heats or melts here). */
    public static boolean coversHeat(float x, float y){
        for(float[] s : sources){
            if(s[3] <= 0.5f) continue;
            float dx = x - s[0], dy = y - s[1];
            if(dx * dx + dy * dy <= s[2] * s[2]) return true;
        }
        return false;
    }
}
