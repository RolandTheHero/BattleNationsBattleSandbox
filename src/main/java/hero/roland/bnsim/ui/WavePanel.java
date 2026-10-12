package hero.roland.bnsim.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.dnd.DragSource;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;

/**
 * The setup-mode enemy wave list in the arena's top-left corner: a scrollable
 * window with one row per wave, holding a "Wave N" toggle (pressed for the wave
 * shown on the enemy side) and a Delete button, with a "+" below the window to
 * add a wave. Dragging a row up or down the list moves that wave in the
 * fighting order.
 */
class WavePanel extends JPanel {

	/** How many wave rows the window shows before it scrolls. */
	private static final int VISIBLE_ROWS = 5;
	/** Vertical space (px) between neighbouring rows' buttons. */
	private static final int ROW_GAP = 4;
	/** Extra width (px) given to each wave button beyond its preferred size. */
	private static final int WAVE_BUTTON_SLACK = 2;
	/** The label every wave button is sized to fit, so they all share one width. */
	private static final String WAVE_BUTTON_WIDEST_LABEL = "Wave 99";
	/** The window's background and border, matching the unit's weapons box. */
	private static final Color WINDOW_BACKGROUND = new Color(255, 255, 255, 220);
	private static final Color WINDOW_BORDER = new Color(54, 66, 96);
	/** Thickness (px) of the line marking where a dragged wave will drop. */
	private static final int DROP_LINE = 2;

	private final BattleField field;
	private final WaveList list = new WaveList();

	// Row drag state.
	/** The wave whose row is held down, and where the press began (list coordinates);
	 * the point is null when no row is held. */
	private int dragWave;
	private Point pressPoint;
	/** Whether the held row has moved far enough to count as a drag. */
	private boolean dragging;
	/** While dragging, the gap (0 to the wave count) the wave would drop into, or -1
	 * when dropping would leave it where it is. */
	private int dropSlot = -1;

	WavePanel(BattleField field) {
		super(new BorderLayout(0, 4));
		this.field = field;
		setOpaque(false);
		// As with the weapons box, keep clicks between the controls off the field.
		addMouseListener(new MouseAdapter() {});

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
		// Half a gap above the first row and below the last, which carry the other half.
		list.setBorder(BorderFactory.createEmptyBorder(ROW_GAP / 2, 0, ROW_GAP / 2, 0));
		JScrollPane scroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
			// Translucent like the weapons box. Filled by hand on a non-opaque pane so
			// Swing repaints the field beneath before drawing it.
			@Override
			protected void paintComponent(Graphics g) {
				g.setColor(WINDOW_BACKGROUND);
				g.fillRect(0, 0, getWidth(), getHeight());
			}
		};
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		// See-through rows can't be scrolled by copying pixels; repaint them instead.
		scroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
		scroll.setBorder(BorderFactory.createLineBorder(WINDOW_BORDER));
		add(scroll, BorderLayout.CENTER);

		JButton add = new JButton("+");
		add.setFocusable(false);
		add.setToolTipText("Add another enemy wave");
		add.addActionListener(e -> {
			field.addWave();
			rebuild();
			// The new wave is at the bottom; scroll to it once the list is laid out.
			SwingUtilities.invokeLater(() -> list.scrollRectToVisible(
					new Rectangle(0, list.getHeight() - 1, 1, 1)));
		});
		add(add, BorderLayout.SOUTH);

