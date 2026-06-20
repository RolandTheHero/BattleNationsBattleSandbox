package hero.roland.bnsim;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Unit} placed on the battlefield: which unit, which side, and which
 * cell it currently occupies. It also owns the unit's idle {@link Animation},
 * which is loaded lazily the first time it is needed and may be {@code null}
 * if the game files do not provide one (the UI then falls back to a token).
 *
 * <p>Player-side units use the unit's back animation; enemy-side units use the
 * front animation.
 */
public class PlacedUnit {

	private final Unit unit;
	private final Side side;
	private Cell cell;

	/** Rank (1-based, up to the unit's max rank); set during placement. */
	private int rank = 1;
	/** Current health and armor; depleted during battle, reset on rank change. */
	private int currentHp;
	private int currentArmor;

	/** Animated bar values that ease toward the current health/armor. */
	private double displayHp;
	private double displayArmor;
	private double hpRate;
	private double armorRate;
	private int lastHp = Integer.MIN_VALUE;
	private int lastArmor = Integer.MIN_VALUE;

	private Animation animation;
	private boolean animationLoaded;

	/** A one-shot attack animation that temporarily replaces the idle. */
	private Animation attackAnimation;
	private int attackStartTick;

	/** Status effects currently afflicting this unit (e.g. poison, stun). */
	private final List<ActiveStatusEffect> statusEffects = new ArrayList<>();

	public PlacedUnit(Unit unit, Side side, Cell cell) {
		this.unit = unit;
		this.side = side;
		this.cell = cell;
		resetHealth();
	}

	public Unit getUnit() {
		return unit;
	}

	public Side getSide() {
		return side;
	}

	public Cell getCell() {
		return cell;
	}

	void setCell(Cell cell) {
		this.cell = cell;
	}

	// --- Rank and health ---------------------------------------------------

	public int getRank() {
		return rank;
	}

	public int getMaxRank() {
		return Math.max(1, unit.getMaxRank());
	}

	/** Cycles the rank up by one, wrapping back to 1 after the last rank. */
	public void cycleRank() {
		rank = rank % getMaxRank() + 1;
		resetHealth();
	}

	/** Restores full health and armor for the current rank. */
	public void resetHealth() {
		if (unit.getMaxRank() >= 1) {
			Unit.Rank stats = unit.getRank(rank);
			currentHp = stats.hp();
			currentArmor = stats.armorHp();
		} else {
			currentHp = 1;
			currentArmor = 0;
		}
		// Snap the animated bars to full (no animation on reset).
		displayHp = lastHp = currentHp;
		displayArmor = lastArmor = currentArmor;
		hpRate = armorRate = 0;
		statusEffects.clear();
	}

	/**
	 * Eases the displayed bar values toward the real health/armor. When a value
	 * changes, the gap is scheduled to close over {@code durationFrames} frames.
	 */
	public void animateBars(int durationFrames) {
		if (lastHp != currentHp) {
			hpRate = (displayHp - currentHp) / durationFrames;
			lastHp = currentHp;
		}
		displayHp = ease(displayHp, currentHp, hpRate);

		if (lastArmor != currentArmor) {
			armorRate = (displayArmor - currentArmor) / durationFrames;
			lastArmor = currentArmor;
		}
		displayArmor = ease(displayArmor, currentArmor, armorRate);
	}

	private static double ease(double display, double target, double rate) {
		if (display == target || rate == 0)
			return target;
		double next = display - rate;
		boolean passed = rate > 0 ? next <= target : next >= target;
		return passed ? target : next;
	}

	public double getDisplayHp() {
		return displayHp;
	}

	public double getDisplayArmor() {
		return displayArmor;
	}

	/** Whether the animated bars have finished easing to the real health/armor. */
	public boolean barsSettled() {
		return displayHp == currentHp && displayArmor == currentArmor;
	}

	public int getCurrentHp() {
		return currentHp;
	}

	public int getCurrentArmor() {
		return currentArmor;
	}

	public int getMaxHp() {
		return unit.getMaxRank() >= 1 ? unit.getRank(rank).hp() : 1;
	}

	public int getMaxArmor() {
		return unit.getMaxRank() >= 1 ? unit.getRank(rank).armorHp() : 0;
	}

	public boolean isFullHealth() {
		return currentHp >= getMaxHp() && currentArmor >= getMaxArmor();
	}

	public boolean isDead() {
		return currentHp <= 0;
	}

	/**
	 * Applies a hit of the given raw damage and type. The armor-piercing
	 * fraction of the damage goes straight to HP and the rest to armor. Armor
	 * damage is scaled by the armor modifier; any raw damage the armor cannot
	 * absorb spills over to HP, where it (and the piercing fraction) is scaled
	 * by the HP modifier — so a unit immune to the type (HP mod 0) takes no HP
	 * damage even when its armor is destroyed. Returns the total damage dealt
	 * (for the floating damage number).
	 */
	public int applyDamage(double rawDamage, Ability.DamageType type, double armorPiercing) {
		if (rawDamage <= 0)
			return 0;
		double ap = Math.max(0, Math.min(1, armorPiercing));
		double hpMod = effectiveDamageMod(type);
		double armorMod = effectiveArmorDamageMod(type);

		// The armor-piercing fraction is aimed straight at HP; the rest at armor.
		double rawToHp = rawDamage * ap;
		double rawToArmor = rawDamage * (1 - ap);

		// Deplete armor, scaled by the armor modifier. The raw damage the armor
		// could not absorb spills over and is re-aimed at HP.
		double armorDamage = rawToArmor * armorMod;
		double armorAbsorbed = Math.min(armorDamage, currentArmor);
		double rawAbsorbed = armorMod > 0 ? armorAbsorbed / armorMod : rawToArmor;
		double rawOverflow = rawToArmor - rawAbsorbed;

		// All HP damage — direct piercing plus armor overflow — is scaled by the
		// HP modifier, so a unit immune to the type (mod 0) takes no HP damage.
		double hpDamage = (rawToHp + rawOverflow) * hpMod;

		currentArmor = (int) Math.round(currentArmor - armorAbsorbed);
		currentHp -= (int) Math.round(hpDamage);
		return (int) Math.round(hpDamage + armorAbsorbed);
	}

