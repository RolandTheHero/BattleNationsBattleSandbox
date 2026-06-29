package hero.roland.bnsim;

import java.awt.Point;
import java.awt.Polygon;
import java.awt.geom.Point2D;

/**
 * Converts between logical grid cells and screen pixels for the two isometric
 * battlefield grids.
 *
 * <p>Both sides live on one shared isometric lattice. Each side has
 * {@link #ROWS} rows where row 0 is the front line; the front rows are
 * {@link #COLS} cells wide and the back row is narrower ({@link #BACK_ROW_COLS}
 * cells, centred). The player's front line and the enemy's front line face each
 * other across {@link #GAP_ROWS} empty row(s) — but the enemy grid is then
 * pulled {@link #ENEMY_GAP_CLOSE} of a row toward the player, leaving a half-tile
 * gap between the two front lines rather than a full one. This places the player
 * grid towards the bottom-left and the enemy grid towards the top-right,
 * parallel — matching the in-game battlefield.
 *
 * <p>Tiles use the game's native size ({@link GridPoint#GRID_X}&times;2 wide by
 * {@link GridPoint#GRID_Y}&times;2 tall) so unit animations draw at their
 * natural size and land squarely on a cell.
 */
public class GridGeometry {

	/**
	 * Width (in cells) of the front rows, and number of rows per side. Adjustable
	 * at setup time via {@link #setDimensions} (the UnitMenu grid-size boxes); the
	 * simulation's occupancy grids must be rebuilt afterwards (see
	 * {@link BattleSimulator#resizeGrids}).
	 */
	public static int COLS = 5;
	public static int ROWS = 3;

	/** Width of each side's back row (the rest of the row is empty). */
	public static int BACK_ROW_COLS = 3;

	/** Empty rows between the player and enemy front lines. */
	public static final int GAP_ROWS = 1;

	/**
	 * Rows by which the enemy grid is pulled toward the player past the
	 * {@link #GAP_ROWS} gap, closing the space between the two front lines. At 0.5
	 * the enemy's front line sits half a tile from the player's instead of a full
	 * tile. Applied to the enemy side only.
	 */
	public static final double ENEMY_GAP_CLOSE = 0.5;

	/** Half the width / height of a single diamond tile, in pixels (native). */
	public static final int HALF_W = GridPoint.GRID_X; // 100
	public static final int HALF_H = GridPoint.GRID_Y; // 50

	/** Pixel anchor of absolute lattice cell (0,0)'s centre. */
	private final Point origin = new Point();

	/**
	 * Sets the grid shape used by both sides: {@code rows} per side (front line is
	 * row 0), front rows {@code cols} wide, and the back row {@code backRowCols}
	 * wide and centred. Each value is clamped to keep the grid valid — at least 1
	 * row/column, and a back row no wider than the front rows (otherwise its cells
	 * could fall outside the occupancy grid). After calling this, rebuild the
	 * simulation's grids (see {@link BattleSimulator#resizeGrids}) so they match the
	 * new shape.
	 */
	public static void setDimensions(int rows, int cols, int backRowCols) {
		ROWS = Math.max(1, rows);
		COLS = Math.max(1, cols);
		BACK_ROW_COLS = Math.max(1, Math.min(backRowCols, COLS));
	}

	/** Columns trimmed from each end of the back row so it stays centred. */
	private static int backRowOffset() {
		return (COLS - BACK_ROW_COLS) / 2;
	}

	/** Whether a (col, row) cell exists on a side (row 0 = front line). */
	public static boolean isValid(int col, int row) {
		if (row < 0 || row >= ROWS)
			return false;
		if (row == ROWS - 1) { // back row: narrower and centred
			int offset = backRowOffset();
			return col >= offset && col < offset + BACK_ROW_COLS;
		}
		return col >= 0 && col < COLS;
	}

	/**
	 * Maps a side-local row (0 = front line) to the shared lattice row. The enemy
	 * side is pulled {@link #ENEMY_GAP_CLOSE} rows toward the player (higher lattice
	 * rows), shrinking the gap between the front lines; the result is fractional for
	 * the enemy, so this returns a {@code double}.
	 */
	private static double absRow(Side side, int row) {
		return side == Side.PLAYER
			? (ROWS + GAP_ROWS) + row
			: (ROWS - 1) - row + ENEMY_GAP_CLOSE;
	}

	/** Bounding box of the whole lattice in (u = col-row, v = col+row) space. */
	private static double[] bounds() {
		double minU = Double.POSITIVE_INFINITY, maxU = Double.NEGATIVE_INFINITY;
		double minV = Double.POSITIVE_INFINITY, maxV = Double.NEGATIVE_INFINITY;
		for (Side side : Side.values())
			for (int row = 0; row < ROWS; row++)
				for (int col = 0; col < COLS; col++) {
					if (!isValid(col, row))
						continue;
					double ar = absRow(side, row);
					double u = col - ar, v = col + ar;
					minU = Math.min(minU, u);
					maxU = Math.max(maxU, u);
					minV = Math.min(minV, v);
					maxV = Math.max(maxV, v);
				}
		return new double[] { minU, maxU, minV, maxV };
	}

