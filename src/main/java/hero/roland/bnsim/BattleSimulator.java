package hero.roland.bnsim;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import hero.roland.bnsim.Ability.TargetSquare;

/**
 * Owns the placement state of the battlefield: which {@link PlacedUnit}
 * occupies each cell on each {@link Side}. It handles placing new units,
 * moving existing ones, and snapping pixel coordinates to grid cells (via
 * {@link GridGeometry}). It holds no Swing/UI state.
 *
 * <p>A unit may only move within its own side. Dropping a unit onto an
 * occupied cell swaps the two units.
 */
public class BattleSimulator {

	private final GridGeometry geometry = new GridGeometry();

	/** Occupancy grid per side: [col][row], {@code null} when empty. */
	private final Map<Side, PlacedUnit[][]> grids = new EnumMap<>(Side.class);

	public BattleSimulator() {
		for (Side side : Side.values())
			grids.put(side, new PlacedUnit[GridGeometry.COLS][GridGeometry.ROWS]);
	}

	public GridGeometry getGeometry() {
		return geometry;
	}

	/**
	 * Places a unit in the first free cell on the given side (row-major
	 * order). Returns the new {@link PlacedUnit}, or {@code null} if that
	 * side is full.
	 */
	public PlacedUnit addUnit(Unit unit, Side side) {
		PlacedUnit[][] grid = grids.get(side);
		for (int row = 0; row < GridGeometry.ROWS; row++) {
			for (int col = 0; col < GridGeometry.COLS; col++) {
				if (GridGeometry.isValid(col, row) && grid[col][row] == null) {
					PlacedUnit placed = new PlacedUnit(unit, side, new Cell(col, row));
					grid[col][row] = placed;
					return placed;
				}
			}
		}
		return null;
	}

	/** The unit occupying a cell on a side, or {@code null}. */
	public PlacedUnit unitAt(Side side, Cell cell) {
		if (cell == null)
			return null;
		return grids.get(side)[cell.col()][cell.row()];
	}

	/**
	 * The unit drawn under the given pixel, searching both sides, or
	 * {@code null}. Used to begin a drag.
	 */
	public PlacedUnit pick(Point2D pixel) {
		for (Side side : Side.values()) {
			PlacedUnit hit = unitAt(side, geometry.cellAt(side, pixel));
			if (hit != null)
				return hit;
		}
		return null;
	}

	/**
	 * Snaps the unit to the cell nearest the given pixel, restricted to the
	 * unit's own side. If the target cell holds another unit, the two swap. If
	 * the drop is outside the unit's grid, the unit is removed from the board.
	 * Returns {@code true} if the unit is still on the board afterwards.
	 */
	public boolean moveTo(PlacedUnit unit, Point2D pixel) {
		Side side = unit.getSide();
		Cell target = geometry.cellAt(side, pixel);
		if (target == null) {
			remove(unit); // dropped off its own grid
			return false;
		}

		PlacedUnit[][] grid = grids.get(side);
		Cell from = unit.getCell();
		if (target.equals(from))
			return true;

		PlacedUnit occupant = grid[target.col()][target.row()];
		grid[target.col()][target.row()] = unit;
		grid[from.col()][from.row()] = occupant;
		unit.setCell(target);
		if (occupant != null)
			occupant.setCell(from);
		return true;
	}

	/** Removes a unit from the board, if it is currently placed. */
	public void remove(PlacedUnit unit) {
		Cell cell = unit.getCell();
		PlacedUnit[][] grid = grids.get(unit.getSide());
		if (cell != null && grid[cell.col()][cell.row()] == unit)
			grid[cell.col()][cell.row()] = null;
	}

	// --- Combat ------------------------------------------------------------

	private final Random random = new Random();

