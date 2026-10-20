package hero.roland.bnsim.ui;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.dnd.DragSource;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * The setup-mode enemy wave list in the arena's top-left corner: a scrollable
 * window with one row per wave, holding that wave's subwaves side by side (one of
 * which is picked at random to fight each battle). Each subwave has an "N(S)"
 * toggle (pressed for the subwave shown on the enemy side) and a Delete button; a
 * "+" ends each row to add a subwave to that wave, and a "+" below the window adds
 * a wave, with Import and Export buttons beneath it loading and saving the whole
 * setup (see {@link SetupFile}). Dragging a subwave moves it along its row, into another row, or (dropped
 * between rows) into a wave of its own; while held it sits translucent in the
 * place it would drop.
 */
class WavePanel extends JPanel {

	/** How many wave rows the window shows before it scrolls. */
	private static final int VISIBLE_ROWS = 5;
	/** How many subwaves a row shows before the window scrolls sideways. */
	private static final int VISIBLE_SUBWAVES = 5;
	/** Vertical space (px) between neighbouring rows' buttons. */
	private static final int ROW_GAP = 4;
	/** Horizontal space (px) between neighbouring subwaves, well clear of the gap
	 * between a subwave's toggle and its Delete button so each X reads as its own. */
	private static final int SUBWAVE_GAP = 14;
	/** Space (px) left of the first subwave and right of the row's "+". */
	private static final int SIDE_MARGIN = 4;
	/** Extra width (px) given to each wave button beyond its preferred size. */
	private static final int WAVE_BUTTON_SLACK = 2;
	/** Inner margins of the panel's buttons, trimming Metal's wide default sides. */
	private static final Insets BUTTON_MARGIN = new Insets(2, 6, 2, 6);
	/** The window's background and border, matching the unit's weapons box. */
	private static final Color WINDOW_BACKGROUND = new Color(255, 255, 255, 220);
	private static final Color WINDOW_BORDER = new Color(54, 66, 96);
	/** Opacity of a held subwave until it is let go. */
	private static final float DRAG_ALPHA = 0.45f;
	/** The share of a row's height, at its top and at its bottom, where a dragged
	 * subwave drops between waves (as a new wave) rather than into that wave. */
	private static final double NEW_WAVE_EDGE = 0.25;

	private final BattleField field;
	private final WaveList list = new WaveList();

	// Subwave drag state.
	/** The subwave held down, its cell, and where the press began (list
	 * coordinates); the point is null when nothing is held. */
	private int dragWave;
	private int dragSubwave;
	private JComponent dragCell;
	private Point pressPoint;
	/** Whether the held subwave has moved far enough to count as a drag. */
	private boolean dragging;
	/** While dragging, a picture of the held cell, drawn translucent where it would drop. */
	private BufferedImage dragImage;
	/** While dragging, where the subwave would drop, or null when it would stay put. */
	private Drop drop;

	/**
	 * Where a dragged subwave would drop. With {@code newWave} it becomes a wave of
	 * its own, inserted before wave {@code wave} (0 to the wave count); otherwise it
	 * joins wave {@code wave} at {@code subwave}, counted without the dragged one.
	 */
	private record Drop(int wave, int subwave, boolean newWave) {}

	WavePanel(BattleField field) {
		super(new BorderLayout(0, 4));
		this.field = field;
		setOpaque(false);
		// As with the weapons box, keep clicks between the controls off the field.
		addMouseListener(new MouseAdapter() {});

		JScrollPane scroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED) {
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
		JButton importButton = new JButton("Import");
		importButton.setFocusable(false);
		importButton.setMargin(BUTTON_MARGIN);
		importButton.setToolTipText("Load a saved setup, replacing both sides and every wave");
		importButton.addActionListener(e -> importSetup());
		JButton exportButton = new JButton("Export");
		exportButton.setFocusable(false);
		exportButton.setMargin(BUTTON_MARGIN);
		exportButton.setToolTipText("Save the grid, the player side and every wave to a file");
		exportButton.addActionListener(e -> exportSetup());
		JPanel files = new JPanel(new GridLayout(1, 2, 4, 0));
		files.setOpaque(false);
		files.add(importButton);
		files.add(exportButton);

		JPanel buttons = new JPanel(new BorderLayout(0, 4));
		buttons.setOpaque(false);
		buttons.add(add, BorderLayout.NORTH);
		buttons.add(files, BorderLayout.SOUTH);
		add(buttons, BorderLayout.SOUTH);

		rebuild();
	}

