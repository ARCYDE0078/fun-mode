package funmode.curses;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.struct.IntFloatMap;
import arc.struct.IntMap;
import arc.struct.IntSet;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.Tmp;
import funmode.core.Curse;
import mindustry.content.Fx;
import mindustry.content.StatusEffects;
import mindustry.content.UnitTypes;
import mindustry.entities.Effect;
import mindustry.entities.Lightning;
import mindustry.entities.Units;
import mindustry.entities.abilities.RepairFieldAbility;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.bullet.EmpBulletType;
import mindustry.entities.bullet.LaserBulletType;
import mindustry.entities.bullet.MissileBulletType;
import mindustry.entities.pattern.ShootPattern;
import mindustry.ai.UnitCommand;
import mindustry.ai.types.CommandAI;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.UnitDestroyEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Teams;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Sounds;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import mindustry.type.Weapon;

import static mindustry.Vars.*;

/**
 * Per-unit bespoke gags for Serpulo units, one shared toggle (the turret-reworks precedent: 25+
 * individual toggles would drown the settings screen). Every gag riffs on the unit's NAME - the
 * catalog batch sonka approved. BATCH 1 = the mech trees (dagger..reign, nova..corvus); spiders,
 * air and naval come in later batches.
 * <p>
 * dagger: a knife - fast on its feet (content speed buff) and vicious at knife-fight distance. The
 * close-up damage is done live: every in-flight dagger bullet's damage is rescaled each tick from
 * how far it currently is from its shooter, so fresh point-blank bullets hit for several times the
 * listed damage and decay to stock by {@link #DAGGER_CLOSE_RANGE}. Mutating {@code Bullet.damage}
 * (the per-bullet copy, not the shared type) is safe live.
 * <p>
 * mace: a bludgeon - its flame now shoves victims backwards (bullet knockback, content) and
 * ignites them. Composes with the Mace Dragonbreath curse (that one stretches range/lifetime,
 * this one touches knockback/status - disjoint fields on the same BulletType).
 * <p>
 * fortress: entrenches - stand still for {@link #ENTRENCH_TICKS} and it digs in (hex rampart +
 * {@link StatusEffects#overdrive} and {@link StatusEffects#shielded}: x1.4 damage with visible
 * gold sparks, x3 effective health), move and it all lapses within moments. Statuses are applied
 * with short durations and re-applied each scan, so there's no restore bookkeeping - an earlier
 * silent armor-multiplier version had zero visible feedback and got replaced.
 * <p>
 * scepter: royalty - allies (except other scepters) near it form a retinue and get
 * {@link StatusEffects#overclock}. A king with NO retinue in range sulks and refuses to fire
 * ({@link StatusEffects#disarmed}, reapplied while
 * alone - the status route survives the per-frame reset of {@code unit.disarmed}, which is
 * transient and recomputed from statuses every tick).
 * <p>
 * reign: there can be only one per team. When a second one appears, the freshest unit id wins the
 * coup: every older reign of that team dies on the spot (fanfare + chat notice for your own team).
 * Census-based (30-tick scan, no spawn-event needed - reconstructor payload spawns are messy, see
 * ExplosiveProductionCurse).
 * <p>
 * nova: a supernova - its stock {@link RepairFieldAbility} heal pulse is upgraded in place to
 * {@link SupernovaAbility}: same healing, plus a blinding flash that damages and saps every enemy
 * in the field's radius. The subclass detects the pulse by watching the protected {@code timer}
 * wrap around zero after {@code super.update()}; unit copies preserve the subclass because
 * {@code Ability.copy()} is {@code clone()}.
 * <p>
 * pulsar: pulses on a strict clock - charge glow swells for {@link #PULSAR_PERIOD} ticks, then an
 * EMP burst: lightning to every enemy in radius + electrified. You can read the glow to know when
 * it blows.
 * <p>
 * quasar: brightest object in the universe - a walking searchlight (huge Drawf.light + halo); its
 * laser hits harder and reaches further but reloads slower (content).
 * <p>
 * vela: the sail constellation - a global WIND blows across the map (direction rerolls every
 * ~2.5 min): velas get a constant velocity push downwind, so they fly with the wind and crawl
 * against it. Each vela shows a wind arrow + drifting streaks so the current direction is
 * readable.
 * <p>
 * corvus: the crow - a carrion bird: any unit dying near a corvus feeds it (heals a slice of the
 * victim's max health), it occasionally caws, and it can spook when an enemy FLYER gets close -
 * jumping back and dropping its aim for a moment.
 * <p>
 * BATCH 2 - spiders and air:
 * <p>
 * atrax: the real-world deadliest spider - a walking PLAGUE. Any enemy that dies within its reach
 * ruptures into a lingering poison cloud ({@link funmode.core.PoisonClouds}, sparing the atrax's
 * own team); neighbours caught in it can die inside and burst again, so the contagion chains
 * through a whole pack. (Its vanilla slag already floods the ground, so no puddle changes.)
 * <p>
 * spiroct: the spiral spider spits poison - its sap is hitscan, so a toxic cloud condenses right
 * where the bolt STRIKES a unit or block ({@link funmode.core.PoisonClouds}, damages + saps + slows
 * enemies inside but SPARES the spiroct's own team), rolled per hit so it doesn't carpet the map.
 * <p>
 * arkyid: the eight-legged giant limps - the more wounded, the slower it drags (per-frame velocity
 * decay) and the more it wobbles off course; badly hurt it can outright stumble (unmoving for a
 * beat). In exchange it's the pack matriarch: nearby spiders of its team get overclock.
 * <p>
 * flare: a signal flare - periodically launches a small homing incendiary rocket at the nearest
 * enemy (a unit, or an enemy building if no unit is near); it streaks in and sets the impact zone
 * alight (splash + burning + real ground fire). Idle with no target, it stays primed.
 * <p>
 * horizon: can't stop - it forever drifts toward the horizon (forward push whenever it slows), and
 * its bombs overshoot the target by a couple of tiles (fresh bombs get a forward velocity kick).
 * <p>
 * zenith: strikes from directly overhead - missile range cut to almost nothing (content lifetime +
 * the type's explicit range/maxRange fields, which do NOT recompute on their own), but x3 damage
 * and it sets victims on fire.
 * <p>
 * antumbra: the half-shadow - 2 translucent phantom copies ORBIT it in a slow ring and EAT shots:
 * a fraction of incoming projectiles that stray near it are swallowed outright by a phantom (deleted
 * with a shimmer on the ghost). Hitscan beams carry no velocity and pass straight through - only
 * real moving shots get absorbed, so it's tanky against fire but not invincible.
 * <p>
 * eclipse: on a strict clock it unleashes a blinding CORONA - a nova-like flare that disarms
 * (blinds) and saps every enemy around it, its charge telegraphed by a swelling black-and-gold
 * glow. As ambient backdrop while any lives (ANY team) the sun still goes a little dark and solar
 * panels lose output - coordinated with MachineQuirks' solar gag when that curse is also driving
 * {@code state.rules.solarMultiplier} (it subtracts {@link #eclipseSolarDrain}), otherwise this
 * curse writes the multiplier itself and restores it when the last eclipse dies.
 * <p>
 * BATCH 3 - support air + naval:
 * <p>
 * mono: the loner - the ONLY mono on the map is a hyper-miner (mineSpeed ×16 + overclock haste), so
 * one digs like a whole swarm; the instant a second appears they all dig at stock speed. mineSpeed
 * is a shared type field gated purely on the live headcount, so every mono stays consistent.
 * <p>
 * poly: the jack-of-all-trades that finishes none - on its own clock it's simply handed a NEW random
 * job (mine / repair / assist / rebuild) via its CommandAI, so it keeps dropping one task for the
 * next. Only AI-commanded polys are reassigned, not one you're directly piloting.
 * <p>
 * mega: deluded medic - while "attacking" it mends nearby ENEMIES; to compensate, it radiates a
 * direct healing AURA over nearby allied UNITS (mega's heal-bolts only mend allied buildings, never
 * units, so the ally heal is done in code, not via projectiles) - a powerful, treacherous medic.
 * <p>
 * quad: everything in fours - each bomb drop becomes a tight series of 4, paid for with a much
 * longer reload (content ShootPattern).
 * <p>
 * oct: the octopus - a hard hit belches an ink cloud that saps + slows enemy units around it AND
 * gums up enemy buildings (turrets in the cloud fire slower via applySlowdown).
 * <p>
 * risso: dolphin famous for a lifetime of squid-fight scars - each time it first dips below half
 * health it keeps a permanent scar, a small stacking armor bonus (up to 5).
 * <p>
 * minke: the smallest, fastest, most curious baleen whale - darts in with a speed burst the moment
 * an enemy wanders close.
 * <p>
 * bryde: a lunge-feeder - a whole CLUSTER of nearby enemies (a baitball) triggers a brief
 * damage+speed lunge with a water-spout blow, on a cooldown.
 * <p>
 * sei: one of the fastest whales, a surface skim-feeder - while actually moving fast it skims a thin
 * damaging wake through anything small in its path.
 * <p>
 * omura: famous for rare lopsided asymmetric colouring - periodically flips between a dark,
 * armored/slow phase and a pale, fragile/fast one.
 * <p>
 * slugs (retusa/oxynoe/cyerce/aegires/navanax) share one baseline trait - SLIDE, drag cut so
 * momentum skids them like ice - but each also gets its own bespoke gimmick:
 * <p>
 * retusa: a burrowing predator - sit still long enough and it digs in (tanky), then ambush-lunges
 * the first prey that wanders close.
 * <p>
 * oxynoe: steals chloroplasts and can shed its own tail to flee (autotomy) - once per life, dropping
 * to critical health triggers a self-amputation: a burst of speed and a moment of invulnerability,
 * at a permanent cost of max health.
 * <p>
 * cyerce: its cerata carry stinging cells that rupture on death, atrax-style - an enemy that dies
 * within its reach sheds a toxic-green poison cloud right where it fell.
 * <p>
 * aegires: a tiny distasteful, aposematically-coloured nudibranch - anything that gets close enough
 * to bite it gets poisoned back.
 * <p>
 * navanax: the slug-eater - wounded, it gnaws a smaller allied slug nearby for a little HP (never
 * below 40% of that ally) and heals several times what it took.
 * <p>
 * the three core defenders (alpha/beta/gamma) - each one bespoke, matching its name rather than a
 * shared "core unit" gimmick:
 * <p>
 * alpha: the weakest, first thing a new player ever loses, always spawned in a crowd from the core -
 * dying near allies shields them for a moment, taking the hit so the rest of the crowd doesn't.
 * <p>
 * beta: the eternal unfinished "beta build" - every one that spawns rolls its own permanent real
 * status effect exactly once (a buff or a debuff, never one that costs HP over time), so no two
 * betas ever turn out the stock unit.
 * <p>
 * gamma: strongest of the three, named for the most energetic/penetrating radiation - it passively
 * ticks out a small damaging pulse to anything close, no aiming required.
 */