		rebuild();
	}

	/** Rebuilds the wave rows from the battlefield's waves, keeping the scroll position. */
	private void rebuild() {
		endDrag();
		list.removeAll();
		int count = field.getWaveCount();
		// Every wave button shares one fixed width, sized for a two-digit label, so
		// adding waves never resizes the buttons or shifts their Delete buttons.
		// Metal toggle buttons clip their label to "..." at exactly their preferred
		// width, so give the text a little room.
		int buttonWidth = new JToggleButton(WAVE_BUTTON_WIDEST_LABEL).getPreferredSize().width
				+ WAVE_BUTTON_SLACK;
		for (int i = 0; i < count; i++)
			list.add(makeRow(i, count, buttonWidth));
		revalidate();
		repaint();
	}

	/**
	 * A wave's row: its "Wave N" toggle and a Delete button (disabled while it is
	 * the only wave). Dragging the row or its toggle moves the wave.
	 */
	private JPanel makeRow(int wave, int count, int buttonWidth) {
		JToggleButton select = new JToggleButton("Wave " + (wave + 1));
		select.setFocusable(false);
		Dimension size = select.getPreferredSize();
		// A label longer than the reference (100+ waves) still gets room for its text.
		size.width = Math.max(buttonWidth, size.width + WAVE_BUTTON_SLACK);
		select.setPreferredSize(size);
		select.setMaximumSize(size); // the row's BoxLayout caps buttons at this
		select.setSelected(wave == field.getSelectedWave());
		select.setToolTipText("Show wave " + (wave + 1) + " on the enemy side (drag to reorder)");
		select.addActionListener(e -> {
			field.selectWave(wave);
			rebuild();
		});

		JButton delete = new JButton("X");
		delete.setFocusable(false);
		// Trim Metal's wide default side margins; a plain button never clips.
		delete.setMargin(new Insets(2, 6, 2, 6));
		delete.setEnabled(count > 1);
		delete.setToolTipText(count > 1 ? "Delete wave " + (wave + 1)
				: "The only wave can't be deleted");
		delete.addActionListener(e -> {
			field.removeWave(wave);
			rebuild();
		});

		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		// Each row carries half the gap above and below it, so neighbouring rows meet
		// midway between their buttons (where the drop line is drawn).
		row.setBorder(BorderFactory.createEmptyBorder(ROW_GAP / 2, 4, ROW_GAP / 2, 4));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(select);
		row.add(Box.createHorizontalStrut(4));
		row.add(delete);

		// The row and its toggle start drags; the Delete button only clicks.
		RowDragger dragger = new RowDragger(wave);
		for (JComponent c : new JComponent[] { row, select }) {
			c.addMouseListener(dragger);
			c.addMouseMotionListener(dragger);
			// Keep drag events coming while the pointer is held outside the window, so
			// the list keeps scrolling towards it.
			c.setAutoscrolls(true);
		}
		return row;
	}

	/**
	 * The gap a wave dragged to height {@code y} (list coordinates) would drop
	 * into: the row boundary nearest the pointer, from 0 above the first row to the
	 * wave count below the last. -1 for the gaps either side of the dragged wave,
	 * where dropping would leave the order unchanged.
	 */
	private int slotAt(int y) {
		int count = list.getComponentCount();
		int slot = count;
		for (int i = 0; i < count; i++) {
			Rectangle row = list.getComponent(i).getBounds();
			if (y < row.y + row.height / 2) {
				slot = i;
				break;
			}
		}
		return (slot == dragWave || slot == dragWave + 1) ? -1 : slot;
	}

	/** Forgets any held or dragged row, clearing its drop line and cursor. */
	private void endDrag() {
		pressPoint = null;
		dragging = false;
		dropSlot = -1;
		list.setCursor(null);
		list.repaint();
	}

	/**
	 * Drags one wave's row. Once the pointer has moved past the drag threshold the
	 * toggle's pending click is cancelled, a line marks the gap the wave would drop
	 * into, and releasing moves the wave there.
	 */
	private final class RowDragger extends MouseAdapter {
		private final int wave;

		RowDragger(int wave) {
			this.wave = wave;
		}

		@Override
		public void mousePressed(MouseEvent e) {
			if (!SwingUtilities.isLeftMouseButton(e))
				return;
			dragWave = wave;
			pressPoint = toList(e);
		}

		@Override
		public void mouseDragged(MouseEvent e) {
			if (pressPoint == null)
				return;
			Point p = toList(e);
			if (!dragging) {
				if (p.distance(pressPoint) < DragSource.getDragThreshold())
					return;
				dragging = true;
				// Cancel the toggle's click so letting go doesn't also show the wave:
				// a press released while disarmed fires no action.
				if (e.getSource() instanceof AbstractButton) {
					ButtonModel model = ((AbstractButton) e.getSource()).getModel();
					model.setArmed(false);
					model.setPressed(false);
				}
				list.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
			}
			dropSlot = slotAt(p.y);
			list.repaint();
			// Scroll the window towards a pointer held above or below it.
			int y = Math.max(0, Math.min(p.y, list.getHeight() - 1));
			list.scrollRectToVisible(new Rectangle(0, y, 1, 1));
		}

		@Override
		public void mouseReleased(MouseEvent e) {
			if (pressPoint == null || !SwingUtilities.isLeftMouseButton(e))
				return;
			int slot = dragging ? dropSlot : -1;
			endDrag();
			if (slot < 0)
				return;
			// Taking the wave out first shifts every gap below it up by one.
			field.moveWave(wave, slot > wave ? slot - 1 : slot);
			rebuild();
		}

		private Point toList(MouseEvent e) {
			return SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), list);
		}
	}

	/**
	 * The scrolling column of wave rows. It shows up to {@link #VISIBLE_ROWS} rows
	 * before scrolling, and draws the drop line over them while a wave is dragged.
	 */
	private final class WaveList extends JPanel implements Scrollable {

		/** Height (px) of a row, all rows being alike (0 when there are none). */
		private int rowHeight() {
			return getComponentCount() == 0 ? 0 : getComponent(0).getPreferredSize().height;
		}

		@Override
		protected void paintChildren(Graphics g) {
			super.paintChildren(g);
			int count = getComponentCount();
			if (dropSlot < 0 || count == 0)
				return;
			Rectangle row = getComponent(Math.min(dropSlot, count - 1)).getBounds();
			int y = dropSlot < count ? row.y : row.y + row.height;
			g.setColor(WINDOW_BORDER);
			g.fillRect(2, y - DROP_LINE / 2, getWidth() - 4, DROP_LINE);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			Dimension size = getPreferredSize();
			Insets insets = getInsets();
			size.height = Math.min(size.height,
					insets.top + VISIBLE_ROWS * rowHeight() + insets.bottom);
			return size;
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
			return Math.max(1, rowHeight());
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
			return Math.max(1, visible.height);
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return false;
		}
	}
}
