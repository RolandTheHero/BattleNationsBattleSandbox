package hero.roland.bnsim;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import hero.roland.bnsim.gamefiles.GameFiles;
import hero.roland.bnsim.model.Ability;
import hero.roland.bnsim.model.Ability.TargetSquare;
import hero.roland.bnsim.model.Unit;

/**
 * Owns the placement state of the battlefield: which {@link PlacedUnit}
 * occupies each cell on each {@link Side}. It handles placing new units,
 * moving existing ones, and snapping pixel coordinates to grid cells (via
 * {@link GridGeometry}). It holds no Swing/UI state.
 *
 * <p>A unit may move anywhere on either side. Dropping a unit onto an
 * occupied cell swaps the two units, exchanging sides if they differ.
 */
public class BattleSimulator {

	private final GridGeometry geometry = new GridGeometry();

	/** The optional house-rule toggles, shared with every {@link PlacedUnit} and
	 * read wherever a rule is enforced. */
	private final BattleRules rules = new BattleRules();

	/** Occupancy grid per side: [col][row], {@code null} when empty. */
	private final Map<Side, PlacedUnit[][]> grids = new EnumMap<>(Side.class);

	/**
	 * How many rows each side has advanced (see {@link #advanceToFront}). Each
	 * advance permanently drops that side's front row, so the side shrinks from the
	 * front and its backmost still-existing row creeps forward. Used by combat to
	 * tell a unit's original depth from its current cell.
	 */
	private final Map<Side, Integer> rowsAdvanced = new EnumMap<>(Side.class);

	public BattleSimulator() {
		for (Side side : Side.values()) {
			grids.put(side, new PlacedUnit[GridGeometry.COLS][GridGeometry.ROWS]);
			rowsAdvanced.put(side, 0);
		}
	}

	/**
	 * Rebuilds each side's occupancy grid to the current {@link GridGeometry}
	 * dimensions, after they have been changed via
	 * {@link GridGeometry#setDimensions}. Units that still sit on a cell that
	 * remains valid in the new shape are kept on that cell; any whose cell no
	 * longer exists are dropped. Per-side advancement is reset, as the grid shape
	 * has changed. Call this only outside battle (during setup).
	 */
	public void resizeGrids() {
		for (Side side : Side.values()) {
			PlacedUnit[][] oldGrid = grids.get(side);
			PlacedUnit[][] newGrid = new PlacedUnit[GridGeometry.COLS][GridGeometry.ROWS];
			if (oldGrid != null)
				for (int col = 0; col < oldGrid.length; col++)
					for (int row = 0; row < oldGrid[col].length; row++) {
						PlacedUnit unit = oldGrid[col][row];
						// Keep a unit only where its cell still exists in the new shape;
						// isValid bounds col/row to the new grid, so the index is safe.
						if (unit != null && GridGeometry.isValid(col, row))
							newGrid[col][row] = unit;
					}
			grids.put(side, newGrid);
			rowsAdvanced.put(side, 0);
		}
	}

	public GridGeometry getGeometry() {
		return geometry;
	}

