package hero.roland.bnsim.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

import hero.roland.bnsim.GridGeometry;
import hero.roland.bnsim.Side;
import hero.roland.bnsim.gamefiles.GameFiles;
import hero.roland.bnsim.model.Text;
import hero.roland.bnsim.model.Unit;

/**
 * A collapsible side menu for searching the loaded {@link Unit}s and adding a
 * selected one to a chosen {@link Side} of the battlefield. The actual
 * placement is delegated to a {@link BiConsumer} supplied via
 * {@link #setPlacer}.
 */
public class UnitMenu extends JPanel {

	private static final int EXPANDED_WIDTH = 250;

	/** Dropdown label for "no environment status effect" (the default). */
	private static final String NO_ENV_EFFECT = "None";

	private final JComboBox<Text.Language> languageSelector = new JComboBox<>();
	private final JComboBox<String> mapSelector = new JComboBox<>();
	private final JComboBox<String> envEffectSelector = new JComboBox<>();
	private final JPanel content = new JPanel(new BorderLayout(0, 6));

	private final DefaultListModel<Unit> listModel = new DefaultListModel<>();
	private final JList<Unit> unitList = new JList<>(listModel);
	private final JTextField search = new JTextField();
	private final JTextField scaleField = new JTextField("1", 4);
	// Start with text so the labels report a real (non-zero) preferred height when
	// their fixed size is captured in gridStepperRow; refreshGridLabels keeps them
	// current after that.
	private final JLabel rowsValue = new JLabel(Integer.toString(GridGeometry.ROWS), SwingConstants.CENTER);
	private final JLabel colsValue = new JLabel(Integer.toString(GridGeometry.COLS), SwingConstants.CENTER);
	private final JLabel backRowColsValue = new JLabel(Integer.toString(GridGeometry.BACK_ROW_COLS), SwingConstants.CENTER);
	private final JCheckBox cooldownToggle = new JCheckBox("Cooldowns & ammo", true);
	private final JCheckBox targetTypesToggle = new JCheckBox("Target types", true);
	private final JCheckBox statusImmunitiesToggle = new JCheckBox("Status immunities", true);
	private final JCheckBox advanceToggle = new JCheckBox("Units advancing", true);

	private final List<Unit> allUnits = new ArrayList<>();

	private BiConsumer<Unit, Side> placer;
	private Consumer<Side> sideClearer;
	private Consumer<Side> sideMaxRanker;
	private Consumer<String> backgroundSelector;
	private Consumer<String> envEffectListener;
	private Consumer<Boolean> combatRulesListener;
	private Consumer<Boolean> targetTypesListener;
	private Consumer<Boolean> statusImmunitiesListener;
	private Consumer<Boolean> advanceListener;
	private Consumer<Double> scaleListener;
	private GridDimensionsListener gridDimensionsListener;
	private Runnable languageChangedListener;

	/** Callback for the grid-size boxes: the new per-side rows, front-row columns
	 * and back-row columns. */
	public interface GridDimensionsListener {
		void onChange(int rows, int cols, int backRowCols);
	}

	public UnitMenu() {
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.add(buildLanguageSelector());
		north.add(Box.createVerticalStrut(4));
		north.add(buildMapSelector());
		north.add(Box.createVerticalStrut(4));
		north.add(buildEnvEffectSelector());
		north.add(Box.createVerticalStrut(4));
		north.add(buildScaleSelector());
		north.add(Box.createVerticalStrut(4));
		north.add(buildGridSizeSelector());
		add(north, BorderLayout.NORTH);

		buildContent();
		add(content, BorderLayout.CENTER);

		loadUnits();
		filter("");
	}

