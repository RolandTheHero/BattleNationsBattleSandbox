package hero.roland.bnsim.enemybehaviour;

import java.util.HashMap;
import java.util.Map;

import hero.roland.bnsim.BattleSimulator;
import hero.roland.bnsim.Cell;
import hero.roland.bnsim.EnemyBehavior;
import hero.roland.bnsim.GridGeometry;
import hero.roland.bnsim.PlacedUnit;
import hero.roland.bnsim.Side;
import hero.roland.bnsim.model.Ability;
import hero.roland.bnsim.model.Ability.TargetSquare;
import hero.roland.bnsim.model.Unit;

/**
 * A greedy enemy AI: for every ready attack of every enemy unit and every place
 * it could be aimed, it estimates the average damage the attack would deal to
 * the player's units, and plays the one that deals the most.
 *
 * <p>The estimate uses each attack's average damage per hit (the mean of its min
 * and max for the unit's rank), spread over the ability's target and damage
 * areas exactly as the real attack is (see {@link BattleSimulator#resolveHits}),
 * and the struck units' resistances and armor (see
 * {@link PlacedUnit#estimateDamage}). How the attack is aimed depends on its
 * target type:
 * <ul>
 * <li>{@code null} (single target): each player unit in range.</li>
 * <li>{@link Ability.TargetType#TARGET TARGET}: each tile in range, including
 * empty ones, since the splash may still hit units around it.</li>
 * <li>{@link Ability.TargetType#WEAPON WEAPON}: its fixed area in front of the
 * unit, counting every tile in it.</li>
 * </ul>
 * Graze and critical chances are not taken into account.
 */
public class HighestDamageEnemyBehavior implements EnemyBehavior {

	@Override
	public Move decideMove(BattleSimulator sim) {
		boolean enforceTargetTypes = sim.getRules().isEnforceTargetTypes();
		Move best = null;
		double bestDamage = 0;
		for (PlacedUnit unit : sim.placedUnits()) {
			if (unit.getSide() != Side.ENEMY)
				continue;
			if (unit.isActionBlocked())
				continue; // stunned/frozen: this unit cannot act this turn
			for (Unit.Weapon weapon : unit.getUnit().getWeapons()) {
				if ("none".equals(weapon.getTag()))
					continue;
				for (Unit.Attack attack : weapon.getAttacks()) {
					Ability ability = attack.getAbility();
					if (ability == Ability.NO_ABILITY)
						continue;
					if (!unit.isAttackReady(attack))
						continue; // on cooldown, reloading or out of ammo
					if (ability.getTargetType() == Ability.TargetType.WEAPON) {
						// Fixed attack: the aim is ignored, so there is only one option.
						double damage = averageDamage(sim, unit, attack, unit.getCell(), enforceTargetTypes);
						if (damage > bestDamage) {
							bestDamage = damage;
							best = new Move(unit, attack, unit.getCell());
						}
						continue;
					}
					for (Cell cell : sim.targetableCells(unit, attack)) {
						if (ability.getTargetType() == null) {
							// Single target: only aim at a player unit the ability can hit.
							PlacedUnit target = sim.unitAt(Side.PLAYER, cell);
							if (target == null || (enforceTargetTypes && !ability.canTarget(target.getUnit())))
								continue;
						}
						double damage = averageDamage(sim, unit, attack, cell, enforceTargetTypes);
						if (damage > bestDamage) {
							bestDamage = damage;
							best = new Move(unit, attack, cell);
						}
					}
				}
			}
		}
		return best;
	}

	/**
	 * The average total damage (HP plus armor) the attack would deal to the
	 * player's units when aimed at {@code aim}, over all of its shots and attacks
	 * per use. The raw damage landing on each unit is added up first and then put
	 * through its resistances and armor in one go, so armor stripped by early hits
	 * lets later hits through to HP.
	 */
	private static double averageDamage(BattleSimulator sim, PlacedUnit attacker,
			Unit.Attack attack, Cell aim, boolean enforceTargetTypes) {
		Ability ability = attack.getAbility();
		TargetSquare[] targetArea = ability.getTargetArea();
		TargetSquare[] damageArea = ability.getDamageArea();
		if (targetArea == null)
			targetArea = new TargetSquare[] { TargetSquare.SINGLE_TARGET };
		if (damageArea == null)
			damageArea = new TargetSquare[] { TargetSquare.SINGLE_TARGET };
		boolean fixed = ability.getTargetType() == Ability.TargetType.WEAPON;
		boolean random = !fixed && ability.getRandomTarget();
		int shots = Math.max(1, ability.getShotsPerAttack());
		int attacks = Math.max(1, ability.getAttacksPerUse());
		double perHit = attack.getAverageDamage(attacker.getRank());
		Side targetSide = BattleSimulator.opponentOf(attacker.getSide());
		// Area offsets are mirrored for the enemy, as in BattleSimulator.
		int xSign = attacker.getSide() == Side.PLAYER ? -1 : 1;

		Map<PlacedUnit, Double> rawByUnit = new HashMap<>();
		for (TargetSquare square : targetArea) {
			Cell origin;
			if (fixed) {
				BattleSimulator.SideCell sc = sim.weaponCell(attacker, ability, square.getX(), square.getY());
				if (sc == null)
					continue; // on the attacker's own side, off the grid or blocked
				origin = sc.cell();
			} else {
				origin = new Cell(aim.col() + xSign * square.getX(), aim.row() - square.getY());
			}
			// A random-target ability fires each shot at one square, picked by its
			// chance, at full damage; otherwise every shot hits every square, scaled by
			// the square's damage value.
			double weight = random ? square.getChance() : square.getValue();
			double raw = perHit * shots * attacks * weight;
			for (TargetSquare splash : damageArea) {
				int col = origin.col() + xSign * splash.getX();
				int row = origin.row() - splash.getY();
				if (!GridGeometry.isValid(col, row))
					continue;
				PlacedUnit target = sim.unitAt(targetSide, new Cell(col, row));
				if (target == null)
					continue;
				if (enforceTargetTypes && !ability.canTarget(target.getUnit()))
					continue; // an untargetable unit takes nothing from the hit
				rawByUnit.merge(target, raw * splash.getValue(), Double::sum);
			}
		}

		double armorPiercing = ability.getArmorPiercingRate();
		double total = 0;
		for (Map.Entry<PlacedUnit, Double> e : rawByUnit.entrySet())
			total += e.getKey().estimateDamage(e.getValue(), ability.getDamageType(), armorPiercing);
		return total;
	}
}
