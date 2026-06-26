package hero.roland.bnsim.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

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
	private final JCheckBox cooldownToggle = new JCheckBox("Cooldowns & ammo", true);
	private final JCheckBox targetTypesToggle = new JCheckBox("Target types", true);
	private final JCheckBox statusImmunitiesToggle = new JCheckBox("Status immunities", true);
	private final JCheckBox advanceToggle = new JCheckBox("Units advancing", true);

	private final List<Unit> allUnits = new ArrayList<>();

	private BiConsumer<Unit, Side> placer;
	private Consumer<Side> sideClearer;
	private Consumer<String> backgroundSelector;
	private Consumer<String> envEffectListener;
	private Consumer<Boolean> combatRulesListener;
	private Consumer<Boolean> targetTypesListener;
	private Consumer<Boolean> statusImmunitiesListener;
	private Consumer<Boolean> advanceListener;
	private Runnable languageChangedListener;

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

		// Unit list.
		unitList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		unitList.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2)
					placeSelected(Side.PLAYER);
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

		// Clear-side buttons.
		JButton clearPlayer = new JButton("Clear Player");
		clearPlayer.addActionListener(e -> clearSide(Side.PLAYER));
		JButton clearEnemy = new JButton("Clear Enemy");
		clearEnemy.addActionListener(e -> clearSide(Side.ENEMY));
		JPanel clearRow = new JPanel(new GridLayout(1, 2, 4, 0));
		clearRow.add(clearPlayer);
		clearRow.add(clearEnemy);

		JPanel bottom = new JPanel(new BorderLayout(0, 4));
		bottom.add(addRow, BorderLayout.NORTH);
		bottom.add(clearRow, BorderLayout.SOUTH);

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
	 * to be), listing the bundle's {@code BattleMap*.png} files with
	 * {@code BattleMap.png} as the default. Selecting one notifies the
	 * {@linkplain #setBackgroundSelector background selector}.
	 */
	private JPanel buildMapSelector() {
		Set<String> names = new LinkedHashSet<>();
		names.add("BattleMap.png"); // default, listed first
		for (File f : GameFiles.active().glob("BattleMap*.png"))
			names.add(f.getName());
		for (String n : names)
			mapSelector.addItem(n);
		mapSelector.setSelectedItem("BattleMap.png");
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

	private static boolean matches(Unit u, String q) {
		return contains(u.getName(), q)
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