public class UnitReworksCurse implements Curse{
    //dagger
    static final float DAGGER_SPEED_MULT = 1.5f;
    static final float DAGGER_CLOSE_RANGE = 44f;
    /** Extra damage multiplier at point blank: total = 1 + bonus, decaying linearly to 1. */
    static final float DAGGER_CLOSE_BONUS = 3f;

    //mace
    static final float MACE_KNOCKBACK = 8f;

    //fortress
    static final float ENTRENCH_TICKS = 180f;
    /** Movement below this per scan-step still counts as standing still (weapon recoil jitter). */
    static final float ENTRENCH_MOVE_EPS = 1.5f;

    //scepter
    static final float RETINUE_RANGE = 88f;

    //nova
    static final float NOVA_FLASH_DAMAGE = 30f;

    //pulsar
    static final float PULSAR_PERIOD = 60f * 7f;
    static final float PULSAR_RANGE = 72f;
    static final float PULSAR_DAMAGE = 22f;
    static final int PULSAR_MAX_TARGETS = 8;

    //quasar
    static final float QUASAR_RELOAD = 95f;
    static final float QUASAR_DAMAGE_MULT = 1.9f;
    static final float QUASAR_LASER_LENGTH = 260f;
    static final float QUASAR_LIGHT_RADIUS = 300f;

    //vela
    static final float WIND_PERIOD = 60f * 150f;
    static final float WIND_PUSH = 0.038f;

    //corvus
    static final float CARRION_RANGE = 96f;
    static final float CARRION_HEAL_FRAC = 0.2f;
    static final float CARRION_HEAL_CAP = 400f;
    static final float CORVUS_FEAR_RANGE = 64f;
    static final float CORVUS_FEAR_CHANCE = 0.12f;
    static final float CORVUS_CAW_CHANCE = 0.006f;

    //spiders
    //atrax contagion: an enemy dying within reach of an atrax bursts into a plague cloud that
    //poisons its neighbours - which can die inside it and burst again = a chain plague through a pack
    static final float ATRAX_CONTAGION_RANGE = 140f; //an atrax must be this close to the corpse
    static final float ATRAX_CONTAGION_RADIUS = 30f;
    static final float ATRAX_CONTAGION_LIFE = 60f * 4f;
    static final float ATRAX_CONTAGION_DPS = 16f;
    static final float SPIROCT_CLOUD_CHANCE = 0.3f; //fraction of sap hits that condense a poison cloud
    static final float SPIROCT_CLOUD_RADIUS = 22f;
    static final float SPIROCT_CLOUD_LIFE = 60f * 3f;
    static final float SPIROCT_CLOUD_DPS = 22f; //real venom damage (was a token 6)
    static final float ARKYID_LIMP_DRAG = 0.35f;
    static final float ARKYID_WOBBLE = 14f;
    static final float ARKYID_STUMBLE_BASE = 0.02f, ARKYID_STUMBLE_SCALE = 0.12f;
    static final float ARKYID_PACK_RANGE = 80f;

    //air
    static final float FLARE_PERIOD = 60f * 10f;
    static final float FLARE_TARGET_RANGE = 240f; //how far the flare hunts for something to rocket
    static final float HORIZON_DRIFT_SPEED = 0.6f;
    static final float HORIZON_DRIFT_PUSH = 0.05f;
    static final float HORIZON_BOMB_KICK = 0.65f;
    static final float ZENITH_RANGE = 26f;
    static final float ZENITH_DAMAGE_MULT = 3f;
    static final float ZENITH_BULLET_LIFE = 16f; //long enough for the homing missile to cross its own tiny range and strike

    //antumbra decoys - a fraction of incoming shots that stray near it get re-aimed onto a trailing
    //phantom, so they sail past the real unit (enemy AI can't be told to aim at a fake point, but
    //its bullets can be nudged onto one, which reads exactly the same)
    static final float ANTUMBRA_DECOY_RADIUS = 210f; //broad-phase cull only - shots outside this are ignored
    static final float ANTUMBRA_ABSORB_RADIUS = 15f;  //a phantom actually eats a shot only this close to it
    static final int ANTUMBRA_PHANTOMS = 2;      //ghosts orbiting the real unit
    static final float ANTUMBRA_ORBIT_SCL = 1.4f; //orbit radius = hitSize × this (far enough to clear the hitbox)
    static final float ANTUMBRA_SPIN = 1.8f;      //degrees/tick the ring rotates

    //eclipse corona flare - on a clock it unleashes a blinding corona that disarms + saps every
    //enemy around it (the darkness/solar drain now runs LIGHTER, as ambient backdrop to the flare)
    static final float ECLIPSE_CORONA_PERIOD = 60f * 9f;
    static final float ECLIPSE_CORONA_RANGE = 150f;
    static final float ECLIPSE_CORONA_DISARM = 150f;
    static final float ECLIPSE_CORONA_SAP = 200f;
    static final float ECLIPSE_DIM_PER = 0.14f, ECLIPSE_DIM_MAX = 0.34f;
    static final float ECLIPSE_SOLAR_PER = 0.75f;

    //support air + naval (batch 3)
    static final float MONO_MINE_MULT = 16f;     //the lone mono digs like a whole swarm
    static final float MEGA_HEAL_RANGE = 140f;   //mega's medic radius (both the ally aura and the enemy delusion)
    static final float MEGA_HEAL_AMOUNT = 14f;   //flat hp the mega mends on each ENEMY per scan while "attacking"
    static final float MEGA_ALLY_HEAL_AMOUNT = 24f; //flat hp the mega's aura mends on each ALLIED UNIT per scan
    static final float OCT_INK_DROP_FRAC = 0.004f; //hp lost since last scan that arms an ink burst (was 0.05 - basically never fired)
    static final float OCT_INK_RADIUS = 96f;
    static final float OCT_INK_COOLDOWN = 90f;   //at most one ink cloud per 1.5s while under fire
    static final float OCT_INK_STATUS = 300f;    //how long the ink saps+slows caught enemy units (was 180)
    static final float OCT_INK_SLOW = 0.5f;      //enemy turrets/buildings in the ink run at this fraction speed
    static final float OCT_INK_BUILD_TICKS = 300f;
    static final float POLY_SWITCH_MIN = 60f * 12f, POLY_SWITCH_MAX = 60f * 18f;
    static final float SLUG_DRAG_MULT = 0.18f;   //lower drag = glides/skids further like ice
    static final float NAVANAX_BITE_RANGE = 72f;
    static final float NAVANAX_BITE_DMG = 22f;   //gnawed off a smaller allied slug
    static final float NAVANAX_HEAL_MULT = 6f;   //navanax heals this × the damage it gnaws
    //navanax's stock emp-cannon-mount boosts allied build speed by timeIncrease (vanilla = 3f, i.e.
    //+300%) via EmpBulletType.hit() -> Building.applyBoost(); bumped to +400%
    static final float NAVANAX_BUILD_BOOST = 4f;

    //naval batch 4 - one bespoke mechanic per species, replacing the old shared whale/slug behaviour
    //risso - Risso's dolphin: wounded and cornered, it fights harder - below a health threshold it
    //carries a shield+speed buff, reapplied every scan for as long as it stays there
    static final float RISSO_LOW_HP_FRAC = 0.5f;
    static final float RISSO_BUFF_DURATION = 20f;
    //minke - the smallest, fastest, most inquisitive baleen whale: darts in to "inspect" the nearest
    //enemy rather than keeping its distance
    static final float MINKE_CURIOUS_RANGE = 160f;
    //bryde - a pursuit/lunge feeder: anything that gets too close gets physically shoved off and
    //left worse for the encounter
    static final float BRYDE_PUSH_RANGE = 70f;
    static final float BRYDE_PUSH_FORCE = 3.2f;
    static final float BRYDE_PUSH_DEBUFF_DURATION = 90f;
    static final float BRYDE_PUSH_COOLDOWN = 60f * 4f; //lunges every few seconds, not every scan
    //sei - one of the fastest whales: killing something gets its blood up, a burst of speed
    static final float SEI_KILL_RANGE = 130f; //how close a kill has to land to count as "its" kill
    static final float SEI_KILL_BUFF_DURATION = 300f;
    //omura - a rare, barely-studied whale famous for lopsided asymmetric colouring: it periodically
    //flips between a dark, armored/slow phase and a pale, fragile/fast one
    static final float OMURA_PHASE_PERIOD = 60f * 8f;
    //retusa - a tiny bubble-snail predator: no more burrowing, it simply dashes at the nearest
    //target every so often
    static final float RETUSA_DASH_PERIOD = 90f;
    static final float RETUSA_DASH_RANGE = 150f;
    static final float RETUSA_DASH_SPEED = 2.6f;
    //oxynoe - steals chloroplasts from the algae it grazes: taking a near-lethal hit buys it a
    //moment of invulnerability, but it comes out of it sluggish for a long while after
    static final float OXYNOE_LETHAL_FRAC = 0.15f;
    static final float OXYNOE_INVINCIBLE_DURATION = 120f;
    static final float OXYNOE_SLOW_DURATION = 60f * 120f;
    //cyerce - like atrax's plague, but keyed to cyerce instead: an enemy that dies in its reach
    //ruptures into a patch of stinging cells, poisoning the water there
    static final float CYERCE_STING_RANGE = 90f; //cyerce must be this close to the corpse
    static final float CYERCE_STING_LIFE = 120f;
    static final float CYERCE_STING_RADIUS = 26f;
    static final float CYERCE_STING_DPS = 12f;
    //a distinct toxic green, so cyerce's sting reads apart from the shared purple PoisonClouds default
    static final Color CYERCE_STING_COLOR = Color.valueOf("6dff45");
    //aegires - a tiny warty nudibranch, distasteful and aposematically coloured: anything that gets
    //close enough to bite it gets a nasty poison bite back, corroded on top of the sap
    static final float AEGIRES_RETALIATE_RANGE = 24f;
    static final float AEGIRES_RETALIATE_CHANCE = 0.35f; //per scan while an enemy is in range
    static final float AEGIRES_RETALIATE_DPS = 14f;
    static final float AEGIRES_RETALIATE_DURATION = 120f;

