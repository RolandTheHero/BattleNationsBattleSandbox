package hero.roland.bnsim.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
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
import hero.roland.bnsim.model.Unit;

/**
 * A collapsible side menu for searching the loaded {@link Unit}s and adding a
 * selected one to a chosen {@link Side} of the battlefield. The actual
 * placement is delegated to a {@link BiConsumer} supplied via
 * {@link #setPlacer}.
 */
public class UnitMenu extends JPanel {

	private static final int EXPANDED_WIDTH = 250;

	private final JComboBox<String> mapSelector = new JComboBox<>();
	private final JPanel content = new JPanel(new BorderLayout(0, 6));

	private final DefaultListModel<Unit> listModel = new DefaultListModel<>();
	private final JList<Unit> unitList = new JList<>(listModel);
	private final JTextField search = new JTextField();
	private final JCheckBox cooldownToggle = new JCheckBox("Cooldowns & ammo", true);

	private final List<Unit> allUnits = new ArrayList<>();

	private BiConsumer<Unit, Side> placer;
	private Consumer<Side> sideClearer;
	private Consumer<String> backgroundSelector;
	private Consumer<Boolean> combatRulesListener;

	public UnitMenu() {
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		add(buildMapSelector(), BorderLayout.NORTH);

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

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		searchPanel.setAlignmentX(LEFT_ALIGNMENT);
		top.add(cooldownToggle);
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

	private void clearSide(Side side) {
		if (sideClearer != null)
			sideClearer.accept(side);
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
