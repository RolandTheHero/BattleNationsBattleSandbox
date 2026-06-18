package hero.roland.bnsim;

import java.io.IOException;

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

	private Animation animation;
	private boolean animationLoaded;

	/** A one-shot attack animation that temporarily replaces the idle. */
	private Animation attackAnimation;
	private int attackStartTick;

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

	/** Applies damage: armor absorbs first, the remainder hits health. */
	public void applyDamage(int damage) {
		if (damage <= 0)
			return;
		int absorbed = Math.min(currentArmor, damage);
		currentArmor -= absorbed;
		currentHp -= (damage - absorbed);
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