	/**
	 * The unit's HP damage modifier for a type, with active status effects taken
	 * into account: an effect's modifier <em>replaces</em> the unit's own for that
	 * type, and when several effects modify the same type the highest one applies.
	 */
	private double effectiveDamageMod(Ability.DamageType type) {
		double base = unit.getMaxRank() >= 1 ? unit.getRank(rank).damageMod(type) : 1.0;
		Double override = null;
		for (ActiveStatusEffect e : statusEffects) {
			Double mod = e.getEffect().getDamageMod(type);
			if (mod != null)
				override = override == null ? mod : Math.max(override, mod);
		}
		return override != null ? override : base;
	}

	/** As {@link #effectiveDamageMod}, but for armor resistances. */
	private double effectiveArmorDamageMod(Ability.DamageType type) {
		double base = unit.getMaxRank() >= 1 ? unit.getRank(rank).armorDamageMod(type) : 1.0;
		Double override = null;
		for (ActiveStatusEffect e : statusEffects) {
			Double mod = e.getEffect().getArmorDamageMod(type);
			if (mod != null)
				override = override == null ? mod : Math.max(override, mod);
		}
		return override != null ? override : base;
	}

	// --- Status effects ----------------------------------------------------

	/**
	 * Afflicts this unit with a status effect. If the unit already has the same
	 * effect (by definition identity), the old one is removed and replaced by the
	 * new one, so re-applying refreshes its duration and starting damage.
	 */
	public void applyStatusEffect(ActiveStatusEffect effect) {
		statusEffects.removeIf(e -> e.getEffect() == effect.getEffect());
		statusEffects.add(effect);
	}

	/** Whether any active effect prevents this unit from attacking. */
	public boolean isActionBlocked() {
		for (ActiveStatusEffect e : statusEffects)
			if (e.blocksAction())
				return true;
		return false;
	}

	/**
	 * The effect this unit should pulse with at the given animation tick: the most
	 * recently applied effect that has a family and whose apply animation has
	 * already begun ({@code displayStartTick <= tick}), or {@code null} if none
	 * apply yet. Gating on the display tick keeps the pulse from showing during the
	 * attack, before the effect's apply icon starts playing.
	 */
	public ActiveStatusEffect getPulseEffect(int tick) {
		for (int i = statusEffects.size() - 1; i >= 0; i--) {
			ActiveStatusEffect e = statusEffects.get(i);
			if (e.getEffect().getFamily() != null && e.getDisplayStartTick() <= tick)
				return e;
		}
		return null;
	}

	/**
	 * Evaluates this unit's status effects for the start of its side's turn: each
	 * effect deals its damage, ages by one turn, and is removed once expired.
	 * Returns one {@link StatusTick} per effect that dealt damage, so the UI can
	 * show a floating number and the effect's icon.
	 */
	public List<StatusTick> tickStatusEffects() {
		List<StatusTick> ticks = new ArrayList<>();
		for (ActiveStatusEffect e : statusEffects) {
			StatusEffect def = e.getEffect();
			double raw = e.nextTickRawDamage();
			int dealt = raw > 0
					? applyDamage(raw, def.getDamageType(), def.getArmorPiercingRate())
					: 0;
			e.onTurnPassed();
			if (dealt > 0)
				ticks.add(new StatusTick(def, dealt));
		}
		statusEffects.removeIf(ActiveStatusEffect::isExpired);
		return ticks;
	}

	/** One effect's start-of-turn result: the effect and the damage it dealt. */
	public record StatusTick(StatusEffect effect, int damageDealt) {
	}

	/**
	 * The unit's idle animation for its side, looping. Loaded on first call;
	 * returns {@code null} if no animation could be loaded (e.g. the game
	 * files are missing the timeline). The result is cached, including a
	 * {@code null} miss, so loading is attempted at most once.
	 */
	public Animation getAnimation() {
		if (!animationLoaded) {
			animationLoaded = true;
			animation = loadAnimation();
		}
		return animation;
	}

	/**
	 * Begins playing a one-shot attack animation (non-looping) from the given
	 * tick. While it is playing, {@link #getActiveAttack} returns it; once it
	 * finishes the unit reverts to its idle animation.
	 */
	public void startAttack(Animation animation, int tick) {
		if (animation == null)
			return;
		animation.setLoop(false);
		this.attackAnimation = animation;
		this.attackStartTick = tick;
	}

	/**
	 * The attack animation if one is still playing at {@code tick}, otherwise
	 * {@code null} (and the finished animation is cleared).
	 */
	public Animation getActiveAttack(int tick) {
		if (attackAnimation != null
				&& tick - attackStartTick >= attackAnimation.getEndFrame())
			attackAnimation = null;
		return attackAnimation;
	}

	/** The tick at which the current attack animation started. */
	public int getAttackStartTick() {
		return attackStartTick;
	}

	private Animation loadAnimation() {
		try {
			Animation anim = (side == Side.PLAYER)
					? unit.getBackAnimation()
					: unit.getFrontAnimation();
			if (anim != null)
				anim.setLoop(true);
			return anim;
		} catch (IOException | RuntimeException e) {
			// Missing or malformed animation data: fall back to a token.
			return null;
		}
	}
}
