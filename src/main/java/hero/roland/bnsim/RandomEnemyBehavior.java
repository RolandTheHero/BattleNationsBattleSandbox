package hero.roland.bnsim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import hero.roland.bnsim.model.Ability;
import hero.roland.bnsim.model.Unit;

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
			if (unit.isActionBlocked())
				continue; // stunned/frozen: this unit cannot act this turn
			for (Unit.Weapon weapon : unit.getUnit().getWeapons()) {
				if ("none".equals(weapon.getTag()))
					continue;
				for (Unit.Attack attack : weapon.getAttacks()) {
					if (attack.getAbility() == Ability.NO_ABILITY)
						continue;
					if (!unit.isAttackReady(attack))
						continue; // on cooldown, reloading or out of ammo
					if (attack.getAbility().getTargetType() == Ability.TargetType.WEAPON) {
						// Fixed attack: usable if its fixed area covers a player unit.
						for (BattleSimulator.SideCell sc : sim.weaponAffectedCells(unit, attack))
							if (sc.side() == Side.PLAYER
									&& sim.unitAt(Side.PLAYER, sc.cell()) != null) {
								candidates.add(new Move(unit, attack, unit.getCell()));
								break;
							}
					} else {
						for (Cell cell : sim.targetableCells(unit, attack))
							if (sim.unitAt(Side.PLAYER, cell) != null)
								candidates.add(new Move(unit, attack, cell));
					}
				}
			}
		}
		if (candidates.isEmpty())
			return null;
		return candidates.get(random.nextInt(candidates.size()));
	}
}
