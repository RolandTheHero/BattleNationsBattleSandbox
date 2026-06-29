package hero.roland.bnsim.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.MouseInputAdapter;

import hero.roland.bnsim.ActiveStatusEffect;
import hero.roland.bnsim.BattleSimulator;
import hero.roland.bnsim.Cell;
import hero.roland.bnsim.EnemyBehavior;
import hero.roland.bnsim.GridGeometry;
import hero.roland.bnsim.PlacedUnit;
import hero.roland.bnsim.RandomEnemyBehavior;
import hero.roland.bnsim.Side;
import hero.roland.bnsim.gamefiles.GameFiles;
import hero.roland.bnsim.model.Ability;
import hero.roland.bnsim.model.Animation;
import hero.roland.bnsim.model.StatusEffect;
import hero.roland.bnsim.model.Unit;

/**
 * The central battlefield component. In setup mode it draws the two parallel
 * isometric grids and lets the user drag units between cells on their own side.
 * In battle mode the user selects a player unit, picks one of its attacks, sees
 * the targetable enemy tiles highlighted in blue, and clicks a tile to fire —
 * playing the attack animation and flashing the struck tiles red.
 */
public class BattleField extends JComponent {

	private static final Color BACKGROUND = Color.WHITE;
	private static final Color GRID_LINE = new Color(54, 66, 96);
	private static final Color PLAYER_TINT = new Color(70, 130, 220);
	private static final Color ENEMY_TINT = new Color(210, 80, 80);
	private static final Color DROP_HIGHLIGHT = new Color(120, 200, 120);
	private static final Color TARGET_HIGHLIGHT = new Color(60, 120, 230, 110);
	/** Cyan highlight for the fixed tiles a WEAPON (fixed) attack will strike. */
	private static final Color WEAPON_HIGHLIGHT = new Color(0, 255, 255, 120);
	/** AOE target footprint: a fully-struck tile (area value 1) is cyan, a
	 * lesser-value (partial splash) tile yellow. */
	private static final Color AOE_FULL_COLOR = WEAPON_HIGHLIGHT;
	private static final Color AOE_PARTIAL_COLOR = new Color(255, 225, 0, 130);
	/** The AOE target reticle is drawn at this fraction of the asset's native size. */
	private static final double AOE_RETICLE_SCALE = 1.6;
	/** Cyan outline traced around a selected unit's sprite: its colour, thickness
	 * (px) and opacity (the ring is drawn half-transparent). */
	private static final Color SELECT_SPRITE_OUTLINE = new Color(0, 255, 255);
	private static final int SELECT_SPRITE_OUTLINE_THICKNESS = 3;
	private static final float SELECT_SPRITE_OUTLINE_ALPHA = 0.5f;
	private static final Color HIT_COLOR = new Color(225, 40, 40);
	private static final Color RANK_COLOR = new Color(0, 220, 255);
	private static final Color HP_COLOR = new Color(70, 210, 60);
	private static final Color ARMOR_COLOR = new Color(0, 200, 255);
	private static final Color DAMAGE_COLOR = new Color(235, 45, 45);
	/** A struck unit's sprite flashes this colour, fading from {@link #HIT_FLASH_ALPHA}
	 * to nothing over {@link #HIT_FLASH_FRAMES}, and shakes left-right by up to
	 * {@link #HIT_SHAKE_MAX} pixels scaled by the fraction of health the hit removed. */
	private static final Color HIT_FLASH_COLOR = new Color(255, 45, 45);
	private static final float HIT_FLASH_ALPHA = 0.7f;
	private static final int HIT_SHAKE_MAX = 16;

	/** Frames a floating damage number lives, and how far it rises (pixels). */
	private static final int DAMAGE_FLOAT_FRAMES = 40;
	private static final int DAMAGE_RISE = 80;
	/** Point size of a normal damage number, the larger size for a critical hit,
	 * and the size of the "DODGE"/"MISS" indications. */
	private static final float DAMAGE_FONT_SIZE = 32f;
	private static final float CRIT_DAMAGE_FONT_SIZE = 36f;
	private static final float DODGE_FONT_SIZE = 32f;
	/** Critical-hit (and dodge) text is white, outlined in black this many pixels thick. */
	private static final Color CRIT_DAMAGE_COLOR = Color.WHITE;
	private static final int CRIT_OUTLINE = 2;
	/** The crit banner is drawn at this fraction of its native size, centred on the number. */
	private static final double CRIT_TAB_SCALE = 0.8;
	/** Grazed-hit numbers are grey; a dodge (graze with no damage) shows white "DODGE". */
	private static final Color GRAZE_DAMAGE_COLOR = new Color(160, 160, 160);
	private static final Color DODGE_COLOR = Color.WHITE;
	/** Frames between successive numbers on one tile, and random spread (px). */
	private static final int DAMAGE_STAGGER_FRAMES = 8;
	private static final int DAMAGE_JITTER_X = 32;
	private static final int DAMAGE_JITTER_Y = 12;
	/** Size (px) of the status icon drawn beside a status-damage number. */
	private static final int STATUS_NUMBER_ICON = 36;

	/** How far the "effect applied" icon sinks while fading. */
	private static final int STATUS_APPLY_DROP = 24;
	/** Largest dimension (px) the "effect applied" icon is drawn at. */
	private static final int STATUS_APPLY_ICON = 48;

	/** Size (px) of a status-effect icon drawn in a row above a unit's health bar. */
	private static final int STATUS_BAR_ICON = 24;
	/** Horizontal gap (px) between successive status icons above the bar. */
	private static final int STATUS_BAR_ICON_GAP = 1;
	/** Gap (px) between the bottom of the status icons and the top of the bar. */
	private static final int STATUS_BAR_ICON_MARGIN = 2;

	/** Pulsing affliction tint: alpha swings between these around the unit. */
	private static final float PULSE_MIN_ALPHA = 0.10f;
	private static final float PULSE_MAX_ALPHA = 0.42f;

	/** Milliseconds between animation frames. */
	private static final int FRAME_DELAY = 32;
	/** Frames a struck tile stays red before fully fading out. */
	private static final int HIT_FADE = 26;
	/** Frames a damaged unit's sprite stays tinted red and shaking. */
	private static final int HIT_FLASH_FRAMES = 16;
	/** Frames for a health bar to finish draining (~1 second). */
	private static final int BAR_ANIM_FRAMES = 1000 / FRAME_DELAY;
	/** Frames the "effect applied" icon shows for (~1 second). */
	private static final int STATUS_APPLY_FRAMES = 1000 / FRAME_DELAY;
	/** Frames a side's units take to slide one row forward (~1 second). */
	private static final int ADVANCE_FRAMES = 1000 / FRAME_DELAY;
	/** Frames an on-field message stays up (~2.5 seconds). */
	private static final int MESSAGE_FRAMES = 2500 / FRAME_DELAY;

	private final BattleSimulator sim;
	private final GridGeometry geometry;
	private final Timer animationTimer;

	private int tick;
	private boolean battleMode;

	/**
	 * Zoom applied to everything drawn on the battlefield (grids, units, health
	 * bars and the rest of the field visuals), scaled about the centre of the
	 * component. The background image and the overlaid battle controls are left at
	 * native size. 1 = no scaling; see {@link #setFieldScale}.
	 */
	private double fieldScale = 1.0;

	/** Status effect applied to a side at the start of each of its turns (whenever
	 * the turn changes to it); null = none. Set via the UnitMenu "Environment" dropdown. */
	private StatusEffect environmentStatusEffect;

	/** Background image scaled to fill the component; null falls back to a colour. */
	private BufferedImage background;

	/**
	 * Turn state. Each side's turn begins with a status-effect step (its effects
	 * deal damage and the turn waits for that animation), then the action: the
	 * enemy fires, or the player regains control. Every turn then ends through
	 * {@code AWAITING_ADVANCE}, which waits for all of the turn's animations to
	 * settle — attack/impact flashes, damage numbers, draining bars, death
	 * animations and death-spawns — before the next side acts, so the next turn
	 * never begins over a still-animating board. If the opposing side's front row
	 * has been emptied (and it still has units), {@code ADVANCING} then plays the
	 * one-row slide before that next turn.
	 */
	private enum Phase {
		PLAYER, PLAYER_FIRING, ENEMY_TURN_STATUS, ENEMY_FIRING, PLAYER_TURN_STATUS,
		AWAITING_ADVANCE, ADVANCING
	}

	private Phase phase = Phase.PLAYER;
	/** Tick at which the current phase's animation finishes and the turn advances. */
	private int attackEndTick;
	/** Tick at which the attack animation and its hits finish; status-apply icons
	 * start here, and the turn waits past it for them (via {@link #attackEndTick}). */
	private int attackAnimEndTick;
	/**
	 * Multi-attack state. An ability fires {@code attacksPerUse} separate attacks
	 * in sequence, each playing its full attack and impact animations before the
	 * next begins (see {@link #fireNextAttack}). The attacker, attack and aim are
	 * held so each repeat can re-resolve its own hits; {@code attacksRemaining}
	 * counts those not yet fired. Status effects accumulate across every attack and
	 * are rolled once, after the last (see {@link #applyPendingStatusEffects}).
	 */
	private PlacedUnit firingAttacker;
	private Unit.Attack firingAttack;
	private Cell firingAim;
	private int attacksRemaining;
	private final EnemyBehavior enemyBehavior = new RandomEnemyBehavior();

	/** The opposing side whose turn comes next, parked while the ended turn's
	 * animations settle; it advances one row first if its front line is then empty
	 * (null when no turn is ending). */
	private Side pendingAdvanceSide;
	/** Side whose units are sliding forward right now (null when not animating). */
	private Side advancingSide;
	/** Tick the current advance slide began at. */
	private int advanceStartTick;
	/** The units sliding forward (so dying units on the same side are not moved). */
	private Set<PlacedUnit> advancingUnits = new HashSet<>();
	/**
	 * How many rows each side has advanced. Each advance permanently drops that
	 * side's back-most row, so its grid keeps the smaller, shifted-forward shape
	 * instead of springing back to the full depth.
	 */
	private final Map<Side, Integer> rowsAdvanced = new EnumMap<>(Side.class);

	// Setup-mode drag state.
	private PlacedUnit dragging;
	private Point dragPoint;

	// Unit selection state. In battle mode the selected player unit fires; in
	// setup mode (and for enemy units) the selection is view-only: it shows the
	// unit's info panel and target area but cannot act.
	private PlacedUnit selectedAttacker;
	private Unit.Attack selectedAttack;
	/** The attack the player fired with, dropped from the aim while the turn plays
	 * out and re-highlighted on the selected unit once control returns (null = none). */
	private Unit.Attack pendingPlayerAim;
	/** Hides the selected unit's sprite outline while its attack plays out and the
	 * enemy acts; cleared so the outline returns once control comes back (see
	 * {@link #playerFire} and {@link #restorePlayerAim}). */
	private boolean selectHighlightSuppressed;
	private Set<Cell> targetable = new HashSet<>();
	/** Fixed tiles a selected WEAPON attack will strike (cyan highlight). */
	private Set<BattleSimulator.SideCell> weaponAffected = new HashSet<>();
	/**
	 * The tile the draggable AOE target reticle sits on, for a selected TARGET
	 * ability that defines a target area; {@code null} when the selected attack has
	 * no draggable target (no area, a WEAPON attack, or nothing selected). The
	 * reticle previews the attack's footprint and fires the ability on its tile.
	 */
	private Cell aoeTarget;
	/** Whether the AOE reticle is being dragged, and whether that drag has moved it
	 * off the tile it was pressed on (a stationary press on it fires instead). */
	private boolean draggingTarget;
	private boolean targetDragMoved;
	private Consumer<PlacedUnit> attackerSelectedListener;
	/** Battle mode: lets enemy units be selected (view-only) for inspection. */
	private boolean enemyViewEnabled;

	private final List<HitMarker> hitMarkers = new ArrayList<>();
	private final List<DamageNumber> damageNumbers = new ArrayList<>();
	/** Ability impact animations playing on struck tiles. */
	private final List<DamageAnim> damageAnims = new ArrayList<>();
	/** Units removed from the simulation but still on screen: each drains its
	 * health bar, then plays its death animation (after which its death-spawn, if
	 * any, takes the tile). */
	private final List<PlacedUnit> dyingUnits = new ArrayList<>();
	/** Sounds queued to play at a future tick (e.g. a weapon's delayed fire sound). */
	private final List<PendingSound> pendingSounds = new ArrayList<>();
	/** "Effect applied" icons floating down from an afflicted tile. */
	private final List<StatusApplyVisual> statusApplyVisuals = new ArrayList<>();
	/** Per-tile status-effect rolls accumulated over an attack, applied once it ends. */
	private final Map<BattleSimulator.SideCell, StatusAccumulator> pendingStatus = new HashMap<>();
	/** Cache of loaded status icons (effect/ui icons), including null misses. */
	private final Map<String, BufferedImage> iconCache = new HashMap<>();
	/** The "cannot be targeted" circle, loaded once on first use (may stay null). */
	private BufferedImage doNotTargetCircle;
	private boolean doNotTargetCircleLoaded;
	/** The draggable AOE target reticle, loaded once on first use (may stay null). */
	private BufferedImage aoeTargetCircle;
	private boolean aoeTargetCircleLoaded;
	/** The critical-hit banner stamped under crit numbers, loaded once (may stay null). */
	private BufferedImage critTab;
	private boolean critTabLoaded;
	private final Random random = new Random();

	/** A transient message shown across the field (e.g. "unit is stunned"). */
	private String message;
	private int messageEndTick;

