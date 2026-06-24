package hero.roland.bnsim.ui;

import java.awt.BorderLayout;

import javax.swing.JComponent;
import javax.swing.JFrame;

import hero.roland.bnsim.BattleSimulator;

/**
 * The main battle-simulator window: a collapsible {@link UnitMenu} on the left
 * and an {@link ArenaPane} (battlefield plus battle controls) filling the rest.
 * Starting a battle hides the unit menu; ending it brings the menu back.
 * Create it on the Event Dispatch Thread after the game files have been loaded.
 */
public class BattleFrame extends JFrame {

	public BattleFrame() {
		super("Battle Nations Battle Simulator");
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setLayout(new BorderLayout());

		BattleSimulator sim = new BattleSimulator();
		ArenaPane arena = new ArenaPane(sim);
		UnitMenu menu = new UnitMenu();
		menu.setPlacer(arena.getField()::placeUnit);
		menu.setSideClearer(arena.getField()::clearSide);
		menu.setBackgroundSelector(arena.getField()::setBackgroundImage);
		menu.setCombatRulesListener(arena.getField()::setCombatRulesEnabled);
		menu.setTargetTypesListener(arena.getField()::setTargetTypesEnabled);
		menu.setStatusImmunitiesListener(arena.getField()::setStatusImmunitiesEnabled);

		arena.setOnBattleModeChanged(battle -> {
			menu.setVisible(!battle);
			((JComponent) getContentPane()).revalidate();
			getContentPane().repaint();
		});

		add(menu, BorderLayout.WEST);
		add(arena, BorderLayout.CENTER);

		pack();
		setLocationRelativeTo(null);
	}
}
