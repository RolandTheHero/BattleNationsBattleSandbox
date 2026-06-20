package hero.roland.bnsim;

/**
 * A single {@link StatusEffect} currently afflicting a {@link PlacedUnit}. The
 * {@link StatusEffect} is the shared, immutable definition (loaded from config);
 * this is the per-unit, mutable instance that tracks how much longer the effect
 * lasts and how its damage diminishes over the turns it has been active.
 *
 * <p>The effect's starting damage is fixed when it is applied: it is the
 * effect's ability-damage multiplier times the damage the triggering hit dealt
 * to the unit. For example a poison with multiplier 1 applied by a hit that
 * dealt 100 damage starts at 100 raw damage per tick (the unit's resistances are
 * then applied again each time it ticks, via {@link PlacedUnit#applyDamage}).
 */
public class ActiveStatusEffect {

	private final StatusEffect effect;
	/** Raw damage of the first tick, before the unit's resistances. */
	private final double baseDamage;
	/** Animation tick from which this effect's pulse should be shown (the tick the
	 * apply icon begins playing); a UI-timing value, unused by the damage logic. */
	private final int displayStartTick;
	/** Turns remaining; the effect is removed once this reaches zero. */
	private int remaining;
	/** Turns this effect has already ticked, for the diminishing multiplier. */
	private int ticks;

	/**
	 * @param effect the shared effect definition
	 * @param damageDealt the damage the triggering hit dealt to the unit, which
	 *        scales the effect's starting damage
	 * @param displayStartTick the animation tick the apply icon begins playing,
	 *        from which the unit's pulse should be shown
	 */
	public ActiveStatusEffect(StatusEffect effect, double damageDealt, int displayStartTick) {
		this.effect = effect;
		this.baseDamage = effect.getAbilityDamageMultiplier() * Math.max(0, damageDealt);
		this.displayStartTick = displayStartTick;
		this.remaining = Math.max(1, effect.getDuration());
		this.ticks = 0;
	}

	/** The animation tick from which this effect's pulse should be shown. */
	public int getDisplayStartTick() {
		return displayStartTick;
	}

	public StatusEffect getEffect() {
		return effect;
	}

	public int getRemaining() {
		return remaining;
	}

	/** Whether the effect has run out and should be removed from the unit. */
	public boolean isExpired() {
		return remaining <= 0;
	}

	/** Whether this effect stops the unit from using abilities. */
	public boolean blocksAction() {
		return effect.isBlockAction();
	}

	/**
	 * The raw damage for this effect's next tick, before the unit's resistances:
	 * the starting damage scaled by the diminishing multiplier (0.5^(n-1) for the
	 * n-th tick, when diminishing), plus the effect's flat bonus damage.
	 */
	public double nextTickRawDamage() {
		double mult = effect.isDiminishing() ? Math.pow(0.5, ticks) : 1.0;
		return baseDamage * mult + effect.getBonusDamage();
	}

	/** Records that the unit's turn has passed: ages the effect by one turn. */
	public void onTurnPassed() {
		ticks++;
		remaining--;
	}
}