	/** Pixel width spanned by both grids together (including diamond tips). */
	public static int combinedWidth() {
		double[] b = bounds();
		return (int) Math.round((b[1] - b[0] + 2) * HALF_W);
	}

	/** Pixel height spanned by both grids together (including diamond tips). */
	public static int combinedHeight() {
		double[] b = bounds();
		return (int) Math.round((b[3] - b[2] + 2) * HALF_H);
	}

	/** Recentres the battlefield for a drawing surface of the given size. */
	public void setViewport(int width, int height) {
		double[] b = bounds();
		double centreU = (b[0] + b[1]) / 2.0;
		double centreV = (b[2] + b[3]) / 2.0;
		origin.x = (int) Math.round(width / 2.0 - centreU * HALF_W);
		origin.y = (int) Math.round(height / 2.0 - centreV * HALF_H);
	}

	/** Pixel centre of the given cell on the given side. */
	public Point2D.Double cellCentre(Side side, int col, int row) {
		double ar = absRow(side, row);
		double x = origin.x + (col - ar) * (double) HALF_W;
		double y = origin.y + (col + ar) * (double) HALF_H;
		return new Point2D.Double(x, y);
	}

	public Point2D.Double cellCentre(Side side, Cell cell) {
		return cellCentre(side, cell.col(), cell.row());
	}

	/** Outline of a tile, for drawing the grid. */
	public Polygon cellDiamond(Side side, int col, int row) {
		Point2D.Double c = cellCentre(side, col, row);
		return diamondAt(c.x, c.y);
	}

	/**
	 * Outline of a tile centred at an arbitrary pixel, for drawing a tile that is
	 * sliding between cells (e.g. while a side's rows advance toward the front).
	 */
	public Polygon diamondAt(double cx, double cy) {
		Polygon p = new Polygon();
		p.addPoint((int) Math.round(cx), (int) Math.round(cy - HALF_H));
		p.addPoint((int) Math.round(cx + HALF_W), (int) Math.round(cy));
		p.addPoint((int) Math.round(cx), (int) Math.round(cy + HALF_H));
		p.addPoint((int) Math.round(cx - HALF_W), (int) Math.round(cy));
		return p;
	}

	/**
	 * Outline of a cell's tile collapsed toward its front edge (the edge facing the
	 * gap) by fraction {@code p} in [0, 1]: the two back-edge vertices slide one
	 * row forward, so at {@code p == 0} it is the full tile and at {@code p == 1} it
	 * has flattened onto its front edge. Used to animate a front row shrinking away
	 * as the rows behind it advance.
	 */
	public Polygon frontCollapsedDiamond(Side side, int col, int row, double p) {
		Point2D.Double c = cellCentre(side, col, row);
		Point2D.Double behind = cellCentre(side, col, row + 1);
		double fwdX = c.x - behind.x; // toward the front line / gap
		double fwdY = c.y - behind.y;
		double[][] verts = { { 0, -HALF_H }, { HALF_W, 0 }, { 0, HALF_H }, { -HALF_W, 0 } };
		Polygon poly = new Polygon();
		for (double[] v : verts) {
			double ox = v[0], oy = v[1];
			// A back-edge vertex points away from the front; slide it forward.
			if (ox * fwdX + oy * fwdY < 0) {
				ox += p * fwdX;
				oy += p * fwdY;
			}
			poly.addPoint((int) Math.round(c.x + ox), (int) Math.round(c.y + oy));
		}
		return poly;
	}

	/**
	 * The cell on the given side nearest the pixel, or {@code null} if the
	 * pixel does not fall on a valid cell of that side. Used for snapping a
	 * dropped unit to the grid.
	 */
	public Cell cellAt(Side side, Point2D point) {
		double a = (point.getX() - origin.x) / HALF_W;  // col - absRow
		double b = (point.getY() - origin.y) / HALF_H;  // col + absRow
		double absRow = (b - a) / 2.0;
		int col = (int) Math.round((a + b) / 2.0);
		// Invert absRow() — the enemy's lattice rows are shifted by ENEMY_GAP_CLOSE,
		// so undo that shift before rounding to a side-local row.
		int row = (int) Math.round(side == Side.PLAYER
			? absRow - (ROWS + GAP_ROWS)
			: (ROWS - 1) + ENEMY_GAP_CLOSE - absRow);
		if (!isValid(col, row))
			return null;
		return new Cell(col, row);
	}
}
