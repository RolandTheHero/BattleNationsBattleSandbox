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

import hero.roland.bnsim.Ability;
import hero.roland.bnsim.Animation;
import hero.roland.bnsim.BattleSimulator;
import hero.roland.bnsim.Cell;
import hero.roland.bnsim.EnemyBehavior;
import hero.roland.bnsim.GameFiles;
import hero.roland.bnsim.GridGeometry;
import hero.roland.bnsim.PlacedUnit;
import hero.roland.bnsim.RandomEnemyBehavior;
import hero.roland.bnsim.Side;
import hero.roland.bnsim.StatusEffect;
import hero.roland.bnsim.ActiveStatusEffect;
import hero.roland.bnsim.Unit;

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
	private static final int DAMAGE_FLOAT_FRAMES = 24;
	private static final int DAMAGE_RISE = 42;
	/** Frames between successive numbers on one tile, and random spread (px). */
	private static final int DAMAGE_STAGGER_FRAMES = 8;
	private static final int DAMAGE_JITTER_X = 26;
	private static final int DAMAGE_JITTER_Y = 12;
	/** Size (px) of the status icon drawn beside a status-damage number. */
	private static final int STATUS_NUMBER_ICON = 22;

	/** How far the "effect applied" icon sinks while fading. */
	private static final int STATUS_APPLY_DROP = 24;
	/** Largest dimension (px) the "effect applied" icon is drawn at. */
	private static final int STATUS_APPLY_ICON = 48;

	/** Pulsing affliction tint: alpha swings between these around the unit. */
	private static final float PULSE_MIN_ALPHA = 0.10f;
	private static final float PULSE_MAX_ALPHA = 0.42f;

	/** Milliseconds between animation frames (~20 fps). */
	private static final int FRAME_DELAY = 50;
	/** Frames a struck tile stays red before fully fading out. */
	private static final int HIT_FADE = 26;
	/** Frames for a health bar to finish draining (~1 second). */
	private static final int BAR_ANIM_FRAMES = 1000 / FRAME_DELAY;
	/** Frames the "effect applied" icon shows for (~1 second). */
	private static final int STATUS_APPLY_FRAMES = 1000 / FRAME_DELAY;
	/** Frames an on-field message stays up (~2.5 seconds). */
	private static final int MESSAGE_FRAMES = 2500 / FRAME_DELAY;

	private final BattleSimulator sim;
	private final GridGeometry geometry;
	private final Timer animationTimer;

	private int tick;
	private boolean battleMode;

	/** Background image scaled to fill the component; null falls back to a colour. */
	private BufferedImage background;

	/**
	 * Turn state. Each side's turn begins with a status-effect step (its effects
	 * deal damage and the turn waits for that animation), then the action: the
	 * enemy fires, or the player regains control.
	 */
	private enum Phase {
		PLAYER, PLAYER_FIRING, ENEMY_TURN_STATUS, ENEMY_FIRING, PLAYER_TURN_STATUS
	}

	private Phase phase = Phase.PLAYER;
	/** Tick at which the current phase's animation finishes and the turn advances. */
	private int attackEndTick;
	/** Tick at which the attack animation and its hits finish; status-apply icons
	 * start here, and the turn waits past it for them (via {@link #attackEndTick}). */
	private int attackAnimEndTick;
	private final EnemyBehavior enemyBehavior = new RandomEnemyBehavior();

	// Setup-mode drag state.
	private PlacedUnit dragging;
	private Point dragPoint;

	// Battle-mode selection state.
	private PlacedUnit selectedAttacker;
	private Unit.Attack selectedAttack;
	private Set<Cell> targetable = new HashSet<>();
	/** Fixed tiles a selected WEAPON attack will strike (cyan highlight). */
	private Set<BattleSimulator.SideCell> weaponAffected = new HashSet<>();
	private Consumer<PlacedUnit> attackerSelectedListener;

	private final List<HitMarker> hitMarkers = new ArrayList<>();
	private final List<DamageNumber> damageNumbers = new ArrayList<>();
	/** Units removed from the simulation but still on screen while their bar drains. */
	private final List<PlacedUnit> dyingUnits = new ArrayList<>();
	/** Sounds queued to play at a future tick (e.g. a weapon's delayed fire sound). */
	private final List<PendingSound> pendingSounds = new ArrayList<>();
	/** "Effect applied" icons floating down from an afflicted tile. */
	private final List<StatusApplyVisual> statusApplyVisuals = new ArrayList<>();
	/** Cache of loaded status icons (effect/ui icons), including null misses. */
	private final Map<String, BufferedImage> iconCache = new HashMap<>();
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
			animateHealthBars();
			updateDyingUnits();
			pruneHitMarkers();
			pruneDamageNumbers();
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
					sim.moveTo(dragging, e.getPoint());
					dragging = null;
					dragPoint = null;
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
		repaint();
	}

	/** Switches between setup mode (drag to place) and battle mode (attack). */
	public void setBattleMode(boolean battle) {
		this.battleMode = battle;
		phase = Phase.PLAYER;
		dragging = null;
		dragPoint = null;
		clearSelection();
		hitMarkers.clear();
		dyingUnits.clear();
		pendingSounds.clear();
		damageNumbers.clear();
		statusApplyVisuals.clear();
		message = null;
		if (battle)
			for (PlacedUnit unit : sim.placedUnits())
				unit.resetHealth();
		repaint();
	}

	/** Listener notified when the selected attacker changes (null = cleared). */
	public void setAttackerSelectedListener(Consumer<PlacedUnit> listener) {
		this.attackerSelectedListener = listener;
	}

	/**
	 * Sets the battlefield background to the named bundle image, scaled to fill
	 * the component. A null or unreadable file falls back to a plain background.
	 */
	public void setBackgroundImage(String name) {
		background = null;
		if (name != null) {
			File file = GameFiles.file(name);
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
		repaint();
	}

	/** Right-click during setup: cycle the unit's rank up to its maximum. */
	private void cycleRank(Point p) {
		PlacedUnit unit = sim.pick(p);
		if (unit != null) {
			unit.cycleRank();
			repaint();
		}
	}

	private void onBattleClick(Point p) {
		// Input is only accepted during the player's turn (not while an attack
		// animation is playing or during the enemy's turn).
		if (phase != Phase.PLAYER)
			return;
		// Firing at a highlighted tile takes priority.
		if (selectedAttacker != null && selectedAttack != null) {
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
		// Otherwise (re)select a player unit, or clear the selection.
		PlacedUnit clicked = sim.pick(p);
		if (clicked != null && clicked.getSide() == Side.PLAYER)
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
	 * Skips the player's turn without attacking, handing control straight to the
	 * enemy. Does nothing outside the player's turn (e.g. while an attack is
	 * animating or during the enemy's turn).
	 */
	public void passTurn() {
		if (!battleMode || phase != Phase.PLAYER)
			return;
		clearSelection();
		beginEnemyTurn();
	}

	/**
	 * Plays an attack: starts the attacker's attack animation, schedules the
	 * struck tiles, and records when the turn may advance.
	 */
	private void executeAttack(PlacedUnit attacker, Unit.Attack attack, Cell aim) {
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
					hit.areaValue(), ability));
			lastHit = Math.max(lastHit, start - tick);
		}

		int animFrames = (anim != null) ? anim.getEndFrame() : 0;
		// The attack animation and last landed tile finish here; status-apply
		// icons start from this tick.
		attackAnimEndTick = tick + Math.max(1, Math.max(animFrames, lastHit + 4));
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

	/** Drives the turn state machine once per frame while in battle mode. */
	private void advanceTurns() {
		if (!battleMode || tick < attackEndTick)
			return;
		switch (phase) {
		case PLAYER_FIRING:
			beginEnemyTurn();
			break;
		case ENEMY_TURN_STATUS:
			enemyAct();
			break;
		case ENEMY_FIRING:
			beginPlayerTurn();
			break;
		case PLAYER_TURN_STATUS:
			phase = Phase.PLAYER; // status damage finished animating; hand control back
			break;
		default:
			break;
		}
	}

	/**
	 * Begins the enemy's turn by evaluating its status effects. The enemy only
	 * acts once their status-damage animation has finished playing.
	 */
	private void beginEnemyTurn() {
		int frames = tickStatusEffects(Side.ENEMY);
		phase = Phase.ENEMY_TURN_STATUS;
		attackEndTick = tick + frames; // wait for the status-damage animation
	}

	/** The enemy chooses and plays its attack, after its status effects ticked. */
	private void enemyAct() {
		EnemyBehavior.Move move = enemyBehavior.decideMove(sim);
		if (move == null) {
			beginPlayerTurn(); // enemy has no legal move; skip to the player's turn
			return;
		}
		executeAttack(move.attacker(), move.attack(), move.target());
		phase = Phase.ENEMY_FIRING;
	}

	/**
	 * Begins the player's turn by evaluating their status effects. The player
	 * only regains control once their status-damage animation has finished.
	 */
	private void beginPlayerTurn() {
		int frames = tickStatusEffects(Side.PLAYER);
		phase = Phase.PLAYER_TURN_STATUS;
		attackEndTick = tick + frames; // wait for the status-damage animation
	}

	/**
	 * Evaluates start-of-turn status effects for every unit on the given side:
	 * each effect deals its damage (shown as a floating number with the effect's
	 * icon), ages by a turn, and is removed when it expires. Units killed by an
	 * effect are taken out of the simulation and left to drain on screen. Returns
	 * the number of frames the turn should wait for the damage animation to finish
	 * (0 when no effect dealt damage).
	 */
	private int tickStatusEffects(Side side) {
		int endTick = tick;
		for (PlacedUnit unit : sim.placedUnits()) {
			if (unit.getSide() != side)
				continue;
			for (PlacedUnit.StatusTick st : unit.tickStatusEffects()) {
				StatusEffect.StatusFamily family = st.effect().getFamily();
				BufferedImage icon = family != null ? loadIcon(family.getUiIcon()) : null;
				endTick = Math.max(endTick,
						spawnDamageNumber(side, unit.getCell(), st.damageDealt(), icon));
			}
			if (unit.isDead()) {
				sim.remove(unit);
				dyingUnits.add(unit);
			}
		}
		return endTick - tick;
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
		drawSideLabels(g2);

		if (battleMode) {
			drawTargetable(g2);
			drawSelection(g2);
		} else if (dragging != null && dragPoint != null) {
			Cell target = geometry.cellAt(dragging.getSide(), dragPoint);
			if (target != null) {
				g2.setColor(DROP_HIGHLIGHT);
				g2.fillPolygon(geometry.cellDiamond(dragging.getSide(),
						target.col(), target.row()));
			}
		}

		drawUnits(g2);
		if (battleMode)
			drawStatusPulses(g2);
		drawHitMarkers(g2);
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
		for (int col = 0; col < GridGeometry.COLS; col++)
			for (int row = 0; row < GridGeometry.ROWS; row++)
				if (GridGeometry.isValid(col, row))
					g.drawPolygon(geometry.cellDiamond(side, col, row));
	}

	private void drawSideLabels(Graphics2D g) {
		int backRow = GridGeometry.ROWS - 1;
		int midCol = GridGeometry.COLS / 2;
		Point2D enemy = geometry.cellCentre(Side.ENEMY, midCol, backRow);
		Point2D player = geometry.cellCentre(Side.PLAYER, midCol, backRow);
		g.setColor(ENEMY_TINT);
		g.drawString("Enemy", (int) enemy.getX() - 16,
				(int) enemy.getY() - GridGeometry.HALF_H - 8);
		g.setColor(PLAYER_TINT);
		g.drawString("Player", (int) player.getX() - 16,
				(int) player.getY() + GridGeometry.HALF_H + 18);
	}

	private void drawTargetable(Graphics2D g) {
		g.setColor(TARGET_HIGHLIGHT);
		for (Cell cell : targetable)
			g.fillPolygon(geometry.cellDiamond(Side.ENEMY, cell.col(), cell.row()));
		// A WEAPON (fixed) attack highlights only the fixed tiles it strikes, cyan.
		g.setColor(WEAPON_HIGHLIGHT);
		for (BattleSimulator.SideCell sc : weaponAffected)
			g.fillPolygon(geometry.cellDiamond(sc.side(), sc.cell().col(), sc.cell().row()));
	}

	private void drawSelection(Graphics2D g) {
		if (selectedAttacker == null)
			return;
		Cell cell = selectedAttacker.getCell();
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(3f));
		g.setColor(SELECT_OUTLINE);
		g.drawPolygon(geometry.cellDiamond(Side.PLAYER, cell.col(), cell.row()));
		g.setStroke(old);
	}

	private void drawHitMarkers(Graphics2D g) {
		for (HitMarker marker : hitMarkers) {
			int elapsed = tick - marker.startTick;
			if (elapsed < 0)
				continue; // hit not landed yet (weapon hit delay)
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

	private void pruneStatusApplyVisuals() {
		statusApplyVisuals.removeIf(v -> tick - v.startTick >= STATUS_APPLY_FRAMES);
	}

	/** Spawns a plain (attack) damage number with no status icon. */
	private int spawnDamageNumber(Side side, Cell cell, int amount) {
		return spawnDamageNumber(side, cell, amount, null);
	}

	/**
	 * Spawns a floating damage number at a random spot near the tile centre,
	 * optionally with a status icon drawn to its left (for status-effect ticks).
	 * Numbers stacking on the same tile are delayed so they appear one after
	 * another instead of all at once. Returns the tick at which the number
	 * finishes fading, so callers can wait for the animation.
	 */
	private int spawnDamageNumber(Side side, Cell cell, int amount, BufferedImage icon) {
		int slot = countDamageNumbersAt(side, cell);
		int start = tick + slot * DAMAGE_STAGGER_FRAMES;
		int dx = random.nextInt(2 * DAMAGE_JITTER_X + 1) - DAMAGE_JITTER_X;
		int dy = random.nextInt(2 * DAMAGE_JITTER_Y + 1) - DAMAGE_JITTER_Y;
		damageNumbers.add(new DamageNumber(side, cell, amount, start, dx, dy, icon));
		return start + DAMAGE_FLOAT_FRAMES;
	}

	/** Eases every unit's health bar toward its real value (~1 second). */
	private void animateHealthBars() {
		for (PlacedUnit unit : sim.placedUnits())
			unit.animateBars(BAR_ANIM_FRAMES);
	}

	/** Drains dying units' bars, dropping each once its drain has finished. */
	private void updateDyingUnits() {
		for (PlacedUnit unit : dyingUnits)
			unit.animateBars(BAR_ANIM_FRAMES);
		dyingUnits.removeIf(PlacedUnit::barsSettled);
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
			String text = "-" + number.amount;

			Graphics2D g2 = (Graphics2D) g.create();
			g2.setComposite(AlphaComposite.getInstance(
					AlphaComposite.SRC_OVER, 1f - t));
			g2.setFont(g2.getFont().deriveFont(Font.BOLD, 26f));
			int tw = g2.getFontMetrics().stringWidth(text);
			int x = (int) Math.round(c.getX() - tw / 2.0 + number.dx);
			int y = (int) Math.round(c.getY() + number.dy - t * DAMAGE_RISE);
			// Status-tick numbers carry the effect's icon to the left of the text.
			if (number.icon != null) {
				int iconY = y - STATUS_NUMBER_ICON + 4;
				g2.drawImage(number.icon, x - STATUS_NUMBER_ICON - 2, iconY,
						STATUS_NUMBER_ICON, STATUS_NUMBER_ICON, null);
			}
			g2.setColor(Color.BLACK);
			g2.drawString(text, x + 1, y + 1);
			g2.setColor(DAMAGE_COLOR);
			g2.drawString(text, x, y);
			g2.dispose();
		}
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
			if (marker.ability != null && sounded.add(marker.cell)) {
				boolean metal = target.getUnit().hasTag(Unit.UnitTag.METAL);
				sim.playSound(marker.ability.getHitSound(metal));
			}
			int dealt = target.applyDamage(marker.rawDamage,
					marker.damageType, marker.armorPiercing);
			if (dealt > 0)
				spawnDamageNumber(marker.side, marker.cell, dealt);
			if (target.isDead()) {
				// Take it out of the simulation now (so it cannot be hit or act
				// again), but keep drawing it until its health bar finishes draining.
				sim.remove(target);
				dyingUnits.add(target);
			} else if (marker.ability != null) {
				// A surviving target may be afflicted with the ability's effects.
				applyStatusEffects(marker, target, dealt);
			}
		}
	}

	/**
	 * Rolls each of the ability's status effects against the struck unit and
	 * applies those that succeed. The chance is the effect's base chance scaled
	 * by this tile's damage-area value; the effect's starting damage scales with
	 * the damage this hit dealt. A successful application shows the family's
	 * effect icon on the tile once the attack animation has finished.
	 */
	private void applyStatusEffects(HitMarker marker, PlacedUnit target, int dealt) {
		for (Ability.StatusEffectChance sec : marker.ability.getStatusEffects()) {
			StatusEffect effect = sec.effect();
			if (effect == null)
				continue;
			double chance = sec.chance() * marker.areaValue;
			if (random.nextDouble() >= chance)
				continue;
			target.applyStatusEffect(new ActiveStatusEffect(effect, dealt, attackAnimEndTick));

			// Show the family's "applied" icon on the tile after the attack ends,
			// play its sound at the same moment, and hold the turn until that
			// apply animation has finished playing.
			StatusEffect.StatusFamily family = effect.getFamily();
			if (family != null) {
				BufferedImage icon = loadIcon(family.getEffectIcon());
				statusApplyVisuals.add(new StatusApplyVisual(
						marker.side, marker.cell, icon, attackAnimEndTick));
				if (family.getSound() != null)
					pendingSounds.add(new PendingSound(family.getSound(), attackAnimEndTick));
				attackEndTick = Math.max(attackEndTick,
						attackAnimEndTick + STATUS_APPLY_FRAMES);
			}
		}
	}

	private void drawUnits(Graphics2D g) {
		// Painter's order: draw back-to-front so nearer units overlap farther.
		// Dying units are no longer in the simulation, so add them in explicitly.
		List<PlacedUnit> units = sim.placedUnits();
		units.addAll(dyingUnits);
		units.sort(Comparator.comparingDouble(u ->
				geometry.cellCentre(u.getSide(), u.getCell()).y));

		for (PlacedUnit unit : units) {
			if (unit == dragging)
				continue; // drawn last, at the cursor
			Point2D c = geometry.cellCentre(unit.getSide(), unit.getCell());
			drawUnit(g, unit, c.getX(), c.getY(), 1f);
		}
	}

	/**
	 * Per-unit overlays: the rank badge while placing units, and the health bar
	 * during battle (only when a unit is below full health/armor).
	 */
	private void drawOverlays(Graphics2D g) {
		for (PlacedUnit unit : sim.placedUnits()) {
			Point2D c = geometry.cellCentre(unit.getSide(), unit.getCell());
			if (battleMode) {
				if (!unit.isFullHealth())
					drawHealthBar(g, unit, c.getX(), c.getY());
			} else {
				drawRankBadge(g, unit, c.getX(), c.getY());
			}
		}
		// Keep showing dying units' bars as they drain to empty.
		if (battleMode)
			for (PlacedUnit unit : dyingUnits) {
				Point2D c = geometry.cellCentre(unit.getSide(), unit.getCell());
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
		int barW = 60, barH = 6;
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

	private void drawUnit(Graphics2D g, PlacedUnit unit,
			double centreX, double centreY, float alpha) {
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

		Animation attack = unit.getActiveAttack(tick);
		Animation anim = (attack != null) ? attack : unit.getAnimation();
		int frame = (attack != null) ? (tick - unit.getAttackStartTick()) : tick;

		if (!drawAnimation(g2, anim, frame, centreX, centreY))
			drawToken(g2, unit, centreX, centreY);
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
	 * Pulses a translucent tint over each afflicted unit's tile in its status
	 * family's colour, oscillating at the family's pulse speed. The pulse only
	 * begins once the effect's apply icon starts playing, and is timed from that
	 * moment so it eases up from nothing rather than snapping mid-cycle.
	 */
	private void drawStatusPulses(Graphics2D g) {
		for (PlacedUnit unit : sim.placedUnits()) {
			ActiveStatusEffect effect = unit.getPulseEffect(tick);
			if (effect == null)
				continue;
			StatusEffect.StatusFamily family = effect.getEffect().getFamily();
			double speed = Math.max(0.1, family.getPulseSpeed());     // seconds per pulse
			double period = speed * 1000.0 / FRAME_DELAY;             // frames per pulse
			double elapsed = tick - effect.getDisplayStartTick();
			double phase = (elapsed % period) / period;
			float wave = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * phase));
			float alpha = PULSE_MIN_ALPHA + wave * (PULSE_MAX_ALPHA - PULSE_MIN_ALPHA);

			Graphics2D g2 = (Graphics2D) g.create();
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
			g2.setColor(parseHexColor(family.getColorHex()));
			Cell cell = unit.getCell();
			g2.fillPolygon(geometry.cellDiamond(unit.getSide(), cell.col(), cell.row()));
			g2.dispose();
		}
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
		File file = GameFiles.file(name);
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
		boolean applied;

		HitMarker(Side side, Cell cell, int startTick, double rawDamage,
				Ability.DamageType damageType, double armorPiercing,
				double areaValue, Ability ability) {
			this.side = side;
			this.cell = cell;
			this.startTick = startTick;
			this.rawDamage = rawDamage;
			this.damageType = damageType;
			this.armorPiercing = armorPiercing;
			this.areaValue = areaValue;
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

	/** A red damage number that floats up from a tile and fades out. */
	private static final class DamageNumber {
		final Side side;
		final Cell cell;
		final int amount;
		final int startTick;
		/** Random pixel offset from the tile centre. */
		final int dx, dy;
		/** Status icon drawn to the left of the number, or null for plain hits. */
		final BufferedImage icon;

		DamageNumber(Side side, Cell cell, int amount, int startTick, int dx, int dy,
				BufferedImage icon) {
			this.side = side;
			this.cell = cell;
			this.amount = amount;
			this.startTick = startTick;
			this.dx = dx;
			this.dy = dy;
			this.icon = icon;
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