    //core defenders (alpha/beta/gamma) - each one bespoke, matching its name rather than a shared
    //"core unit" gimmick
    //alpha - the weakest of the three, decaying particle namesake: every so often it just flickers
    //briefly untouchable, no trigger needed
    static final float ALPHA_INVINCIBLE_CHANCE = 0.05f; //per scan
    static final float ALPHA_INVINCIBLE_DURATION = 120f;
    //beta - the eternal unfinished "beta build": every one that spawns rolls its OWN permanent real
    //status effect once (never the stock unit twice) - reapplied every scan since these lapse on
    //their own; the pool is HP-loss-free on purpose, no burning/melting/corroded/sapped etc.
    static final float BETA_EFFECT_DURATION = 20f; //reapplied every scan, matches the scan interval
    //gamma - the strongest of the three, named for the most energetic/penetrating radiation: it
    //passively ticks out a small damaging pulse to anything close, no aiming required, corroding
    //whatever it touches
    static final float GAMMA_PULSE_PERIOD = 60f * 3f;
    static final float GAMMA_PULSE_RANGE = 46f;
    static final float GAMMA_PULSE_DAMAGE = 26f;
    static final float GAMMA_CORRODE_DURATION = 120f;

    /** Bright expanding supernova ring; rotation carries the radius. */
    static final Effect novaFlash = new Effect(35f, 300f, e -> {
        Draw.color(Color.white, Pal.heal, e.fin());
        Lines.stroke(4f * e.fout());
        Lines.circle(e.x, e.y, e.finpow() * e.rotation);
        Draw.color(Color.white, e.fout() * 0.9f);
        Fill.circle(e.x, e.y, 16f * e.fout());
        Drawf.light(e.x, e.y, e.rotation * 2.5f * e.fout(), Pal.heal, 0.9f);
    });

    /** Crackling EMP discharge ring; rotation carries the radius. */
    static final Effect empRing = new Effect(30f, 200f, e -> {
        Draw.color(Color.white, Pal.lancerLaser, e.fin());
        Lines.stroke(3.5f * e.fout());
        Lines.circle(e.x, e.y, e.finpow() * e.rotation);
        //jagged sparks flying outward with the ring's edge
        for(int i = 0; i < 9; i++){
            float ang = i * 40f + e.id * 31f;
            float rad = e.finpow() * e.rotation;
            Lines.lineAngle(e.x + Angles.trnsx(ang, rad), e.y + Angles.trnsy(ang, rad), ang + Mathf.randomSeed(e.id + i, -40f, 40f), 7f * e.fout());
        }
        Drawf.light(e.x, e.y, e.rotation * 1.8f * e.fout(), Pal.lancerLaser, 0.8f);
    });

    /** Muzzle puff as the flare kicks off its incendiary rocket; rotation = launch direction. */
    static final Effect flareLaunch = new Effect(18f, e -> {
        Draw.color(Pal.lightOrange, Color.white, e.fout());
        for(int i = 0; i < 5; i++){
            float a = e.rotation + Mathf.range(24f);
            float len = e.fin() * 13f;
            Fill.circle(e.x + Angles.trnsx(a, len), e.y + Angles.trnsy(a, len), 2.2f * e.fout());
        }
    });

    /** The flare's signature: a small homing incendiary rocket that streaks at a target and sets
     * the impact zone alight (splash + burning status + real ground fire). */
    static final BulletType flareRocketBullet = new MissileBulletType(3.3f, 16f){{
        lifetime = 70f;
        width = 7f;
        height = 12f;
        homingPower = 0.12f;
        homingRange = 140f;
        frontColor = Color.white;
        backColor = Pal.lightOrange;
        trailColor = Pal.lightOrange;
        trailLength = 8;
        trailWidth = 2.4f;
        shrinkY = 0f;
        splashDamage = 24f;
        splashDamageRadius = 36f;
        makeFire = true;
        despawnHit = true;
        status = StatusEffects.burning;
        statusDuration = 300f;
        incendChance = 1f;
        incendAmount = 5;
        incendSpread = 7f;
        hitEffect = Fx.blastExplosion;
        despawnEffect = Fx.blastExplosion;
        hitSound = Sounds.explosion;
        lightColor = Pal.lightOrange;
        lightRadius = 45f;
        lightOpacity = 0.6f;
    }};

    /** Eclipse corona: a black disc rimmed in blazing gold that blooms into a blinding flash;
     * rotation carries the radius. */
    static final Effect coronaFlash = new Effect(48f, 400f, e -> {
        //the occluded sun - a dark core ringed with fire
        Draw.color(Color.black, 0.5f * e.fout());
        Fill.circle(e.x, e.y, e.finpow() * e.rotation * 0.68f);
        Draw.color(Pal.accent, Color.white, e.fin());
        Lines.stroke(4.5f * e.fout());
        Lines.circle(e.x, e.y, e.finpow() * e.rotation);
        //corona streamers licking outward
        for(int i = 0; i < 12; i++){
            float ang = i * 30f + e.id * 17f;
            float rad = e.finpow() * e.rotation;
            Lines.stroke(2.5f * e.fout());
            Lines.lineAngle(e.x + Angles.trnsx(ang, rad * 0.82f), e.y + Angles.trnsy(ang, rad * 0.82f), ang, 15f * e.fout());
        }
        Draw.color(Color.white, e.fout());
        Fill.circle(e.x, e.y, 18f * e.fout());
        Drawf.light(e.x, e.y, e.rotation * 2f * e.fout(), Color.white, 0.9f);
    });

    /** A quick shimmer on the phantom a fooled shot veers toward. */
    static final Effect antumbraLure = new Effect(22f, e -> {
        Draw.color(Color.black, Color.white, e.fout());
        Lines.stroke(2f * e.fout());
        Lines.circle(e.x, e.y, 4f + e.fin() * 11f);
    });

    /** Plague burst when an atrax kill ruptures - a spray of toxic droplets and an expanding ring. */
    static final Effect atraxPlague = new Effect(42f, e -> {
        Draw.color(Color.valueOf("bf92f9"));
        Lines.stroke(2.5f * e.fout());
        Lines.circle(e.x, e.y, e.fin() * 30f);
        Angles.randLenVectors(e.id, 10, 4f + e.fin() * 26f, (x, y) -> Fill.circle(e.x + x, e.y + y, 2.4f * e.fout()));
    });

    /** A whale's blow: a column of water rising off its back with a spray of droplets at the top. */
    static final Effect waterSpout = new Effect(46f, e -> {
        float h = e.fin() * 34f;
        Draw.color(Color.valueOf("cfe7ff"), 0.75f * e.fout());
        Lines.stroke(3f * e.fout());
        Lines.line(e.x, e.y, e.x, e.y + h);
        Angles.randLenVectors(e.id, 8, 6f + e.fin() * 18f, (x, y) -> Fill.circle(e.x + x, e.y + y + h, 2.6f * e.fout()));
    });

    /** Oxynoe's near-death flinch: a small pale burst as it goes briefly untouchable. */
    static final Effect autotomyBurst = new Effect(30f, e -> {
        Draw.color(Color.valueOf("9de08a"), e.fout());
        Fill.circle(e.x, e.y, 3f + e.fin() * 8f);
        Angles.randLenVectors(e.id, 6, 4f + e.fin() * 14f, (x, y) -> Fill.circle(e.x + x, e.y + y, 1.8f * e.fout()));
    });

    /** Gamma's radiation tick: a sickly green ring pulsing outward. */
    static final Effect gammaPulseFx = new Effect(26f, e -> {
        Draw.color(Color.valueOf("8bff6b"), e.fout());
        Lines.stroke(2.4f * e.fout());
        Lines.circle(e.x, e.y, e.finpow() * GAMMA_PULSE_RANGE);
    });

    /** Alpha's flicker: a thin pale ring blooming out as it briefly goes untouchable. */
    static final Effect alphaFlickerFx = new Effect(28f, e -> {
        Draw.color(Color.white, e.fout() * 0.8f);
        Lines.stroke(2f * e.fout());
        Lines.circle(e.x, e.y, 6f + e.fin() * 14f);
    });

    /** Omura's phase flip: a small dark/pale ripple as it switches colouring. */
    static final Effect omuraPhaseFx = new Effect(30f, e -> {
        Draw.color(Color.valueOf("2b2b33"), Color.valueOf("d8d8e0"), e.fin());
        Lines.stroke(2f * e.fout());
        Lines.circle(e.x, e.y, e.fin() * 20f);
    });

    /** Oct's ink: a fat near-black cloud belched out when it's hit hard - a dense core plus a wide
     * spatter so it actually reads on a busy battlefield. */
    static final Effect inkCloud = new Effect(120f, 240f, e -> {
        Draw.color(Color.valueOf("14141c"), 0.78f * e.fout());
        Fill.circle(e.x, e.y, 8f + e.finpow() * 60f);
        Angles.randLenVectors(e.id, 22, 10f + e.finpow() * 96f, (x, y) -> Fill.circle(e.x + x, e.y + y, 9f * e.fout()));
    });

    /** A white streak sliding downwind; rotation = wind direction. */
    static final Effect windStreak = new Effect(38f, e -> {
        Draw.color(Color.white, 0.35f * e.fout());
        float dist = e.finpow() * 46f;
        Lines.stroke(1.2f);
        Lines.lineAngle(e.x + Angles.trnsx(e.rotation, dist), e.y + Angles.trnsy(e.rotation, dist),
            e.rotation, 8f + 8f * e.fout());
    });

