package hero.roland.bnsim.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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
	private static final Color WEAPON_HIGHLIGHT = new Color(0, 220, 255, 120);
	private static final Color SELECT_OUTLINE = new Color(245, 205, 70);
	private static final Color HIT_COLOR = new Color(225, 40, 40);
	private static final Color RANK_COLOR = new Color(0, 220, 255);
	private static final Color HP_COLOR = new Color(70, 210, 60);
	private static final Color ARMOR_COLOR = new Color(0, 200, 255);
	private static final Color DAMAGE_COLOR = new Color(235, 45, 45);

	/** Frames a floating damage number lives, and how far it rises (pixels). */
	private static final int DAMAGE_FLOAT_FRAMES = 40;
	private static final int DAMAGE_RISE = 30;
	/** Point size of a normal damage number, the larger size for a critical hit,
	 * and the size of the "DODGE"/"MISS" indications. */
	private static final float DAMAGE_FONT_SIZE = 32f;
	private static final float CRIT_DAMAGE_FONT_SIZE = 36f;
	private static final float DODGE_FONT_SIZE = 32f;
	/** Critical-hit (and dodge) text is white, outlined in black this many pixels thick. */
	private static final Color CRIT_DAMAGE_COLOR = Color.WHITE;
	private static final int CRIT_OUTLINE = 2;
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
	/** Whether the next battle tracks cooldowns, ammo, reloads and prep time. Set
	 * before the battle starts via {@link #setCombatRulesEnabled} (the UnitMenu toggle). */
	private boolean combatRulesEnabled = true;

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
	private Set<Cell> targetable = new HashSet<>();
	/** Fixed tiles a selected WEAPON attack will strike (cyan highlight). */
	private Set<BattleSimulator.SideCell> weaponAffected = new HashSet<>();
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
				if (battleMode)
					onBattleClick(e.getPoint());
				else if (SwingUtilities.isRightMouseButton(e))
					cycleRank(e.getPoint());
				else
					beginDrag(e.getPoint());
			}

			@Override
			public void mouseDragged(java.awt.event.MouseEvent e) {
				if (!battleMode && dragging != null) {
					dragPoint = e.getPoint();
					repaint();
				}
			}

			@Override
			public void mouseReleased(java.awt.event.MouseEvent e) {
				if (!battleMode && dragging != null) {
					boolean onBoard = sim.moveTo(dragging, e.getPoint());
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
		message = null;
		if (battle)
			for (PlacedUnit unit : sim.placedUnits()) {
				unit.resetHealth();
				unit.startBattle(combatRulesEnabled);
			}
		repaint();
	}

	/**
	 * Sets whether the next battle tracks cooldowns, ammo, reloads and prep time.
	 * Driven by the UnitMenu toggle and read when a battle starts (so changing it
	 * mid-battle takes effect from the following battle).
	 */
	public void setCombatRulesEnabled(boolean enabled) {
		this.combatRulesEnabled = enabled;
	}

	/** Listener notified when the selected attacker changes (null = cleared). */
	public void setAttackerSelectedListener(Consumer<PlacedUnit> listener) {
		this.attackerSelectedListener = listener;
	}

	/**
	 * Battle mode: when enabled, enemy units may be selected to inspect their
	 * health, abilities and target area (view-only — they cannot be made to act).
	 * Disabling it clears any enemy unit that is currently selected.
	 */
	public void setEnemyViewEnabled(boolean enabled) {
		this.enemyViewEnabled = enabled;
		// Only one side is selectable at a time; deselect a unit on the side that
		// can no longer be picked (the player when enabling, the enemy when not).
		Side selectableSide = enabled ? Side.ENEMY : Side.PLAYER;
		if (selectedAttacker != null && selectedAttacker.getSide() != selectableSide)
			clearSelection();
		repaint();
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

	/**
	 * Sets the attack to aim with, recomputing the highlighted tiles. A normal
	 * attack highlights its aimable enemy tiles in blue; a WEAPON (fixed) attack
	 * highlights only the fixed tiles it strikes (from the unit's position) in cyan.
	 */
	public void setSelectedAttack(Unit.Attack attack) {
		this.selectedAttack = attack;
		targetable = new HashSet<>();
		weaponAffected = new HashSet<>();
		if (selectedAttacker != null && attack != null) {
			if (attack.getAbility().getTargetType() == Ability.TargetType.WEAPON)
				weaponAffected = sim.weaponAffectedCells(selectedAttacker, attack);
			else
				targetable = sim.targetableCells(selectedAttacker, attack);
		}
		repaint();
	}

	// --- Interaction -------------------------------------------------------

	private void beginDrag(Point p) {
		dragging = sim.pick(p);
		dragPoint = p;
		// Picking up a unit also selects it, showing its info panel and target
		// area (view-only in setup); clicking empty space clears the selection.
		if (dragging != null)
			selectAttacker(dragging);
		else
			clearSelection();
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
					tryFire(target);
					return;
				}
			}
		}
		// Otherwise (re)select a unit, or clear the selection. Only one side is
		// selectable: enemy units (view-only) while enemy viewing is enabled,
		// the player's own units otherwise.
		Side selectableSide = enemyViewEnabled ? Side.ENEMY : Side.PLAYER;
		PlacedUnit clicked = sim.pick(p);
		if (clicked != null && clicked.getSide() == selectableSide)
			selectAttacker(clicked);
		else
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
		selectedAttack = null;
		targetable = new HashSet<>();
		weaponAffected = new HashSet<>();
		if (attackerSelectedListener != null)
			attackerSelectedListener.accept(unit);
		repaint();
	}

	private void clearSelection() {
		selectedAttacker = null;
		selectedAttack = null;
		targetable = new HashSet<>();
		weaponAffected = new HashSet<>();
		if (attackerSelectedListener != null)
			attackerSelectedListener.accept(null);
		repaint();
	}

	private void playerFire(Cell aim) {
		PlacedUnit attacker = selectedAttacker;
		Unit.Attack attack = selectedAttack;
		clearSelection();              // disable further selection/attacks
		executeAttack(attacker, attack, aim);
		phase = Phase.PLAYER_FIRING;   // wait for the animation to finish
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
			if (turnVisualsSettled())
				phase = Phase.PLAYER;
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
	}

	/** Begins the turn that follows {@code actingSide}'s. */
	private void nextTurnAfter(Side actingSide) {
		if (actingSide == Side.PLAYER)
			beginEnemyTurn();
		else
			beginPlayerTurn();
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
		// No gap to close (front row occupied, or the side has no units left): just
		// hand off to the next turn without sliding.
		if (!sim.isFrontRowEmpty(side) || !sim.hasUnits(side)) {
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
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
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

		drawGrid(g2, Side.ENEMY);
		drawGrid(g2, Side.PLAYER);

		// A selected unit shows its target area and outline in either mode (no-op
		// when nothing is selected); setup mode also shows the drag-drop highlight.
		drawTargetable(g2);
		drawSelection(g2);
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
		g.setColor(TARGET_HIGHLIGHT);
		// Skip tiles a side has advanced past — those tiles no longer exist.
		for (Cell cell : targetable)
			if (isCellVisible(targetSide, cell.col(), cell.row()))
				g.fillPolygon(geometry.cellDiamond(targetSide, cell.col(), cell.row()));
		// A WEAPON (fixed) attack highlights only the fixed tiles it strikes, cyan.
		g.setColor(WEAPON_HIGHLIGHT);
		for (BattleSimulator.SideCell sc : weaponAffected)
			if (isCellVisible(sc.side(), sc.cell().col(), sc.cell().row()))
				g.fillPolygon(geometry.cellDiamond(sc.side(), sc.cell().col(), sc.cell().row()));
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

	private void drawSelection(Graphics2D g) {
		if (selectedAttacker == null)
			return;
		Cell cell = selectedAttacker.getCell();
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(3f));
		g.setColor(SELECT_OUTLINE);
		g.drawPolygon(geometry.cellDiamond(selectedAttacker.getSide(), cell.col(), cell.row()));
		g.setStroke(old);
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
		return spawnDamageNumber(side, cell, amount, null, null, critical, false, false, false);
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
		return spawnDamageNumber(side, cell, amount, icon, sound, critical, false, false, false);
	}

	/**
	 * As {@link #spawnDamageNumber(Side, Cell, int, BufferedImage, String, boolean)},
	 * but also flags a grazed hit (drawn grey), a dodge (a graze that dealt no
	 * damage, drawn as white "DODGE") or a miss (a hit whose damage rounded down to
	 * nothing, drawn as grey "MISS").
	 */
	private int spawnDamageNumber(Side side, Cell cell, int amount, BufferedImage icon,
			String sound, boolean critical, boolean grazed, boolean dodge, boolean miss) {
		int slot = countDamageNumbersAt(side, cell);
		int start = tick + slot * DAMAGE_STAGGER_FRAMES;
		int dx = random.nextInt(2 * DAMAGE_JITTER_X + 1) - DAMAGE_JITTER_X;
		int dy = random.nextInt(2 * DAMAGE_JITTER_Y + 1) - DAMAGE_JITTER_Y;
		damageNumbers.add(new DamageNumber(side, cell, amount, start, dx, dy, icon,
				critical, grazed, dodge, miss));
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
		for (DamageNumber number : damageNumbers) {
			int elapsed = tick - number.startTick;
			if (elapsed < 0 || elapsed >= DAMAGE_FLOAT_FRAMES)
				continue;
			float t = elapsed / (float) DAMAGE_FLOAT_FRAMES;
			Point2D c = geometry.cellCentre(number.side, number.cell);
			String text = number.dodge ? "DODGE"
					: number.miss ? "MISS"
					: "-" + number.amount;

			Graphics2D g2 = (Graphics2D) g.create();
			g2.setComposite(AlphaComposite.getInstance(
					AlphaComposite.SRC_OVER, 1f - t));
			float fontSize = number.critical ? CRIT_DAMAGE_FONT_SIZE
					: number.dodge || number.miss ? DODGE_FONT_SIZE
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
			if (number.critical || number.dodge || number.miss) {
				// A crit, dodge or miss reads as big text ringed by a solid black
				// outline so it stays legible over any tile.
				g2.setColor(Color.BLACK);
				for (int ox = -CRIT_OUTLINE; ox <= CRIT_OUTLINE; ox++)
					for (int oy = -CRIT_OUTLINE; oy <= CRIT_OUTLINE; oy++)
						if (ox != 0 || oy != 0)
							g2.drawString(text, x + ox, y + oy);
				g2.setColor(number.miss ? GRAZE_DAMAGE_COLOR
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
			if (marker.ability != null && !marker.ability.canTarget(target.getUnit()))
				continue;
			if (marker.ability != null && sounded.add(marker.cell)) {
				boolean metal = target.getUnit().hasTag(Unit.UnitTag.METAL);
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
			if (dodge)
				spawnDamageNumber(marker.side, marker.cell, 0, null, null, false, true, true, false);
			else if (dealt <= 0)
				// The hit connected but its damage rounded down to nothing: a grey "MISS".
				spawnDamageNumber(marker.side, marker.cell, 0, null, null, false, false, false, true);
			else if (grazed)
				spawnDamageNumber(marker.side, marker.cell, dealt, null, null, false, true, false, false);
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

		if (!drawAnimation(g2, anim, frame, centreX, centreY))
			drawToken(g2, unit, centreX, centreY);
		else if (battleMode)
			drawStatusPulse(g2, unit, anim, frame);
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
	 * Pulses the afflicted unit's own sprite in its status family's colour,
	 * oscillating at the family's pulse speed by blending a colour overlay masked
	 * to the sprite shape over the just-drawn unit. The pulse only begins once the
	 * effect's apply icon starts playing, and is timed from that moment so it eases
	 * up from nothing rather than snapping mid-cycle. {@code anim}/{@code frame} are
	 * the animation and frame already drawn for this unit, so the tint lines up
	 * exactly with the sprite.
	 */
	private void drawStatusPulse(Graphics2D g, PlacedUnit unit, Animation anim, int frame) {
		ActiveStatusEffect effect = unit.getPulseEffect(tick);
		if (effect == null)
			return;
		StatusEffect.StatusFamily family = effect.getEffect().getFamily();
		double speed = Math.max(0.1, family.getPulseSpeed());     // seconds per pulse
		double period = speed * 1000.0 / FRAME_DELAY;             // frames per pulse
		double elapsed = tick - effect.getDisplayStartTick();
		double phase = (elapsed % period) / period;
		float wave = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * phase));
		float alpha = PULSE_MIN_ALPHA + wave * (PULSE_MAX_ALPHA - PULSE_MIN_ALPHA);

		anim.drawFrameTinted(frame, g, parseHexColor(family.getColorHex()), alpha);
	}

	/** Draws the "effect applied" icons sinking and fading from afflicted tiles. */
	private void drawStatusApplyVisuals(Graphics2D g) {
		for (StatusApplyVisual visual : statusApplyVisuals) {
			if (visual.icon == null)
				continue;
			int elapsed = tick - visual.startTick;
			if (elapsed < 0 || elapsed >= STATUS_APPLY_FRAMES)
				continue;
			float t = elapsed / (float) STATUS_APPLY_FRAMES;
			Point2D c = geometry.cellCentre(visual.side, visual.cell);
			int size = STATUS_APPLY_ICON;
			int x = (int) Math.round(c.getX() - size / 2.0);
			int y = (int) Math.round(c.getY() - size / 2.0 + t * STATUS_APPLY_DROP);

			Graphics2D g2 = (Graphics2D) g.create();
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f - t));
			g2.drawImage(visual.icon, x, y, size, size, null);
			g2.dispose();
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

		DamageNumber(Side side, Cell cell, int amount, int startTick, int dx, int dy,
				BufferedImage icon, boolean critical, boolean grazed, boolean dodge,
				boolean miss) {
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
