package hero.roland.bnsim;

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

	/** An enemy action: the attacking unit, the chosen attack, and the target cell. */
	record Move(PlacedUnit attacker, Unit.Attack attack, Cell target) {
	}
}
