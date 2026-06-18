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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.MouseInputAdapter;

import hero.roland.bnsim.Animation;
import hero.roland.bnsim.BattleSimulator;
import hero.roland.bnsim.Cell;
import hero.roland.bnsim.EnemyBehavior;
import hero.roland.bnsim.GridGeometry;
import hero.roland.bnsim.PlacedUnit;
import hero.roland.bnsim.RandomEnemyBehavior;
import hero.roland.bnsim.Side;
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
	private static final Color SELECT_OUTLINE = new Color(245, 205, 70);
	private static final Color HIT_COLOR = new Color(225, 40, 40);
	private static final Color RANK_COLOR = new Color(0, 220, 255);
	private static final Color HP_COLOR = new Color(70, 210, 60);
	private static final Color ARMOR_COLOR = new Color(0, 200, 255);

	/** Milliseconds between animation frames (~20 fps). */
	private static final int FRAME_DELAY = 50;
	/** Frames a struck tile stays red before fully fading out. */
	private static final int HIT_FADE = 26;

	private final BattleSimulator sim;
	private final GridGeometry geometry;
	private final Timer animationTimer;

	private int tick;
	private boolean battleMode;

	/** Whose turn it is, and whether an attack animation is in progress. */
	private enum Phase { PLAYER, PLAYER_FIRING, ENEMY_FIRING }

	private Phase phase = Phase.PLAYER;
	/** Tick at which the in-progress attack finishes and the turn advances. */
	private int attackEndTick;
	private final EnemyBehavior enemyBehavior = new RandomEnemyBehavior();

	// Setup-mode drag state.
	private PlacedUnit dragging;
	private Point dragPoint;

	// Battle-mode selection state.
	private PlacedUnit selectedAttacker;
	private Unit.Attack selectedAttack;
	private Set<Cell> targetable = new HashSet<>();
	private Consumer<PlacedUnit> attackerSelectedListener;

	private final List<HitMarker> hitMarkers = new ArrayList<>();

	public BattleField(BattleSimulator sim) {
		this.sim = sim;
		this.geometry = sim.getGeometry();
		setBackground(BACKGROUND);
		setOpaque(true);
		setPreferredSize(new Dimension(
				GridGeometry.combinedWidth() + 120,
				GridGeometry.combinedHeight() + 120));

		animationTimer = new Timer(FRAME_DELAY, e -> {
			tick++;
			applyLandedHits();
			pruneHitMarkers();
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

	/** Switches between setup mode (drag to place) and battle mode (attack). */
	public void setBattleMode(boolean battle) {
		this.battleMode = battle;
		phase = Phase.PLAYER;
		dragging = null;
		dragPoint = null;
		clearSelection();
		hitMarkers.clear();
		if (battle)
			for (PlacedUnit unit : sim.placedUnits())
				unit.resetHealth();
		repaint();
	}

	/** Listener notified when the selected attacker changes (null = cleared). */
	public void setAttackerSelectedListener(Consumer<PlacedUnit> listener) {
		this.attackerSelectedListener = listener;
	}

	/** Sets the attack to aim with, recomputing the targetable tiles. */
	public void setSelectedAttack(Unit.Attack attack) {
		this.selectedAttack = attack;
		targetable = (selectedAttacker != null && attack != null)
				? sim.targetableCells(selectedAttacker, attack)
				: new HashSet<>();
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
		// Firing at a highlighted enemy tile takes priority.
		if (selectedAttacker != null && selectedAttack != null) {
			Cell target = geometry.cellAt(Side.ENEMY, p);
			if (target != null && targetable.contains(target)) {
				playerFire(target);
				return;
			}
		}
		// Otherwise (re)select a player unit, or clear the selection.
		PlacedUnit clicked = sim.pick(p);
		if (clicked != null && clicked.getSide() == Side.PLAYER)
			selectAttacker(clicked);
		else
			clearSelection();
	}

	private void selectAttacker(PlacedUnit unit) {
		selectedAttacker = unit;
		selectedAttack = null;
		targetable = new HashSet<>();
		if (attackerSelectedListener != null)
			attackerSelectedListener.accept(unit);
		repaint();
	}

	private void clearSelection() {
		selectedAttacker = null;
		selectedAttack = null;
		targetable = new HashSet<>();
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
	 * Plays an attack: starts the attacker's attack animation, schedules the
	 * struck tiles, and records when the turn may advance.
	 */
	private void executeAttack(PlacedUnit attacker, Unit.Attack attack, Cell aim) {
		Animation anim = loadAttackAnimation(attacker, attack);
		attacker.startAttack(anim, tick);

		int base = tick + Math.max(0, attack.getHitDelay());
		int lastHit = 0;
		for (BattleSimulator.Hit hit : sim.resolveHits(attacker, attack, aim)) {
			int start = base + hit.delayFrames();
			hitMarkers.add(new HitMarker(hit.side(), hit.cell(), start, hit.damage()));
			lastHit = Math.max(lastHit, start - tick);
		}

		int animFrames = (anim != null) ? anim.getEndFrame() : 0;
		// Advance once the animation has finished and the last tile has landed.
		attackEndTick = tick + Math.max(1, Math.max(animFrames, lastHit + 4));
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
		if (phase == Phase.PLAYER_FIRING)
			startEnemyTurn();
		else if (phase == Phase.ENEMY_FIRING)
			phase = Phase.PLAYER;
	}

	private void startEnemyTurn() {
		EnemyBehavior.Move move = enemyBehavior.decideMove(sim);
		if (move == null) {
			phase = Phase.PLAYER; // enemy has no legal move; skip its turn
			return;
		}
		executeAttack(move.attacker(), move.attack(), move.target());
		phase = Phase.ENEMY_FIRING;
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
		g2.setColor(BACKGROUND);
		g2.fillRect(0, 0, getWidth(), getHeight());

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
		drawHitMarkers(g2);
		drawOverlays(g2);

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

	/** Applies each hit's damage when it lands, removing units killed by it. */
	private void applyLandedHits() {
		for (HitMarker marker : hitMarkers) {
			if (marker.applied || tick < marker.startTick)
				continue;
			marker.applied = true;
			PlacedUnit target = sim.unitAt(marker.side, marker.cell);
			if (target != null) {
				target.applyDamage(marker.damage);
				if (target.isDead())
					sim.remove(target);
			}
		}
	}

	private void drawUnits(Graphics2D g) {
		// Painter's order: draw back-to-front so nearer units overlap farther.
		List<PlacedUnit> units = sim.placedUnits();
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
		int y = (int) Math.round(centreY - GridGeometry.HALF_H - 12);

		int hpLen = (int) Math.round(barW * Math.max(0, unit.getCurrentHp()) / (double) maxTotal);
		int armorLen = (int) Math.round(barW * Math.max(0, unit.getCurrentArmor()) / (double) maxTotal);

		g.setColor(new Color(20, 20, 20, 200));
		g.fillRect(x - 1, y - 1, barW + 2, barH + 2);
		g.setColor(HP_COLOR);
		g.fillRect(x, y, hpLen, barH);
		g.setColor(ARMOR_COLOR);
		g.fillRect(x + hpLen, y, armorLen, barH);
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

	/** A struck tile that flashes red, deals its damage on landing, and fades. */
	private static final class HitMarker {
		final Side side;
		final Cell cell;
		final int startTick;
		final int damage;
		boolean applied;

		HitMarker(Side side, Cell cell, int startTick, int damage) {
			this.side = side;
			this.cell = cell;
			this.startTick = startTick;
			this.damage = damage;
		}
	}
}
