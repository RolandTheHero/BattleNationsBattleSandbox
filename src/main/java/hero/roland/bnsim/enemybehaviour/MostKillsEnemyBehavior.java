package hero.roland.bnsim.enemybehaviour;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import hero.roland.bnsim.BattleSimulator;
import hero.roland.bnsim.Cell;
import hero.roland.bnsim.GridGeometry;
import hero.roland.bnsim.PlacedUnit;
import hero.roland.bnsim.Side;
import hero.roland.bnsim.model.Ability;
import hero.roland.bnsim.model.Ability.TargetSquare;
import hero.roland.bnsim.model.Unit;

/**
 * A greedy enemy AI that goes for kills: it makes the same checks and damage
 * estimates as {@link HighestDamageEnemyBehavior}, but plays the move that would
 * kill the most player units (picking at random when several are tied). A unit
 * counts as killed when the average HP damage the move deals it, after its
 * resistances and armor, would take its HP to 0.
 * If no move would kill anything, it falls back to the highest-damage move.
 */
public class MostKillsEnemyBehavior extends HighestDamageEnemyBehavior {
	@Override
	public String name() {
		return "Ruthless";
	}

	@Override
	public String description() {
		return "Chooses the move that would kill the most units. Can target empty tiles to maximise kills. If no move would kill, uses " + super.name() + " behaviour.";
	}

	private final Random random = new Random();

	/** A possible move with the number of units it would kill. */
	private record ScoredMove(Move move, int kills) {
	}

	@Override
	public Move decideMove(BattleSimulator sim) {
		boolean enforceTargetTypes = sim.getRules().isEnforceTargetTypes();
		List<ScoredMove> candidates = new ArrayList<>();
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
						candidates.add(new ScoredMove(new Move(unit, attack, unit.getCell()),
								potentialKills(sim, unit, attack, unit.getCell(), enforceTargetTypes)));
						continue;
					}
					for (Cell cell : sim.targetableCells(unit, attack)) {
						if (ability.getTargetType() == null) {
							// Single target: only aim at a player unit the ability can hit.
							PlacedUnit target = sim.unitAt(Side.PLAYER, cell);
							if (target == null || (enforceTargetTypes && !ability.canTarget(target.getUnit())))
								continue;
						}
						candidates.add(new ScoredMove(new Move(unit, attack, cell),
								potentialKills(sim, unit, attack, cell, enforceTargetTypes)));
					}
				}
			}
		}

		// Pick at random among the moves tied for the most kills; with no kills on
		// offer, go for the most damage instead.
		int mostKills = 0;
		for (ScoredMove c : candidates)
			mostKills = Math.max(mostKills, c.kills());
		if (mostKills == 0)
			return super.decideMove(sim);
		List<Move> best = new ArrayList<>();
		for (ScoredMove c : candidates)
			if (c.kills() == mostKills)
				best.add(c.move());
		return best.get(random.nextInt(best.size()));
	}

	/**
	 * How many player units the attack would kill when aimed at {@code aim}: those
	 * whose average HP damage from it (worked out as in
	 * {@link HighestDamageEnemyBehavior}, after resistances and armor) would take
	 * their HP to 0, whatever armor they have left.
	 */
	private static int potentialKills(BattleSimulator sim, PlacedUnit attacker,
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

		int kills = 0;
		for (Map.Entry<PlacedUnit, Double> e : rawByUnit.entrySet()) {
			PlacedUnit target = e.getKey();
			if (target.isDead())
				continue;
			double hpDamage = target.estimateHpDamage(e.getValue(), ability);
			if (hpDamage >= target.getCurrentHp())
				kills++;
		}
		return kills;
	}
}
