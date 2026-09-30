package hero.roland.bnsim.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JSplitPane;

import hero.roland.bnsim.BattleSimulator;

/**
 * The main battle-simulator window: a resizable {@link UnitMenu} on the left
 * and an {@link ArenaPane} (battlefield plus battle controls) filling the rest.
 * Starting a battle hides the unit menu; ending it brings the menu back.
 * Create it on the Event Dispatch Thread after the game files have been loaded.
 */
public class BattleFrame extends JFrame {

	/** How narrow the unit menu and the arena can be dragged. */
	private static final int MENU_MIN_WIDTH = 150, ARENA_MIN_WIDTH = 200;

	public BattleFrame() {
		super("Battle Nations Battle Sandbox");
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setLayout(new BorderLayout());

		BattleSimulator sim = new BattleSimulator();
		ArenaPane arena = new ArenaPane(sim);
		UnitMenu menu = new UnitMenu();
		menu.setPlacer(arena.getField()::placeUnit);
		menu.setSideClearer(arena.getField()::clearSide);
		menu.setSideMaxRanker(arena.getField()::maxRankSide);
		menu.setBackgroundSelector(arena.getField()::setBackgroundImage);
		menu.setEnvironmentStatusEffectListener(arena.getField()::setEnvironmentStatusEffect);
		menu.setCombatRulesListener(arena.getField()::setCombatRulesEnabled);
		menu.setTargetTypesListener(arena.getField()::setTargetTypesEnabled);
		menu.setStatusImmunitiesListener(arena.getField()::setStatusImmunitiesEnabled);
		menu.setAdvanceListener(arena.getField()::setAdvanceEnabled);
		menu.setScaleListener(arena.getField()::setFieldScale);
		menu.setGridDimensionsListener(arena.getField()::setGridDimensions);
		menu.setLanguageChangedListener(arena::refreshLanguage);

		// The menu sits in a split pane so its edge can be dragged to resize it. The
		// small minimum sizes let the divider move freely either way.
		menu.setMinimumSize(new Dimension(MENU_MIN_WIDTH, 0));
		arena.setMinimumSize(new Dimension(ARENA_MIN_WIDTH, 0));
		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, menu, arena);
		split.setContinuousLayout(true);
		split.setResizeWeight(0); // window resizes go to the arena, not the menu
		split.setBorder(null);
		int dividerSize = split.getDividerSize();

		// Hide the menu and its divider in battle, then restore the width it had.
		int[] savedLocation = new int[1];
		arena.setOnBattleModeChanged(battle -> {
			if (battle) {
				savedLocation[0] = split.getDividerLocation();
				menu.setVisible(false);
				split.setDividerSize(0);
			} else {
				menu.setVisible(true);
				split.setDividerSize(dividerSize);
				split.setDividerLocation(savedLocation[0]);
			}
			((JComponent) getContentPane()).revalidate();
			getContentPane().repaint();
		});

		add(split, BorderLayout.CENTER);

		pack();
		// Open wider than packed: a 16:9 window at the packed height, within the screen.
		Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		setSize(Math.min(Math.max(getWidth(), getHeight() * 16 / 9), screen.width), getHeight());
		setLocationRelativeTo(null);
	}
}