	/** Asks where to save the setup (in the program's folder by default) and writes it there. */
	private void exportSetup() {
		JFileChooser chooser = setupChooser("Export Setup");
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
			return;
		File file = chooser.getSelectedFile();
		if (!file.getName().contains("."))
			file = new File(file.getParentFile(), file.getName() + ".json");
		if (file.exists() && JOptionPane.showConfirmDialog(this,
				file.getName() + " already exists. Replace it?", "Export Setup",
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION)
			return;
		try {
			Files.writeString(file.toPath(), SetupFile.write(field.exportSetup()));
		} catch (IOException | RuntimeException ex) {
			ErrorDialog.show(this, "Export failed", "Could not save the setup to:\n" + file, ex);
		}
	}

	/**
	 * Asks for a saved setup, reads it, and once the user agrees to lose the current
	 * sides and waves, loads it in their place.
	 */
	private void importSetup() {
		JFileChooser chooser = setupChooser("Import Setup");
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
			return;
		File file = chooser.getSelectedFile();
		SetupFile.Setup setup;
		try {
			setup = SetupFile.read(Files.readString(file.toPath()));
		} catch (IOException | RuntimeException ex) {
			ErrorDialog.show(this, "Import failed", "Could not read a setup from:\n" + file, ex);
			return;
		}
		if (JOptionPane.showConfirmDialog(this,
				"This will clear and replace all current sides and waves.\nContinue?",
				"Import Setup", JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION)
			return;
		Set<String> unknown = field.importSetup(setup);
		rebuild();
		if (!unknown.isEmpty())
			JOptionPane.showMessageDialog(this,
					"These units aren't in the loaded game files and were left out:\n"
							+ String.join("\n", unknown),
					"Import Setup", JOptionPane.WARNING_MESSAGE);
	}

	/** A chooser for setup files, opening in the program's folder. */
	private static JFileChooser setupChooser(String title) {
		JFileChooser chooser = new JFileChooser(new File(System.getProperty("user.dir")));
		chooser.setDialogTitle(title);
		chooser.setFileFilter(new FileNameExtensionFilter("Setup files (*.json)", "json"));
		return chooser;
	}

	/** Rebuilds the subwave cells from the battlefield's waves, keeping the scroll position. */
	private void rebuild() {
		endDrag();
		list.removeAll();
		list.rows.clear();
		list.adders.clear();
		int total = field.getTotalSubwaveCount();
		// Every wave button shares one width, just fitting the longest label, so the
		// subwaves line up in columns. Metal toggle buttons clip their label to "..."
		// at exactly their preferred width, so give the text a little room.
		int buttonWidth = 0;
		for (int w = 0; w < field.getWaveCount(); w++)
			for (int s = 0; s < field.getSubwaveCount(w); s++)
				buttonWidth = Math.max(buttonWidth, makeToggle(label(w, s)).getPreferredSize().width);
		buttonWidth += WAVE_BUTTON_SLACK;
		for (int w = 0; w < field.getWaveCount(); w++) {
			List<JComponent> row = new ArrayList<>();
			for (int s = 0; s < field.getSubwaveCount(w); s++) {
				JComponent cell = makeCell(w, s, total, buttonWidth);
				row.add(cell);
				list.add(cell);
			}
			list.rows.add(row);
			JButton adder = makeAdder(w);
			list.adders.add(adder);
			list.add(adder);
		}
		revalidate();
		repaint();
	}

	/**
	 * A subwave's cell: its "N(S)" toggle and a Delete button (disabled while it is
	 * the only subwave left). Dragging the cell or its toggle moves the subwave.
	 */
	private JPanel makeCell(int wave, int subwave, int total, int buttonWidth) {
		String name = label(wave, subwave);
		JToggleButton select = makeToggle(name);
		Dimension size = select.getPreferredSize();
		size.width = buttonWidth;
		select.setPreferredSize(size);
		select.setMaximumSize(size); // the cell's BoxLayout caps buttons at this
		select.setSelected(wave == field.getSelectedWave() && subwave == field.getSelectedSubwave());
		select.setToolTipText("Show wave " + name + " on the enemy side (drag to reorder)");
		select.addActionListener(e -> {
			field.selectSubwave(wave, subwave);
			rebuild();
		});

		JButton delete = new JButton("X");
		delete.setFocusable(false);
		delete.setMargin(BUTTON_MARGIN); // a plain button never clips
		delete.setEnabled(total > 1);
		delete.setToolTipText(total > 1 ? "Delete wave " + name
				: "The only wave can't be deleted");
		delete.addActionListener(e -> {
			field.removeSubwave(wave, subwave);
			rebuild();
		});

		JPanel cell = new JPanel();
		cell.setLayout(new BoxLayout(cell, BoxLayout.X_AXIS));
		cell.setOpaque(false);
		cell.add(select);
		cell.add(Box.createHorizontalStrut(4));
		cell.add(delete);

		// The cell and its toggle start drags; the Delete button only clicks.
		CellDragger dragger = new CellDragger(wave, subwave, cell);
		for (JComponent c : new JComponent[] { cell, select }) {
			c.addMouseListener(dragger);
			c.addMouseMotionListener(dragger);
			// Keep drag events coming while the pointer is held outside the window, so
			// the list keeps scrolling towards it.
			c.setAutoscrolls(true);
		}
		return cell;
	}

	/** A subwave's "N(S)" label. */
	private static String label(int wave, int subwave) {
		return (wave + 1) + "(" + (subwave + 1) + ")";
	}

	/** A subwave's toggle, unsized, with the panel's trimmed margins. */
	private static JToggleButton makeToggle(String label) {
		JToggleButton toggle = new JToggleButton(label);
		toggle.setFocusable(false);
		toggle.setMargin(BUTTON_MARGIN);
		return toggle;
	}

	/** The "+" ending a wave's row, adding a subwave to that wave. */
	private JButton makeAdder(int wave) {
		JButton adder = new JButton("+");
		adder.setFocusable(false);
		adder.setMargin(BUTTON_MARGIN);
		adder.setToolTipText("Add another version of wave " + (wave + 1)
				+ " (one is picked at random each battle)");
		adder.addActionListener(e -> {
			field.addSubwave(wave);
			rebuild();
			// The new subwave ends its row; scroll to it (and the row's "+") once laid out.
			SwingUtilities.invokeLater(() -> {
				List<JComponent> row = list.rows.get(wave);
				Rectangle shown = row.get(row.size() - 1).getBounds();
				shown.add(list.adders.get(wave).getBounds());
				list.scrollRectToVisible(shown);
			});
		});
		return adder;
	}

	/**
	 * Where a subwave dragged to {@code p} (list coordinates) would drop, judged
	 * against the rows as they stood before the drag so the answer doesn't shift as
	 * the preview rearranges them. Near the line between two rows (or beyond the
	 * first or last) it becomes a new wave there; over a row it joins that wave
	 * ahead of the first other subwave whose middle is right of the pointer. Null
	 * where dropping would leave everything as it is.
	 */
	private Drop dropAt(Point p) {
		int waves = list.rows.size();
		double pos = (p.y - ROW_GAP / 2) / (double) list.rowHeight();
		int row = (int) Math.floor(pos);
		double within = pos - row;
		Drop d;
		if (row < 0)
			d = new Drop(0, 0, true);
		else if (row >= waves)
			d = new Drop(waves, 0, true);
		else if (within < NEW_WAVE_EDGE)
			d = new Drop(row, 0, true);
		else if (within > 1 - NEW_WAVE_EDGE)
			d = new Drop(row + 1, 0, true);
		else {
			int pitch = list.pitch();
			int half = list.cellSize().width / 2;
			int index = 0;
			for (int i = 0; i < list.rows.get(row).size(); i++)
				if (!(row == dragWave && i == dragSubwave) && SIDE_MARGIN + i * pitch + half < p.x)
					index++;
			d = new Drop(row, index, false);
		}
		boolean alone = list.rows.get(dragWave).size() == 1;
		boolean unchanged = d.newWave()
				? alone && (d.wave() == dragWave || d.wave() == dragWave + 1)
				: d.wave() == dragWave && d.subwave() == dragSubwave;
		return unchanged ? null : d;
	}

	/** Forgets any held or dragged subwave, clearing its preview and cursor. */
	private void endDrag() {
		pressPoint = null;
		dragging = false;
		dragCell = null;
		dragImage = null;
		drop = null;
		list.setCursor(null);
		list.repaint();
	}

	/** A picture of the component as it looks now. */
	private static BufferedImage snapshot(JComponent c) {
		BufferedImage image = new BufferedImage(Math.max(1, c.getWidth()), Math.max(1, c.getHeight()),
				BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		c.paint(g);
		g.dispose();
		return image;
	}

	/**
	 * Drags one subwave. Once the pointer has moved past the drag threshold the
	 * toggle's pending click is cancelled and the subwave is shown, translucent, in
	 * the place it would drop, the others making room; releasing moves it there.
	 */
	private final class CellDragger extends MouseAdapter {
		private final int wave;
		private final int subwave;
		private final JComponent cell;

		CellDragger(int wave, int subwave, JComponent cell) {
			this.wave = wave;
			this.subwave = subwave;
			this.cell = cell;
		}

		@Override
		public void mousePressed(MouseEvent e) {
			if (!SwingUtilities.isLeftMouseButton(e))
				return;
			dragWave = wave;
			dragSubwave = subwave;
			dragCell = cell;
			pressPoint = toList(e);
		}

		@Override
		public void mouseDragged(MouseEvent e) {
			if (pressPoint == null)
				return;
			Point p = toList(e);
			boolean started = false;
			if (!dragging) {
				if (p.distance(pressPoint) < DragSource.getDragThreshold())
					return;
				dragging = started = true;
				// Cancel the toggle's click so letting go doesn't also show the subwave:
				// a press released while disarmed fires no action.
				if (e.getSource() instanceof AbstractButton) {
					ButtonModel model = ((AbstractButton) e.getSource()).getModel();
					model.setArmed(false);
					model.setPressed(false);
				}
				dragImage = snapshot(cell);
				list.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
			}
			Drop d = dropAt(p);
			if (started || !Objects.equals(d, drop)) {
				drop = d;
				// The preview can widen or lengthen the list, so the window may resize.
				list.invalidate();
				revalidate();
			}
			list.repaint();
			// Scroll the window towards a pointer held beyond it.
			int x = Math.max(0, Math.min(p.x, list.getWidth() - 1));
			int y = Math.max(0, Math.min(p.y, list.getHeight() - 1));
			list.scrollRectToVisible(new Rectangle(x, y, 1, 1));
		}

		@Override
		public void mouseReleased(MouseEvent e) {
			if (pressPoint == null || !SwingUtilities.isLeftMouseButton(e))
				return;
			boolean moved = dragging;
			Drop d = dragging ? drop : null;
			endDrag();
			if (d != null)
				field.moveSubwave(wave, subwave, d.wave(), d.subwave(), d.newWave());
			// The held cell was folded away for the preview; lay everything out afresh.
			if (moved)
				rebuild();
		}

		private Point toList(MouseEvent e) {
			return SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), list);
		}
	}

	/**
	 * The scrolling grid of subwave cells, one row per wave with the row's "+" after
	 * its last cell. It shows up to {@link #VISIBLE_ROWS} rows and
	 * {@link #VISIBLE_SUBWAVES} subwaves a row before scrolling. While a subwave is
	 * dragged it lays the rows out as they would be after the drop (the dragged
	 * subwave's old row keeping its place, even if emptied) and draws the dragged
	 * cell translucent in its new place.
	 */
	private final class WaveList extends JPanel implements Scrollable {

		/** Each wave's subwave cells, and the "+" ending each wave's row. */
		final List<List<JComponent>> rows = new ArrayList<>();
		final List<JComponent> adders = new ArrayList<>();

		WaveList() {
			super(null);
			setOpaque(false);
		}

		/** The size every cell is laid out at: the largest cell's preferred size. */
		Dimension cellSize() {
			Dimension size = new Dimension();
			for (List<JComponent> row : rows)
				for (JComponent cell : row) {
					Dimension pref = cell.getPreferredSize();
					size.width = Math.max(size.width, pref.width);
					size.height = Math.max(size.height, pref.height);
				}
			return size;
		}

		/** The size of a row's "+" (all alike). */
		private Dimension adderSize() {
			return adders.isEmpty() ? new Dimension() : adders.get(0).getPreferredSize();
		}

		/** Height (px) of a row, its buttons plus the gap between rows. */
		int rowHeight() {
			return Math.max(cellSize().height, adderSize().height) + ROW_GAP;
		}

		/** Horizontal distance (px) from one cell to the next along a row. */
		int pitch() {
			return cellSize().width + SUBWAVE_GAP;
		}

		/**
		 * The rows as laid out, each with its "+" (null for a new wave being
		 * previewed, or a row the dragged cell would leave empty): as they are, or
		 * with the dragged cell moved to its drop.
		 */
		private List<List<JComponent>> shownRows(List<JComponent> shownAdders) {
			List<List<JComponent>> shown = new ArrayList<>();
			for (List<JComponent> row : rows)
				shown.add(new ArrayList<>(row));
			shownAdders.addAll(adders);
			if (dragging && drop != null) {
				JComponent cell = shown.get(dragWave).remove(dragSubwave);
				if (shown.get(dragWave).isEmpty())
					shownAdders.set(dragWave, null);
				if (drop.newWave()) {
					shown.add(drop.wave(), new ArrayList<>(List.of(cell)));
					shownAdders.add(drop.wave(), null);
				} else {
					shown.get(drop.wave()).add(drop.subwave(), cell);
				}
			}
			return shown;
		}

		@Override
		public void doLayout() {
			Dimension cell = cellSize();
			Dimension adder = adderSize();
			int rowHeight = rowHeight();
			int inner = rowHeight - ROW_GAP;
			int pitch = pitch();
			List<JComponent> shownAdders = new ArrayList<>();
			List<List<JComponent>> shown = shownRows(shownAdders);
			for (JComponent add : adders)
				if (!shownAdders.contains(add))
					add.setBounds(0, 0, 0, 0);
			for (int r = 0; r < shown.size(); r++) {
				int top = ROW_GAP / 2 + r * rowHeight + ROW_GAP / 2;
				List<JComponent> row = shown.get(r);
				for (int i = 0; i < row.size(); i++) {
					JComponent c = row.get(i);
					int x = SIDE_MARGIN + i * pitch;
					int y = top + (inner - cell.height) / 2;
					// The held cell is drawn as a translucent picture instead. It stays in
					// the list, folded to nothing, so it keeps receiving the drag.
					if (dragging && c == dragCell)
						c.setBounds(x, y, 0, 0);
					else
						c.setBounds(x, y, cell.width, cell.height);
				}
				JComponent add = shownAdders.get(r);
				if (add != null)
					add.setBounds(SIDE_MARGIN + row.size() * pitch, top + (inner - adder.height) / 2,
							adder.width, adder.height);
			}
		}

		@Override
		protected void paintChildren(Graphics g) {
			super.paintChildren(g);
			if (!dragging || dragImage == null || dragCell == null)
				return;
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setComposite(AlphaComposite.SrcOver.derive(DRAG_ALPHA));
			g2.drawImage(dragImage, dragCell.getX(), dragCell.getY(), null);
			g2.dispose();
		}

		@Override
		public Dimension getPreferredSize() {
			List<List<JComponent>> shown = shownRows(new ArrayList<>());
			int cells = 0;
			for (List<JComponent> row : shown)
				cells = Math.max(cells, row.size());
			return new Dimension(SIDE_MARGIN + cells * pitch() + adderSize().width + SIDE_MARGIN,
					ROW_GAP + shown.size() * rowHeight());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			Dimension size = getPreferredSize();
			size.width = Math.min(size.width,
					SIDE_MARGIN + VISIBLE_SUBWAVES * pitch() + adderSize().width + SIDE_MARGIN);
			size.height = Math.min(size.height, ROW_GAP + VISIBLE_ROWS * rowHeight());
			return size;
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
			return Math.max(1, orientation == SwingConstants.VERTICAL ? rowHeight() : pitch());
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
			return Math.max(1, orientation == SwingConstants.VERTICAL ? visible.height : visible.width);
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return false;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return false;
		}
	}
}
