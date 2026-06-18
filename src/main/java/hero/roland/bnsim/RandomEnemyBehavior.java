package hero.roland.bnsim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A simple enemy AI: it collects every (enemy unit, ability, reachable player
 * unit) combination — respecting each attack's range and the player units'
 * blocking — and picks one at random.
 */
public class RandomEnemyBehavior implements EnemyBehavior {

	private final Random random = new Random();

	@Override
	public Move decideMove(BattleSimulator sim) {
		List<Move> candidates = new ArrayList<>();
		for (PlacedUnit unit : sim.placedUnits()) {
			if (unit.getSide() != Side.ENEMY)
				continue;
			for (Unit.Weapon weapon : unit.getUnit().getWeapons()) {
				if ("none".equals(weapon.getTag()))
					continue;
				for (Unit.Attack attack : weapon.getAttacks()) {
					if (attack.getAbility() == Ability.NO_ABILITY)
						continue;
					for (Cell cell : sim.targetableCells(unit, attack))
						if (sim.unitAt(Side.PLAYER, cell) != null)
							candidates.add(new Move(unit, attack, cell));
				}
			}
		}
		if (candidates.isEmpty())
			return null;
		return candidates.get(random.nextInt(candidates.size()));
	}
}