	public BattleField(BattleSimulator sim) {
		this.sim = sim;
		this.geometry = sim.getGeometry();
		setBackground(BACKGROUND);
		setOpaque(true);
		setPreferredSize(new Dimension(
				GridGeometry.combinedWidth() + 120,
				GridGeometry.combinedHeight() + 120));
		setBackgroundImage("BattleMap.png"); // default battlefield background

		animationTimer = new Timer(FRAME_DELAY, e -> {
			tick++;
			playPendingSounds();
			applyLandedHits();
			applyPendingStatusEffects();
			animateHealthBars();
			updateDyingUnits();
			pruneHitMarkers();
			pruneDamageNumbers();
			pruneDamageAnims();
			pruneStatusApplyVisuals();
			advanceTurns();
			repaint();
		});

		MouseInputAdapter mouse = new MouseInputAdapter() {
			@Override
			public void mousePressed(java.awt.event.MouseEvent e) {
				Point p = toFieldPoint(e.getPoint());
				if (battleMode)
					onBattlePress(p);
				else if (SwingUtilities.isRightMouseButton(e))
					cycleRank(p);
				else
					beginDrag(p);
			}

			@Override
			public void mouseDragged(java.awt.event.MouseEvent e) {
				Point p = toFieldPoint(e.getPoint());
				if (battleMode)
					onBattleDrag(p);
				else if (dragging != null) {
					dragPoint = p;
					repaint();
				}
			}

			@Override
			public void mouseReleased(java.awt.event.MouseEvent e) {
				Point p = toFieldPoint(e.getPoint());
				if (battleMode) {
					onBattleRelease(p);
					return;
				}
				if (dragging != null) {
					boolean onBoard = sim.moveTo(dragging, p);
					PlacedUnit dropped = dragging;
					dragging = null;
					dragPoint = null;
					if (dropped == selectedAttacker) {
						if (!onBoard)
							clearSelection(); // dropped off the board: nothing to show
						else
							setSelectedAttack(selectedAttack); // recompute area from new cell
					}
					repaint();
				}
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	// --- Public API --------------------------------------------------------

	/** Places a unit on the given side, in its first free cell (setup mode). */
	public void placeUnit(Unit unit, Side side) {
		if (unit == null)
			return;
		sim.addUnit(unit, side);
		repaint();
	}

	/**
	 * Resizes the battlefield grid (setup mode): sets the per-side rows, front-row
	 * columns and back-row columns, then rebuilds the simulation's occupancy grids
	 * to match. Units that still fit on a valid cell are kept; any whose cell no
	 * longer exists are removed. Values are clamped to a valid range (see
	 * {@link GridGeometry#setDimensions}). Driven by the UnitMenu's grid-size boxes,
	 * which are only reachable during setup (the menu is hidden in battle).
	 */
	public void setGridDimensions(int rows, int cols, int backRowCols) {
		int oldRows = GridGeometry.ROWS, oldCols = GridGeometry.COLS;
		int oldBack = GridGeometry.BACK_ROW_COLS;
		GridGeometry.setDimensions(rows, cols, backRowCols);
		// A box committing its unchanged value (e.g. on focus loss) must not rebuild
		// the board or drop the selection — only an actual shape change does.
		if (GridGeometry.ROWS == oldRows && GridGeometry.COLS == oldCols
				&& GridGeometry.BACK_ROW_COLS == oldBack)
			return;
		sim.resizeGrids();
		for (Side s : Side.values())
			rowsAdvanced.put(s, 0);
		// The board shape changed: drop any drag/selection so nothing points at a
		// cell that may no longer exist.
		dragging = null;
		dragPoint = null;
		clearSelection();
		setPreferredSize(new Dimension(
			GridGeometry.combinedWidth() + 120,
			GridGeometry.combinedHeight() + 120));
		revalidate();
		repaint();
	}

	/** Removes all units from the given side (setup mode). */
	public void clearSide(Side side) {
		sim.clearSide(side);
		if (dragging != null && dragging.getSide() == side) {
			dragging = null;
			dragPoint = null;
		}
		if (selectedAttacker != null && selectedAttacker.getSide() == side)
			clearSelection();
		repaint();
	}

	/** Switches between setup mode (drag to place) and battle mode (attack). */
	public void setBattleMode(boolean battle) {
		this.battleMode = battle;
		phase = Phase.PLAYER;
		pendingAdvanceSide = null;
		advancingSide = null;
		advancingUnits.clear();
		for (Side s : Side.values())
			rowsAdvanced.put(s, 0);
		sim.resetAdvancement();
		dragging = null;
		dragPoint = null;
		enemyViewEnabled = false;
		clearSelection();
		hitMarkers.clear();
		dyingUnits.clear();
		pendingSounds.clear();
		damageNumbers.clear();
		statusApplyVisuals.clear();
		pendingStatus.clear();
		firingAttacker = null;
		firingAttack = null;
		firingAim = null;
		attacksRemaining = 0;
		pendingPlayerAim = null;
		selectHighlightSuppressed = false;
		message = null;
		if (battle)
			for (PlacedUnit unit : sim.placedUnits()) {
				unit.resetHealth();
				unit.startBattle();
			}
		repaint();
	}

	/**
	 * Sets whether the next battle tracks cooldowns, ammo, reloads and prep time.
	 * Driven by the UnitMenu toggle and read when a battle starts (so changing it
	 * mid-battle takes effect from the following battle).
	 */
	public void setCombatRulesEnabled(boolean enabled) {
		sim.getRules().setCombatRulesEnabled(enabled);
	}

	/**
	 * Sets whether abilities are restricted to the unit types in their targetable
	 * list. When disabled every ability may hit any unit. Driven by the UnitMenu
	 * toggle; applies immediately so the untargetable highlight updates live.
	 */
	public void setTargetTypesEnabled(boolean enabled) {
		sim.getRules().setEnforceTargetTypes(enabled);
		repaint();
	}

	/**
	 * Sets whether units' status-effect immunities are enforced. When disabled no
	 * unit is immune, so any status effect can be applied to any unit. Driven by
	 * the UnitMenu toggle.
	 */
	public void setStatusImmunitiesEnabled(boolean enabled) {
		sim.getRules().setEnforceImmunities(enabled);
	}

	/**
	 * Sets whether a side's units slide one row forward to fill an emptied front
	 * line. When disabled, units hold their cells and no advance slide plays. Driven
	 * by the UnitMenu toggle and read live as each turn ends (see
	 * {@link #beginAdvanceSlide}).
	 */
	public void setAdvanceEnabled(boolean enabled) {
		sim.getRules().setAdvanceEnabled(enabled);
	}

	/**
	 * Sets the status effect applied to a side at the start of each of its turns —
	 * i.e. whenever the turn changes to it. {@code id} is a loaded status-effect id
	 * (one of the "env" effects offered by the UnitMenu dropdown), or {@code null}
	 * for none. Immune units are unaffected, as immunity is enforced on apply.
	 */
	public void setEnvironmentStatusEffect(String id) {
		environmentStatusEffect = id != null ? StatusEffect.get(id) : null;
	}

	/**
	 * Sets the zoom applied to everything drawn on the battlefield — the grids,
	 * units, health bars and the rest of the field visuals — scaled about the
	 * centre of the field. The background image and the overlaid battle controls
	 * (weapons box, View Enemy / Pass / End Battle buttons) are not affected. A
	 * factor of 1 draws at native size, 1.5 draws everything half again as large.
	 * Non-positive values are ignored (treated as 1). Driven by the UnitMenu's
	 * field-scale box.
	 */
	public void setFieldScale(double scale) {
		this.fieldScale = scale > 0 ? scale : 1.0;
		repaint();
	}

	/** Listener notified when the selected attacker changes (null = cleared). */
	public void setAttackerSelectedListener(Consumer<PlacedUnit> listener) {
		this.attackerSelectedListener = listener;
	}

	/**
	 * Re-notifies the attacker-selected listener with the current selection and
	 * repaints, so the unit info panel and the painted unit labels rebuild after
	 * the data they show has changed (e.g. the text language was switched).
	 */
	public void refreshAttacker() {
		if (attackerSelectedListener != null)
			attackerSelectedListener.accept(selectedAttacker);
		repaint();
	}

	/**
	 * Battle mode: when enabled, an enemy unit is always kept selected so its
	 * health, abilities and target area can be inspected (view-only — enemy units
	 * cannot be made to act, and clicking elsewhere does not deselect it). Enabling
	 * auto-selects an enemy; disabling clears any enemy unit that was selected.
	 *
	 * <p>Returns whether enemy viewing is now active: enabling it does nothing and
	 * returns {@code false} when there is no enemy to view, so the caller can leave
	 * its toggle unpressed.
	 */
	public boolean setEnemyViewEnabled(boolean enabled) {
		if (enabled) {
			PlacedUnit enemy = firstEnemy();
			// No enemy to view: leave everything as it was and report that enemy
			// viewing did not engage.
			if (enemy == null) {
				enemyViewEnabled = false;
				return false;
			}
			enemyViewEnabled = true;
			// Always keep an enemy selected for inspection; if a player unit (or
			// nothing) was selected, show this enemy instead.
			if (selectedAttacker == null || selectedAttacker.getSide() != Side.ENEMY)
				selectAttacker(enemy);
		} else {
			enemyViewEnabled = false;
			// Back to controlling the player: drop the enemy unit that was being viewed.
			if (selectedAttacker != null && selectedAttacker.getSide() != Side.PLAYER)
				clearSelection();
		}
		repaint();
		return enemyViewEnabled;
	}

	/** The first enemy unit placed, or {@code null} if the enemy side has no units. */
	private PlacedUnit firstEnemy() {
		for (PlacedUnit unit : sim.placedUnits())
			if (unit.getSide() == Side.ENEMY)
				return unit;
		return null;
	}

	/**
	 * Sets the battlefield background to the named bundle image, scaled to fill
	 * the component. A null or unreadable file falls back to a plain background.
	 */
	public void setBackgroundImage(String name) {
		background = null;
		if (name != null) {
			File file = GameFiles.active().file(name);
			if (file.isFile()) {
				try {
					background = ImageIO.read(file);
				} catch (IOException e) {
					background = null; // best-effort: keep the plain background
				}
			}
		}
		repaint();
	}

	/** The attack currently aimed with, or {@code null} when none is selected. */
	public Unit.Attack getSelectedAttack() {
		return selectedAttack;
	}

	/**
	 * Sets the attack to aim with, recomputing the highlighted tiles. A normal
	 * attack highlights its aimable enemy tiles in blue; a WEAPON (fixed) attack
	 * highlights only the fixed tiles it strikes (from the unit's position) in cyan.
	 */
	public void setSelectedAttack(Unit.Attack attack) {
		this.selectedAttack = attack;
		targetable = new HashSet<>();
		weaponAffected = new HashSet<>();
		aoeTarget = null;
		draggingTarget = false;
		targetDragMoved = false;
		if (selectedAttacker != null && attack != null) {
			if (attack.getAbility().getTargetType() == Ability.TargetType.WEAPON)
				weaponAffected = sim.weaponAffectedCells(selectedAttacker, attack);
			else {
				targetable = sim.targetableCells(selectedAttacker, attack);
				// A TARGET ability with a target area gets a draggable reticle,
				// starting on the front row's centre (see defaultAoeTarget).
				if (hasAoeTarget(attack))
					aoeTarget = defaultAoeTarget();
			}
		}
		repaint();
	}

	/**
	 * Whether the attack drives a draggable AOE reticle: a TARGET ability that
	 * defines a target area (an AOE footprint to preview, drag around and fire on).
	 */
	private static boolean hasAoeTarget(Unit.Attack attack) {
		Ability ability = attack.getAbility();
		return ability.getTargetType() == Ability.TargetType.TARGET
				&& ability.getTargetArea() != null;
	}

	/**
	 * The reticle's starting cell: the targetable tile on the second row's centre
	 * column, or — when that tile is out of range — the targetable tile nearest to
	 * it. If the second row no longer exists on the target side (it has advanced past
	 * it), the first row is used instead. {@code null} when the attack can reach
	 * nothing.
	 */
	private Cell defaultAoeTarget() {
		if (targetable.isEmpty())
			return null;
		Side targetSide = BattleSimulator.opponentOf(selectedAttacker.getSide());
		int centreCol = GridGeometry.COLS / 2;
		int centreRow = Math.min(1, GridGeometry.ROWS - 1);
		// Fall back to the first row when the preferred row has been advanced past.
		if (!isCellVisible(targetSide, centreCol, centreRow))
			centreRow = 0;
		Cell start = new Cell(centreCol, centreRow);
		if (targetable.contains(start) && isCellVisible(targetSide, start.col(), start.row()))
			return start;
		// Otherwise the nearest still-existing targetable tile, measured from the
		// chosen centre (front rows preferred).
		Cell best = null;
		int bestDist = Integer.MAX_VALUE;
		for (Cell cell : targetable) {
			if (!isCellVisible(targetSide, cell.col(), cell.row()))
				continue;
			int dist = Math.abs(cell.row() - centreRow) * GridGeometry.COLS
					+ Math.abs(cell.col() - centreCol);
			if (dist < bestDist) {
				bestDist = dist;
				best = cell;
			}
		}
		return best;
	}

	// --- Interaction -------------------------------------------------------

	/**
	 * Maps a pixel in the component's coordinate space into the unscaled field
	 * space the {@linkplain #geometry geometry} and simulation work in, undoing
	 * the {@link #fieldScale} zoom (applied about the field centre when painting).
	 * All mouse input is routed through this so clicks line up with the scaled
	 * drawing.
	 */
	private Point toFieldPoint(Point screen) {
		if (fieldScale == 1.0)
			return screen;
		double cx = getWidth() / 2.0, cy = getHeight() / 2.0;
		int x = (int) Math.round(cx + (screen.x - cx) / fieldScale);
		int y = (int) Math.round(cy + (screen.y - cy) / fieldScale);
		return new Point(x, y);
	}

	private void beginDrag(Point p) {
		dragging = sim.pick(p);
		dragPoint = p;
		// Picking up a unit also selects it, showing its info panel and target
		// area (view-only in setup); clicking empty space clears the selection.
		// An already-selected unit keeps its current ability — only a fresh
		// selection resets the aim — so dragging a unit doesn't change the
		// selected ability.
		if (dragging != null) {
			if (dragging != selectedAttacker)
				selectAttacker(dragging);
		} else {
			clearSelection();
		}
		repaint();
	}

	/** Right-click during setup: cycle the unit's rank up to its maximum. */
	private void cycleRank(Point p) {
		PlacedUnit unit = sim.pick(p);
		if (unit != null) {
			unit.cycleRank();
			// Refresh the info panel's health display if this unit is selected.
			if (unit == selectedAttacker && attackerSelectedListener != null)
				attackerSelectedListener.accept(unit);
			repaint();
		}
	}

	/**
	 * Battle-mode press. When the player presses the draggable AOE reticle's own
	 * tile, a drag begins: a stationary press there fires the ability, while moving
	 * the cursor repositions the reticle (see {@link #onBattleDrag} and
	 * {@link #onBattleRelease}). Any other press falls through to normal handling.
	 */
	private void onBattlePress(Point p) {
		if (phase == Phase.PLAYER && isAoeReticleInteractive() && pressedOnReticle(p)) {
			draggingTarget = true;
			targetDragMoved = false;
			return;
		}
		onBattleClick(p);
	}

	/** Drags the AOE reticle to follow the targetable tile under the cursor. */
	private void onBattleDrag(Point p) {
		if (!draggingTarget)
			return;
		Side targetSide = BattleSimulator.opponentOf(selectedAttacker.getSide());
		Cell cell = geometry.cellAt(targetSide, p);
		if (cell != null && targetable.contains(cell) && !cell.equals(aoeTarget)) {
			aoeTarget = cell;
			targetDragMoved = true;
			repaint();
		}
	}

	/**
	 * Ends an AOE reticle drag. A press that never moved the reticle is a click on
	 * its tile and fires the ability there; a drag simply leaves the reticle where
	 * it was moved to.
	 */
	private void onBattleRelease(Point p) {
		if (!draggingTarget)
			return;
		boolean moved = targetDragMoved;
		draggingTarget = false;
		targetDragMoved = false;
		if (!moved && phase == Phase.PLAYER && isAoeReticleInteractive())
			tryFire(aoeTarget);
	}

	/**
	 * Whether the AOE reticle belongs to an attack the player can fire — a selected
	 * player unit aiming a TARGET-area ability — so it can be dragged and fired
	 * (an enemy unit's reticle, shown for inspection, is view-only).
	 */
	private boolean isAoeReticleInteractive() {
		return aoeTarget != null && selectedAttack != null && selectedAttacker != null
				&& selectedAttacker.getSide() == Side.PLAYER;
	}

	/** Whether the pixel falls on the current AOE reticle's tile. */
	private boolean pressedOnReticle(Point p) {
		Side targetSide = BattleSimulator.opponentOf(selectedAttacker.getSide());
		return aoeTarget != null && aoeTarget.equals(geometry.cellAt(targetSide, p));
	}

	private void onBattleClick(Point p) {
		// Input is only accepted during the player's turn (not while an attack
		// animation is playing or during the enemy's turn).
		if (phase != Phase.PLAYER)
			return;
		// Firing at a highlighted tile takes priority, but only for the player's
		// own units — enemy units are view-only.
		if (selectedAttacker != null && selectedAttacker.getSide() == Side.PLAYER
				&& selectedAttack != null) {
			if (selectedAttack.getAbility().getTargetType() == Ability.TargetType.WEAPON) {
				// Fixed attack: clicking any highlighted tile fires it (its aim is
				// the attacker's own position, so it is passed unused).
				if (clickedWeaponTile(p)) {
					tryFire(selectedAttacker.getCell());
					return;
				}
			} else {
				Cell target = geometry.cellAt(Side.ENEMY, p);
				if (target != null && targetable.contains(target)) {
					// An AOE attack moves its reticle to the clicked in-range tile
					// (firing happens by pressing the reticle's own tile); any other
					// TARGET attack fires straight at the clicked tile.
					if (aoeTarget != null) {
						aoeTarget = target;
						repaint();
					} else {
						tryFire(target);
					}
					return;
				}
			}
		}
		// Otherwise (re)select a unit. Only one side is selectable: enemy units
		// (view-only) while enemy viewing is enabled, the player's own units
		// otherwise. Clicking away clears the selection — except while enemy
		// viewing, which always keeps an enemy selected.
		Side selectableSide = enemyViewEnabled ? Side.ENEMY : Side.PLAYER;
		PlacedUnit clicked = sim.pick(p);
		if (clicked != null && clicked.getSide() == selectableSide)
			selectAttacker(clicked);
		else if (!enemyViewEnabled)
			clearSelection();
	}

	/** Whether the pixel falls on one of the highlighted fixed-attack tiles. */
	private boolean clickedWeaponTile(Point p) {
		for (BattleSimulator.SideCell sc : weaponAffected)
			if (sc.cell().equals(geometry.cellAt(sc.side(), p)))
				return true;
		return false;
	}

	/**
	 * Fires the selected attack at {@code aim}, unless the attacker is blocked by a
	 * status effect (then a message is shown and the selection is kept so the
	 * player can pick a different unit).
	 */
	private void tryFire(Cell aim) {
		if (selectedAttacker.isActionBlocked()) {
			showMessage(selectedAttacker.getUnit().getName() + " is unable to act!");
			return;
		}
		if (!selectedAttacker.isAttackReady(selectedAttack)) {
			int turns = selectedAttacker.getAttackCooldown(selectedAttack);
			if (turns > 0)
				showMessage(selectedAttack.getName() + " is on cooldown for " + turns
						+ " more turn" + (turns == 1 ? "" : "s") + "!");
			else
				showMessage("Not enough ammo for " + selectedAttack.getName() + "!");
			return;
		}
		playerFire(aim);
	}

	private void selectAttacker(PlacedUnit unit) {
		selectedAttacker = unit;
		selectHighlightSuppressed = false;
		// Default the aim to the unit's first usable attack (also computing its
		// highlighted tiles) so a target can be clicked straight away.
		setSelectedAttack(firstAttack(unit));
		if (attackerSelectedListener != null)
			attackerSelectedListener.accept(unit);
		repaint();
	}

	/**
	 * The unit's first usable attack — the first real ability across its weapons —
	 * or {@code null} if it has none. Used to default the aim when a unit is selected.
	 */
	private Unit.Attack firstAttack(PlacedUnit unit) {
		for (Unit.Weapon weapon : unit.getUnit().getWeapons()) {
			if ("none".equals(weapon.getTag()))
				continue;
			for (Unit.Attack attack : weapon.getAttacks())
				if (attack.getAbility() != Ability.NO_ABILITY)
					return attack;
		}
		return null;
	}

	/**
	 * Rebuilds the selected unit's info panel so its cooldowns, ammo and health
	 * reflect the unit's current state, without changing the current aim. Lets the
	 * panel stay open and current after firing and across turns instead of having to
	 * be closed and reopened. A no-op display-wise when nothing is selected.
	 */
	private void refreshSelectionInfo() {
		if (attackerSelectedListener != null)
			attackerSelectedListener.accept(selectedAttacker);
	}

	private void clearSelection() {
		selectedAttacker = null;
		selectedAttack = null;
		targetable = new HashSet<>();
		weaponAffected = new HashSet<>();
		aoeTarget = null;
		draggingTarget = false;
		targetDragMoved = false;
		if (attackerSelectedListener != null)
			attackerSelectedListener.accept(null);
		repaint();
	}

	private void playerFire(Cell aim) {
		PlacedUnit attacker = selectedAttacker;
		Unit.Attack attack = selectedAttack;
		executeAttack(attacker, attack, aim);
		phase = Phase.PLAYER_FIRING;   // wait for the animation to finish
		// Remember the aim so the selected unit's target tiles can be re-highlighted
		// once control returns to the player (see restorePlayerAim).
		pendingPlayerAim = attack;
		// Keep the attacker selected so its weapon box stays open, but drop the aim so
		// no target highlight lingers while the attack plays out and the enemy acts.
		// The unit's sprite outline is hidden the same way, returning with the target
		// tiles once control comes back (see restorePlayerAim).
		// Refresh the box to show the ability's new cooldown and the weapon's spent
		// ammo (no further firing is possible until the player's turn comes back, as
		// input is ignored outside Phase.PLAYER).
		selectedAttack = null;
		selectHighlightSuppressed = true;
		targetable = new HashSet<>();
		weaponAffected = new HashSet<>();
		aoeTarget = null;
		draggingTarget = false;
		targetDragMoved = false;
		refreshSelectionInfo();
		repaint();
	}

	/**
	 * Skips the player's turn without attacking, handing control to the enemy.
	 * Passing still ends the turn, so the enemy advances first if the player has
	 * left its front line empty. Does nothing outside the player's turn (e.g. while
	 * an attack is animating or during the enemy's turn).
	 */
	public void passTurn() {
		if (!battleMode || phase != Phase.PLAYER)
			return;
		clearSelection();
		endOfTurn(Side.PLAYER);
	}

	/**
	 * Begins an attack: an ability fires {@code attacksPerUse} separate attacks in
	 * sequence. This sets up the sequence and plays the first; each subsequent
	 * attack is started by {@link #advanceTurns} once the previous one's animations
	 * have finished (see {@link #fireNextAttack}).
	 */
	private void executeAttack(PlacedUnit attacker, Unit.Attack attack, Cell aim) {
		// Spend the weapon's ammo and start the ability's cooldown/reload once per use
		// (not per attack in the sequence).
		attacker.useAttack(attack);
		firingAttacker = attacker;
		firingAttack = attack;
		firingAim = aim;
		attacksRemaining = Math.max(1, attack.getAbility().getAttacksPerUse());
		fireNextAttack();
	}

	/**
	 * Plays the next attack in the current sequence: starts the attacker's attack
	 * animation, schedules the struck tiles, and records when the turn may advance.
	 * Status effects are not rolled here — they accumulate across the sequence and
	 * are applied once the final attack finishes.
	 */
	private void fireNextAttack() {
		attacksRemaining--;
		PlacedUnit attacker = firingAttacker;
		Unit.Attack attack = firingAttack;
		Cell aim = firingAim;
		Animation anim = loadAttackAnimation(attacker, attack);
		attacker.startAttack(anim, tick);

		// Queue the weapon's fire sound to play after its fire-sound frame.
		int fireTick = tick + Math.max(0, attack.getWeapon().firesoundFrame());
		pendingSounds.add(new PendingSound(attack.getWeapon().firesound(), fireTick));

		// The ability's hit sound is played per struck enemy as each hit lands
		// (see applyLandedHits), so its variant can match that unit's type.
		Ability ability = attack.getAbility();
		int base = tick + Math.max(0, attack.getHitDelay());
		int lastHit = 0;
		for (BattleSimulator.Hit hit : sim.resolveHits(attacker, attack, aim)) {
			int start = base + hit.delayFrames();
			hitMarkers.add(new HitMarker(hit.side(), hit.cell(), start,
					hit.rawDamage(), hit.damageType(), hit.armorPiercing(),
					hit.areaValue(), ability, hit.critical(), hit.attackerOffense()));
			lastHit = Math.max(lastHit, start - tick);
		}

		int animFrames = (anim != null) ? anim.getEndFrame() : 0;
		// Each struck tile plays the ability's impact animation after its hit lands;
		// the last one finishes at lastHit + impactFrames. Status effects wait until
		// then so the impact animation fully plays out before the status-apply
		// animation (and any status damage) begins.
		Animation impact = loadDamageAnimation(
				BattleSimulator.opponentOf(attacker.getSide()), ability);
		int impactFrames = (impact != null) ? impact.getEndFrame() : 0;
		// The attack animation and last landed tile's impact finish here; status-apply
		// icons start from this tick.
		attackAnimEndTick = tick + Math.max(1,
				Math.max(animFrames, lastHit + Math.max(4, impactFrames)));
		// The turn advances when the attack finishes; applying a status effect
		// extends this to wait for the status-apply animation (see applyStatusEffects).
		attackEndTick = attackAnimEndTick;
		repaint();
	}

	private Animation loadAttackAnimation(PlacedUnit attacker, Unit.Attack attack) {
		// Each side fires using the animation that faces the opponent: the
		// player (bottom) uses its back animation, the enemy (top) its front.
		boolean player = attacker.getSide() == Side.PLAYER;
		try {
			Animation anim = player ? attack.getWeapon().getBackAnimation()
					: attack.getWeapon().getFrontAnimation();
			if (anim == null)
				anim = player ? attack.getBackAnimation() : attack.getFrontAnimation();
			return anim;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * The ability's impact animation for a hit on the given side's tile. Like
	 * {@link #loadAttackAnimation}, the variant follows the tile's position: the
	 * player (bottom) uses the back animation, the enemy (top) the front. Returns
	 * {@code null} when the ability has no damage animation configured.
	 */
	private Animation loadDamageAnimation(Side targetSide, Ability ability) {
		try {
			return targetSide == Side.PLAYER
					? ability.getBackAnimation() : ability.getFrontAnimation();
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** The unit's death animation, or {@code null} if none could be loaded. */
	private Animation loadDeathAnimation(PlacedUnit unit) {
		try {
			return unit.getUnit().getDeathAnimation();
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Drives the turn state machine once per frame while in battle mode. */
	private void advanceTurns() {
		if (!battleMode)
			return;
		// Phases that wait for the board to fully settle — every animation finished,
		// including any death playing out — rather than a fixed end tick. Polled
		// each frame so the next step never begins over a still-animating board.
		switch (phase) {
		case AWAITING_ADVANCE:
			// The ended turn waits before the next side acts (and any advance slides).
			if (turnVisualsSettled())
				beginAdvanceSlide();
			return;
		case ENEMY_TURN_STATUS:
			// Wait for start-of-turn status damage and any deaths it causes to finish
			// before the enemy attacks, so a status kill isn't stranded behind it.
			if (turnVisualsSettled())
				enemyAct();
			return;
		case PLAYER_TURN_STATUS:
			// As above, before handing control back to the player.
			if (turnVisualsSettled()) {
				phase = Phase.PLAYER;
				restorePlayerAim();
			}
			return;
		default:
			break;
		}
		if (tick < attackEndTick)
			return;
		switch (phase) {
		case PLAYER_FIRING:
			// Play the ability's remaining attacks before ending the turn; status
			// effects (rolled in applyPendingStatusEffects) wait for the last one.
			if (attacksRemaining > 0)
				fireNextAttack();
			else
				endOfTurn(Side.PLAYER);
			break;
		case ENEMY_FIRING:
			if (attacksRemaining > 0)
				fireNextAttack();
			else
				endOfTurn(Side.ENEMY);
			break;
		case ADVANCING:
			finishAdvance(); // slide finished; hand off to the next turn
			break;
		default:
			break;
		}
	}

	/**
	 * Wraps up {@code actingSide}'s turn. Rather than handing straight to the next
	 * side, the turn always parks in {@code AWAITING_ADVANCE} until every one of its
	 * animations has finished (see {@link #turnVisualsSettled}) — attack and impact
	 * flashes, floating damage numbers, draining bars, death animations and their
	 * death-spawns — so the next side never starts acting over a still-animating
	 * board. Once settled, {@link #beginAdvanceSlide} advances the opposing side one
	 * row if its front line is now empty, then the next turn begins.
	 */
	private void endOfTurn(Side actingSide) {
		// Advance the acting side's cooldowns/reloads as its turn ends, so their
		// countdowns are up to date before the opposing side gets to look or act.
		for (PlacedUnit unit : sim.placedUnits())
			if (unit.getSide() == actingSide)
				unit.tickCooldowns();
		pendingAdvanceSide = BattleSimulator.opponentOf(actingSide);
		phase = Phase.AWAITING_ADVANCE;
		// The acting side's cooldowns and reloads just advanced; refresh the open info
		// panel so a shown unit's countdowns stay current without reselecting it.
		refreshSelectionInfo();
	}

	/** Begins the turn that follows {@code actingSide}'s. */
	private void nextTurnAfter(Side actingSide) {
		// The turn is changing sides: apply the environment effect to the side it is
		// changing to, just before that side's effects tick. This never fires for the
		// player's opening turn, which starts the battle rather than following a turn.
		applyEnvironmentStatusEffect(BattleSimulator.opponentOf(actingSide));
		if (actingSide == Side.PLAYER)
			beginEnemyTurn();
		else
			beginPlayerTurn();
	}

	/**
	 * Applies the configured environment status effect (if any) to every unit on
	 * {@code side} as its turn begins, just before its effects tick — so each unit
	 * takes the environment's damage at the start of its turn. Re-applying every
	 * turn refreshes the effect; immune units are unaffected (immunity is enforced
	 * by {@link PlacedUnit#applyStatusEffect}).
	 */
	private void applyEnvironmentStatusEffect(Side side) {
		if (environmentStatusEffect == null)
			return;
		for (PlacedUnit unit : sim.placedUnits())
			if (unit.getSide() == side)
				unit.applyStatusEffect(new ActiveStatusEffect(environmentStatusEffect, 0, tick));
	}

	/**
	 * Whether every animation from the turn that just ended has finished, so the
	 * advance slide can begin on a clean board (no lingering hit flashes, damage
	 * numbers, impact/status visuals, draining bars or dying units).
	 */
	private boolean turnVisualsSettled() {
		if (!hitMarkers.isEmpty() || !damageNumbers.isEmpty()
				|| !damageAnims.isEmpty() || !statusApplyVisuals.isEmpty()
				|| !dyingUnits.isEmpty() || !pendingStatus.isEmpty()
				|| !pendingSounds.isEmpty())
			return false;
		for (PlacedUnit unit : sim.placedUnits())
			if (!unit.barsSettled())
				return false;
		return true;
	}

	/**
	 * Called once the ended turn's animations have settled. If the pending side's
	 * front line is empty (and it still has units), applies its one-row advance to
	 * the simulation — so every unit's range and targeting immediately use the new
	 * cells — and begins the slide animation; otherwise it simply hands control to
	 * the next turn. The settled board is re-checked here because a death-spawn may
	 * have filled (or, in a back row, left empty) the front line while we waited.
	 * The advancing units are captured up front so dying units on the same side —
	 * which keep their last cell while their bars drain — are not dragged along.
	 */
	private void beginAdvanceSlide() {
		Side side = pendingAdvanceSide;
		pendingAdvanceSide = null;
		// Nothing to slide — advancing turned off, the front row still occupied, or
		// the side has no units left: just hand off to the next turn without sliding.
		if (!sim.getRules().isAdvanceEnabled() || !sim.isFrontRowEmpty(side)
				|| !sim.hasUnits(side)) {
			nextTurnAfter(BattleSimulator.opponentOf(side));
			return;
		}
		sim.advanceToFront(side);
		advancingUnits = new HashSet<>();
		for (PlacedUnit unit : sim.placedUnits())
			if (unit.getSide() == side)
				advancingUnits.add(unit);
		advancingSide = side;
		advanceStartTick = tick;
		phase = Phase.ADVANCING;
		attackEndTick = tick + ADVANCE_FRAMES; // wait for the slide to finish
		repaint();
	}

	/** Ends the advance slide and hands control to the next turn. */
	private void finishAdvance() {
		Side advanced = advancingSide;
		Side acting = BattleSimulator.opponentOf(advanced);
		// Permanently drop the back-most row so the grid keeps its new, smaller
		// shape; the sliding tiles have already landed on the rows ahead.
		rowsAdvanced.put(advanced, rowsAdvanced.get(advanced) + 1);
		advancingSide = null;
		advancingUnits.clear();
		nextTurnAfter(acting);
	}

	/** How many rows the given side has advanced (0 if it never has). */
	private int rowsAdvanced(Side side) {
		return rowsAdvanced.getOrDefault(side, 0);
	}

	/** Number of rows still drawn for the side (it shrinks by one per advance). */
	private int visibleRows(Side side) {
		return GridGeometry.ROWS - rowsAdvanced(side);
	}

	/**
	 * Whether the side still draws a tile at the given cell after advancing: the row
	 * must remain, and the column must be valid for the original row it came from
	 * (so columns trimmed from a narrow row are not highlighted once it advances).
	 */
	private boolean isCellVisible(Side side, int col, int row) {
		return row >= 0 && row < visibleRows(side)
				&& GridGeometry.isValid(col, row + rowsAdvanced(side));
	}

	/** Progress (0..1) of the current advance slide; 1 when none is animating. */
	private double advanceProgress() {
		if (advancingSide == null)
			return 1.0;
		double p = (tick - advanceStartTick) / (double) ADVANCE_FRAMES;
		return Math.max(0.0, Math.min(1.0, p));
	}

	/**
	 * Pixel centre at which to draw a unit. While its side is sliding forward, an
	 * advancing unit eases from its old row (one behind its current cell) toward its
	 * new cell; every other unit sits at its cell centre.
	 */
	private Point2D.Double unitDrawCentre(PlacedUnit unit) {
		Cell cell = unit.getCell();
		Point2D.Double to = geometry.cellCentre(unit.getSide(), cell);
		if (unit.getSide() != advancingSide || !advancingUnits.contains(unit))
			return to;
		double p = advanceProgress();
		Point2D.Double from = geometry.cellCentre(unit.getSide(), cell.col(), cell.row() + 1);
		return new Point2D.Double(from.x + p * (to.x - from.x), from.y + p * (to.y - from.y));
	}

	/**
	 * Begins the enemy's turn by evaluating its status effects. The enemy only acts
	 * once every start-of-turn status animation has finished — the damage numbers
	 * and any death (and death-spawn) a status effect causes (see {@link #advanceTurns}).
	 */
	private void beginEnemyTurn() {
		tickStatusEffects(Side.ENEMY);
		phase = Phase.ENEMY_TURN_STATUS;
	}

	/** The enemy chooses and plays its attack, after its status effects ticked. */
	private void enemyAct() {
		EnemyBehavior.Move move = enemyBehavior.decideMove(sim);
		if (move == null) {
			// No legal move: the enemy effectively passes, but ending its turn still
			// lets the player advance if its front line has been emptied.
			endOfTurn(Side.ENEMY);
			return;
		}
		executeAttack(move.attacker(), move.attack(), move.target());
		phase = Phase.ENEMY_FIRING;
	}

	/**
	 * Begins the player's turn by evaluating their status effects. The player only
	 * regains control once every start-of-turn status animation has finished — the
	 * damage numbers and any death a status effect causes (see {@link #advanceTurns}).
	 */
	private void beginPlayerTurn() {
		tickStatusEffects(Side.PLAYER);
		phase = Phase.PLAYER_TURN_STATUS;
	}

	/**
	 * Re-highlights the selected player unit's target tiles once control returns to
	 * the player, restoring the attack it fired with — so the targetable tiles
	 * reappear without the player having to reselect the unit. A no-op when nothing
	 * is selected, the selection is an enemy (view-only) unit, the unit died during
	 * the turn (its selection was already cleared by {@link #beginDying}), or the
	 * player passed rather than fired (no pending aim to restore).
	 */
	private void restorePlayerAim() {
		if (selectedAttacker != null && selectedAttacker.getSide() == Side.PLAYER
				&& selectedAttack == null && pendingPlayerAim != null)
			setSelectedAttack(pendingPlayerAim);
		pendingPlayerAim = null;
		// Bring the selected unit's sprite outline back now control is the player's.
		selectHighlightSuppressed = false;
	}

	/**
	 * Evaluates start-of-turn status effects for every unit on the given side:
	 * each effect deals its damage (shown as a floating number with the effect's
	 * icon), ages by a turn, and is removed when it expires. A unit killed by an
	 * effect is taken out of the simulation and left on screen to play out its
	 * death; the turn waits for that to finish before proceeding (see
	 * {@link #advanceTurns}).
	 */
	private void tickStatusEffects(Side side) {
		for (PlacedUnit unit : sim.placedUnits()) {
			if (unit.getSide() != side)
				continue;
			for (PlacedUnit.StatusTick st : unit.tickStatusEffects()) {
				StatusEffect.StatusFamily family = st.effect().getFamily();
				BufferedImage icon = family != null ? loadIcon(family.getUiIcon()) : null;
				// Play the effect's sound as its damage number appears.
				String sound = family != null ? family.getSound() : null;
				spawnDamageNumber(side, unit.getCell(), st.damageDealt(), icon, sound, false);
				// Flash and shake the unit for the status damage, as for a direct hit.
				unit.registerHit(st.damageDealt(), tick);
			}
			if (unit.isDead())
				beginDying(unit);
		}
	}

	// --- Painting ----------------------------------------------------------

	@Override
	public void addNotify() {
		super.addNotify();
		animationTimer.start();
	}

	@Override
	public void removeNotify() {
		animationTimer.stop();
		super.removeNotify();
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		//g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		if (background != null) {
			// Scale proportionally to cover the component, cropping the overflow.
			int cw = getWidth(), ch = getHeight();
			int iw = background.getWidth(), ih = background.getHeight();
			double scale = Math.max((double) cw / iw, (double) ch / ih);
			int sw = (int) Math.ceil(iw * scale);
			int sh = (int) Math.ceil(ih * scale);
			g2.drawImage(background, (cw - sw) / 2, (ch - sh) / 2, sw, sh, null);
		} else {
			g2.setColor(BACKGROUND);
			g2.fillRect(0, 0, getWidth(), getHeight());
		}

		geometry.setViewport(getWidth(), getHeight());

		// Zoom the battlefield itself — grids, units, bars and every other field
		// visual — about the centre of the component, leaving the background (drawn
		// above) and the overlaid Swing controls unscaled. Mouse input is mapped back
		// through toFieldPoint so it stays aligned with this scaled drawing.
		if (fieldScale != 1.0) {
			double cx = getWidth() / 2.0, cy = getHeight() / 2.0;
			g2.translate(cx, cy);
			g2.scale(fieldScale, fieldScale);
			g2.translate(-cx, -cy);
		}

		drawGrid(g2, Side.ENEMY);
		drawGrid(g2, Side.PLAYER);

		// A selected unit shows its target area in either mode (no-op when nothing is
		// selected); setup mode also shows the drag-drop highlight.
		drawTargetable(g2);
		if (!battleMode && dragging != null && dragPoint != null) {
			Cell target = geometry.cellAt(dragging.getSide(), dragPoint);
			if (target != null) {
				g2.setColor(DROP_HIGHLIGHT);
				g2.fillPolygon(geometry.cellDiamond(dragging.getSide(),
						target.col(), target.row()));
			}
		}

		// Struck tiles flash red on the grid layer, beneath the units standing on them.
		drawHitMarkers(g2);
		drawUnits(g2);
		// Mark enemy units the selected ability cannot hit, over their sprites.
		drawUntargetable(g2);
		// The draggable AOE reticle rides over the units so it stays visible.
		drawAoeReticle(g2);
		drawDamageAnims(g2);
		drawOverlays(g2);
		drawStatusApplyVisuals(g2);
		drawDamageNumbers(g2);
		if (battleMode)
			drawMessage(g2);

		if (!battleMode && dragging != null && dragPoint != null)
			drawUnit(g2, dragging, dragPoint.x, dragPoint.y, 0.7f);

		g2.dispose();
	}

	private void drawGrid(Graphics2D g, Side side) {
		g.setColor(GRID_LINE);
		int advanced = rowsAdvanced(side);
		// The side has shrunk by one row per advance; only the front rows remain.
		int visible = GridGeometry.ROWS - advanced;
		// While the side is sliding, its front row stays put and the rows behind it
		// flow forward onto the row ahead; the back-most row empties out (and stays
		// gone, since `advanced` is bumped once the slide finishes).
		boolean sliding = (side == advancingSide);
		double p = sliding ? advanceProgress() : 1.0;
		for (int row = 0; row < visible; row++) {
			// Each row keeps the width of the original row it advanced from, so a
			// narrow back row stays narrow as it moves toward the front.
			int sourceRow = row + advanced;
			for (int col = 0; col < GridGeometry.COLS; col++) {
				if (!GridGeometry.isValid(col, sourceRow))
					continue;
				if (!sliding) {
					g.drawPolygon(geometry.cellDiamond(side, col, row));
				} else if (row == 0) {
					// The front row shrinks toward the front edge and vanishes as the
					// rows behind it move forward into its place.
					g.drawPolygon(geometry.frontCollapsedDiamond(side, col, row, p));
				} else {
					Point2D from = geometry.cellCentre(side, col, row);
					Point2D to = geometry.cellCentre(side, col, row - 1);
					double cx = from.getX() + p * (to.getX() - from.getX());
					double cy = from.getY() + p * (to.getY() - from.getY());
					g.drawPolygon(geometry.diamondAt(cx, cy));
				}
			}
		}
	}

	private void drawTargetable(Graphics2D g) {
		if (selectedAttacker == null)
			return;
		// The aimable tiles lie on the side facing the attacker (enemy tiles for a
		// player unit, player tiles for an enemy unit).
		Side targetSide = BattleSimulator.opponentOf(selectedAttacker.getSide());
		// The AOE footprint is computed first so the blue range highlight can leave
		// out any tile the footprint already colours (cyan or yellow).
		AoeFootprint footprint = aoeFootprint();
		g.setColor(TARGET_HIGHLIGHT);
		// Skip tiles a side has advanced past — those tiles no longer exist — and any
		// the footprint already highlights.
		for (Cell cell : targetable)
			if (isCellVisible(targetSide, cell.col(), cell.row())
					&& (footprint == null || !footprint.covers(cell)))
				g.fillPolygon(geometry.cellDiamond(targetSide, cell.col(), cell.row()));
		// A WEAPON (fixed) attack highlights only the fixed tiles it strikes, cyan.
		g.setColor(WEAPON_HIGHLIGHT);
		for (BattleSimulator.SideCell sc : weaponAffected)
			if (isCellVisible(sc.side(), sc.cell().col(), sc.cell().row()))
				g.fillPolygon(geometry.cellDiamond(sc.side(), sc.cell().col(), sc.cell().row()));
		// Draw the footprint over the range highlight: yellow splash first, then the
		// cyan direct-hit tiles over it, so a target cell always reads as a direct hit.
		if (footprint != null) {
			fillCells(g, targetSide, footprint.splash, AOE_PARTIAL_COLOR);
			fillCells(g, targetSide, footprint.direct, AOE_FULL_COLOR);
		}
	}

	/**
	 * The tiles a TARGET-area attack's footprint covers, relative to the draggable
	 * reticle: every target-area cell (each square offset from the reticle's tile)
	 * is a direct hit; the damage area then splashes around each of those cells, and
	 * any tile it reaches that is not itself a target cell is a partial (splash) hit.
	 * The areas are read from the attacker's perspective, so their x is mirrored for
	 * the enemy — matching how the hits resolve. {@code null} when no reticle is shown.
	 */
	private AoeFootprint aoeFootprint() {
		if (aoeTarget == null || selectedAttack == null)
			return null;
		Ability ability = selectedAttack.getAbility();
		Ability.TargetSquare[] targetArea = ability.getTargetArea();
		if (targetArea == null)
			return null;
		int xSign = selectedAttacker.getSide() == Side.PLAYER ? -1 : 1;
		// Each target-area square lands a direct hit on its cell (cyan).
		Set<Cell> direct = new HashSet<>();
		for (Ability.TargetSquare square : targetArea)
			direct.add(new Cell(aoeTarget.col() + xSign * square.getX(),
					aoeTarget.row() - square.getY()));
		// The damage area splashes around every target cell; tiles it reaches that
		// are not themselves target cells are partial (splash) hits (yellow).
		Ability.TargetSquare[] damageArea = ability.getDamageArea();
		Set<Cell> splash = new HashSet<>();
		if (damageArea != null)
			for (Cell base : direct)
				for (Ability.TargetSquare square : damageArea) {
					Cell cell = new Cell(base.col() + xSign * square.getX(),
							base.row() - square.getY());
					if (!direct.contains(cell))
						splash.add(cell);
				}
		return new AoeFootprint(direct, splash);
	}

	/** Fills each still-visible cell on the side with the colour. */
	private void fillCells(Graphics2D g, Side side, Set<Cell> cells, Color color) {
		g.setColor(color);
		for (Cell cell : cells)
			if (isCellVisible(side, cell.col(), cell.row()))
				g.fillPolygon(geometry.cellDiamond(side, cell.col(), cell.row()));
	}

	/**
	 * While an attack is selected, stamps the "do not target" circle over every
	 * enemy unit the selected ability cannot hit — one whose unit types match none
	 * of the ability's targetable tags — so the player can see which units the shot
	 * would pass over. Does nothing until an attack is chosen.
	 */
	private void drawUntargetable(Graphics2D g) {
		if (selectedAttacker == null || selectedAttack == null)
			return;
		// Untargetable units only exist when the target-type rule is enforced; with it
		// off every unit can be hit, so nothing is highlighted.
		if (!sim.getRules().isEnforceTargetTypes())
			return;
		BufferedImage circle = doNotTargetCircle();
		if (circle == null || circle.getWidth() <= 0)
			return;
		Ability ability = selectedAttack.getAbility();
		Side targetSide = BattleSimulator.opponentOf(selectedAttacker.getSide());
		// Keep the asset's aspect ratio, sizing it to roughly half a tile's width.
		int w = GridGeometry.HALF_W / 2;
		int h = (int) Math.round((double) w * circle.getHeight() / circle.getWidth());
		for (PlacedUnit unit : sim.placedUnits()) {
			if (unit.getSide() != targetSide || ability.canTarget(unit.getUnit()))
				continue;
			Point2D c = unitDrawCentre(unit);
			int x = (int) Math.round(c.getX() - w / 2.0);
			int y = (int) Math.round(c.getY() - h / 2.0);
			g.drawImage(circle, x, y, w, h, null);
		}
	}

	/**
	 * Stamps the draggable AOE target reticle on its tile, centred and drawn at
	 * {@link #AOE_RETICLE_SCALE} of the asset's native size. Drawn over the units so
	 * it stays visible as it is dragged; the footprint it covers is drawn on the
	 * grid layer (see {@link #drawAoeFootprint}). A no-op when no reticle is shown.
	 */
	private void drawAoeReticle(Graphics2D g) {
		if (aoeTarget == null || selectedAttacker == null)
			return;
		BufferedImage circle = aoeTargetCircle();
		if (circle == null || circle.getWidth() <= 0)
			return;
		Side targetSide = BattleSimulator.opponentOf(selectedAttacker.getSide());
		if (!isCellVisible(targetSide, aoeTarget.col(), aoeTarget.row()))
			return;
		Point2D c = geometry.cellCentre(targetSide, aoeTarget);
		int w = (int) Math.round(circle.getWidth() * AOE_RETICLE_SCALE);
		int h = (int) Math.round(circle.getHeight() * AOE_RETICLE_SCALE);
		int x = (int) Math.round(c.getX() - w / 2.0);
		int y = (int) Math.round(c.getY() - h / 2.0);
		g.drawImage(circle, x, y, w, h, null);
	}

	private void drawHitMarkers(Graphics2D g) {
		for (HitMarker marker : hitMarkers) {
			int elapsed = tick - marker.startTick;
			if (elapsed < 0)
				continue; // hit not landed yet (weapon hit delay)
			// An area-of-effect hit can land on a cell the side has advanced past
			// (or a column trimmed from a narrow row); don't flash a tile that is
			// no longer drawn there.
			if (!isCellVisible(marker.side, marker.cell.col(), marker.cell.row()))
				continue;
			float alpha = Math.max(0f, 1f - (float) elapsed / HIT_FADE);
			if (alpha <= 0f)
				continue;
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
			g2.setColor(HIT_COLOR);
			g2.fillPolygon(geometry.cellDiamond(marker.side,
					marker.cell.col(), marker.cell.row()));
			g2.dispose();
		}
	}

	private void pruneHitMarkers() {
		hitMarkers.removeIf(m -> tick - m.startTick >= HIT_FADE);
	}

	private void pruneDamageNumbers() {
		damageNumbers.removeIf(d -> tick - d.startTick >= DAMAGE_FLOAT_FRAMES);
	}

	/** Draws each ability impact animation at its struck tile's centre. */
	private void drawDamageAnims(Graphics2D g) {
		for (DamageAnim da : damageAnims) {
			Point2D c = geometry.cellCentre(da.side, da.cell);
			// Each animation draws on its own copy: Animation.drawFrame translates
			// the context, which would otherwise shift every later overlay (health
			// bars, damage numbers) and the next animation in this loop.
			Graphics2D g2 = (Graphics2D) g.create();
			drawAnimation(g2, da.animation, tick - da.startTick, c.getX(), c.getY());
			g2.dispose();
		}
	}

	private void pruneDamageAnims() {
		damageAnims.removeIf(da -> tick - da.startTick >= da.animation.getEndFrame());
	}

	private void pruneStatusApplyVisuals() {
		statusApplyVisuals.removeIf(v -> tick - v.startTick >= STATUS_APPLY_FRAMES);
	}

	/** Spawns an attack damage number, drawn as a critical hit when {@code critical}. */
	private int spawnDamageNumber(Side side, Cell cell, int amount, boolean critical) {
		return spawnDamageNumber(side, cell, amount, null, null, critical, false, false, false, false);
	}

	/**
	 * Spawns a floating damage number at a random spot near the tile centre,
	 * optionally with a status icon drawn to its left (for status-effect ticks).
	 * Numbers stacking on the same tile are delayed so they appear one after
	 * another instead of all at once. When {@code sound} is non-null it is queued
	 * to play as the number appears (a status effect's sound when its damage
	 * lands). A {@code critical} number is drawn larger and white with a black
	 * outline. Returns the tick at which the number finishes fading, so callers can
	 * wait for the animation.
	 */
	private int spawnDamageNumber(Side side, Cell cell, int amount, BufferedImage icon,
			String sound, boolean critical) {
		return spawnDamageNumber(side, cell, amount, icon, sound, critical, false, false, false, false);
	}

	/**
	 * As {@link #spawnDamageNumber(Side, Cell, int, BufferedImage, String, boolean)},
	 * but also flags a grazed hit (drawn grey), a dodge (a graze that dealt no
	 * damage, drawn as white "DODGE"), a miss (a hit whose damage rounded down to
	 * nothing, drawn as grey "MISS") or an immune hit (the target's resistance to
	 * the damage type is 0, drawn as grey "IMMUNE").
	 */
	private int spawnDamageNumber(Side side, Cell cell, int amount, BufferedImage icon,
			String sound, boolean critical, boolean grazed, boolean dodge, boolean miss,
			boolean immune) {
		int slot = countDamageNumbersAt(side, cell);
		int start = tick + slot * DAMAGE_STAGGER_FRAMES;
		int dx = random.nextInt(2 * DAMAGE_JITTER_X + 1) - DAMAGE_JITTER_X;
		int dy = random.nextInt(2 * DAMAGE_JITTER_Y + 1) - DAMAGE_JITTER_Y;
		damageNumbers.add(new DamageNumber(side, cell, amount, start, dx, dy, icon,
				critical, grazed, dodge, miss, immune));
		if (sound != null)
			pendingSounds.add(new PendingSound(sound, start));
		return start + DAMAGE_FLOAT_FRAMES;
	}

	/** Eases every unit's health bar toward its real value (~1 second). */
	private void animateHealthBars() {
		for (PlacedUnit unit : sim.placedUnits())
			unit.animateBars(BAR_ANIM_FRAMES);
	}

	/**
	 * Takes a just-killed unit off the board but keeps it on screen to die. The
	 * cell is freed at once so it can no longer be hit or act; the unit then
	 * drains its health bar and plays its death animation (see
	 * {@link #updateDyingUnits}).
	 */
	private void beginDying(PlacedUnit unit) {
		sim.remove(unit);
		dyingUnits.add(unit);
		// If the unit whose info panel is open just died, close it — it can no longer
		// act or be inspected.
		if (unit == selectedAttacker)
			clearSelection();
	}

	/**
	 * Whether no attack sequence is mid-animation — the firing unit has played out
	 * every attack of its ability. True between turns and during the status/advance
	 * phases. A unit's death animation and death-spawn wait for this so a unit killed
	 * early in a multi-hit attack does not die before the barrage finishes.
	 */
	private boolean attacksFinished() {
		return attacksRemaining <= 0 && tick >= attackAnimEndTick;
	}

	/**
	 * Advances each dying unit: first its health bar drains, then (once the bar has
	 * settled and the attacker has finished all its attack animations) it plays its
	 * death animation, and once that finishes the unit is dropped and its
	 * death-spawn unit (if any) takes the tile it leaves behind.
	 */
	private void updateDyingUnits() {
		for (int i = dyingUnits.size() - 1; i >= 0; i--) {
			PlacedUnit unit = dyingUnits.get(i);
			if (!unit.isPlayingDeathAnimation()) {
				// Phase 1: drain the bar. Once it has emptied and the attacker has
				// played out all of its attacks, begin the death animation.
				unit.animateBars(BAR_ANIM_FRAMES);
				if (unit.barsSettled() && attacksFinished())
					unit.startDeathAnimation(loadDeathAnimation(unit), tick);
			} else if (unit.deathAnimationFinished(tick)) {
				// Phase 2 finished: drop the unit and place its death-spawn (if any).
				spawnDeathUnit(unit);
				dyingUnits.remove(i);
			}
		}
	}

	/**
	 * Replaces a unit that has finished dying with its configured death-spawn unit
	 * on the same tile (at full health, ready to act on its side's next turn).
	 * Does nothing when the unit has no death-spawn or its id is unknown.
	 */
	private void spawnDeathUnit(PlacedUnit dying) {
		String spawnId = dying.getUnit().getDeathSpawnedUnit();
		if (spawnId == null)
			return;
		Unit spawn = Unit.get(spawnId);
		if (spawn != null)
			sim.spawnAt(spawn, dying.getSide(), dying.getCell());
	}

	/** Plays any queued sounds whose scheduled tick has arrived. */
	private void playPendingSounds() {
		for (int i = pendingSounds.size() - 1; i >= 0; i--) {
			if (tick >= pendingSounds.get(i).playTick) {
				sim.playSound(pendingSounds.get(i).name);
				pendingSounds.remove(i);
			}
		}
	}

	/** How many live damage numbers currently share the given tile. */
	private int countDamageNumbersAt(Side side, Cell cell) {
		int count = 0;
		for (DamageNumber d : damageNumbers)
			if (d.side == side && d.cell.equals(cell)
					&& tick - d.startTick < DAMAGE_FLOAT_FRAMES)
				count++;
		return count;
	}

	/** Draws each floating damage number, rising from its tile and fading out. */
	private void drawDamageNumbers(Graphics2D g) {
		GameFiles gf = GameFiles.active();
		for (DamageNumber number : damageNumbers) {
			int elapsed = tick - number.startTick;
			if (elapsed < 0 || elapsed >= DAMAGE_FLOAT_FRAMES)
				continue;
			float t = elapsed / (float) DAMAGE_FLOAT_FRAMES;
			Point2D c = geometry.cellCentre(number.side, number.cell);
			String text = number.dodge ? gf.getText("dodgeattack")
					: number.miss ? gf.getText("miss")
					: number.immune ? gf.getText("immune")
					: "-" + number.amount;

			Graphics2D g2 = (Graphics2D) g.create();
			g2.setComposite(AlphaComposite.getInstance(
					AlphaComposite.SRC_OVER, 1f - t));
			float fontSize = number.critical ? CRIT_DAMAGE_FONT_SIZE
					: number.dodge || number.miss || number.immune ? DODGE_FONT_SIZE
					: DAMAGE_FONT_SIZE;
			g2.setFont(g2.getFont().deriveFont(Font.BOLD, fontSize));
			int tw = g2.getFontMetrics().stringWidth(text);
			int x = (int) Math.round(c.getX() - tw / 2.0 + number.dx);
			int y = (int) Math.round(c.getY() + number.dy - t * DAMAGE_RISE);
			// Status-tick numbers carry the effect's icon to the left of the text.
			if (number.icon != null) {
				int iconY = y - STATUS_NUMBER_ICON + 4;
				g2.drawImage(number.icon, x - STATUS_NUMBER_ICON - 2, iconY,
						STATUS_NUMBER_ICON, STATUS_NUMBER_ICON, null);
			}
			if (number.critical || number.dodge || number.miss || number.immune) {
				// A critical hit sits on its banner: the image is centred on the same
				// spot as the number's text, so the number reads on top of it (and
				// fades along with it). Drawn first, behind the text.
				if (number.critical) {
					BufferedImage tab = critTab();
					if (tab != null && tab.getWidth() > 0) {
						int w = (int) Math.round(tab.getWidth() * CRIT_TAB_SCALE);
						int h = (int) Math.round(tab.getHeight() * CRIT_TAB_SCALE);
						int ascent = g2.getFontMetrics().getAscent();
						int descent = g2.getFontMetrics().getDescent();
						int cx = (int) Math.round(x + tw / 2.0 - w / 2.0);
						int cy = (int) Math.round(y - ascent / 2.0 + descent / 2.0 - h / 2.0);
						g2.drawImage(tab, cx, cy, w, h, null);
					}
				}
				// A crit, dodge or miss reads as big text ringed by a solid black
				// outline so it stays legible over any tile.
				g2.setColor(Color.BLACK);
				for (int ox = -CRIT_OUTLINE; ox <= CRIT_OUTLINE; ox++)
					for (int oy = -CRIT_OUTLINE; oy <= CRIT_OUTLINE; oy++)
						if (ox != 0 || oy != 0)
							g2.drawString(text, x + ox, y + oy);
				g2.setColor(number.miss || number.immune ? GRAZE_DAMAGE_COLOR
						: number.dodge ? DODGE_COLOR
						: CRIT_DAMAGE_COLOR);
				g2.drawString(text, x, y);
			} else {
				// Normal hits are red; a graze is grey.
				Color color = number.grazed ? GRAZE_DAMAGE_COLOR : DAMAGE_COLOR;
				g2.setColor(Color.BLACK);
				g2.drawString(text, x + 1, y + 1);
				g2.setColor(color);
				g2.drawString(text, x, y);
			}
			g2.dispose();
		}
	}

	/**
	 * Rolls whether {@code target} grazes a hit from an attacker with the given
	 * offense. The graze chance (percent) is the defender's defense minus the
	 * attacker's offense plus a flat 5, so evenly-matched units graze 5% of hits.
	 */
	private boolean rollGraze(int attackerOffense, PlacedUnit target) {
		double chancePercent = target.getDefense() - attackerOffense + 5;
		return random.nextDouble() * 100 < chancePercent;
	}

	/** Applies each hit's damage when it lands, removing units killed by it. */
	private void applyLandedHits() {
		// One hit sound per enemy struck this frame: several shots landing on the
		// same tile at once would otherwise stack into a single over-loud sound.
		Set<Cell> sounded = new HashSet<>();
		for (HitMarker marker : hitMarkers) {
			if (marker.applied || tick < marker.startTick)
				continue;
			marker.applied = true;
			PlacedUnit target = sim.unitAt(marker.side, marker.cell);
			if (target == null) continue;
			// A unit the ability cannot target takes nothing: no sound, animation,
			// damage or status — the hit simply does not happen to it.
			if (marker.ability != null && sim.getRules().isEnforceTargetTypes()
					&& !marker.ability.canTarget(target.getUnit()))
				continue;
			if (marker.ability != null && sounded.add(marker.cell)) {
				boolean metal = target.getUnit().hasTag(GameFiles.active().getUnitTag("Metal"));
				sim.playSound(marker.ability.getHitSound(metal));
				// Play the ability's impact animation on the struck tile.
				Animation dmgAnim = loadDamageAnimation(marker.side, marker.ability);
				if (dmgAnim != null)
					damageAnims.add(new DamageAnim(marker.side, marker.cell, dmgAnim, tick));
			}
			// The defender's graze roll comes first: a grazed hit cuts the damage to the
			// ability's secondary fraction — or to nothing (a dodge) when that is 0 — and
			// never crits. Only a hit that is not grazed can land its critical multiplier.
			double rawDamage = marker.rawDamage;
			boolean grazed = false, dodge = false, critical = false;
			if (marker.ability != null && rawDamage > 0
					&& rollGraze(marker.attackerOffense, target)) {
				grazed = true;
				rawDamage *= marker.ability.getSecondaryDamageRatio();
				dodge = marker.ability.getSecondaryDamageRatio() <= 0;
			} else if (marker.critical) {
				critical = true;
				rawDamage *= BattleSimulator.CRIT_MULTIPLIER;
			}
			int dealt = target.applyDamage(rawDamage,
					marker.damageType, marker.armorPiercing);
			// Flash the struck unit red and shake it in proportion to the health lost.
			if (dealt > 0)
				target.registerHit(dealt, tick);
			if (dodge)
				spawnDamageNumber(marker.side, marker.cell, 0, null, null, false, true, true, false, false);
			else if (dealt <= 0) {
				// The hit connected but dealt nothing. When the target's resistance to
				// this damage type is exactly 0 it is wholly immune — a grey "IMMUNE";
				// otherwise the damage merely rounded down to nothing, a grey "MISS".
				boolean immune = target.isImmuneToDamageType(marker.damageType);
				spawnDamageNumber(marker.side, marker.cell, 0, null, null,
						false, false, false, !immune, immune);
			} else if (grazed)
				spawnDamageNumber(marker.side, marker.cell, dealt, null, null, false, true, false, false, false);
			else
				spawnDamageNumber(marker.side, marker.cell, dealt, critical);
			if (target.isDead()) {
				// Take it out of the simulation now (so it cannot be hit or act
				// again), but keep drawing it while its death animation plays.
				beginDying(target);
				// Drop any status roll accumulated for this now-dead unit.
				pendingStatus.remove(new BattleSimulator.SideCell(marker.side, marker.cell));
			} else if (marker.ability != null && !dodge) {
				// Record the hit; the ability's status effects are rolled once, after
				// the whole attack finishes (see applyPendingStatusEffects). A dodge
				// avoids the hit entirely, so it applies no status effects.
				accumulateStatus(marker, target, dealt);
			}
		}
	}

	/**
	 * Records one of an attack's hits on a tile so its ability's status effects (and
	 * any distraction-based suppression) can be rolled <em>once</em> for the whole
	 * attack — after every hit has landed — rather than once per hit. The starting
	 * damage accumulates across the attack's hits on the tile, and the chance uses
	 * the strongest area value that struck it.
	 */
	private void accumulateStatus(HitMarker marker, PlacedUnit target, int dealt) {
		if (marker.ability.getStatusEffects().length == 0
				&& !marker.ability.causesDistraction())
			return;
		BattleSimulator.SideCell key = new BattleSimulator.SideCell(marker.side, marker.cell);
		StatusAccumulator acc = pendingStatus.get(key);
		if (acc == null) {
			acc = new StatusAccumulator(marker.side, marker.cell, target, marker.ability);
			pendingStatus.put(key, acc);
		}
		acc.totalDealt += dealt;
		acc.areaValue = Math.max(acc.areaValue, marker.areaValue);
	}

	/**
	 * Once an attack's hits have all landed (at {@link #attackAnimEndTick}), rolls
	 * each struck tile's status effects a single time and applies those that
	 * succeed. Doing this here — rather than as each hit lands — means hitting a
	 * tile several times in one attack no longer multiplies its chance of being
	 * afflicted. Runs before {@link #advanceTurns}, so a successful application can
	 * still hold the turn for its apply animation.
	 */
	private void applyPendingStatusEffects() {
		// An ability's attacks accumulate status rolls across the whole sequence;
		// hold off until the final attack so each effect is rolled once per use.
		if (attacksRemaining > 0)
			return;
		if (pendingStatus.isEmpty() || tick < attackAnimEndTick)
			return;
		for (StatusAccumulator acc : pendingStatus.values())
			rollStatusEffects(acc);
		pendingStatus.clear();
	}

	/**
	 * Rolls each of the ability's status effects against the struck unit and
	 * applies those that succeed. The chance is the effect's base chance scaled
	 * by the tile's damage-area value; the effect's starting damage scales with the
	 * total damage the attack dealt to the unit. A successful application shows the
	 * family's effect icon on the tile once the attack animation has finished. The
	 * attack's distraction is then checked for suppression (see {@link #rollSuppression}).
	 */
	private void rollStatusEffects(StatusAccumulator acc) {
		for (Ability.StatusEffectChance sec : acc.ability.getStatusEffects()) {
			StatusEffect effect = sec.effect();
			if (effect == null)
				continue;
			double chance = sec.chance() * acc.areaValue;
			if (random.nextDouble() < chance)
				applyStatusEffect(acc, effect);
		}
		rollSuppression(acc);
	}

	/**
	 * Suppresses the struck unit when the attack distracts it past its bravery: the
	 * damage dealt to the unit scaled by the ability's distraction, plus its flat
	 * distraction bonus, must exceed the unit's bravery. A non-distracting ability
	 * (or one whose distraction does not clear the unit's bravery) suppresses nothing.
	 */
	private void rollSuppression(StatusAccumulator acc) {
		if (!acc.ability.causesDistraction())
			return;
		double distraction = acc.totalDealt * acc.ability.getDamageDistraction()
				+ acc.ability.getDamageDistractionBonus();
		if (distraction <= acc.target.getBravery())
			return;
		StatusEffect suppression = StatusEffect.get("suppression");
		if (suppression != null)
			applyStatusEffect(acc, suppression);
	}

	/**
	 * Applies one status effect to the accumulated tile's unit, unless the unit is
	 * immune to its family (then nothing happens). On success it shows the family's
	 * "applied" icon on the tile after the attack ends, plays its sound at the same
	 * moment, and holds the turn until that apply animation has finished playing.
	 */
	private void applyStatusEffect(StatusAccumulator acc, StatusEffect effect) {
		if (!acc.target.applyStatusEffect(
				new ActiveStatusEffect(effect, acc.totalDealt, attackAnimEndTick)))
			return;
		StatusEffect.StatusFamily family = effect.getFamily();
		if (family != null) {
			BufferedImage icon = loadIcon(family.getEffectIcon());
			statusApplyVisuals.add(new StatusApplyVisual(
					acc.side, acc.cell, icon, attackAnimEndTick));
			if (family.getSound() != null)
				pendingSounds.add(new PendingSound(family.getSound(), attackAnimEndTick));
			attackEndTick = Math.max(attackEndTick,
					attackAnimEndTick + STATUS_APPLY_FRAMES);
		}
	}

	private void drawUnits(Graphics2D g) {
		// Painter's order: draw back-to-front so nearer units overlap farther.
		// Dying units are no longer in the simulation, so add them in explicitly.
		List<PlacedUnit> units = sim.placedUnits();
		units.addAll(dyingUnits);
		units.sort(Comparator.comparingDouble(u -> unitDrawCentre(u).y));

		for (PlacedUnit unit : units) {
			if (unit == dragging)
				continue; // drawn last, at the cursor
			Point2D c = unitDrawCentre(unit);
			drawUnit(g, unit, c.getX(), c.getY(), 1f);
		}
	}

	/**
	 * Per-unit overlays: the rank badge while placing units, and the health bar
	 * during battle (only when a unit is below full health/armor).
	 */
	private void drawOverlays(Graphics2D g) {
		for (PlacedUnit unit : sim.placedUnits()) {
			Point2D c = unitDrawCentre(unit);
			if (battleMode) {
				if (!unit.isFullHealth())
					drawHealthBar(g, unit, c.getX(), c.getY());
				drawStatusIcons(g, unit, c.getX(), c.getY());
			} else {
				drawRankBadge(g, unit, c.getX(), c.getY());
			}
		}
		// Keep showing dying units' bars as they drain to empty; once the death
		// animation takes over, the bar is dropped.
		if (battleMode)
			for (PlacedUnit unit : dyingUnits) {
				if (unit.isPlayingDeathAnimation())
					continue;
				Point2D c = unitDrawCentre(unit);
				drawHealthBar(g, unit, c.getX(), c.getY());
			}
	}

	private void drawRankBadge(Graphics2D g, PlacedUnit unit,
			double centreX, double centreY) {
		String text = Integer.toString(unit.getRank());
		Font old = g.getFont();
		g.setFont(old.deriveFont(Font.BOLD, 14f));
		int tw = g.getFontMetrics().stringWidth(text);
		int x = (int) Math.round(centreX - tw / 2.0);
		int y = (int) Math.round(centreY + 5);
		g.setColor(new Color(0, 0, 0, 160));
		g.drawString(text, x + 1, y + 1);
		g.setColor(RANK_COLOR);
		g.drawString(text, x, y);
		g.setFont(old);
	}

	private void drawHealthBar(Graphics2D g, PlacedUnit unit,
			double centreX, double centreY) {
		int maxTotal = unit.getMaxHp() + unit.getMaxArmor();
		if (maxTotal <= 0)
			return;
		int barW = 76, barH = 8;
		int x = (int) Math.round(centreX - barW / 2.0);
		// Sit the bar at the bottom of the tile (its lower diamond vertex).
		int y = (int) Math.round(centreY + GridGeometry.HALF_H - barH);

		int hpLen = (int) Math.round(barW * Math.max(0, unit.getDisplayHp()) / maxTotal);
		int armorLen = (int) Math.round(barW * Math.max(0, unit.getDisplayArmor()) / maxTotal);

		g.setColor(new Color(20, 20, 20, 200));
		g.fillRect(x - 1, y - 1, barW + 2, barH + 2);
		g.setColor(HP_COLOR);
		g.fillRect(x, y, hpLen, barH);
		g.setColor(ARMOR_COLOR);
		g.fillRect(x + hpLen, y, armorLen, barH);
		// Black divider between the HP and armor segments.
		if (hpLen > 0 && armorLen > 0) {
			g.setColor(Color.BLACK);
			g.fillRect(x + hpLen, y, 1, barH);
		}
	}

	/**
	 * Draws a unit's active status-effect icons in a row just above where its
	 * health bar sits, the first one flush with the bar's right edge and each
	 * further effect stepping leftwards. A small number on each icon shows how
	 * many turns that effect has left. Does nothing when the unit has no effects.
	 */
	private void drawStatusIcons(Graphics2D g, PlacedUnit unit,
			double centreX, double centreY) {
		List<ActiveStatusEffect> effects = unit.getActiveStatusEffects();
		if (effects.isEmpty())
			return;
		// Mirror drawHealthBar's geometry so the icons line up with the bar's
		// right edge and sit just above it.
		int barW = 76, barH = 8;
		int barX = (int) Math.round(centreX - barW / 2.0);
		int barY = (int) Math.round(centreY + GridGeometry.HALF_H - barH);
		int size = STATUS_BAR_ICON;
		int iconY = barY - size - STATUS_BAR_ICON_MARGIN;
		int rightEdge = barX + barW; // first icon's right edge sits on the bar's
		for (ActiveStatusEffect effect : effects) {
			StatusEffect.StatusFamily family = effect.getEffect().getFamily();
			if (family == null)
				continue;
			BufferedImage icon = loadIcon(family.getUiIcon());
			if (icon == null)
				continue;
			int iconX = rightEdge - size;
			g.drawImage(icon, iconX, iconY, size, size, null);
			drawTurnCount(g, effect.getRemaining(), iconX, iconY, size);
			rightEdge -= size + STATUS_BAR_ICON_GAP;
		}
	}

	/** Draws the remaining-turns number in the lower-right corner of an icon. */
	private void drawTurnCount(Graphics2D g, int turns, int iconX, int iconY, int size) {
		String text = Integer.toString(turns);
		Font old = g.getFont();
		g.setFont(old.deriveFont(Font.BOLD, 14f));
		int tw = g.getFontMetrics().stringWidth(text);
		int tx = iconX + size - tw;
		int ty = iconY + size; // baseline near the icon's bottom edge
		g.setColor(Color.BLACK);
		g.drawString(text, tx + 1, ty + 1);
		g.setColor(Color.WHITE);
		g.drawString(text, tx, ty);
		g.setFont(old);
	}

	private void drawUnit(Graphics2D g, PlacedUnit unit,
			double centreX, double centreY, float alpha) {
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

		// A recently struck unit shakes horizontally, the jitter fading over the next
		// few frames (0 when it isn't shaking, so this is a no-op outside battle).
		centreX += hitShakeOffset(unit);

		if (unit.isPlayingDeathAnimation()) {
			// Once the health bar has drained, the death animation replaces the
			// sprite; with no death animation nothing is drawn (it is off the board).
			drawAnimation(g2, unit.getDeathAnimation(),
					tick - unit.getDeathStartTick(), centreX, centreY);
			g2.dispose();
			return;
		}

		Animation attack = unit.getActiveAttack(tick);
		Animation anim = (attack != null) ? attack : unit.getAnimation();
		int frame = (attack != null) ? (tick - unit.getAttackStartTick()) : tick;

		// The selected unit gets a cyan outline traced around its sprite, drawn
		// beneath it so only the ring sticking out past its edges shows. Hidden while
		// the unit's attack plays out and the enemy acts (see playerFire).
		if (unit == selectedAttacker && !selectHighlightSuppressed && anim != null)
			drawSpriteOutline(g2, anim, frame, centreX, centreY);

		if (!drawAnimation(g2, anim, frame, centreX, centreY))
			drawToken(g2, unit, centreX, centreY);
		else if (battleMode) {
			drawStatusPulse(g2, unit, anim, frame);
			// Red damage flash over the sprite, on top of any status pulse.
			drawHitFlash(g2, unit, anim, frame);
		}
		g2.dispose();
	}

	/**
	 * Traces a half-transparent cyan ring around the sprite of {@code anim}'s frame,
	 * at the same spot {@link #drawAnimation} draws it. The sprite's silhouette is
	 * stamped in cyan offset in eight directions to build a slightly larger copy,
	 * then the sprite's own shape is punched back out so only the edge ring remains
	 * — so a translucent (dragged) sprite cannot let the fill show through its body.
	 * The ring is built off-screen and blitted at half opacity. A no-op when the
	 * frame has no bounds or is too large to be worth outlining.
	 */
	private void drawSpriteOutline(Graphics2D g, Animation anim, int frame,
			double centreX, double centreY) {
		anim.setPosition(centreX, centreY + GridGeometry.HALF_H);
		Rectangle2D.Double bounds = anim.getBounds();
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
			return;
		int r = SELECT_SPRITE_OUTLINE_THICKNESS;
		int margin = r + 2; // room for the offset ring and its antialiased edge
		int ox = (int) Math.floor(bounds.x) - margin;
		int oy = (int) Math.floor(bounds.y) - margin;
		int w = (int) Math.ceil(bounds.width) + margin * 2;
		int h = (int) Math.ceil(bounds.height) + margin * 2;
		if (w <= 0 || h <= 0 || (long) w * h > 4_000_000L)
			return;

		BufferedImage ring = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D rg = ring.createGraphics();
		rg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
		rg.translate(-ox, -oy); // map component coords into the ring image
		// Stamp the cyan silhouette offset in eight directions: their union is a
		// filled copy of the sprite grown by the outline thickness.
		int[][] offsets = {
				{-r, 0}, {r, 0}, {0, -r}, {0, r},
				{-r, -r}, {r, -r}, {-r, r}, {r, r} };
		for (int[] off : offsets) {
			Graphics2D og = (Graphics2D) rg.create();
			og.translate(off[0], off[1]);
			anim.drawFrameTinted(frame, og, SELECT_SPRITE_OUTLINE, 1f);
			og.dispose();
		}
		// Punch the sprite's own shape out of the filled copy, leaving just the ring.
		rg.setComposite(AlphaComposite.DstOut);
		anim.drawFrame(frame, rg);
		rg.dispose();

		Graphics2D g2 = (Graphics2D) g.create();
		g2.setComposite(AlphaComposite.getInstance(
				AlphaComposite.SRC_OVER, SELECT_SPRITE_OUTLINE_ALPHA));
		g2.drawImage(ring, ox, oy, null);
		g2.dispose();
	}

	/**
	 * Draws an animation at its natural size, anchored at the diamond centre
	 * and shifted down half a tile so the unit sits in the middle of the cell.
	 * Returns {@code false} if nothing could be drawn.
	 */
	private boolean drawAnimation(Graphics2D g, Animation anim, int frame,
			double centreX, double centreY) {
		try {
			if (anim == null)
				return false;
			anim.setPosition(centreX, centreY + GridGeometry.HALF_H);
			anim.drawFrame(frame, g);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/** A simple labelled marker used when no animation is available. */
	private void drawToken(Graphics2D g, PlacedUnit unit,
			double centreX, double centreY) {
		Color tint = unit.getSide() == Side.PLAYER ? PLAYER_TINT : ENEMY_TINT;
		int w = GridGeometry.HALF_W;
		int h = GridGeometry.HALF_H;
		int x = (int) Math.round(centreX - w / 2.0);
		int y = (int) Math.round(centreY - h);

		g.setColor(new Color(0, 0, 0, 40));
		g.fillOval((int) centreX - w / 2, (int) centreY - h / 4, w, h / 2);

		g.setColor(tint);
		g.fillRoundRect(x, y, w, h, 10, 10);
		g.setColor(Color.WHITE);
		g.drawRoundRect(x, y, w, h, 10, 10);

		String label = unit.getUnit().getShortName();
		if (label == null)
			label = unit.getUnit().getName();
		if (label != null) {
			int tw = g.getFontMetrics().stringWidth(label);
			int clip = w - 8;
			while (tw > clip && label.length() > 1) {
				label = label.substring(0, label.length() - 1);
				tw = g.getFontMetrics().stringWidth(label + "…");
			}
			g.drawString(label, (int) Math.round(centreX - tw / 2.0),
					(int) Math.round(centreY - h / 2.0 + 4));
		}
	}

	/**
	 * Pulses the afflicted unit's own sprite in its status families' colours,
	 * oscillating by blending a colour overlay masked to the sprite shape over the
	 * just-drawn unit. When the unit carries several afflictions the pulse cycles
	 * through each one's colour, giving each a single beat (a full fade in/out) at
	 * its family's pulse speed before advancing to the next and looping. Colours
	 * swap at the dim trough of each beat so the change isn't abrupt. The pulse only
	 * begins once an effect's apply icon starts playing, and is timed from the first
	 * such moment so it eases up from nothing rather than snapping mid-cycle.
	 * {@code anim}/{@code frame} are the animation and frame already drawn for this
	 * unit, so the tint lines up exactly with the sprite.
	 */
	private void drawStatusPulse(Graphics2D g, PlacedUnit unit, Animation anim, int frame) {
		List<ActiveStatusEffect> effects = unit.getPulseEffects(tick);
		if (effects.isEmpty())
			return;

		// One beat per effect, back to back, looping; each beat lasts its family's
		// pulse speed. Walk the beat timeline to find which effect is pulsing now.
		double[] periods = new double[effects.size()];
		double total = 0;
		int startTick = Integer.MAX_VALUE;
		for (int i = 0; i < effects.size(); i++) {
			double speed = Math.max(0.1, effects.get(i).getEffect().getFamily().getPulseSpeed());
			periods[i] = speed * 1000.0 / FRAME_DELAY;           // frames per beat
			total += periods[i];
			startTick = Math.min(startTick, effects.get(i).getDisplayStartTick());
		}

		double pos = (((tick - startTick) % total) + total) % total;  // frames into the cycle
		int idx = 0;
		while (pos >= periods[idx]) {
			pos -= periods[idx];
			idx++;
		}

		double phase = pos / periods[idx];
		float wave = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * phase));
		float alpha = PULSE_MIN_ALPHA + wave * (PULSE_MAX_ALPHA - PULSE_MIN_ALPHA);

		StatusEffect.StatusFamily family = effects.get(idx).getEffect().getFamily();
		anim.drawFrameTinted(frame, g, parseHexColor(family.getColorHex()), alpha);
	}

	/**
	 * Horizontal shake offset (px) for a unit struck within the last
	 * {@link #HIT_FLASH_FRAMES} frames: a left-right jitter whose amplitude fades to
	 * nothing over that window and scales with the fraction of the unit's health the
	 * hit removed (see {@link PlacedUnit#getHitFraction}). 0 when the unit isn't shaking.
	 */
	private double hitShakeOffset(PlacedUnit unit) {
		int elapsed = tick - unit.getHitTick();
		if (elapsed < 0 || elapsed >= HIT_FLASH_FRAMES)
			return 0;
		double decay = 1.0 - (double) elapsed / HIT_FLASH_FRAMES;
		double amplitude = HIT_SHAKE_MAX * unit.getHitFraction() * decay;
		return amplitude * Math.sin(elapsed * 2.0);
	}

	/**
	 * Tints a just-struck unit's sprite red over its normal frame, fading from
	 * {@link #HIT_FLASH_ALPHA} to nothing across {@link #HIT_FLASH_FRAMES}. Relies on
	 * {@code anim} already being positioned by the preceding sprite draw, exactly as
	 * {@link #drawStatusPulse} does. A no-op when the unit isn't currently flashing.
	 */
	private void drawHitFlash(Graphics2D g, PlacedUnit unit, Animation anim, int frame) {
		int elapsed = tick - unit.getHitTick();
		if (elapsed < 0 || elapsed >= HIT_FLASH_FRAMES)
			return;
		float alpha = HIT_FLASH_ALPHA * (1f - (float) elapsed / HIT_FLASH_FRAMES);
		anim.drawFrameTinted(frame, g, HIT_FLASH_COLOR, alpha);
	}

	/** Draws the "effect applied" icons sinking and fading from afflicted tiles. */
	private void drawStatusApplyVisuals(Graphics2D g) {
		// Effects applied to the same tile at the same moment share a centre, so
		// group them and lay each group's icons out in a row rather than stacking
		// them all on the same spot.
		Map<String, List<StatusApplyVisual>> groups = new LinkedHashMap<>();
		for (StatusApplyVisual visual : statusApplyVisuals) {
			if (visual.icon == null)
				continue;
			int elapsed = tick - visual.startTick;
			if (elapsed < 0 || elapsed >= STATUS_APPLY_FRAMES)
				continue;
			String key = visual.side + "|" + visual.cell + "|" + visual.startTick;
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(visual);
		}

		int size = STATUS_APPLY_ICON;
		for (List<StatusApplyVisual> group : groups.values()) {
			int count = group.size();
			for (int i = 0; i < count; i++) {
				StatusApplyVisual visual = group.get(i);
				float t = (tick - visual.startTick) / (float) STATUS_APPLY_FRAMES;
				Point2D c = geometry.cellCentre(visual.side, visual.cell);
				// Scale the icon to fit within a size-square box while keeping its
				// natural aspect ratio, so non-square icons aren't squashed.
				int iw = visual.icon.getWidth();
				int ih = visual.icon.getHeight();
				double scale = size / (double) Math.max(iw, ih);
				int dw = (int) Math.round(iw * scale);
				int dh = (int) Math.round(ih * scale);
				// Centre the row of icons on the tile: each box is offset from centre
				// by its position relative to the middle of the group, and the icon is
				// centred within its box.
				double offset = (i - (count - 1) / 2.0) * size;
				int x = (int) Math.round(c.getX() + offset - dw / 2.0);
				int y = (int) Math.round(c.getY() - dh / 2.0 + t * STATUS_APPLY_DROP);

				Graphics2D g2 = (Graphics2D) g.create();
				g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f - t));
				g2.drawImage(visual.icon, x, y, dw, dh, null);
				g2.dispose();
			}
		}
	}

	/** Shows a transient message banner across the field for a short time. */
	private void showMessage(String text) {
		message = text;
		messageEndTick = tick + MESSAGE_FRAMES;
		repaint();
	}

	private void drawMessage(Graphics2D g) {
		if (message == null || tick >= messageEndTick)
			return;
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setFont(g2.getFont().deriveFont(Font.BOLD, 18f));
		int tw = g2.getFontMetrics().stringWidth(message);
		int pad = 12;
		int boxW = tw + pad * 2;
		int boxH = g2.getFontMetrics().getHeight() + pad;
		int x = (getWidth() - boxW) / 2;
		int y = 70;
		g2.setColor(new Color(20, 20, 20, 200));
		g2.fillRoundRect(x, y, boxW, boxH, 12, 12);
		int ty = y + (boxH - g2.getFontMetrics().getHeight()) / 2 + g2.getFontMetrics().getAscent();
		g2.setColor(Color.WHITE);
		g2.drawString(message, x + pad, ty);
		g2.dispose();
	}

	/**
	 * Loads a status icon from the bundle, scaled later at draw time. Results are
	 * cached for the battle, including {@code null} for icons that cannot load.
	 */
	private BufferedImage loadIcon(String name) {
		if (name == null)
			return null;
		if (iconCache.containsKey(name))
			return iconCache.get(name);
		BufferedImage img = null;
		File file = GameFiles.active().file(name);
		if (file.isFile()) {
			try {
				img = ImageIO.read(file);
			} catch (IOException e) {
				img = null; // best-effort: a missing icon just isn't drawn
			}
		}
		iconCache.put(name, img);
		return img;
	}

	/**
	 * The bundle's "do not target" circle, loaded once on first use and cached
	 * (including a {@code null} miss, so a missing asset is not re-read each frame).
	 */
	private BufferedImage doNotTargetCircle() {
		if (!doNotTargetCircleLoaded) {
			doNotTargetCircleLoaded = true;
			File file = GameFiles.active().getDoNotTargetCircle();
			if (file != null && file.isFile()) {
				try {
					doNotTargetCircle = ImageIO.read(file);
				} catch (IOException e) {
					doNotTargetCircle = null; // best-effort: a missing asset just isn't drawn
				}
			}
		}
		return doNotTargetCircle;
	}

	/**
	 * The bundle's draggable AOE target reticle, loaded once on first use and cached
	 * (including a {@code null} miss, so a missing asset is not re-read each frame).
	 */
	private BufferedImage aoeTargetCircle() {
		if (!aoeTargetCircleLoaded) {
			aoeTargetCircleLoaded = true;
			File file = GameFiles.active().getAOETargetCircle();
			if (file != null && file.isFile()) {
				try {
					aoeTargetCircle = ImageIO.read(file);
				} catch (IOException e) {
					aoeTargetCircle = null; // best-effort: a missing asset just isn't drawn
				}
			}
		}
		return aoeTargetCircle;
	}

	/**
	 * The bundle's critical-hit banner, loaded once on first use and cached
	 * (including a {@code null} miss, so a missing asset is not re-read each frame).
	 */
	private BufferedImage critTab() {
		if (!critTabLoaded) {
			critTabLoaded = true;
			File file = GameFiles.active().getCritTab();
			if (file != null && file.isFile()) {
				try {
					critTab = ImageIO.read(file);
				} catch (IOException e) {
					critTab = null; // best-effort: a missing asset just isn't drawn
				}
			}
		}
		return critTab;
	}

	/** Parses an {@code #RRGGBB} (or {@code RRGGBB}) colour, white on failure. */
	private static Color parseHexColor(String hex) {
		if (hex == null)
			return Color.WHITE;
		String h = hex.startsWith("#") ? hex.substring(1) : hex;
		try {
			return new Color(Integer.parseInt(h, 16));
		} catch (NumberFormatException e) {
			return Color.WHITE;
		}
	}

	/**
	 * A TARGET-area attack's footprint: its direct-hit (cyan) target cells and the
	 * partial (yellow) splash cells the damage area adds around them.
	 */
	private static final class AoeFootprint {
		final Set<Cell> direct, splash;

		AoeFootprint(Set<Cell> direct, Set<Cell> splash) {
			this.direct = direct;
			this.splash = splash;
		}

		/** Whether either set highlights the cell. */
		boolean covers(Cell cell) {
			return direct.contains(cell) || splash.contains(cell);
		}
	}

	/** A struck tile that flashes red, deals its damage on landing, and fades. */
	private static final class HitMarker {
		final Side side;
		final Cell cell;
		final int startTick;
		final double rawDamage;
		final Ability.DamageType damageType;
		final double armorPiercing;
		/** This tile's damage-area value, which scales status-effect chance. */
		final double areaValue;
		/** Ability whose hit sound plays when this marker lands; may be null. */
		final Ability ability;
		/** Whether the shot that produced this tile rolled a critical hit. */
		final boolean critical;
		/** Attacker's total offense, rolled against the defender's defense for a graze. */
		final int attackerOffense;
		boolean applied;

		HitMarker(Side side, Cell cell, int startTick, double rawDamage,
				Ability.DamageType damageType, double armorPiercing,
				double areaValue, Ability ability, boolean critical, int attackerOffense) {
			this.side = side;
			this.cell = cell;
			this.startTick = startTick;
			this.rawDamage = rawDamage;
			this.damageType = damageType;
			this.armorPiercing = armorPiercing;
			this.areaValue = areaValue;
			this.ability = ability;
			this.critical = critical;
			this.attackerOffense = attackerOffense;
		}
	}

	/**
	 * Accumulates an attack's hits on a single tile so its status effects can be
	 * rolled once, after the attack finishes. Sums the damage dealt (which scales
	 * the effect's starting damage) and keeps the strongest area value that struck
	 * the tile (which scales the chance).
	 */
	private static final class StatusAccumulator {
		final Side side;
		final Cell cell;
		final PlacedUnit target;
		final Ability ability;
		int totalDealt;
		double areaValue;

		StatusAccumulator(Side side, Cell cell, PlacedUnit target, Ability ability) {
			this.side = side;
			this.cell = cell;
			this.target = target;
			this.ability = ability;
		}
	}

	/** A sound queued to play once the tick counter reaches {@link #playTick}. */
	private static final class PendingSound {
		final String name;
		final int playTick;

		PendingSound(String name, int playTick) {
			this.name = name;
			this.playTick = playTick;
		}
	}

	/** An ability's impact animation playing once on a struck tile. */
	private static final class DamageAnim {
		final Side side;
		final Cell cell;
		final Animation animation;
		final int startTick;

		DamageAnim(Side side, Cell cell, Animation animation, int startTick) {
			this.side = side;
			this.cell = cell;
			this.animation = animation;
			this.startTick = startTick;
		}
	}

	/** A floating damage number that rises from a tile and fades out (red, or
	 * larger white-on-black for a critical hit, or grey for a graze, white "DODGE"
	 * for a graze that deals no damage, or grey "MISS" for a hit whose damage
	 * rounded down to nothing). */
	private static final class DamageNumber {
		final Side side;
		final Cell cell;
		final int amount;
		final int startTick;
		/** Random pixel offset from the tile centre. */
		final int dx, dy;
		/** Status icon drawn to the left of the number, or null for plain hits. */
		final BufferedImage icon;
		/** Whether this number is for a critical hit (drawn bigger and white). */
		final boolean critical;
		/** Whether this number is for a grazed hit (drawn grey). */
		final boolean grazed;
		/** Whether this is a dodge — a graze with no damage (drawn as white "DODGE"). */
		final boolean dodge;
		/** Whether this is a miss — a hit whose damage rounded to 0 (grey "MISS"). */
		final boolean miss;
		/** Whether the target is immune to the damage type — resistance 0 (grey "IMMUNE"). */
		final boolean immune;

		DamageNumber(Side side, Cell cell, int amount, int startTick, int dx, int dy,
				BufferedImage icon, boolean critical, boolean grazed, boolean dodge,
				boolean miss, boolean immune) {
			this.side = side;
			this.cell = cell;
			this.amount = amount;
			this.startTick = startTick;
			this.dx = dx;
			this.dy = dy;
			this.icon = icon;
			this.critical = critical;
			this.grazed = grazed;
			this.dodge = dodge;
			this.miss = miss;
			this.immune = immune;
		}
	}

	/** A status-family icon that appears on a tile when an effect is applied,
	 * then sinks and fades over about a second. */
	private static final class StatusApplyVisual {
		final Side side;
		final Cell cell;
		final BufferedImage icon;
		final int startTick;

		StatusApplyVisual(Side side, Cell cell, BufferedImage icon, int startTick) {
			this.side = side;
			this.cell = cell;
			this.icon = icon;
			this.startTick = startTick;
		}
	}
}