	/**
	 * The set of enemy cells the attacker can hit with the given attack.
	 *
	 * <p>Range is measured along the depth axis: distance = (tiles in front of
	 * the attacker) + (tiles the target is behind the enemy front line) + 1, so
	 * an attack with min/max range 1..5 can reach any enemy tile from anywhere.
	 * A cell is excluded if an enemy unit in front of it (same column, nearer
	 * the front) blocks the attack's line of fire.
	 */
	public Set<Cell> targetableCells(PlacedUnit attacker, Unit.Attack attack) {
		Set<Cell> cells = new HashSet<>();
		if (attacker == null || attack == null)
			return cells;
		Ability ability = attack.getAbility();
		int min = attack.getMinRange();
		int max = attack.getMaxRange();
		int lineOfFire = ability.getLineOfFire();
		int attackerRow = attacker.getCell().row(); // 0 = own front line
		Side targetSide = opponentOf(attacker.getSide());
		// BACK attacks reach the defender from behind, so depth and blocking are
		// measured from the defender's back row instead of its front.
		boolean fromBack = ability.getAttackDirection() == Ability.AttackDirection.BACK;

		for (int row = 0; row < GridGeometry.ROWS; row++) {
			for (int col = 0; col < GridGeometry.COLS; col++) {
				if (!GridGeometry.isValid(col, row))
					continue;
				int targetDepth = fromBack ? (GridGeometry.ROWS - 1 - row) : row;
				int distance = attackerRow + targetDepth + 1;
				if (distance < min || distance > max)
					continue;
				if (isBlocked(targetSide, col, row, lineOfFire, fromBack))
					continue;
				cells.add(new Cell(col, row));
			}
		}
		return cells;
	}

	/** The side facing the given one. */
	public static Side opponentOf(Side side) {
		return side == Side.PLAYER ? Side.ENEMY : Side.PLAYER;
	}

	/**
	 * Whether a unit between the attack's entry side and the target blocks the
	 * shot: units in front of the target for a FRONT attack, behind it for a
	 * BACK attack.
	 */
	private boolean isBlocked(Side targetSide, int col, int targetRow,
			int lineOfFire, boolean fromBack) {
		if (lineOfFire == Ability.LOF_INDIRECT)
			return false;
		int start = fromBack ? targetRow + 1 : 0;
		int end = fromBack ? GridGeometry.ROWS : targetRow;
		for (int row = start; row < end; row++) {
			PlacedUnit blocker = unitAt(targetSide, new Cell(col, row));
			if (blocker != null
					&& blocksLineOfFire(blocker.getUnit().getBlocking(), lineOfFire))
				return true;
		}
		return false;
	}

	private static boolean blocksLineOfFire(int blocking, int lineOfFire) {
		if (lineOfFire == Ability.LOF_INDIRECT)
			return false;                         // indirect: never blocked
		if (blocking >= Unit.BLOCKING)
			return true;                          // full blockers stop everything but indirect
		switch (lineOfFire) {
		case Ability.LOF_CONTACT:
			return true;                          // any unit blocks
		case Ability.LOF_DIRECT:
			return blocking <= Unit.PARTIAL;      // partial blocking or less
		default:
			return false;                         // precise: only full blocking (handled above)
		}
	}

