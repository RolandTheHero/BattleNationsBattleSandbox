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
 * other across {@link #GAP_ROWS} empty row(s), which places the player grid
 * towards the bottom-left and the enemy grid towards the top-right, parallel —
 * matching the in-game battlefield.
 *
 * <p>Tiles use the game's native size ({@link GridPoint#GRID_X}&times;2 wide by
 * {@link GridPoint#GRID_Y}&times;2 tall) so unit animations draw at their
 * natural size and land squarely on a cell.
 */
public class GridGeometry {

	/** Width (in cells) of the front rows, and number of rows per side. */
	public static final int COLS = 5;
	public static final int ROWS = 3;

	/** Width of each side's back row (the rest of the row is empty). */
	public static final int BACK_ROW_COLS = 3;

	/** Empty rows between the player and enemy front lines. */
	public static final int GAP_ROWS = 1;

	/** Half the width / height of a single diamond tile, in pixels (native). */
	public static final int HALF_W = GridPoint.GRID_X; // 100
	public static final int HALF_H = GridPoint.GRID_Y; // 50

	/** Columns trimmed from each end of the back row so it stays centred. */
	private static final int BACK_ROW_OFFSET = (COLS - BACK_ROW_COLS) / 2;

	/** Pixel anchor of absolute lattice cell (0,0)'s centre. */
	private final Point origin = new Point();

	/** Whether a (col, row) cell exists on a side (row 0 = front line). */
	public static boolean isValid(int col, int row) {
		if (row < 0 || row >= ROWS)
			return false;
		if (row == ROWS - 1) // back row: narrower and centred
			return col >= BACK_ROW_OFFSET && col < BACK_ROW_OFFSET + BACK_ROW_COLS;
		return col >= 0 && col < COLS;
	}

	/** Maps a side-local row (0 = front line) to the shared lattice row. */
	private static int absRow(Side side, int row) {
		return side == Side.PLAYER ? (ROWS + GAP_ROWS) + row : (ROWS - 1) - row;
	}

	/** Bounding box of the whole lattice in (u = col-row, v = col+row) space. */
	private static int[] bounds() {
		int minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE;
		int minV = Integer.MAX_VALUE, maxV = Integer.MIN_VALUE;
		for (Side side : Side.values())
			for (int row = 0; row < ROWS; row++)
				for (int col = 0; col < COLS; col++) {
					if (!isValid(col, row))
						continue;
					int ar = absRow(side, row);
					int u = col - ar, v = col + ar;
					minU = Math.min(minU, u);
					maxU = Math.max(maxU, u);
					minV = Math.min(minV, v);
					maxV = Math.max(maxV, v);
				}
		return new int[] { minU, maxU, minV, maxV };
	}

	/** Pixel width spanned by both grids together (including diamond tips). */
	public static int combinedWidth() {
		int[] b = bounds();
		return (b[1] - b[0] + 2) * HALF_W;
	}

	/** Pixel height spanned by both grids together (including diamond tips). */
	public static int combinedHeight() {
		int[] b = bounds();
		return (b[3] - b[2] + 2) * HALF_H;
	}

	/** Recentres the battlefield for a drawing surface of the given size. */
	public void setViewport(int width, int height) {
		int[] b = bounds();
		double centreU = (b[0] + b[1]) / 2.0;
		double centreV = (b[2] + b[3]) / 2.0;
		origin.x = (int) Math.round(width / 2.0 - centreU * HALF_W);
		origin.y = (int) Math.round(height / 2.0 - centreV * HALF_H);
	}

	/** Pixel centre of the given cell on the given side. */
	public Point2D.Double cellCentre(Side side, int col, int row) {
		int ar = absRow(side, row);
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
		Polygon p = new Polygon();
		p.addPoint((int) Math.round(c.x), (int) Math.round(c.y - HALF_H));
		p.addPoint((int) Math.round(c.x + HALF_W), (int) Math.round(c.y));
		p.addPoint((int) Math.round(c.x), (int) Math.round(c.y + HALF_H));
		p.addPoint((int) Math.round(c.x - HALF_W), (int) Math.round(c.y));
		return p;
	}

	/**
	 * The cell on the given side nearest the pixel, or {@code null} if the
	 * pixel does not fall on a valid cell of that side. Used for snapping a
	 * dropped unit to the grid.
	 */
	public Cell cellAt(Side side, Point2D point) {
		double a = (point.getX() - origin.x) / HALF_W;  // col - absRow
		double b = (point.getY() - origin.y) / HALF_H;  // col + absRow
		int col = (int) Math.round((a + b) / 2);
		int absRow = (int) Math.round((b - a) / 2);
		int row = side == Side.PLAYER
				? absRow - (ROWS + GAP_ROWS)
				: (ROWS - 1) - absRow;
		if (!isValid(col, row))
			return null;
		return new Cell(col, row);
	}
}