	private void buildContent() {
		// Search field.
		JPanel searchPanel = new JPanel(new BorderLayout(4, 0));
		searchPanel.add(new JLabel("Search:"), BorderLayout.WEST);
		searchPanel.add(search, BorderLayout.CENTER);
		search.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { filter(search.getText()); }
			@Override public void removeUpdate(DocumentEvent e) { filter(search.getText()); }
			@Override public void changedUpdate(DocumentEvent e) { filter(search.getText()); }
		});

		// Battle-rules toggle: enables/disables cooldowns, reloads, prep time and
		// ammo for the next battle (set before starting one).
		cooldownToggle.setToolTipText("Whether ammo, reload, cooldowns and prep time are enabled.");
		cooldownToggle.setAlignmentX(LEFT_ALIGNMENT);
		cooldownToggle.addActionListener(e -> fireCombatRules());

		// Target-types toggle: when enabled abilities may only hit unit types in
		// their targetable list; when disabled any ability can hit any unit.
		targetTypesToggle.setToolTipText(
				"Whether abilities can only hit unit types in their targetable list.");
		targetTypesToggle.setAlignmentX(LEFT_ALIGNMENT);
		targetTypesToggle.addActionListener(e -> fireTargetTypes());

		// Status-immunities toggle: when enabled units resist status effects of the
		// families they are immune to; when disabled any effect can be applied.
		statusImmunitiesToggle.setToolTipText(
				"Whether units' status-effect immunities are enforced.");
		statusImmunitiesToggle.setAlignmentX(LEFT_ALIGNMENT);
		statusImmunitiesToggle.addActionListener(e -> fireStatusImmunities());

		// Move-forward toggle: when enabled a side's units slide one row forward to
		// fill an emptied front line; when disabled units hold their cells.
		advanceToggle.setToolTipText(
				"Whether units move forward to fill an emptied front row.");
		advanceToggle.setAlignmentX(LEFT_ALIGNMENT);
		advanceToggle.addActionListener(e -> fireAdvance());

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		searchPanel.setAlignmentX(LEFT_ALIGNMENT);
		top.add(cooldownToggle);
		top.add(targetTypesToggle);
		top.add(statusImmunitiesToggle);
		top.add(advanceToggle);
		top.add(Box.createVerticalStrut(4));
		top.add(searchPanel);

		// Unit list, labelled "NAME (Side)"; only the label changes, not the unit.
		unitList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		unitList.setCellRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value,
					int index, boolean isSelected, boolean cellHasFocus) {
				return super.getListCellRendererComponent(list, listLabel((Unit) value),
						index, isSelected, cellHasFocus);
			}
		});
		// Double left-click adds to the player side, double right-click to the enemy.
		unitList.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				// JList only selects on left-click; select the row under a right-click
				// too, so a double right-click places the unit that was clicked.
				if (SwingUtilities.isRightMouseButton(e)) {
					int index = unitList.locationToIndex(e.getPoint());
					if (index >= 0 && unitList.getCellBounds(index, index).contains(e.getPoint()))
						unitList.setSelectedIndex(index);
				}
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() != 2)
					return;
				if (SwingUtilities.isLeftMouseButton(e))
					placeSelected(Side.PLAYER);
				else if (SwingUtilities.isRightMouseButton(e))
					placeSelected(Side.ENEMY);
			}
		});

		// Add-to-side buttons.
		JButton addPlayer = new JButton("Add to Player");
		addPlayer.addActionListener(e -> placeSelected(Side.PLAYER));
		JButton addEnemy = new JButton("Add to Enemy");
		addEnemy.addActionListener(e -> placeSelected(Side.ENEMY));
		JPanel addRow = new JPanel(new GridLayout(1, 2, 4, 0));
		addRow.add(addPlayer);
		addRow.add(addEnemy);

		// Max-rank-side buttons. Slim margins so the long labels fit the default width.
		JButton maxRankPlayer = new JButton("Max Rank Player");
		maxRankPlayer.addActionListener(e -> maxRankSide(Side.PLAYER));
		JButton maxRankEnemy = new JButton("Max Rank Enemy");
		maxRankEnemy.addActionListener(e -> maxRankSide(Side.ENEMY));
		Insets slim = new Insets(2, 2, 2, 2);
		maxRankPlayer.setMargin(slim);
		maxRankEnemy.setMargin(slim);
		JPanel maxRankRow = new JPanel(new GridLayout(1, 2, 4, 0));
		maxRankRow.add(maxRankPlayer);
		maxRankRow.add(maxRankEnemy);

		// Clear-side buttons.
		JButton clearPlayer = new JButton("Clear Player");
		clearPlayer.addActionListener(e -> clearSide(Side.PLAYER));
		JButton clearEnemy = new JButton("Clear Enemy");
		clearEnemy.addActionListener(e -> clearSide(Side.ENEMY));
		JPanel clearRow = new JPanel(new GridLayout(1, 2, 4, 0));
		clearRow.add(clearPlayer);
		clearRow.add(clearEnemy);

		JPanel bottom = new JPanel(new GridLayout(3, 1, 0, 4));
		bottom.add(addRow);
		bottom.add(maxRankRow);
		bottom.add(clearRow);

		content.add(top, BorderLayout.NORTH);
		content.add(new JScrollPane(unitList), BorderLayout.CENTER);
		content.add(bottom, BorderLayout.SOUTH);
	}

	/** Sets the callback invoked when the user adds a unit to the board. */
	public void setPlacer(BiConsumer<Unit, Side> placer) {
		this.placer = placer;
	}

	/** Sets the callback invoked to clear all units from a side. */
	public void setSideClearer(Consumer<Side> sideClearer) {
		this.sideClearer = sideClearer;
	}

	/** Sets the callback invoked to set all units on a side to their maximum rank. */
	public void setSideMaxRanker(Consumer<Side> sideMaxRanker) {
		this.sideMaxRanker = sideMaxRanker;
	}

	/**
	 * Sets the callback invoked when the cooldowns/ammo toggle changes, and
	 * immediately pushes its current value so the field starts in sync.
	 */
	public void setCombatRulesListener(Consumer<Boolean> listener) {
		this.combatRulesListener = listener;
		fireCombatRules();
	}

	private void fireCombatRules() {
		if (combatRulesListener != null)
			combatRulesListener.accept(cooldownToggle.isSelected());
	}

	/**
	 * Sets the callback invoked when the target-types toggle changes, and
	 * immediately pushes its current value so the field starts in sync.
	 */
	public void setTargetTypesListener(Consumer<Boolean> listener) {
		this.targetTypesListener = listener;
		fireTargetTypes();
	}

	private void fireTargetTypes() {
		if (targetTypesListener != null)
			targetTypesListener.accept(targetTypesToggle.isSelected());
	}

	/**
	 * Sets the callback invoked when the status-immunities toggle changes, and
	 * immediately pushes its current value so the field starts in sync.
	 */
	public void setStatusImmunitiesListener(Consumer<Boolean> listener) {
		this.statusImmunitiesListener = listener;
		fireStatusImmunities();
	}

	private void fireStatusImmunities() {
		if (statusImmunitiesListener != null)
			statusImmunitiesListener.accept(statusImmunitiesToggle.isSelected());
	}

	/**
	 * Sets the callback invoked when the move-forward toggle changes, and
	 * immediately pushes its current value so the field starts in sync.
	 */
	public void setAdvanceListener(Consumer<Boolean> listener) {
		this.advanceListener = listener;
		fireAdvance();
	}

	private void fireAdvance() {
		if (advanceListener != null)
			advanceListener.accept(advanceToggle.isSelected());
	}

	private void clearSide(Side side) {
		if (sideClearer != null)
			sideClearer.accept(side);
	}

	private void maxRankSide(Side side) {
		if (sideMaxRanker != null)
			sideMaxRanker.accept(side);
	}

	/**
	 * Builds the language dropdown, listing every {@link Text.Language} with the
	 * bundle's current language pre-selected. Choosing one re-reads the bundle's
	 * text in that language (see {@link GameFiles#setLanguage}). Units and
	 * abilities resolve their names from the loaded text on demand, so the unit
	 * list and (via the {@linkplain #setLanguageChangedListener language-changed
	 * listener}) the battlefield are refreshed to show the new language.
	 */
	private JPanel buildLanguageSelector() {
		for (Text.Language lang : Text.Language.values())
			languageSelector.addItem(lang);
		GameFiles active = GameFiles.active();
		if (active != null)
			languageSelector.setSelectedItem(active.getLanguage());
		languageSelector.addActionListener(e -> {
			Text.Language sel = (Text.Language) languageSelector.getSelectedItem();
			if (sel == null)
				return;
			try {
				GameFiles.active().setLanguage(sel);
			} catch (IOException ex) {
				// Could not read the chosen language's text files; keep whatever text
				// was loaded before so the app stays usable.
				ex.printStackTrace();
				return;
			}
			// Names are looked up from the text live, so a repaint re-renders the
			// unit list in the new language; the listener refreshes the rest.
			unitList.repaint();
			if (languageChangedListener != null)
				languageChangedListener.run();
		});

		JPanel panel = new JPanel(new BorderLayout(4, 0));
		panel.add(new JLabel("Language:"), BorderLayout.WEST);
		panel.add(languageSelector, BorderLayout.CENTER);
		return panel;
	}

	/**
	 * Sets the callback invoked after the text language has been switched, for the
	 * caller to refresh any other views that display loaded text (e.g. the
	 * battlefield). The unit list is refreshed by the menu itself.
	 */
	public void setLanguageChangedListener(Runnable listener) {
		this.languageChangedListener = listener;
	}

	/**
	 * Builds the battlefield-background dropdown (shown where the menu toggle used
	 * to be), listing the bundle's backgrounds with its default selected.
	 * Selecting one notifies the {@linkplain #setBackgroundSelector background
	 * selector}.
	 */
	private JPanel buildMapSelector() {
		try {
			for (String name : GameFiles.active().getBackgroundNames())
				mapSelector.addItem(name);
		} catch (IOException e) {
			// No backgrounds: leave the dropdown empty.
		}
		if (mapSelector.getItemCount() > 0)
			mapSelector.setSelectedIndex(0);
		mapSelector.addActionListener(e -> {
			Object sel = mapSelector.getSelectedItem();
			if (sel != null && backgroundSelector != null)
				backgroundSelector.accept((String) sel);
		});

		JPanel panel = new JPanel(new BorderLayout(4, 0));
		panel.add(new JLabel("Background:"), BorderLayout.WEST);
		panel.add(mapSelector, BorderLayout.CENTER);
		return panel;
	}

	/** Sets the callback invoked when a battlefield background is chosen. */
	public void setBackgroundSelector(Consumer<String> selector) {
		this.backgroundSelector = selector;
	}

	/**
	 * Builds the environment-status-effect dropdown, listing every loaded status
	 * effect whose id contains {@code "env"} with {@code None} as the default. The
	 * chosen effect is applied to a side at the start of each of its turns (see
	 * {@link BattleField#setEnvironmentStatusEffect}); selecting one notifies the
	 * {@linkplain #setEnvironmentStatusEffectListener environment-effect listener}
	 * with its id, or {@code null} for {@code None}.
	 */
	private JPanel buildEnvEffectSelector() {
		envEffectSelector.addItem(NO_ENV_EFFECT); // default, listed first
		try {
			for (String id : GameFiles.active().getStatusEffectIds())
				if (id.contains("env"))
					envEffectSelector.addItem(id);
		} catch (RuntimeException e) {
			// Status effects not loaded yet: leave only the None option.
		}
		envEffectSelector.setSelectedItem(NO_ENV_EFFECT);
		envEffectSelector.addActionListener(e -> {
			Object sel = envEffectSelector.getSelectedItem();
			if (envEffectListener != null)
				envEffectListener.accept(NO_ENV_EFFECT.equals(sel) ? null : (String) sel);
		});

		JPanel panel = new JPanel(new BorderLayout(4, 0));
		panel.add(new JLabel("Environment:"), BorderLayout.WEST);
		panel.add(envEffectSelector, BorderLayout.CENTER);
		return panel;
	}

	/**
	 * Sets the callback invoked when the environment status effect is chosen, and
	 * immediately pushes its current value so the field starts in sync.
	 */
	public void setEnvironmentStatusEffectListener(Consumer<String> listener) {
		this.envEffectListener = listener;
		Object sel = envEffectSelector.getSelectedItem();
		listener.accept(NO_ENV_EFFECT.equals(sel) ? null : (String) sel);
	}

	/**
	 * Builds the field-scale input: a small numeric box (default {@code 1})
	 * controlling the zoom of everything drawn on the battlefield — the tiles,
	 * units, health bars and other field visuals — scaled about the centre of the
	 * field (see {@link BattleField#setFieldScale}). The overlaid UI controls (the
	 * weapons box and the battle buttons) are not affected. The box accepts only a
	 * number (digits with an optional decimal point, e.g. {@code 1.5}); each valid
	 * value is pushed to the {@linkplain #setScaleListener scale listener}, with a
	 * blank or non-positive value treated as 1 (no scaling).
	 */
	private JPanel buildScaleSelector() {
		((AbstractDocument) scaleField.getDocument()).setDocumentFilter(new NumericFilter());
		scaleField.setToolTipText(
			"Zoom for the battlefield drawing (tiles, units, bars); e.g. 1.5 = 1.5x bigger.");
		scaleField.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { fireScale(); }
			@Override public void removeUpdate(DocumentEvent e) { fireScale(); }
			@Override public void changedUpdate(DocumentEvent e) { fireScale(); }
		});

		JPanel panel = new JPanel(new BorderLayout(4, 0));
		panel.add(new JLabel("Field scale:"), BorderLayout.WEST);
		panel.add(scaleField, BorderLayout.CENTER);
		return panel;
	}

	/**
	 * Sets the callback invoked when the field-scale value changes, and immediately
	 * pushes the current value so the field starts in sync.
	 */
	public void setScaleListener(Consumer<Double> listener) {
		this.scaleListener = listener;
		fireScale();
	}

	private void fireScale() {
		if (scaleListener != null)
			scaleListener.accept(parseScale(scaleField.getText()));
	}

	/**
	 * Parses the scale box's text into a positive zoom factor, defaulting to 1 for
	 * a blank, incomplete (e.g. just {@code "."}) or non-positive value.
	 */
	private static double parseScale(String text) {
		if (text == null)
			return 1.0;
		try {
			double value = Double.parseDouble(text.trim());
			return value > 0 ? value : 1.0;
		} catch (NumberFormatException e) {
			return 1.0;
		}
	}

	/**
	 * A document filter that keeps a text field's contents a valid non-negative
	 * decimal number: digits with at most one decimal point (e.g. {@code 1},
	 * {@code 1.5}, {@code .5}). Any edit that would produce something else is
	 * rejected, so the box only ever holds a number.
	 */
	private static final class NumericFilter extends DocumentFilter {
		@Override
		public void insertString(FilterBypass fb, int offset, String text, AttributeSet attr)
				throws BadLocationException {
			if (resultIsNumeric(fb, offset, 0, text))
				super.insertString(fb, offset, text, attr);
		}

		@Override
		public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attr)
				throws BadLocationException {
			if (resultIsNumeric(fb, offset, length, text))
				super.replace(fb, offset, length, text, attr);
		}

		/** Whether applying the edit leaves the field matching {@code \d*\.?\d*}. */
		private boolean resultIsNumeric(FilterBypass fb, int offset, int length, String text)
				throws BadLocationException {
			String current = fb.getDocument().getText(0, fb.getDocument().getLength());
			String result = current.substring(0, offset)
				+ (text == null ? "" : text)
				+ current.substring(offset + length);
			return result.matches("\\d*\\.?\\d*");
		}
	}

	/**
	 * Builds the grid-size controls: three rows of {@code [-] value [+]} stepper
	 * buttons setting the per-side row count, front-row column count and back-row
	 * column count of the battlefield (see {@link BattleField#setGridDimensions}).
	 * The value is display-only — it cannot be typed into — and each button steps it
	 * by one, resizing the board immediately. Values are clamped to a valid range,
	 * so the shown numbers always reflect what is actually in force.
	 */
	private JPanel buildGridSizeSelector() {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.add(gridStepperRow("Rows:", rowsValue,
				() -> changeRows(-1), () -> changeRows(1),
				"Number of rows per side (the front line is the first row)."));
		panel.add(Box.createVerticalStrut(2));
		panel.add(gridStepperRow("Cols:", colsValue,
				() -> changeCols(-1), () -> changeCols(1),
				"Number of cells across the front rows."));
		panel.add(Box.createVerticalStrut(2));
		panel.add(gridStepperRow("Back row cols:", backRowColsValue,
				() -> changeBackRowCols(-1), () -> changeBackRowCols(1),
				"Width of the narrower back row (at most the column count)."));
		refreshGridLabels();
		return panel;
	}

	/**
	 * A "label: [-] value [+]" stepper row for one grid dimension. The value label is
	 * read-only; the minus/plus buttons run the given step actions.
	 */
	private JPanel gridStepperRow(String label, JLabel value,
			Runnable onMinus, Runnable onPlus, String tip) {
		value.setHorizontalAlignment(SwingConstants.CENTER);
		value.setPreferredSize(new Dimension(28, value.getPreferredSize().height));
		value.setToolTipText(tip);
		JPanel stepper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		stepper.add(stepButton("−", onMinus, tip)); // − (minus sign)
		stepper.add(value);
		stepper.add(stepButton("+", onPlus, tip));

		JPanel panel = new JPanel(new BorderLayout(4, 0));
		panel.add(new JLabel(label), BorderLayout.WEST);
		panel.add(stepper, BorderLayout.EAST);
		return panel;
	}

	/** A small, non-focusable stepper button running {@code action} when clicked. */
	private JButton stepButton(String text, Runnable action, String tip) {
		JButton button = new JButton(text);
		button.setMargin(new Insets(0, 6, 0, 6));
		button.setFocusable(false);
		button.setToolTipText(tip);
		button.addActionListener(e -> action.run());
		return button;
	}

	/** Sets the callback invoked when a grid-size value changes. */
	public void setGridDimensionsListener(GridDimensionsListener listener) {
		this.gridDimensionsListener = listener;
	}

	private void changeRows(int delta) {
		applyDimensions(GridGeometry.ROWS + delta, GridGeometry.COLS, GridGeometry.BACK_ROW_COLS);
	}

	private void changeCols(int delta) {
		applyDimensions(GridGeometry.ROWS, GridGeometry.COLS + delta, GridGeometry.BACK_ROW_COLS);
	}

	private void changeBackRowCols(int delta) {
		applyDimensions(GridGeometry.ROWS, GridGeometry.COLS, GridGeometry.BACK_ROW_COLS + delta);
	}

	/**
	 * Resizes the battlefield to the given dimensions (via the listener) and then
	 * refreshes the value labels from the geometry, so they show the clamped values
	 * actually in force (e.g. a back-row count never exceeds the column count, and a
	 * shrunk column count pulls the back row down with it).
	 */
	private void applyDimensions(int rows, int cols, int backRowCols) {
		if (gridDimensionsListener != null)
			gridDimensionsListener.onChange(rows, cols, backRowCols);
		refreshGridLabels();
	}

	/** Updates the three value labels to the geometry's current dimensions. */
	private void refreshGridLabels() {
		rowsValue.setText(Integer.toString(GridGeometry.ROWS));
		colsValue.setText(Integer.toString(GridGeometry.COLS));
		backRowColsValue.setText(Integer.toString(GridGeometry.BACK_ROW_COLS));
	}

	private void loadUnits() {
		try {
			Unit[] units = Unit.getAll();
			if (units != null)
				for (Unit u : units)
					if (u != null)
						allUnits.add(u);
		} catch (RuntimeException e) {
			// Units not loaded yet: leave the list empty.
		}
	}

	private void filter(String query) {
		String q = query == null ? "" : query.trim().toLowerCase();
		listModel.clear();
		for (Unit u : allUnits)
			if (q.isEmpty() || matches(u, q))
				listModel.addElement(u);
	}

	/** The unit's label in the list: its name followed by its side, e.g. "NAME (Hostile)". */
	private static String listLabel(Unit u) {
		return u.getSide() == null ? u.getName() : u.getName() + " (" + u.getSide() + ")";
	}

	private static boolean matches(Unit u, String q) {
		return contains(listLabel(u), q)
			|| contains(u.getShortName(), q)
			|| contains(u.getId(), q);
	}

	private static boolean contains(String value, String q) {
		return value != null && value.toLowerCase().contains(q);
	}

	private void placeSelected(Side side) {
		Unit selected = unitList.getSelectedValue();
		if (selected != null && placer != null)
			placer.accept(selected, side);
	}

	@Override
	public Dimension getPreferredSize() {
		return new Dimension(EXPANDED_WIDTH, super.getPreferredSize().height);
	}
}