	/**
	 * Resolves which opposing cells a fired attack hits when aimed at
	 * {@code aim}, and how much damage each lands. Each shot rolls a damage
	 * value in [min, max) for the attacker's rank; the damage area then spreads
	 * each shot to nearby cells, scaled by each square's value multiplier.
	 */
	public List<Hit> resolveHits(PlacedUnit attacker, Unit.Attack attack, Cell aim) {
		List<Hit> result = new ArrayList<>();
		if (attacker == null || attack == null || aim == null)
			return result;
		Ability ability = attack.getAbility();
		Side targetSide = opponentOf(attacker.getSide());
		TargetSquare[] targetArea = ability.getTargetArea();
		TargetSquare[] damageArea = ability.getDamageArea();
		if (targetArea == null)
			targetArea = new TargetSquare[] { TargetSquare.SINGLE_TARGET };
		if (damageArea == null)
			damageArea = new TargetSquare[] { TargetSquare.SINGLE_TARGET };
		int shots = Math.max(1, ability.getNumAttacks());
		int aoeDelay = ability.getAoeDelay();
		int minDamage = attack.getMinDamage(attacker.getRank());
		int maxDamage = attack.getMaxDamage(attacker.getRank());

		// Area offsets are authored from the player's perspective: +x is one
		// tile to the player's right. The enemy faces the opposite way, so its
		// x is mirrored; y is the same for both sides.
		int xSign = attacker.getSide() == Side.PLAYER ? 1 : -1;

		// Each successive tile is staggered by aoeDelay. The ripple sequence is
		// taken from the squares' "order" when it varies, otherwise from their
		// position in the array, so a multi-tile area always ripples.
		int[] targetSteps = sequenceSteps(targetArea);
		int[] damageSteps = sequenceSteps(damageArea);

		if (ability.getRandomTarget()) {
			for (int shot = 0; shot < shots; shot++) {
				TargetSquare hit = pickWeighted(targetArea);
				if (hit != null)
					addImpact(result, targetSide, aim, hit, shot, damageArea,
							damageSteps, aoeDelay, xSign, rollDamage(minDamage, maxDamage));
			}
		} else {
			for (int t = 0; t < targetArea.length; t++)
				for (int shot = 0; shot < shots; shot++)
					addImpact(result, targetSide, aim, targetArea[t], targetSteps[t],
							damageArea, damageSteps, aoeDelay, xSign,
							rollDamage(minDamage, maxDamage));
		}
		return result;
	}

	/** Adds one shot's damage-area tiles, each staggered by the aoe delay. */
	private void addImpact(List<Hit> result, Side targetSide, Cell aim,
			TargetSquare target, int targetStep, TargetSquare[] damageArea,
			int[] damageSteps, int aoeDelay, int xSign, int baseDamage) {
		int baseCol = aim.col() + xSign * target.getX();
		int baseRow = aim.row() - target.getY();
		for (int d = 0; d < damageArea.length; d++) {
			int col = baseCol + xSign * damageArea[d].getX();
			int row = baseRow - damageArea[d].getY();
			if (!GridGeometry.isValid(col, row))
				continue;
			int delay = Math.max(0, aoeDelay * (targetStep + damageSteps[d]));
			int damage = (int) Math.round(baseDamage * damageArea[d].getValue());
			result.add(new Hit(targetSide, new Cell(col, row), delay, damage));
		}
	}

	/** Rolls a damage value in [min, max) (inclusive min, exclusive max). */
	private int rollDamage(int min, int max) {
		return max > min ? min + random.nextInt(max - min) : min;
	}

	/**
	 * Per-square ripple step: the (normalised) "order" when the orders differ,
	 * otherwise the array index, so a uniform-order area still ripples.
	 */
	private static int[] sequenceSteps(TargetSquare[] squares) {
		int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
		for (TargetSquare square : squares) {
			min = Math.min(min, square.getOrder());
			max = Math.max(max, square.getOrder());
		}
		int[] steps = new int[squares.length];
		if (max > min)
			for (int i = 0; i < squares.length; i++)
				steps[i] = squares[i].getOrder() - min;
		else
			for (int i = 0; i < squares.length; i++)
				steps[i] = i;
		return steps;
	}

	private TargetSquare pickWeighted(TargetSquare[] squares) {
		double roll = random.nextDouble();
		double cumulative = 0;
		for (TargetSquare square : squares) {
			cumulative += square.getChance();
			if (roll <= cumulative)
				return square;
		}
		return squares.length > 0 ? squares[squares.length - 1] : null;
	}

	/**
	 * A struck cell: which side it is on, the delay (in animation frames) before
	 * it is hit, and the damage it takes. Tiles in an area-of-effect ripple
	 * outwards using the ability's aoe delay.
	 */
	public record Hit(Side side, Cell cell, int delayFrames, int damage) {
	}

	/** All placed units across both sides, in no particular order. */
	public List<PlacedUnit> placedUnits() {
		List<PlacedUnit> all = new ArrayList<>();
		for (PlacedUnit[][] grid : grids.values())
			for (PlacedUnit[] column : grid)
				for (PlacedUnit unit : column)
					if (unit != null)
						all.add(unit);
		return all;
	}
}
