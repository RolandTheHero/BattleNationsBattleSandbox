package hero.roland.bnsim.enemybehaviour;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import hero.roland.bnsim.BattleSimulator;
import hero.roland.bnsim.Cell;
import hero.roland.bnsim.EnemyBehavior;
import hero.roland.bnsim.PlacedUnit;
import hero.roland.bnsim.Side;
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
		boolean enforceTargetTypes = sim.getRules().isEnforceTargetTypes();
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
						// Fixed attack: usable if its fixed area covers a player unit
						// the ability can actually hit (by unit type).
						for (BattleSimulator.SideCell sc : sim.weaponAffectedCells(unit, attack)) {
							if (sc.side() != Side.PLAYER)
								continue;
							PlacedUnit target = sim.unitAt(Side.PLAYER, sc.cell());
							if (target != null && (!enforceTargetTypes || attack.getAbility().canTarget(target.getUnit()))) {
								candidates.add(new Move(unit, attack, unit.getCell()));
								break;
							}
						}
					} else {
						for (Cell cell : sim.targetableCells(unit, attack)) {
							PlacedUnit target = sim.unitAt(Side.PLAYER, cell);
							if (target != null && (!enforceTargetTypes || attack.getAbility().canTarget(target.getUnit())))
								candidates.add(new Move(unit, attack, cell));
						}
					}
				}
			}
		}
		if (candidates.isEmpty())
			return null;
		return candidates.get(random.nextInt(candidates.size()));
	}
}
