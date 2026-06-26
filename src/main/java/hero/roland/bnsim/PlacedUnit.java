package hero.roland.bnsim;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import hero.roland.bnsim.model.Ability;
import hero.roland.bnsim.model.Animation;
import hero.roland.bnsim.model.StatusEffect;
import hero.roland.bnsim.model.Unit;

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

	/** A one-shot death animation that replaces the sprite once the unit's health
	 * bar has finished draining. */
	private Animation deathAnimation;
	private int deathStartTick;
	private boolean playingDeathAnimation;

	/** Status effects currently afflicting this unit (e.g. poison, stun). */
	private final List<ActiveStatusEffect> statusEffects = new ArrayList<>();

	/**
	 * Combat-resource state, live only between {@link #startBattle} and the end of a
	 * battle. When {@code combatRulesEnabled} is false the unit ignores ammo,
	 * cooldowns, reloads and prep time entirely — every attack is always usable.
	 */
	private boolean combatRulesEnabled;
	/** Each weapon's current ammo ({@code -1} when the weapon has infinite ammo). */
	private final Map<Unit.Weapon, Integer> ammo = new HashMap<>();
	/** Turns each weapon still has left to reload (absent/0 = not reloading). */
	private final Map<Unit.Weapon, Integer> reloadRemaining = new HashMap<>();
	/** Turns each ability still has left on cooldown (absent/0 = ready). */
	private final Map<Ability, Integer> cooldownRemaining = new HashMap<>();
	/**
	 * Cooldowns/reloads set this turn, which skip the very next tick so that a value
	 * of N blocks exactly N of the unit's upcoming turns (see {@link #tickCooldowns}).
	 */
	private final Set<Ability> cooldownFresh = new HashSet<>();
	private final Set<Unit.Weapon> reloadFresh = new HashSet<>();

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

	/** This unit's rank critical-hit bonus as a fraction (0-1); rank stores a percent. */
	public double getCriticalBonus() {
		return (unit.getMaxRank() >= 1 ? unit.getRank(rank).critical() : 0) / 100.0;
	}

	/** This unit's accuracy for the current rank (0 when the unit has no ranks);
	 * part of its offense when attacking. */
	public int getAccuracy() {
		return unit.getMaxRank() >= 1 ? unit.getRank(rank).accuracy() : 0;
	}

	/** This unit's defense for the current rank (0 when the unit has no ranks);
	 * drives its chance to graze incoming hits. */
	public int getDefense() {
		return unit.getMaxRank() >= 1 ? unit.getRank(rank).defense() : 0;
	}

	/** This unit's bravery for the current rank (0 when the unit has no ranks); a
	 * distracting hit suppresses the unit when its scaled damage exceeds this. */
	public int getBravery() {
		return unit.getMaxRank() >= 1 ? unit.getRank(rank).bravery() : 0;
	}

	/** The total flat offense reduction from this unit's active status effects
	 * (e.g. suppression); subtracted from its offense when it attacks. */
	public int getOffenseReduction() {
		int total = 0;
		for (ActiveStatusEffect e : statusEffects)
			total += e.getEffect().getOffenseDown();
		return total;
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

		// The floating number must equal the HP and armor actually removed, so derive it
		// from the integer deltas rather than rounding (hpDamage + armorAbsorbed) on its
		// own: a hit that each component rounds away to nothing (e.g. 0.5 armor that leaves
		// the rounded armor unchanged) would otherwise still display as 1.
		int hpBefore = currentHp;
		int armorBefore = currentArmor;
		currentArmor = (int) Math.round(currentArmor - armorAbsorbed);
		currentHp -= (int) Math.round(hpDamage);
		return (hpBefore - currentHp) + (armorBefore - currentArmor);
	}

	/**
	 * Whether this unit is wholly immune to the given damage type: its effective
	 * HP damage modifier (resistance) for the type is exactly 0, so a hit of that
	 * type deals it no HP damage. Active status-effect resistance overrides are
	 * taken into account, exactly as in {@link #applyDamage}.
	 */
	public boolean isImmuneToDamageType(Ability.DamageType type) {
		return effectiveDamageMod(type) == 0;
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
	 * Afflicts this unit with a status effect, unless the unit is immune to the
	 * effect's family (then nothing changes). If the unit already has the same
	 * effect (by definition identity), the old one is removed and replaced by the
	 * new one, so re-applying refreshes its duration and starting damage. Returns
	 * {@code true} if the effect was applied, {@code false} if it was blocked by
	 * immunity.
	 */
	public boolean applyStatusEffect(ActiveStatusEffect effect) {
		if (unit.isImmuneTo(effect.getEffect().getFamily()))
			return false;
		statusEffects.removeIf(e -> e.getEffect() == effect.getEffect());
		statusEffects.add(effect);
		return true;
	}

	/** A read-only view of the status effects currently afflicting this unit, in
	 * the order they were applied (oldest first). */
	public List<ActiveStatusEffect> getActiveStatusEffects() {
		return Collections.unmodifiableList(statusEffects);
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

	// --- Cooldowns, ammo and reloads ---------------------------------------

	/**
	 * Initialises this unit's combat resources for the start of a battle: every
	 * weapon is filled to its ammo capacity and cleared of any reload, and every
	 * ability starts on a cooldown equal to its prep (charge) time. When
	 * {@code rulesEnabled} is false the unit ignores cooldowns, ammo and reloads
	 * entirely — every attack stays usable for the whole battle.
	 */
	public void startBattle(boolean rulesEnabled) {
		combatRulesEnabled = rulesEnabled;
		ammo.clear();
		reloadRemaining.clear();
		cooldownRemaining.clear();
		cooldownFresh.clear();
		reloadFresh.clear();
		for (Unit.Weapon weapon : unit.getWeapons()) {
			if ("none".equals(weapon.getTag()))
				continue;
			ammo.put(weapon, weapon.getAmmo());
			if (!rulesEnabled)
				continue;
			for (Unit.Attack attack : weapon.getAttacks()) {
				Ability ability = attack.getAbility();
				if (ability == Ability.NO_ABILITY || ability.getPrepTime() <= 0)
					continue;
				cooldownRemaining.put(ability, ability.getPrepTime());
			}
		}
	}

	/** Whether this unit is tracking cooldowns, ammo and reloads this battle. */
	public boolean isCombatRulesEnabled() {
		return combatRulesEnabled;
	}

	/**
	 * Whether the given attack can be used right now: its weapon is not reloading,
	 * the ability is off cooldown, and the weapon has enough ammo for the ability's
	 * cost (or is infinite). Always {@code true} when combat rules are disabled.
	 */
	public boolean isAttackReady(Unit.Attack attack) {
		if (!combatRulesEnabled || attack == null)
			return true;
		Ability ability = attack.getAbility();
		if (ability == Ability.NO_ABILITY)
			return true;
		Unit.Weapon weapon = attack.getWeapon();
		if (reloadRemaining.getOrDefault(weapon, 0) > 0)
			return false;
		if (cooldownRemaining.getOrDefault(ability, 0) > 0)
			return false;
		int required = ability.getAmmoRequired();
		return weapon.getAmmo() < 0 || required <= 0
				|| ammo.getOrDefault(weapon, weapon.getAmmo()) >= required;
	}

	/**
	 * Turns until the given attack can next be used (0 when it is ready now): the
	 * larger of its weapon's reload and its ability's cooldown. Used for the UI
	 * overlay. Always 0 when combat rules are disabled.
	 */
	public int getAttackCooldown(Unit.Attack attack) {
		if (!combatRulesEnabled || attack == null)
			return 0;
		Ability ability = attack.getAbility();
		if (ability == Ability.NO_ABILITY)
			return 0;
		return Math.max(reloadRemaining.getOrDefault(attack.getWeapon(), 0),
				cooldownRemaining.getOrDefault(ability, 0));
	}

	/** The weapon's current ammo, or {@code -1} when it has infinite ammo. */
	public int getWeaponAmmo(Unit.Weapon weapon) {
		if (weapon == null || weapon.getAmmo() < 0)
			return -1;
		return ammo.getOrDefault(weapon, weapon.getAmmo());
	}

	/**
	 * Records that this unit has used the given attack, spending the weapon's ammo
	 * and starting the relevant timers. The ability's ammo cost is subtracted from
	 * the weapon's pool; emptying the weapon puts it on reload (its ammo refilling
	 * once the reload finishes). Either way the used ability goes on its own cooldown
	 * and the weapon's other abilities go on its global cooldown; where several waits
	 * apply to one attack (reload, cooldown, global cooldown) the longest one stands.
	 * A no-op when combat rules are disabled.
	 */
	public void useAttack(Unit.Attack attack) {
		if (!combatRulesEnabled || attack == null)
			return;
		Ability ability = attack.getAbility();
		if (ability == Ability.NO_ABILITY)
			return;
		Unit.Weapon weapon = attack.getWeapon();
		if (weapon.getAmmo() >= 0) { // finite ammo: spend it, reload when empty
			int left = ammo.getOrDefault(weapon, weapon.getAmmo())
					- Math.max(0, ability.getAmmoRequired());
			if (left <= 0) {
				ammo.put(weapon, 0);
				if (weapon.getReloadTime() > 0) {
					// Reload the whole weapon, but only if this reload is longer than
					// one already running — a shorter one leaves the timer untouched.
					if (weapon.getReloadTime() > reloadRemaining.getOrDefault(weapon, 0)) {
						reloadRemaining.put(weapon, weapon.getReloadTime());
						reloadFresh.add(weapon);
					}
				} else {
					ammo.put(weapon, weapon.getAmmo()); // no reload time: refill at once
				}
			} else {
				ammo.put(weapon, left);
			}
		}
		// The used ability goes on its own cooldown and the weapon's other abilities
		// on its global cooldown; these stack with any reload via the longest wait.
		setCooldown(ability, ability.getCooldown());
		int global = ability.getGlobalCooldown();
		if (global > 0)
			for (Unit.Attack other : weapon.getAttacks()) {
				Ability otherAbility = other.getAbility();
				if (otherAbility != Ability.NO_ABILITY && otherAbility != ability)
					setCooldown(otherAbility, global);
			}
	}

	/** Puts an ability on cooldown for {@code turns} of the unit's turns, flagged to
	 * skip the next tick so it blocks for exactly that many. A shorter or equal value
	 * than the cooldown already running is ignored, leaving the longer one in place. */
	private void setCooldown(Ability ability, int turns) {
		if (turns <= cooldownRemaining.getOrDefault(ability, 0))
			return;
		cooldownRemaining.put(ability, turns);
		cooldownFresh.add(ability);
	}

	/**
	 * Advances this unit's cooldowns and reloads by one turn — called as the unit's
	 * own turn ends. Each counts down by one, and a weapon whose reload finishes is
	 * refilled to full ammo. Entries set during this same turn skip this first tick
	 * (so a value of N blocks exactly N of the unit's following turns and the count
	 * shown to an onlooker drops as soon as a blocked turn passes). Does nothing while
	 * a movement-blocking effect (e.g. a freeze) is active — freezing the timers — or
	 * when combat rules are disabled.
	 */
	public void tickCooldowns() {
		if (!combatRulesEnabled || isMovementBlocked())
			return;
		for (Iterator<Map.Entry<Ability, Integer>> it = cooldownRemaining.entrySet().iterator();
				it.hasNext();) {
			Map.Entry<Ability, Integer> entry = it.next();
			if (cooldownFresh.remove(entry.getKey()))
				continue; // set this turn: skip its first tick
			int next = entry.getValue() - 1;
			if (next <= 0)
				it.remove();
			else
				entry.setValue(next);
		}
		for (Iterator<Map.Entry<Unit.Weapon, Integer>> it = reloadRemaining.entrySet().iterator();
				it.hasNext();) {
			Map.Entry<Unit.Weapon, Integer> entry = it.next();
			Unit.Weapon weapon = entry.getKey();
			if (reloadFresh.remove(weapon))
				continue;
			int next = entry.getValue() - 1;
			if (next <= 0) {
				it.remove();
				ammo.put(weapon, weapon.getAmmo()); // reload finished: refill to full
			} else {
				entry.setValue(next);
			}
		}
	}

	/** Whether any active effect freezes this unit's cooldown and reload timers. */
	private boolean isMovementBlocked() {
		for (ActiveStatusEffect e : statusEffects)
			if (e.getEffect().isBlockMovement())
				return true;
		return false;
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

	/**
	 * Starts this unit's death animation: a non-looping animation (which may be
	 * {@code null}) played from {@code tick} in place of its sprite. Called once
	 * the unit's health bar has finished draining.
	 */
	public void startDeathAnimation(Animation animation, int tick) {
		playingDeathAnimation = true;
		if (animation != null)
			animation.setLoop(false);
		this.deathAnimation = animation;
		this.deathStartTick = tick;
	}

	/** Whether this unit's death animation is currently playing. */
	public boolean isPlayingDeathAnimation() {
		return playingDeathAnimation;
	}

	/** This unit's death animation, or {@code null} if none could be loaded. */
	public Animation getDeathAnimation() {
		return deathAnimation;
	}

	/** The tick at which the death animation started. */
	public int getDeathStartTick() {
		return deathStartTick;
	}

	/**
	 * Whether the death animation has finished playing — {@code true} at once
	 * when the unit has no death animation, so it is then removed without delay.
	 */
	public boolean deathAnimationFinished(int tick) {
		return deathAnimation == null
				|| tick - deathStartTick >= deathAnimation.getEndFrame();
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