    final ObjectSet<BulletType> daggerBullets = new ObjectSet<>();
    final ObjectSet<BulletType> horizonBombs = new ObjectSet<>();
    final ObjectSet<BulletType> spiroctBullets = new ObjectSet<>();
    final ObjectSet<UnitType> spiderTypes = new ObjectSet<>();
    final ObjectSet<UnitType> slugTypes = new ObjectSet<>();
    final Seq<Unit> doomedReigns = new Seq<>();
    final Seq<Unit> monos = new Seq<>();
    final IntFloatMap polyTimer = new IntFloatMap();
    final IntFloatMap octLastHp = new IntFloatMap();
    final IntFloatMap octInkTimer = new IntFloatMap();
    //naval batch 4 per-species state
    final IntFloatMap brydeCooldown = new IntFloatMap();
    final IntFloatMap omuraTimer = new IntFloatMap();
    final IntSet omuraDark = new IntSet();
    final IntFloatMap retusaDash = new IntFloatMap();
    final IntSet oxynoeBelowThreshold = new IntSet();
    //core defenders per-unit state
    final IntMap<StatusEffect> betaEffect = new IntMap<>();
    StatusEffect[] betaPool;
    final IntFloatMap gammaPulse = new IntFloatMap();
    /** The jobs poly cycles through - populated in init() (UnitCommand is content, NULL at class-init). */
    UnitCommand[] polyCommands;
    float monoBaseMineSpeed = -1f; //captured at init so the lone-mono boost can be toggled off
    /** Live antumbras, refreshed each slow scan - the decoy pass reads their live x/y off these. */
    final Seq<Unit> antumbras = new Seq<>();
    final IntFloatMap flareTimer = new IntFloatMap();
    final IntFloatMap eclipseCharge = new IntFloatMap();
    /** Sap bolts we've already condensed a cloud for - a SapBulletType hits once (in init) but the
     * projectile then lingers ~35 ticks as a visual, so we must fire exactly once per bolt id. */
    final IntSet spiroctSeen = new IntSet();
    /** Shots a phantom swallowed this frame - removed AFTER the bullet loop (never mid-iteration). */
    final Seq<Bullet> absorbBuf = new Seq<>();

    /** Live eclipse headcount (any team) - MachineQuirks' solar formula reads the drain off this. */
    public static int eclipseCount;
    static boolean eclipseSolarTouched;

    /** How much the eclipses currently subtract from the solar multiplier (0 while none/inactive). */
    public static float eclipseSolarDrain(){
        return eclipseCount * ECLIPSE_SOLAR_PER;
    }
    final IntFloatMap stillTicks = new IntFloatMap();
    final IntFloatMap lastX = new IntFloatMap();
    final IntFloatMap lastY = new IntFloatMap();
    final IntFloatMap pulsarCharge = new IntFloatMap();
    final IntSet knownReigns = new IntSet();

    float windDir;
    float windTimer;
    float scanTimer, reignTimer;

    @Override
    public String id(){
        return "unit-reworks";
    }

    @Override
    public String titleKey(){
        return "fun.curse.unit-reworks.title";
    }

    @Override
    public boolean needsHost(){
        return true;
    }

    @Override
    public void loadContent(){
        UnitTypes.dagger.speed *= DAGGER_SPEED_MULT;

        eachWeaponBullet(UnitTypes.mace, b -> {
            b.knockback += MACE_KNOCKBACK;
            b.status = StatusEffects.burning;
            b.statusDuration = Math.max(b.statusDuration, 120f);
        });

        //swap nova's stock heal pulse for the damaging supernova variant, params carried over
        Seq<mindustry.entities.abilities.Ability> novaAbilities = UnitTypes.nova.abilities;
        for(int i = 0; i < novaAbilities.size; i++){
            if(novaAbilities.get(i) instanceof RepairFieldAbility rf && !(rf instanceof SupernovaAbility)){
                novaAbilities.set(i, new SupernovaAbility(rf));
            }
        }

        for(Weapon w : UnitTypes.quasar.weapons){
            if(w.bullet instanceof LaserBulletType lb){
                w.reload = QUASAR_RELOAD;
                lb.damage *= QUASAR_DAMAGE_MULT;
                lb.length = QUASAR_LASER_LENGTH;
            }
        }

        //atrax's gag (plague contagion) is entirely runtime, in onUnitDeath - nothing to mutate here;
        //its vanilla slag already floods the ground on its own, so no puddle changes.

        //zenith "strikes from directly overhead": tiny engagement range + x3 damage + it sets the
        //target ALIGHT. The old lifetime=8f was the ignite bug - the homing missile self-destructed
        //in the air a hair short of the target, so it never collided, never splashed, and the
        //burning status/fire never applied ("поджег не работает"). Fix: give it enough lifetime to
        //cross its own tiny range, force a hit on despawn, and make real FIRE on impact so the
        //ignite is unmistakable. range/maxRange are EXPLICIT fields (140f) and must be written too,
        //or the AI would hover at 140 lobbing missiles that die mid-air.
        eachWeaponBullet(UnitTypes.zenith, b -> {
            b.damage *= ZENITH_DAMAGE_MULT;
            b.lifetime = ZENITH_BULLET_LIFE;
            b.makeFire = true;
            b.despawnHit = true;
            b.status = StatusEffects.burning;
            b.statusDuration = Math.max(b.statusDuration, 300f);
            b.splashDamageRadius = Math.max(b.splashDamageRadius, 26f);
            b.incendChance = 1f;
            b.incendAmount = Math.max(b.incendAmount, 4);
            b.incendSpread = Math.max(b.incendSpread, 5f);
        });
        UnitTypes.zenith.range = ZENITH_RANGE;
        UnitTypes.zenith.maxRange = ZENITH_RANGE;

        //quad "everything in fours": each drop becomes a tight SERIES of 4 bombs, paid for with a
        //much longer reload between series (its bomb weapon is its only one)
        for(Weapon w : UnitTypes.quad.weapons){
            w.shoot = new ShootPattern(){{
                shots = 4;
                shotDelay = 6f;
            }};
            w.reload = Math.max(w.reload, 55f) * 3.1f;
        }

        //sea slugs SLIDE - drop their drag so they coast and skid like they're on ice (naval units
        //are drag-braked; cutting it lets momentum carry them)
        for(UnitType slug : new UnitType[]{UnitTypes.retusa, UnitTypes.oxynoe, UnitTypes.cyerce, UnitTypes.aegires, UnitTypes.navanax}){
            slug.drag *= SLUG_DRAG_MULT;
        }

        //navanax's emp-cannon-mount already boosts allied build speed (vanilla +300%) - just raise it
        eachWeaponBullet(UnitTypes.navanax, b -> {
            if(b instanceof EmpBulletType emp) emp.timeIncrease = NAVANAX_BUILD_BOOST;
        });

        //beta's status-effect pool: real buffs and debuffs only, nothing with damage/intervalDamage/
        //healthMultiplier<1 (that would be a disguised HP-loss debuff, which sonka explicitly ruled out)
        betaPool = new StatusEffect[]{
            StatusEffects.fast, StatusEffects.overclock, StatusEffects.shielded, //buffs
            StatusEffects.slow, StatusEffects.disarmed, StatusEffects.electrified //debuffs
        };
    }

