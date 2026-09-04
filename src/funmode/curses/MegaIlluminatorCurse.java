package funmode.curses;

import arc.Core;
import arc.Events;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Time;
import funmode.core.Curse;
import mindustry.content.Items;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.graphics.Layer;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.blocks.power.LightBlock;
import mindustry.world.meta.BuildVisibility;

import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;

/**
 * A 3x3 illuminator that is VERY, VERY bright. So bright that being anywhere near a powered one
 * whites out your entire screen - a fullscreen white overlay ramps up as the camera approaches,
 * reaching total blindness up close. The light itself also covers a good chunk of the map, but
 * that part is the least of your problems. The whiteout is drawn under the UI, so you can still
 * fumble your way to deconstructing the thing.
 */
public class MegaIlluminatorCurse implements Curse{
    /** Camera this close to a powered illuminator = fully blind. Wider than a screen at normal zoom - the whole screen is white light. */
    static final float FULL_BLIND_TILES = 90f;
    /** Whiteout fades to nothing at this distance - a narrow band; there's no "partially bright" zone worth speaking of. */
    static final float FADE_END_TILES = 105f;
    static final float SCAN_INTERVAL_TICKS = 60f;

    Block block;
    final Seq<Building> lit = new Seq<>();
    float scanTimer = 0f;

    @Override
    public String id(){
        return "mega-illuminator";
    }

    @Override
    public String titleKey(){
        return "fun.curse.mega-illuminator.title";
    }

    @Override
    public void loadContent(){
        block = new LightBlock("mega-illuminator"){{
            requirements(Category.effect, BuildVisibility.shown, ItemStack.with(Items.graphite, 120, Items.silicon, 80, Items.lead, 80));
            size = 3;
            brightness = 1f;
            //covers the ENTIRE map - no vanilla map comes close to this in world units
            radius = 30000f;
            consumePower(2f);
            health = 400;
            alwaysUnlocked = true;
        }};
        funmode.core.Research.node(block, mindustry.content.Blocks.illuminator);
    }

    @Override
    public void init(){
        if(isEnabled()){ //campaign database lore, only while this curse is on
            funmode.core.Lore.details(block, "fun.lore.mega-illuminator");
        }

        Events.on(WorldLoadEvent.class, e -> lit.clear());
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.draw, this::drawBlind);
    }

    void update(){
        //block is null when the curse was disabled at launch (loadContent skipped)
        if(block == null || !isEnabled() || !state.isGame()) return;

        scanTimer += Time.delta;
        if(scanTimer < SCAN_INTERVAL_TICKS) return;
        scanTimer = 0f;

        //any team's illuminator blinds - light doesn't check IFF
        lit.clear();
        Groups.build.each(b -> {
            if(b.block == block && b.efficiency > 0.01f) lit.add(b);
        });
    }

    void drawBlind(){
        if(block == null || !isEnabled() || !state.isGame() || lit.isEmpty()) return;

        //a raw additive bloom around each lit illuminator - VERY, VERY bright even in broad
        //daylight, and at x50 ring radius (up to 2500 tiles) it reaches across the whole map
        Draw.z(Layer.effect);
        Draw.blend(Blending.additive);
        for(Building b : lit){
            if(!b.isValid()) continue;
            for(int ring = 5; ring >= 1; ring--){
                Draw.color(Color.white, (0.6f - ring * 0.1f) * b.efficiency);
                Fill.circle(b.x, b.y, ring * 500f * tilesize);
            }
        }
        Draw.blend();
        Draw.reset();

        float fullDst = FULL_BLIND_TILES * tilesize, fadeDst = FADE_END_TILES * tilesize;
        float alpha = 0f;
        for(Building b : lit){
            if(!b.isValid()) continue;
            float dst = Core.camera.position.dst(b.x, b.y);
            alpha = Math.max(alpha, Mathf.clamp((fadeDst - dst) / (fadeDst - fullDst)) * b.efficiency);
        }
        if(alpha <= 0.001f) return;

        Draw.z(Layer.overlayUI + 5f);
        Draw.color(Color.white, alpha);
        float w = Core.camera.width, h = Core.camera.height;
        Fill.crect(Core.camera.position.x - w / 2f, Core.camera.position.y - h / 2f, w, h);
        Draw.reset();
    }
}
