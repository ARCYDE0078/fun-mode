package funmode.curses;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.content.StatusEffects;
import mindustry.entities.Damage;
import mindustry.entities.Units;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.world.Block;

import static mindustry.Vars.tilesize;

/**
 * The overdrive projector's evil twins, in two sizes: the slow projector and the slow DOME.
 * Instead of boosting buildings they slow ALL buildings in radius (via the same time-scale
 * mechanism overdrive uses, just downward - sparing only themselves) and chill enemy units.
 * Recolored icy blue so nobody confuses them with the originals - which matters, because a slow
 * field touching an overdrive projector or dome annihilates both sides of the argument.
 */
public class SlowProjectorCurse implements Curse{
    Block proj, dome;

    @Override
    public String id(){
        return "slow-projector";
    }

    @Override
    public String titleKey(){
        return "fun.curse.slow-projector.title";
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(proj, "fun.lore.slow-field");
            funmode.core.Lore.details(dome, "fun.lore.slow-field");
        }
    }

    @Override
    public void loadContent(){
        proj = new SlowProjector("slow-projector");
        //research the slow variants BEFORE their overdrive twins: each slow block takes the overdrive
        //block's slot in the tree, and the overdrive block re-hangs under it
        funmode.core.Research.insertBefore(proj, mindustry.content.Blocks.overdriveProjector);

        dome = new SlowProjector("slow-dome"){{
            requirements(Category.effect, ItemStack.with(Items.lead, 200, Items.titanium, 130, Items.silicon, 130, Items.metaglass, 60));
            size = 3;
            health = 900;
            range = 30f * tilesize;
            slowdown = 0.4f;
            consumePower(2.5f);
        }};
        funmode.core.Research.insertBefore(dome, mindustry.content.Blocks.overdriveDome);
    }

    public static class SlowProjector extends Block{
        public float range = 15f * tilesize;
        public float slowdown = 0.5f;
        static final float PULSE_TICKS = 10f;
        static final Color fieldColor = new Color(0.55f, 0.85f, 1f, 1f);

        public SlowProjector(String name){
            super(name);
            requirements(Category.effect, ItemStack.with(Items.lead, 100, Items.titanium, 75, Items.silicon, 75));
            size = 2;
            health = 500;
            update = true;
            solid = true;
            destructible = true;
            alwaysUnlocked = true;
            consumePower(1f);
        }

        public class SlowProjectorBuild extends Building{
            float pulse = 0f;
            /** 1 right as a pulse fires, decays to 0 - brightens the ring for a visible "tick". */
            float flash = 0f;
            /** Set during the pulse scan, resolved after it - no killing buildings mid-iteration. */
            Building overdriveVictim = null;

            @Override
            public void updateTile(){
                if(efficiency <= 0f) return;

                pulse += Time.delta;
                if(pulse < PULSE_TICKS) return;
                pulse = 0f;
                flash = 1f;

                //ALL buildings in radius, yours included - the field doesn't discriminate; the
                //projector spares only itself
                overdriveVictim = null;
                Groups.build.each(b -> {
                    if(b == this || !b.within(this, range)) return;
                    if(b.block == Blocks.overdriveProjector || b.block == Blocks.overdriveDome){
                        overdriveVictim = b;
                        return;
                    }
                    b.applySlowdown(slowdown, PULSE_TICKS * 2f);
                });
                Units.nearbyEnemies(team, x - range, y - range, range * 2f, range * 2f, u -> {
                    if(u.within(this, range)) u.apply(StatusEffects.slow, PULSE_TICKS * 3f);
                });

                //slow field meets overdrive field: matter meets antimatter, both projectors go up
                if(overdriveVictim != null){
                    Fx.massiveExplosion.at(overdriveVictim.x, overdriveVictim.y);
                    Fx.massiveExplosion.at(x, y);
                    Damage.damage(overdriveVictim.x, overdriveVictim.y, 5f * tilesize, 600f);
                    Damage.damage(x, y, 5f * tilesize, 600f);
                    Building victim = overdriveVictim;
                    overdriveVictim = null;
                    victim.kill();
                    kill();
                }
            }

            @Override
            public void drawSelect(){
                Drawf.dashCircle(x, y, range, Pal.lancerLaser);
            }

            /** Persistent field radius, so the slowdown zone is visible without hovering/selecting
             * the block - plus a brief brighter flash each time {@link #updateTile} actually pulses. */
            @Override
            public void draw(){
                super.draw();
                if(efficiency <= 0f) return;

                flash = Mathf.approachDelta(flash, 0f, 1f / 20f);

                Draw.z(Layer.effect);
                Draw.color(fieldColor, 0.18f + 0.5f * flash + 0.08f * Mathf.absin(Time.time, 8f, 1f));
                Lines.stroke(1.5f + 2.5f * flash);
                Lines.circle(x, y, range);
                Draw.reset();
            }
        }
    }
}