    @Override
    public void init(){
        //campaign database lore, one line per cursed unit (only while this curse is on) - the
        //turret-reworks precedent (see TurretReworksCurse)
        if(isEnabled()){
            funmode.core.Lore.details(UnitTypes.dagger, "fun.lore.unit.dagger");
            funmode.core.Lore.details(UnitTypes.mace, "fun.lore.unit.mace");
            funmode.core.Lore.details(UnitTypes.fortress, "fun.lore.unit.fortress");
            funmode.core.Lore.details(UnitTypes.scepter, "fun.lore.unit.scepter");
            funmode.core.Lore.details(UnitTypes.reign, "fun.lore.unit.reign");
            funmode.core.Lore.details(UnitTypes.nova, "fun.lore.unit.nova");
            funmode.core.Lore.details(UnitTypes.pulsar, "fun.lore.unit.pulsar");
            funmode.core.Lore.details(UnitTypes.quasar, "fun.lore.unit.quasar");
            funmode.core.Lore.details(UnitTypes.vela, "fun.lore.unit.vela");
            funmode.core.Lore.details(UnitTypes.corvus, "fun.lore.unit.corvus");
            funmode.core.Lore.details(UnitTypes.atrax, "fun.lore.unit.atrax");
            funmode.core.Lore.details(UnitTypes.spiroct, "fun.lore.unit.spiroct");
            funmode.core.Lore.details(UnitTypes.arkyid, "fun.lore.unit.arkyid");
            funmode.core.Lore.details(UnitTypes.flare, "fun.lore.unit.flare");
            funmode.core.Lore.details(UnitTypes.horizon, "fun.lore.unit.horizon");
            funmode.core.Lore.details(UnitTypes.zenith, "fun.lore.unit.zenith");
            funmode.core.Lore.details(UnitTypes.antumbra, "fun.lore.unit.antumbra");
            funmode.core.Lore.details(UnitTypes.eclipse, "fun.lore.unit.eclipse");
            funmode.core.Lore.details(UnitTypes.mono, "fun.lore.unit.mono");
            funmode.core.Lore.details(UnitTypes.poly, "fun.lore.unit.poly");
            funmode.core.Lore.details(UnitTypes.mega, "fun.lore.unit.mega");
            funmode.core.Lore.details(UnitTypes.quad, "fun.lore.unit.quad");
            funmode.core.Lore.details(UnitTypes.oct, "fun.lore.unit.oct");
            funmode.core.Lore.details(UnitTypes.risso, "fun.lore.unit.risso");
            funmode.core.Lore.details(UnitTypes.minke, "fun.lore.unit.minke");
            funmode.core.Lore.details(UnitTypes.bryde, "fun.lore.unit.bryde");
            funmode.core.Lore.details(UnitTypes.sei, "fun.lore.unit.sei");
            funmode.core.Lore.details(UnitTypes.omura, "fun.lore.unit.omura");
            funmode.core.Lore.details(UnitTypes.retusa, "fun.lore.unit.retusa");
            funmode.core.Lore.details(UnitTypes.oxynoe, "fun.lore.unit.oxynoe");
            funmode.core.Lore.details(UnitTypes.cyerce, "fun.lore.unit.cyerce");
            funmode.core.Lore.details(UnitTypes.aegires, "fun.lore.unit.aegires");
            funmode.core.Lore.details(UnitTypes.navanax, "fun.lore.unit.navanax");
            funmode.core.Lore.details(UnitTypes.alpha, "fun.lore.unit.alpha");
            funmode.core.Lore.details(UnitTypes.beta, "fun.lore.unit.beta");
            funmode.core.Lore.details(UnitTypes.gamma, "fun.lore.unit.gamma");
        }

        //runtime-only lookup sets: reading weapon bullet refs mutates nothing, so unlike the
        //loadContent stat changes these are filled unconditionally and work after a mid-session
        //enable too
        eachWeaponBullet(UnitTypes.dagger, b -> daggerBullets.add(b));
        eachWeaponBullet(UnitTypes.horizon, b -> horizonBombs.add(b));
        eachWeaponBullet(UnitTypes.spiroct, b -> spiroctBullets.add(b));
        spiderTypes.addAll(UnitTypes.crawler, UnitTypes.atrax, UnitTypes.spiroct, UnitTypes.arkyid, UnitTypes.toxopid);
        slugTypes.addAll(UnitTypes.retusa, UnitTypes.oxynoe, UnitTypes.cyerce, UnitTypes.aegires, UnitTypes.navanax);
        if(monoBaseMineSpeed < 0f) monoBaseMineSpeed = UnitTypes.mono.mineSpeed; //remember stock dig speed
        //poly's job pool - safe here (init runs at client load, long after UnitCommand.loadAll())
        polyCommands = new UnitCommand[]{UnitCommand.mineCommand, UnitCommand.repairCommand, UnitCommand.assistCommand, UnitCommand.rebuildCommand};

        funmode.core.PoisonClouds.init();
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.draw, this::draw);
        Events.on(UnitDestroyEvent.class, e -> onUnitDeath(e.unit));
        Events.on(WorldLoadEvent.class, e -> {
            stillTicks.clear();
            lastX.clear();
            lastY.clear();
            pulsarCharge.clear();
            knownReigns.clear();
            flareTimer.clear();
            spiroctSeen.clear();
            eclipseCharge.clear();
            antumbras.clear();
            polyTimer.clear();
            octLastHp.clear();
            octInkTimer.clear();
            monos.clear();
            brydeCooldown.clear();
            omuraTimer.clear();
            omuraDark.clear();
            retusaDash.clear();
            oxynoeBelowThreshold.clear();
            betaEffect.clear();
            gammaPulse.clear();
            if(monoBaseMineSpeed > 0f) UnitTypes.mono.mineSpeed = monoBaseMineSpeed;
            eclipseCount = 0;
            eclipseSolarTouched = false;
            rollWind();
        });
        rollWind();
    }

    void update(){
        if(!isActive()){
            eclipseCount = 0; //stale eclipses must not keep draining the sun while inert
            return;
        }

        updateBullets();

        windTimer += Time.delta;
        if(windTimer >= WIND_PERIOD) rollWind();
        float windX = Angles.trnsx(windDir, WIND_PUSH) * Time.delta;
        float windY = Angles.trnsy(windDir, WIND_PUSH) * Time.delta;

        //single per-frame pass over units: vela sail, pulsar clock, horizon drift, arkyid limp
        for(Unit u : Groups.unit){
            if(u.dead()) continue;
            UnitType t = u.type;

            if(t == UnitTypes.vela){
                u.vel.add(windX, windY);
                if(Mathf.chanceDelta(0.1)){
                    windStreak.at(u.x + Mathf.range(60f), u.y + Mathf.range(60f), windDir);
                }
            }else if(t == UnitTypes.pulsar){
                float charge = pulsarCharge.get(u.id) + Time.delta / PULSAR_PERIOD;
                if(charge >= 1f){
                    charge = 0f;
                    pulsarBurst(u);
                }
                pulsarCharge.put(u.id, charge);
            }else if(t == UnitTypes.eclipse){
                //corona builds on a strict clock, then flares - the growing glow (drawn in draw())
                //telegraphs it, same read as the pulsar
                float charge = eclipseCharge.get(u.id) + Time.delta / ECLIPSE_CORONA_PERIOD;
                if(charge >= 1f){
                    charge = 0f;
                    eclipseCorona(u);
                }
                eclipseCharge.put(u.id, charge);
            }else if(t == UnitTypes.horizon){
                //never stops: whenever it slows down, it gets nudged over the horizon
                if(u.vel.len() < HORIZON_DRIFT_SPEED){
                    u.vel.add(Angles.trnsx(u.rotation, HORIZON_DRIFT_PUSH * Time.delta),
                        Angles.trnsy(u.rotation, HORIZON_DRIFT_PUSH * Time.delta));
                }
            }else if(t == UnitTypes.arkyid){
                float limp = 1f - u.healthf();
                if(limp > 0.1f){
                    //extra velocity decay = the limp, sinusoidal heading wobble = the swagger
                    u.vel.scl(Mathf.pow(1f - ARKYID_LIMP_DRAG * limp, Time.delta));
                    u.vel.rotate(Mathf.sin(Time.time, 5f, ARKYID_WOBBLE * limp) * Time.delta * 0.5f);
                }
            }
        }

        scanTimer += Time.delta;
        if(scanTimer >= 10f){
            float step = scanTimer;
            scanTimer = 0f;
            slowScan(step);
        }
        reignTimer += Time.delta;
        if(reignTimer >= 30f){
            reignTimer = 0f;
            reignCensus();
        }
    }

    /**
     * Per-frame bullet pass. Dagger: knife-fight damage - every dagger bullet's damage is rescaled
     * each tick from its CURRENT distance to the shooter, so fresh point-blank bullets carry the
     * full bonus and decay to stock by DAGGER_CLOSE_RANGE (writing the per-bullet damage copy, not
     * the shared type, keeps this safe and self-restoring). Horizon: brand-new bombs get one
     * forward velocity kick so they sail a couple of tiles past the target (bl.time < 1 is true on
     * exactly one frame at normal speed).
     */
    void updateBullets(){
        boolean anyAntumbra = !antumbras.isEmpty();
        if(anyAntumbra) absorbBuf.clear();
        for(Bullet bl : Groups.bullet){
            //phantom absorption acts on ANY incoming shot whoever fired it (turret owners aren't
            //Units), so this runs before the owner-must-be-a-unit gate below
            if(anyAntumbra) antumbraAbsorb(bl);

            if(!(bl.owner instanceof Unit u)) continue;
            if(u.type == UnitTypes.dagger && daggerBullets.contains(bl.type)){
                float frac = Mathf.clamp(1f - bl.dst(u) / DAGGER_CLOSE_RANGE);
                bl.damage = bl.type.damage * (1f + DAGGER_CLOSE_BONUS * frac);
            }else if(u.type == UnitTypes.horizon && horizonBombs.contains(bl.type) && bl.time < 1f){
                bl.vel.add(Angles.trnsx(u.rotation, HORIZON_BOMB_KICK), Angles.trnsy(u.rotation, HORIZON_BOMB_KICK));
            }else if(u.type == UnitTypes.spiroct && spiroctBullets.contains(bl.type)){
                //spiroct's sap is HITSCAN (SapBulletType, speed 0): it resolves its target in init()
                //and parks a laser to it, storing the struck entity/building in bl.data (a bare Vec2
                //there means it hit nothing). Condense a poison cloud exactly where the sap LANDS on
                //a unit or block - once per bolt (spiroctSeen), rolled against the cloud chance so
                //fast-firing spirocts don't carpet the map. Posc = a real unit/building hit; a Vec2
                //miss is neither Posc nor added to the set, so it silently retries nothing.
                if(bl.data instanceof mindustry.gen.Posc hit && spiroctSeen.add(bl.id) && Mathf.chance(SPIROCT_CLOUD_CHANCE)){
                    //spare the spiroct's own team - the venom shouldn't corrode the spider that spat it
                    funmode.core.PoisonClouds.spawn(hit.x(), hit.y(), SPIROCT_CLOUD_RADIUS, SPIROCT_CLOUD_LIFE, SPIROCT_CLOUD_DPS, u.team());
                }
            }
        }
        //remove swallowed shots now that iteration is done (never mid-loop)
        for(int i = 0; i < absorbBuf.size; i++) absorbBuf.get(i).remove();
    }

    void rollWind(){
        windDir = Mathf.random(360f);
        windTimer = 0f;
    }

    void pulsarBurst(Unit u){
        Sounds.shockBullet.at(u.x, u.y, 0.8f);
        //the ring shows the EMP even when it finds no victims (the lightning alone reads as
        //nothing at all if the radius happens to be empty - sonka's "молний не видно")
        empRing.at(u.x, u.y, PULSAR_RANGE);
        int[] left = {PULSAR_MAX_TARGETS};
        Units.nearbyEnemies(u.team, u.x, u.y, PULSAR_RANGE, e -> {
            if(left[0]-- <= 0) return;
            Lightning.create(u.team, Pal.lancerLaser, PULSAR_DAMAGE, u.x, u.y, u.angleTo(e), 12);
            Fx.hitLancer.at(e.x, e.y);
            e.apply(StatusEffects.electrified, 120f);
        });
    }

    /** The 10-tick pass: fortress entrenching, scepter court, corvus habits, spiders, air antics.
     * (spiroct's poison is handled per-frame off its sap hits in {@link #updateBullets}.) */
    void slowScan(float step){
        //bound the sap-dedup set: bolt ids never recur, so old ones are dead weight; a plain reset
        //once large is safe (no live bolt could be forgotten - it already hit once).
        if(spiroctSeen.size > 4096) spiroctSeen.clear();

        int eclipses = 0;
        antumbras.clear();
        monos.clear();
        for(Unit u : Groups.unit){
            if(u.dead()) continue;
            UnitType t = u.type;
            if(t == UnitTypes.fortress){
                updateEntrench(u, step);
            }else if(t == UnitTypes.scepter){
                updateCourt(u);
            }else if(t == UnitTypes.corvus){
                updateCrow(u, step);
            }else if(t == UnitTypes.arkyid){
                updateMatriarch(u);
            }else if(t == UnitTypes.flare){
                updateFlare(u, step);
            }else if(t == UnitTypes.antumbra){
                antumbras.add(u);
            }else if(t == UnitTypes.eclipse){
                eclipses++;
            }else if(t == UnitTypes.mono){
                monos.add(u);
            }else if(t == UnitTypes.mega){
                updateMega(u);
            }else if(t == UnitTypes.oct){
                updateOct(u, step);
            }else if(t == UnitTypes.poly){
                updatePoly(u, step);
            }else if(t == UnitTypes.navanax){
                updateNavanax(u);
            }else if(t == UnitTypes.risso){
                updateRisso(u);
            }else if(t == UnitTypes.minke){
                updateMinke(u);
            }else if(t == UnitTypes.bryde){
                updateBryde(u, step);
            }else if(t == UnitTypes.omura){
                updateOmura(u, step);
            }else if(t == UnitTypes.retusa){
                updateRetusa(u, step);
            }else if(t == UnitTypes.oxynoe){
                updateOxynoe(u);
            }else if(t == UnitTypes.aegires){
                updateAegires(u);
            }else if(t == UnitTypes.alpha){
                updateAlpha(u);
            }else if(t == UnitTypes.beta){
                updateBeta(u);
            }else if(t == UnitTypes.gamma){
                updateGamma(u, step);
            }
        }
        eclipseCount = eclipses;
        updateEclipseSun();

        //the LONE mono is a hyper-miner; the instant there are two or more, they all dig at stock
        //speed again. mineSpeed is a shared type field, but gating it purely on the headcount keeps
        //every mono consistent (the rule itself is uniform). The unique one also gets speed/haste.
        boolean loneMono = monos.size == 1;
        UnitTypes.mono.mineSpeed = loneMono ? monoBaseMineSpeed * MONO_MINE_MULT : monoBaseMineSpeed;
        if(loneMono) monos.first().apply(StatusEffects.overclock, 40f);
    }

    /** mega: its compensating buff is a direct healing AURA over nearby ALLIED UNITS (its heal-bolts
     * only mend allied buildings, never units - so the aura is done in code); the delusion is that
     * while it's "attacking" it ALSO mends nearby enemies, a medic for the wrong side too. */
    void updateMega(Unit u){
        Units.nearby(u.team, u.x, u.y, MEGA_HEAL_RANGE, ally -> {
            if(ally.dead() || ally.health >= ally.maxHealth) return;
            ally.heal(MEGA_ALLY_HEAL_AMOUNT);
            if(Mathf.chance(0.12f)) Fx.heal.at(ally.x, ally.y);
        });
        if(u.isShooting){
            Units.nearbyEnemies(u.team, u.x, u.y, MEGA_HEAL_RANGE, e -> {
                if(e.health >= e.maxHealth) return;
                e.heal(MEGA_HEAL_AMOUNT);
                if(Mathf.chance(0.25f)) Fx.heal.at(e.x, e.y);
            });
        }
    }

    /** oct: losing health since the last scan arms an ink cloud (saps + slows nearby attackers),
     * throttled to one burst per {@link #OCT_INK_COOLDOWN} so sustained fire billows ink steadily
     * instead of never (the old 5%-of-24000hp trigger practically never fired). */
    void updateOct(Unit u, float step){
        float prev = octLastHp.get(u.id, u.maxHealth);
        float ink = octInkTimer.get(u.id) - step;
        if(prev - u.health > u.maxHealth * OCT_INK_DROP_FRAC && ink <= 0f){
            inkCloud.at(u.x, u.y);
            Units.nearbyEnemies(u.team, u.x, u.y, OCT_INK_RADIUS, e -> {
                e.apply(StatusEffects.sapped, OCT_INK_STATUS);
                e.apply(StatusEffects.sporeSlowed, OCT_INK_STATUS);
            });
            //ink gums up enemy buildings too - turrets in the cloud fire slower (units aren't the only victims)
            for(Building b : Groups.build){
                if(b.team != u.team && b.within(u.x, u.y, OCT_INK_RADIUS)) b.applySlowdown(OCT_INK_SLOW, OCT_INK_BUILD_TICKS);
            }
            ink = OCT_INK_COOLDOWN;
        }
        octInkTimer.put(u.id, Math.max(0f, ink));
        octLastHp.put(u.id, u.health);
    }

    /** poly can't focus: on its own clock it's simply handed a NEW random job - mine, repair, assist
     * or rebuild - so it keeps dropping one task for another. Only AI-commanded polys (not one you're
     * directly piloting) get reassigned. */
    void updatePoly(Unit u, float step){
        float period = POLY_SWITCH_MIN + (u.id % 7) * ((POLY_SWITCH_MAX - POLY_SWITCH_MIN) / 7f);
        float timer = polyTimer.get(u.id) + step;
        if(timer >= period){
            timer = 0f;
            if(polyCommands != null && !u.isPlayer() && u.controller() instanceof CommandAI cai){
                cai.command(polyCommands[Mathf.random(polyCommands.length - 1)]);
                Fx.smeltsmoke.at(u.x, u.y + u.hitSize * 0.4f);
            }
        }
        polyTimer.put(u.id, timer);
    }

    /** risso: wounded and cornered, it fights harder - below {@link #RISSO_LOW_HP_FRAC} health it
     * carries a shield+speed buff, reapplied every scan for as long as it stays down there (fully
     * reversible: nothing to undo once it heals back up). */
    void updateRisso(Unit u){
        if(u.healthf() < RISSO_LOW_HP_FRAC){
            u.apply(StatusEffects.shielded, RISSO_BUFF_DURATION);
            u.apply(StatusEffects.fast, RISSO_BUFF_DURATION);
        }
    }

    /** minke: the smallest, fastest, most inquisitive baleen whale - it can't resist darting in close
     * the instant something enemy wanders near, trading caution for a burst of speed. */
    void updateMinke(Unit u){
        if(Units.closestEnemy(u.team, u.x, u.y, MINKE_CURIOUS_RANGE, e -> true) != null){
            u.apply(StatusEffects.fast, 20f);
        }
    }

    /** bryde: a pursuit/lunge feeder - every {@link #BRYDE_PUSH_COOLDOWN} it lunges once, shoving off
     * and slowing anything currently too close (not a constant shove every scan). */
    void updateBryde(Unit u, float step){
        float cd = brydeCooldown.get(u.id) - step;
        if(cd <= 0f){
            boolean[] pushed = {false};
            Units.nearbyEnemies(u.team, u.x, u.y, BRYDE_PUSH_RANGE, e -> {
                float ang = u.angleTo(e);
                e.vel.add(Angles.trnsx(ang, BRYDE_PUSH_FORCE), Angles.trnsy(ang, BRYDE_PUSH_FORCE));
                e.apply(StatusEffects.slow, BRYDE_PUSH_DEBUFF_DURATION);
                pushed[0] = true;
            });
            if(pushed[0]){
                waterSpout.at(u.x, u.y);
                cd = BRYDE_PUSH_COOLDOWN;
            }
        }
        brydeCooldown.put(u.id, Math.max(0f, cd));
    }

    /** sei: one of the fastest whales - killing something gets its blood up, a burst of speed. Purely
     * event-driven off {@link #onUnitDeath}, no per-scan work needed. */

    /** omura: a rare, barely-studied whale famous for lopsided asymmetric colouring (one side pale,
     * one dark) - it periodically flips between a dark, armored/slow phase and a pale, fragile/fast
     * one. Purely status-driven (reapplied each scan) so the flip is fully reversible either way, and
     * since the applied status is synced, {@link #draw} can read the SAME phase back out on clients
     * via {@code hasEffect} without any extra plumbing. */
    void updateOmura(Unit u, float step){
        float t = omuraTimer.get(u.id) + step;
        if(t >= OMURA_PHASE_PERIOD){
            t = 0f;
            if(omuraDark.contains(u.id)) omuraDark.remove(u.id); else omuraDark.add(u.id);
            omuraPhaseFx.at(u.x, u.y);
        }
        omuraTimer.put(u.id, t);
        if(omuraDark.contains(u.id)){
            u.apply(StatusEffects.slow, 20f);
            u.apply(StatusEffects.shielded, 20f);
        }else{
            u.apply(StatusEffects.fast, 20f);
        }
    }

    /** retusa: a tiny bubble-snail predator - no burrowing anymore, it just dashes at the nearest
     * target every {@link #RETUSA_DASH_PERIOD} ticks, a plain velocity kick toward it. */
    void updateRetusa(Unit u, float step){
        float t = retusaDash.get(u.id) + step;
        if(t >= RETUSA_DASH_PERIOD){
            t = 0f;
            Unit prey = Units.closestEnemy(u.team, u.x, u.y, RETUSA_DASH_RANGE, e -> true);
            if(prey != null){
                float ang = u.angleTo(prey);
                u.vel.add(Angles.trnsx(ang, RETUSA_DASH_SPEED), Angles.trnsy(ang, RETUSA_DASH_SPEED));
                Fx.smeltsmoke.at(u.x, u.y);
            }
        }
        retusaDash.put(u.id, t);
    }

    /** oxynoe: steals chloroplasts from the algae it grazes - a near-lethal hit buys it a moment of
     * true invulnerability, but it comes out the other side sluggish for a long while. Edge-triggered
     * on first dipping under {@link #OXYNOE_LETHAL_FRAC} (not every tick spent down there), but can
     * refire later if it recovers and drops into the red again. */
    void updateOxynoe(Unit u){
        boolean below = u.healthf() < OXYNOE_LETHAL_FRAC;
        boolean wasBelow = oxynoeBelowThreshold.contains(u.id);
        if(below && !wasBelow){
            u.apply(StatusEffects.invincible, OXYNOE_INVINCIBLE_DURATION);
            u.apply(StatusEffects.slow, OXYNOE_SLOW_DURATION);
            autotomyBurst.at(u.x, u.y);
            oxynoeBelowThreshold.add(u.id);
        }else if(!below && wasBelow){
            oxynoeBelowThreshold.remove(u.id);
        }
    }

    /** aegires: a tiny warty nudibranch, distasteful and aposematically coloured - anything that gets
     * close enough to bite it gets a nasty poison bite back, corroded on top of the sap. */
    void updateAegires(Unit u){
        Unit toucher = Units.closestEnemy(u.team, u.x, u.y, AEGIRES_RETALIATE_RANGE, e -> true);
        if(toucher != null && Mathf.chance(AEGIRES_RETALIATE_CHANCE)){
            toucher.damage(AEGIRES_RETALIATE_DPS);
            toucher.apply(StatusEffects.sapped, AEGIRES_RETALIATE_DURATION);
            toucher.apply(StatusEffects.corroded, AEGIRES_RETALIATE_DURATION);
            Fx.hitLancer.at(toucher.x, toucher.y);
        }
    }

    /** alpha: the weakest of the three core defenders, decaying particle namesake - every so often it
     * just flickers briefly untouchable, no trigger needed. */
    void updateAlpha(Unit u){
        if(Mathf.chance(ALPHA_INVINCIBLE_CHANCE)){
            u.apply(StatusEffects.invincible, ALPHA_INVINCIBLE_DURATION);
            alphaFlickerFx.at(u.x, u.y);
        }
    }

    /** beta: the eternal "beta build" - every one that spawns rolls its own permanent real status
     * effect exactly once from {@link #betaPool} (a buff or a debuff, never one that costs HP over
     * time), so no two betas turn out the stock unit; some are duds, some are surprisingly strong.
     * Reapplied every scan since status effects lapse on their own. */
    void updateBeta(Unit u){
        StatusEffect effect = betaEffect.get(u.id);
        if(effect == null){
            effect = betaPool[Mathf.random(betaPool.length - 1)];
            betaEffect.put(u.id, effect);
        }
        u.apply(effect, BETA_EFFECT_DURATION);
    }

    /** gamma: the strongest of the three core defenders, named for the most energetic/penetrating
     * radiation - it doesn't need to aim, it just passively ticks out a small damaging pulse to
     * anything close every so often, corroding whatever it touches. */
    void updateGamma(Unit u, float step){
        float t = gammaPulse.get(u.id) + step;
        if(t >= GAMMA_PULSE_PERIOD){
            t = 0f;
            Units.nearbyEnemies(u.team, u.x, u.y, GAMMA_PULSE_RANGE, e -> {
                e.damage(GAMMA_PULSE_DAMAGE);
                e.apply(StatusEffects.corroded, GAMMA_CORRODE_DURATION);
            });
            gammaPulseFx.at(u.x, u.y);
        }
        gammaPulse.put(u.id, t);
    }

    /** navanax is a slug-eating slug: when wounded it gnaws a smaller allied slug nearby for a little
     * HP (never below 40% of that ally) and heals several times what it took. */
    void updateNavanax(Unit u){
        if(u.health >= u.maxHealth) return;
        Unit[] prey = {null};
        float[] best = {NAVANAX_BITE_RANGE};
        Units.nearby(u.team, u.x, u.y, NAVANAX_BITE_RANGE, ally -> {
            if(ally == u || ally.dead() || !slugTypes.contains(ally.type) || ally.hitSize >= u.hitSize) return;
            float d = ally.dst(u);
            if(d < best[0]){ best[0] = d; prey[0] = ally; }
        });
        Unit p = prey[0];
        if(p == null || !Mathf.chance(0.3f)) return;
        float floor = p.maxHealth * 0.4f;
        float dmg = Math.min(NAVANAX_BITE_DMG, p.health - floor);
        if(dmg <= 0f) return;
        p.damage(dmg);
        u.heal(dmg * NAVANAX_HEAL_MULT);
        Fx.hitLancer.at(p.x, p.y);
        Fx.heal.at(u.x, u.y);
    }

    /** Angle of phantom {@code i} on antumbra {@code u}'s orbiting ring at the current time - shared
     * by the draw and the decoy so a fooled shot veers onto exactly the ghost you see. */
    float phantomAngle(Unit u, int i){
        return Time.time * ANTUMBRA_SPIN + u.id * 40f + i * (360f / ANTUMBRA_PHANTOMS);
    }

    /**
     * antumbra absorption: a phantom physically EATS an enemy projectile that crosses within
     * {@link #ANTUMBRA_ABSORB_RADIUS} of the ghost's actual orbiting position - so shots vanish ON a
     * copy, not randomly out in the open (the old big-radius roll ate them anywhere, which looked
     * broken). Swallowed shots are buffered and removed after the bullet loop. Hitscan beams carry
     * no velocity and pass straight through.
     */
    void antumbraAbsorb(Bullet bl){
        if(bl.vel.len() < 0.1f) return;
        for(int i = 0; i < antumbras.size; i++){
            Unit a = antumbras.get(i);
            if(a == null || a.dead() || a.team == bl.team) continue;
            if(bl.dst(a) > ANTUMBRA_DECOY_RADIUS) continue; //cheap cull before the per-phantom check

            float orbit = a.hitSize * ANTUMBRA_ORBIT_SCL;
            for(int p = 0; p < ANTUMBRA_PHANTOMS; p++){
                float ang = phantomAngle(a, p);
                float px = a.x + Angles.trnsx(ang, orbit);
                float py = a.y + Angles.trnsy(ang, orbit);
                if(bl.within(px, py, ANTUMBRA_ABSORB_RADIUS)){
                    antumbraLure.at(px, py);
                    absorbBuf.add(bl);
                    return;
                }
            }
        }
    }

    /** Eclipse corona: a blinding flare that disarms (blinds) and saps every enemy around it. */
    void eclipseCorona(Unit u){
        coronaFlash.at(u.x, u.y, ECLIPSE_CORONA_RANGE);
        Sounds.explosion.at(u.x, u.y, 0.7f);
        Units.nearbyEnemies(u.team, u.x, u.y, ECLIPSE_CORONA_RANGE, e -> {
            e.apply(StatusEffects.disarmed, ECLIPSE_CORONA_DISARM);
            e.apply(StatusEffects.sapped, ECLIPSE_CORONA_SAP);
        });
    }

    /** Wounded arkyid stumbles outright; healthy or not, it drills the spider pack around it. */
    void updateMatriarch(Unit u){
        float limp = 1f - u.healthf();
        if(limp > 0.1f && Mathf.chance(ARKYID_STUMBLE_BASE + ARKYID_STUMBLE_SCALE * limp)){
            u.vel.setZero();
            u.apply(StatusEffects.unmoving, 50f);
            Fx.smeltsmoke.at(u.x + Mathf.range(u.hitSize * 0.4f), u.y + Mathf.range(u.hitSize * 0.4f));
        }
        Units.nearby(u.team, u.x, u.y, ARKYID_PACK_RANGE, ally -> {
            if(ally != u && !ally.dead() && spiderTypes.contains(ally.type)){
                ally.apply(StatusEffects.overclock, 40f);
            }
        });
    }

    /** Signal flare: on its own clock, launches a small homing incendiary rocket at the nearest
     * enemy - a unit if one's in range, otherwise an enemy building - which streaks in and sets the
     * impact zone alight. With nothing to shoot at it just stays primed and fires the instant a
     * target wanders into range. */
    void updateFlare(Unit u, float step){
        float period = FLARE_PERIOD + (u.id % 5) * 90f; //desync the squadron's launches
        float timer = flareTimer.get(u.id) + step;
        if(timer >= period){
            mindustry.gen.Teamc target = Units.closestEnemy(u.team, u.x, u.y, FLARE_TARGET_RANGE, e -> true);
            if(target == null) target = indexer.findEnemyTile(u.team, u.x, u.y, FLARE_TARGET_RANGE, b -> true);
            if(target != null){
                timer = 0f;
                float ang = u.angleTo(target);
                flareRocketBullet.create(u, u.team, u.x, u.y, ang);
                flareLaunch.at(u.x, u.y, ang);
                Sounds.shootMissile.at(u.x, u.y, 1.05f);
            }else{
                timer = period; //hold ready
            }
        }
        flareTimer.put(u.id, timer);
    }

    /**
     * The dying sun. While MachineQuirks' solar gag is actively driving the multiplier, it already
     * subtracts {@link #eclipseSolarDrain} inside its own formula and this does nothing; otherwise
     * this curse owns the field - writes while eclipses live, restores exactly once after the last
     * one dies (so maps keep their own multiplier when no eclipse ever showed up).
     */
    void updateEclipseSun(){
        if(state.rules == null || MachineQuirksCurse.drivingSolar) return;
        if(eclipseCount > 0){
            state.rules.solarMultiplier = Mathf.clamp(1f - eclipseSolarDrain(), -1f, 1f);
            eclipseSolarTouched = true;
        }else if(eclipseSolarTouched){
            state.rules.solarMultiplier = 1f;
            eclipseSolarTouched = false;
        }
    }

    /** Shared "how long has this unit sat still" timer (fortress entrenching, retusa burrowing both
     * key off it - a plain timer isn't a MECHANIC, just plumbing, so this isn't the kind of shared
     * behaviour sonka wants split apart). */
    float trackStillness(Unit u, float step){
        float dx = u.x - lastX.get(u.id, u.x), dy = u.y - lastY.get(u.id, u.y);
        lastX.put(u.id, u.x);
        lastY.put(u.id, u.y);
        float still = (dx * dx + dy * dy) > ENTRENCH_MOVE_EPS * ENTRENCH_MOVE_EPS ? 0f : stillTicks.get(u.id) + step;
        stillTicks.put(u.id, still);
        return still;
    }

    void updateEntrench(Unit u, float step){
        float still = trackStillness(u, step);
        //short-lived statuses reapplied each scan: they lapse on their own right after it moves.
        //overdrive = x1.4 damage + the visible gold sparks, shielded = x3 effective health -
        //replaces the original silent armor multiplier ("по факту ничего не меняется" - sonka)
        if(still >= ENTRENCH_TICKS){
            u.apply(StatusEffects.overdrive, 25f);
            u.apply(StatusEffects.shielded, 25f);
        }
    }

    void updateCourt(Unit king){
        int[] retinue = {0};
        //retinue = allies in range get overclock; no more tugging stragglers toward the king (per sonka)
        Units.nearby(king.team, king.x, king.y, RETINUE_RANGE, ally -> {
            if(ally == king || ally.dead() || ally.type == UnitTypes.scepter) return;
            retinue[0]++;
            ally.apply(StatusEffects.overclock, 40f);
        });

        if(retinue[0] == 0){
            king.apply(StatusEffects.disarmed, 20f);
            if(Mathf.chance(0.12)){
                Fx.smeltsmoke.at(king.x + Mathf.range(4f), king.y + king.hitSize * 0.5f + Mathf.range(3f));
            }
        }
    }

    void updateCrow(Unit crow, float step){
        if(Mathf.chance(CORVUS_CAW_CHANCE)){
            Sounds.shootAtrax.at(crow.x, crow.y, 2.2f, 0.7f);
        }

        Unit flyer = Units.closestEnemy(crow.team, crow.x, crow.y, CORVUS_FEAR_RANGE, e -> e.isFlying());
        if(flyer != null && Mathf.chance(CORVUS_FEAR_CHANCE)){
            crow.vel.add(Tmp.v1.set(crow.x - flyer.x, crow.y - flyer.y).nor().scl(6f));
            crow.apply(StatusEffects.disarmed, 90f);
            Fx.smeltsmoke.at(crow.x, crow.y + crow.hitSize * 0.4f);
        }
    }

    /**
     * One reign per team: whenever a team fields several, the newest id is the rightful heir and
     * all older ones die in the coup. New ids get a fanfare. Census beats spawn-events here:
     * reconstructors birth units as payloads (see ExplosiveProductionCurse), a periodic headcount
     * catches every path into the world.
     */
    void reignCensus(){
        for(Teams.TeamData td : state.teams.present){
            Unit newest = null;
            for(Unit u : td.units){
                if(u.type != UnitTypes.reign || u.dead()) continue;
                if(newest == null || u.id > newest.id) newest = u;
            }
            if(newest == null) continue;

            if(knownReigns.add(newest.id)){
                Sounds.chargeVela.at(newest.x, newest.y, 1.2f, 2f);
            }

            //snapshot first - kill() mutates the team's unit list mid-iteration otherwise
            doomedReigns.clear();
            for(Unit u : td.units){
                if(u.type == UnitTypes.reign && !u.dead() && u != newest) doomedReigns.add(u);
            }
            for(Unit u : doomedReigns){
                u.kill();
                if(td.team == player.team() && Core.bundle.has("fun.units.coup")){
                    funmode.core.Chat.send(Core.bundle.format("fun.units.coup", newest.type.localizedName));
                }
            }
        }
    }

    /** Death hooks: corvus carrion-heals off any nearby death; an atrax turns a nearby enemy's death
     * into a plague burst (the contagion chains when those victims die inside the cloud too); a cyerce
     * does the same but with its own toxic-green sting cloud; a sei that just landed the kill gets its
     * blood up, a burst of speed. */
    void onUnitDeath(Unit victim){
        if(!isActive() || victim == null) return;

        boolean plagued = false, stung = false;
        for(Unit u : Groups.unit){
            if(u.dead() || u == victim) continue;
            if(u.type == UnitTypes.corvus && u.dst(victim) <= CARRION_RANGE){
                u.heal(Math.min(victim.maxHealth * CARRION_HEAL_FRAC, CARRION_HEAL_CAP));
                Fx.heal.at(u.x, u.y);
            }else if(!plagued && u.type == UnitTypes.atrax && u.team != victim.team && u.dst(victim) <= ATRAX_CONTAGION_RANGE){
                //an enemy that dies in an atrax's reach ruptures into a plague cloud; spare the
                //atrax's own team so the plague only eats the pack the corpse belonged to
                funmode.core.PoisonClouds.spawn(victim.x, victim.y, ATRAX_CONTAGION_RADIUS, ATRAX_CONTAGION_LIFE, ATRAX_CONTAGION_DPS, u.team());
                atraxPlague.at(victim.x, victim.y);
                plagued = true;
            }else if(!stung && u.type == UnitTypes.cyerce && u.team != victim.team && u.dst(victim) <= CYERCE_STING_RANGE){
                //same idea as atrax's plague, but cyerce's own toxic-green cerata instead
                funmode.core.PoisonClouds.spawn(victim.x, victim.y, CYERCE_STING_RADIUS, CYERCE_STING_LIFE, CYERCE_STING_DPS, u.team(), CYERCE_STING_COLOR);
                Fx.hitLancer.at(victim.x, victim.y);
                stung = true;
            }else if(u.type == UnitTypes.sei && u.team != victim.team && u.dst(victim) <= SEI_KILL_RANGE){
                u.apply(StatusEffects.overdrive, SEI_KILL_BUFF_DURATION);
                u.apply(StatusEffects.fast, SEI_KILL_BUFF_DURATION);
            }
        }
    }

    //Runs on EVERY machine (not isActive-gated). Visuals with a synced/deterministic driver (eclipse
    //dim from live eclipse units, antumbra phantoms from Time.time+id, quasar searchlight) show for all;
    //the ones that ride host-only accumulated state (fortress entrench, pulsar/eclipse charge, wind) stay
    //host/SP only (a client's maps are empty), gated below.
    void draw(){
        if(!isEnabled() || !state.isGame()) return;
        float pz = Draw.z();

        //the dimmed sun: a dark wash over the whole view, deeper the more eclipses live. Clients derive the
        //count from synced units (the host-only eclipseCount is 0 there).
        int ec = eclipseCount;
        if(net.client()){
            ec = 0;
            for(Unit u : Groups.unit) if(!u.dead() && u.type == UnitTypes.eclipse) ec++;
        }
        if(ec > 0){
            Draw.z(Layer.overlayUI - 1f);
            Draw.color(0f, 0f, 0f, Math.min(ECLIPSE_DIM_MAX, ECLIPSE_DIM_PER + (ec - 1) * 0.08f));
            Core.camera.bounds(Tmp.r1);
            Fill.crect(Tmp.r1.x, Tmp.r1.y, Tmp.r1.width, Tmp.r1.height);
        }

        Draw.z(Layer.effect);
        for(Unit u : Groups.unit){
            if(u.dead()) continue;
            UnitType t = u.type;

            if(t == UnitTypes.antumbra){
                //phantom copies ORBIT the real unit in a slow ring, drawn just UNDER its layer so
                //the real one always reads on top; each faces along its orbit so it visibly circles.
                //The decoy pass re-aims fooled shots onto these same live positions.
                Draw.z(Layer.flyingUnit - 0.01f);
                float orbit = u.hitSize * ANTUMBRA_ORBIT_SCL;
                for(int i = 0; i < ANTUMBRA_PHANTOMS; i++){
                    float ang = phantomAngle(u, i);
                    float gx = u.x + Angles.trnsx(ang, orbit);
                    float gy = u.y + Angles.trnsy(ang, orbit);
                    Draw.alpha(i == 0 ? 0.34f : 0.22f);
                    Draw.rect(u.type.fullIcon, gx, gy, ang); //draw-angle = ang → sprite flies tangent to the ring
                }
                Draw.color();
                Draw.z(Layer.effect);
                continue;
            }

            if(t == UnitTypes.quasar){
                //walking searchlight - rides only the (synced) unit position, so it shows for everyone
                Draw.color(Color.white, 0.07f);
                Fill.circle(u.x, u.y, QUASAR_LIGHT_RADIUS * 0.35f);
                Drawf.light(u.x, u.y, QUASAR_LIGHT_RADIUS, Color.white, 0.85f);
                continue;
            }

            if(t == UnitTypes.omura){
                //phase reads off the SYNCED status (shielded during the dark/armored phase), not the
                //host-only omuraDark set, so clients see the same ring the host does
                boolean dark = u.hasEffect(StatusEffects.shielded);
                Draw.color(dark ? Color.valueOf("2b2b33") : Color.valueOf("d8d8e0"), 0.55f);
                Lines.stroke(2f);
                Lines.circle(u.x, u.y, u.hitSize * 0.75f);
                continue;
            }

            //the rest ride host-only accumulated state (entrench ticks, charges, wind dir) - host/SP only
            if(net.client()) continue;

            if(t == UnitTypes.fortress && stillTicks.get(u.id) >= ENTRENCH_TICKS){
                //hex rampart around the entrenched fortress
                float pulse = 0.55f + Mathf.absin(Time.time, 6f, 0.2f);
                Draw.color(Pal.accent, pulse);
                Lines.stroke(2f);
                Lines.poly(u.x, u.y, 6, u.hitSize * 0.95f, Time.time * 0.3f);
                Draw.color(Pal.accent, 0.15f);
                Fill.poly(u.x, u.y, 6, u.hitSize * 0.95f, Time.time * 0.3f);
            }else if(t == UnitTypes.pulsar){
                float charge = pulsarCharge.get(u.id);
                Draw.color(Pal.lancerLaser, 0.15f + charge * 0.45f);
                Fill.circle(u.x, u.y, u.hitSize * (0.6f + charge * 0.6f));
                Lines.stroke(1.5f);
                Draw.color(Pal.lancerLaser, charge * 0.8f);
                Lines.circle(u.x, u.y, u.hitSize * (0.8f + charge * 1.2f));
                Drawf.light(u.x, u.y, 60f + charge * 90f, Pal.lancerLaser, 0.4f + charge * 0.5f);
            }else if(t == UnitTypes.vela){
                //wind arrow over the sail so the current direction is readable
                float ax = u.x, ay = u.y + u.hitSize * 0.8f + 6f;
                Draw.color(Color.white, 0.75f);
                Lines.stroke(1.6f);
                float tipX = ax + Angles.trnsx(windDir, 10f), tipY = ay + Angles.trnsy(windDir, 10f);
                Lines.line(ax - Angles.trnsx(windDir, 10f), ay - Angles.trnsy(windDir, 10f), tipX, tipY);
                Lines.lineAngle(tipX, tipY, windDir + 150f, 5f);
                Lines.lineAngle(tipX, tipY, windDir - 150f, 5f);
            }else if(t == UnitTypes.eclipse){
                //a swelling black corona rimmed in gold as the flare charges - the tell before it blows
                float charge = eclipseCharge.get(u.id);
                Draw.color(Color.black, 0.25f + charge * 0.35f);
                Fill.circle(u.x, u.y, u.hitSize * (0.7f + charge * 0.8f));
                Lines.stroke(1.5f + charge * 2f);
                Draw.color(Pal.accent, charge);
                Lines.circle(u.x, u.y, u.hitSize * (0.9f + charge * 1.5f));
                Drawf.light(u.x, u.y, 40f + charge * 120f, Pal.accent, 0.3f + charge * 0.5f);
            }
        }

        Draw.color();
        Draw.z(pz);
    }

    void eachWeaponBullet(UnitType type, arc.func.Cons<BulletType> cons){
        for(Weapon w : type.weapons){
            if(w.bullet != null) cons.get(w.bullet);
        }
    }

    @Override
    public void buildDebug(Table table){
        table.button("Ветер: сменить", this::rollWind).size(220f, 50f);
        table.button("Пульсары: ЭМИ", () -> Groups.unit.each(u -> u.type == UnitTypes.pulsar, u -> pulsarCharge.put(u.id, 0.99f))).size(220f, 50f);
    }

    /**
     * Nova's stock heal pulse with a supernova on top: after the inherited update, a wrapped-around
     * timer means the pulse just fired - add the blinding flash and damage/sap every enemy inside
     * the same field. The protected timer is why this is a subclass and not reflection; unit
     * copies keep the subclass since Ability.copy() is clone().
     */
    public static class SupernovaAbility extends RepairFieldAbility{
        public SupernovaAbility(RepairFieldAbility base){
            super(base.amount, base.reload, base.range, base.healPercent);
            healEffect = base.healEffect;
            activeEffect = base.activeEffect;
            sound = base.sound;
            soundVolume = base.soundVolume;
            sameTypeHealMult = base.sameTypeHealMult;
        }

        @Override
        public void update(Unit unit){
            float before = timer;
            super.update(unit);
            if(timer < before){
                novaFlash.at(unit.x, unit.y, range);
                Units.nearbyEnemies(unit.team, unit.x, unit.y, range, e -> {
                    e.damage(NOVA_FLASH_DAMAGE);
                    e.apply(StatusEffects.sapped, 150f);
                });
            }
        }
    }
}