	/** The shared house-rule toggles for this simulation (target types, immunities
	 * and combat resources). The UnitMenu flips these via the BattleField. */
	public BattleRules getRules() {
		return rules;
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
					PlacedUnit placed = new PlacedUnit(unit, side, new Cell(col, row), rules);
					grid[col][row] = placed;
					return placed;
				}
			}
		}
		return null;
	}

	/**
	 * Places a specific unit at a specific cell on a side (used for death-spawns,
	 * which replace a fallen unit on its own tile). Returns the new
	 * {@link PlacedUnit}, or {@code null} if the cell is invalid or occupied.
	 */
	public PlacedUnit spawnAt(Unit unit, Side side, Cell cell) {
		if (cell == null)
			return null;
		int col = cell.col(), row = cell.row();
		PlacedUnit[][] grid = grids.get(side);
		if (!GridGeometry.isValid(col, row) || grid[col][row] != null)
			return null;
		PlacedUnit placed = new PlacedUnit(unit, side, new Cell(col, row), rules);
		grid[col][row] = placed;
		return placed;
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
	 * The cell (and its side) a unit dragged to the given pixel would land on: a
	 * cell on its own side if the pixel is over that grid, otherwise one on the
	 * opposing side, or {@code null} when the pixel is off both grids.
	 */
	public SideCell dropTarget(PlacedUnit unit, Point2D pixel) {
		Side own = unit.getSide();
		Cell cell = geometry.cellAt(own, pixel);
		if (cell != null)
			return new SideCell(own, cell);
		Side other = opponentOf(own);
		cell = geometry.cellAt(other, pixel);
		return cell != null ? new SideCell(other, cell) : null;
	}

	/**
	 * Snaps the unit to the cell nearest the given pixel, on either side (see
	 * {@link #dropTarget}); a unit dropped on the other side changes sides. If the
	 * target cell holds another unit, the two swap — across sides too. If the drop
	 * is outside both grids, the unit is removed from the board. Returns
	 * {@code true} if the unit is still on the board afterwards.
	 */
	public boolean moveTo(PlacedUnit unit, Point2D pixel) {
		SideCell target = dropTarget(unit, pixel);
		if (target == null) {
			remove(unit); // dropped off both grids
			return false;
		}

		Side fromSide = unit.getSide();
		Cell from = unit.getCell();
		Side toSide = target.side();
		Cell to = target.cell();
		if (toSide == fromSide && to.equals(from))
			return true;

		PlacedUnit[][] fromGrid = grids.get(fromSide);
		PlacedUnit[][] toGrid = grids.get(toSide);
		PlacedUnit occupant = toGrid[to.col()][to.row()];
		toGrid[to.col()][to.row()] = unit;
		fromGrid[from.col()][from.row()] = occupant;
		unit.setSide(toSide);
		unit.setCell(to);
		if (occupant != null) {
			occupant.setSide(fromSide);
			occupant.setCell(from);
		}
		return true;
	}

	/** Removes a unit from the board, if it is currently placed. */
	public void remove(PlacedUnit unit) {
		Cell cell = unit.getCell();
		PlacedUnit[][] grid = grids.get(unit.getSide());
		if (cell != null && grid[cell.col()][cell.row()] == unit)
			grid[cell.col()][cell.row()] = null;
	}

	/** Removes every unit from the given side. */
	public void clearSide(Side side) {
		for (PlacedUnit[] column : grids.get(side))
			Arrays.fill(column, null);
	}

	/** Whether the given side has any unit currently placed. */
	public boolean hasUnits(Side side) {
		for (PlacedUnit[] column : grids.get(side))
			for (PlacedUnit unit : column)
				if (unit != null)
					return true;
		return false;
	}

	/** Whether the side's front line (row 0) is completely unoccupied. */
	public boolean isFrontRowEmpty(Side side) {
		PlacedUnit[][] grid = grids.get(side);
		for (int col = 0; col < GridGeometry.COLS; col++)
			if (GridGeometry.isValid(col, 0) && grid[col][0] != null)
				return false;
		return true;
	}

	/**
	 * Moves every unit on the side one row toward the front line (row {@code r} to
	 * {@code r - 1}), keeping its column. Intended to be called only when the front
	 * row is empty, so the shift never collides: rows are processed front-first, so
	 * each row moves into the (now vacated) row ahead of it. Returns {@code true} if
	 * any unit moved.
	 */
	public boolean advanceToFront(Side side) {
		PlacedUnit[][] grid = grids.get(side);
		boolean moved = false;
		for (int row = 1; row < GridGeometry.ROWS; row++) {
			for (int col = 0; col < GridGeometry.COLS; col++) {
				PlacedUnit unit = grid[col][row];
				if (unit == null)
					continue;
				grid[col][row] = null;
				grid[col][row - 1] = unit;
				unit.setCell(new Cell(col, row - 1));
				moved = true;
			}
		}
		rowsAdvanced.put(side, rowsAdvanced.get(side) + 1);
		return moved;
	}

	/** How many rows the given side has advanced toward the front (0 if never). */
	public int rowsAdvanced(Side side) {
		return rowsAdvanced.getOrDefault(side, 0);
	}

	/** Resets every side's advance count; call when (re)starting a battle. */
	public void resetAdvancement() {
		for (Side side : Side.values())
			rowsAdvanced.put(side, 0);
	}

	/** Resets one side's advance count, e.g. when a fresh enemy wave takes the field. */
	public void resetAdvancement(Side side) {
		rowsAdvanced.put(side, 0);
	}

	/**
	 * Plays a sound effect by name (e.g. an ability's hit sound); the name may
	 * leave off its extension. Missing sounds are ignored.
	 */
	public void playSound(String name) {
		if (name == null || name.isBlank())
			return;
		SoundPlayer.play(GameFiles.active().getSound(name));
	}

	// --- Combat ------------------------------------------------------------

	/** Damage multiplier applied to a critical hit. Applied where the hit lands
	 * (after the defender's graze roll), so a grazed hit never gets the boost. */
	public static final double CRIT_MULTIPLIER = 1.85;

	private final Random random = new Random();

	/**
	 * The set of enemy cells the attacker can hit with the given attack.
	 *
	 * <p>For a FRONT attack range is measured straight ahead: distance = (tiles in
	 * front of the attacker) + (tiles the target is behind the enemy front line) +
	 * 1, so an attack with min/max range 1..5 can reach any enemy tile from
	 * anywhere.
	 *
	 * <p>A BACK attack instead counts <em>backwards</em> off the attacker's side
	 * and loops around the far end to strike the enemy from behind. The backward
	 * leg spans the number of rows that still exist on the attacker's side — not
	 * the unit's own position, so moving the unit between rows does not change its
	 * range — and the loop re-enters the enemy at its backmost still-existing row
	 * (its original back row may have been advanced past).
	 *
	 * <p>A cell is excluded if a unit between the shot's entry side and the target
	 * (in front of it for FRONT, behind it for BACK) blocks the line of fire.
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
		boolean fromBack = ability.getAttackDirection() == Ability.AttackDirection.BACK;

		for (int row = 0; row < GridGeometry.ROWS; row++) {
			for (int col = 0; col < GridGeometry.COLS; col++) {
				int distance;
				if (fromBack) {
					int attackerRows = GridGeometry.ROWS - attackerRow - rowsAdvanced(attacker.getSide());
					int enemyBackRow = (GridGeometry.ROWS - 1) - rowsAdvanced(targetSide);
					if (!GridGeometry.isValid(col, row + rowsAdvanced(targetSide)))
						continue;
					distance = attackerRows + (enemyBackRow - row);
				} else {
					if (!GridGeometry.isValid(col, row))
						continue;
					distance = attackerRow + row + 1;
				}
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
		if (attacker == null || attack == null)
			return result;
		Ability ability = attack.getAbility();
		// A WEAPON (fixed) attack ignores the aim: its damage area is anchored on
		// the attacker's own position (see resolveWeaponHits).
		if (ability.getTargetType() == Ability.TargetType.WEAPON)
			return resolveWeaponHits(attacker, attack);
		if (aim == null)
			return result;
		Side targetSide = opponentOf(attacker.getSide());
		TargetSquare[] targetArea = ability.getTargetArea();
		TargetSquare[] damageArea = ability.getDamageArea();
		if (targetArea == null)
			targetArea = new TargetSquare[] { TargetSquare.SINGLE_TARGET };
		if (damageArea == null)
			damageArea = new TargetSquare[] { TargetSquare.SINGLE_TARGET };
		// One call resolves a single attack; its shots are the ability's
		// shotsPerAttack. The attacksPerUse separate attacks are sequenced by the
		// battlefield, which calls this once per attack.
		int shots = Math.max(1, ability.getShotsPerAttack());
		int aoeDelay = ability.getAoeDelay();
		int minDamage = attack.getMinDamage(attacker.getRank());
		int maxDamage = attack.getMaxDamage(attacker.getRank());
		Ability.DamageType damageType = ability.getDamageType();
		double armorPiercing = Math.max(0, Math.min(1, ability.getArmorPiercingRate())); // Need to clamp to [0, 1] just in case of bad data??
		// Total offense (weapon base attack + ability attack + rank accuracy, less any
		// flat reduction from active status effects such as suppression), carried into
		// each hit so the defender's graze chance can be rolled against it when the hit
		// lands. TODO Clamped at 0 so heavy suppression cannot drive offense negative. Verify whether this is true in the real game
		int offense = Math.max(0, attack.getWeapon().getBaseAttack() + ability.getAttack()
				+ attacker.getAccuracy() - attacker.getOffenseReduction());

		// Area offsets are authored from the player's perspective: +x is one
		// tile to the player's left. The enemy faces the opposite way, so its
		// x is mirrored; y is the same for both sides.
		int xSign = attacker.getSide() == Side.PLAYER ? -1 : 1;

		// Each successive target tile is staggered by aoeDelay; the damage area
		// splashed around a target tile lands all at once. The ripple sequence is
		// taken from the squares' "order" when it varies, otherwise from their
		// position in the array, so a multi-tile area always ripples.
		int[] targetSteps = sequenceSteps(targetArea);

		if (ability.getRandomTarget()) {
			for (int shot = 0; shot < shots; shot++) {
				TargetSquare hit = pickWeighted(targetArea);
				if (hit != null) {
					// Each shot rolls its own crit against the unit on its target tile.
					boolean crit = rollCritical(attacker, ability,
							attack.getWeapon().getBaseCritical(), targetSide,
							aim.col() + xSign * hit.getX(), aim.row() - hit.getY());
					// The picked tile takes full damage: a random area's square value
					// is its pick probability (consumed by pickWeighted), not a
					// damage multiplier — so the target contribution here is 1.
					addImpact(result, targetSide, aim, hit, shot, damageArea,
							aoeDelay, xSign, rollDamage(minDamage, maxDamage),
							damageType, armorPiercing, 1.0, crit, offense);
				}
			}
		} else {
			for (int t = 0; t < targetArea.length; t++)
				for (int shot = 0; shot < shots; shot++) {
					boolean crit = rollCritical(attacker, ability,
							attack.getWeapon().getBaseCritical(), targetSide,
							aim.col() + xSign * targetArea[t].getX(),
							aim.row() - targetArea[t].getY());
					// The target square's value is its authored damagePercent; combine
					// it with the damage area's so a splash authored in either area scales.
					addImpact(result, targetSide, aim, targetArea[t], targetSteps[t],
							damageArea, aoeDelay, xSign,
							rollDamage(minDamage, maxDamage), damageType, armorPiercing,
							targetArea[t].getValue(), crit, offense);
				}
		}
		return result;
	}

	/**
	 * Adds one shot's damage-area tiles. They all land together, delayed by the aoe
	 * delay times the target square's step (the damage area itself does not ripple). A struck
	 * tile's multiplier is the product of its target-square value and its
	 * damage-area value (the game authors the per-tile splash percentage in either
	 * area), so {@code targetValue} is the contribution of the target square — it is
	 * passed in rather than read from {@code target} because the square used to
	 * position the impact is not always the one carrying the value (see the random
	 * and weapon callers).
	 */
	private void addImpact(List<Hit> result, Side targetSide, Cell aim,
			TargetSquare target, int targetStep, TargetSquare[] damageArea,
			int aoeDelay, int xSign, int baseDamage,
			Ability.DamageType damageType, double armorPiercing, double targetValue,
			boolean critical, int attackerOffense) {
		int baseCol = aim.col() + xSign * target.getX();
		int baseRow = aim.row() - target.getY();
		// The crit flag rides along on every tile the shot splashes to; its damage
		// boost is applied where the hit lands, after the defender's graze roll (a
		// grazed hit never crits), so the raw damage carried here is pre-crit.
		int delay = Math.max(0, aoeDelay * targetStep);
		for (int d = 0; d < damageArea.length; d++) {
			int col = baseCol + xSign * damageArea[d].getX();
			int row = baseRow - damageArea[d].getY();
			if (!GridGeometry.isValid(col, row))
				continue;
			double value = targetValue * damageArea[d].getValue();
			double rawDamage = baseDamage * value;
			result.add(new Hit(targetSide, new Cell(col, row), delay,
					rawDamage, damageType, armorPiercing, value, critical, attackerOffense));
		}
	}

	/**
	 * Rolls whether one shot crits. The chance is the ability's rate against the
	 * unit on the shot's primary target cell (so future per-unit-type crit bonuses
	 * apply, see {@link Ability#getCriticalRate}) plus the firing weapon's base
	 * critical chance and the attacker's rank critical bonus. An off-grid or empty
	 * cell uses just the base chance (still including the weapon and rank bonuses).
	 */
	private boolean rollCritical(PlacedUnit attacker, Ability ability, double weaponCrit,
			Side targetSide, int col, int row) {
		Unit target = null;
		if (GridGeometry.isValid(col, row)) {
			PlacedUnit hit = unitAt(targetSide, new Cell(col, row));
			if (hit != null)
				target = hit.getUnit();
		}
		double rate = ability.getCriticalRate(target) + weaponCrit + attacker.getCriticalBonus();
		return random.nextDouble() < rate;
	}

	/**
	 * Resolves a WEAPON (fixed) attack: its damage area is anchored on the
	 * attacker's own cell rather than an aimed tile, so it always strikes the same
	 * pattern in front of the unit ({@code y = -1} is one tile in front). Tiles may
	 * land on either side; any that run off the grids are dropped.
	 */
	private List<Hit> resolveWeaponHits(PlacedUnit attacker, Unit.Attack attack) {
		List<Hit> result = new ArrayList<>();
		Ability ability = attack.getAbility();
		Side targetSide = opponentOf(attacker.getSide());
		TargetSquare[] targetArea = ability.getTargetArea();
		TargetSquare[] damageArea = ability.getDamageArea();
		if (targetArea == null)
			targetArea = new TargetSquare[] { TargetSquare.SINGLE_TARGET };
		if (damageArea == null)
			damageArea = new TargetSquare[] { TargetSquare.SINGLE_TARGET };
		// A single attack's shots; the attacksPerUse repeats are sequenced by the
		// battlefield (see resolveHits).
		int shots = Math.max(1, ability.getShotsPerAttack());
		int aoeDelay = ability.getAoeDelay();
		int minDamage = attack.getMinDamage(attacker.getRank());
		int maxDamage = attack.getMaxDamage(attacker.getRank());
		Ability.DamageType damageType = ability.getDamageType();
		double armorPiercing = Math.max(0, Math.min(1, ability.getArmorPiercingRate()));
		int offense = Math.max(0, attack.getWeapon().getBaseAttack() + ability.getAttack()
				+ attacker.getAccuracy() - attacker.getOffenseReduction());
		int xSign = attacker.getSide() == Side.PLAYER ? -1 : 1;
		int[] targetSteps = sequenceSteps(targetArea);

		// Each target square is a fixed tile relative to the unit; tiles that stay
		// on the attacker's own side are skipped. The damage area then splashes
		// around each tile that lands on the opponent's side.
		for (int t = 0; t < targetArea.length; t++) {
			SideCell origin = weaponCell(attacker, ability, targetArea[t].getX(),
					targetArea[t].getY());
			if (origin == null)
				continue;
			for (int shot = 0; shot < shots; shot++) {
				boolean crit = rollCritical(attacker, ability,
						attack.getWeapon().getBaseCritical(), targetSide,
						origin.cell().col(), origin.cell().row());
				// The impact is positioned at the precomputed origin (SINGLE_TARGET, so
				// no extra offset), but still scaled by the fixed tile's damagePercent.
				addImpact(result, targetSide, origin.cell(), TargetSquare.SINGLE_TARGET,
						targetSteps[t], damageArea, aoeDelay, xSign,
						rollDamage(minDamage, maxDamage), damageType, armorPiercing,
						targetArea[t].getValue(), crit, offense);
			}
		}
		return result;
	}

	/**
	 * The cells a WEAPON (fixed) attack covers, computed from the attacker's own
	 * position (its target area read from the unit's perspective, then splashed by
	 * the damage area), for highlighting. Tiles on the attacker's own side or off
	 * the grids are excluded. Deduplicated.
	 */
	public Set<SideCell> weaponAffectedCells(PlacedUnit attacker, Unit.Attack attack) {
		Set<SideCell> cells = new LinkedHashSet<>();
		if (attacker == null || attack == null)
			return cells;
		Ability ability = attack.getAbility();
		TargetSquare[] targetArea = ability.getTargetArea();
		TargetSquare[] damageArea = ability.getDamageArea();
		if (targetArea == null)
			targetArea = new TargetSquare[] { TargetSquare.SINGLE_TARGET };
		if (damageArea == null)
			damageArea = new TargetSquare[] { TargetSquare.SINGLE_TARGET };
		Side targetSide = opponentOf(attacker.getSide());
		int xSign = attacker.getSide() == Side.PLAYER ? -1 : 1;
		for (TargetSquare target : targetArea) {
			SideCell origin = weaponCell(attacker, ability, target.getX(), target.getY());
			if (origin == null)
				continue;
			for (TargetSquare d : damageArea) {
				int col = origin.cell().col() + xSign * d.getX();
				int row = origin.cell().row() - d.getY();
				if (GridGeometry.isValid(col, row))
					cells.add(new SideCell(targetSide, new Cell(col, row)));
			}
		}
		return cells;
	}

	/**
	 * The cell struck by a fixed attack's offset relative to the attacker:
	 * {@code dx} is lateral (the attacker's right is positive) and {@code dy} is
	 * depth ({@code -1} is one tile in front of the unit). Moving forward crosses
	 * the front line straight onto the opponent's side (the empty gap row is
	 * skipped). Returns {@code null} if the tile stays on the attacker's own side
	 * (those are never affected), runs off the grids, or is blocked from the
	 * attacker's line of fire by a unit in front of it.
	 */
	public SideCell weaponCell(PlacedUnit attacker, Ability ability, int dx, int dy) {
		int forward = -dy;                 // tiles toward the opponent
		int row = attacker.getCell().row();
		if (forward <= row)
			return null; // still on the attacker's own side (or its own tile)
		Side side = attacker.getSide();
		int xSign = side == Side.PLAYER ? -1 : 1;
		int col = attacker.getCell().col() + xSign * dx;
		int cellRow = forward - row - 1;   // depth into the opponent's side
		if (!GridGeometry.isValid(col, cellRow))
			return null;
		Side targetSide = opponentOf(side);
		boolean fromBack = ability.getAttackDirection() == Ability.AttackDirection.BACK;
		if (isBlocked(targetSide, col, cellRow, ability.getLineOfFire(), fromBack))
			return null;
		return new SideCell(targetSide, new Cell(col, cellRow));
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
	 * it is hit, the raw damage before the defender's modifiers, the ability's
	 * damage type and armor-piercing fraction, and the damage-area value of this
	 * tile (which scales both its damage and any status-effect chance). Tiles in
	 * an area-of-effect ripple outwards using the ability's aoe delay. {@code critical}
	 * is true when the shot rolled a crit (the crit multiplier is applied when the hit
	 * lands, not here); all tiles splashed by one shot share that flag. {@code attackerOffense}
	 * is the attacker's total offense (ability attack plus rank accuracy), carried so the
	 * defender's graze chance can be rolled when the hit lands.
	 */
	public record Hit(Side side, Cell cell, int delayFrames, double rawDamage,
			Ability.DamageType damageType, double armorPiercing, double areaValue,
			boolean critical, int attackerOffense) {
	}

	/** A cell together with the side of the battlefield it lies on. */
	public record SideCell(Side side, Cell cell) {
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
