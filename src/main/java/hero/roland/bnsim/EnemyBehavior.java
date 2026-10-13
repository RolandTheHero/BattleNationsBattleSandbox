package hero.roland.bnsim;

import hero.roland.bnsim.model.Unit;

/**
 * Strategy for deciding what the enemy side does on its turn. Different
 * implementations can give the enemy different behaviour (e.g. random,
 * focus-fire, defensive). The battle UI asks the behaviour for a single
 * {@link Move} when the enemy turn begins.
 */
public interface EnemyBehavior {

	/**
	 * Chooses the enemy's action given the current battle state, or returns
	 * {@code null} if the enemy has no legal move (the turn is then skipped).
	 */
	Move decideMove(BattleSimulator sim);

	/**
	 * Whether the player commands the enemy on its turn instead: the battle UI
	 * then hands control over and never calls {@link #decideMove}.
	 */
	default boolean isPlayerControlled() { return false; }

	String name();
	String description();

	/** An enemy action: the attacking unit, the chosen attack, and the target cell. */
	record Move(PlacedUnit attacker, Unit.Attack attack, Cell target) {}

}
