package hero.roland.bnsim.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

import hero.roland.bnsim.Ability;
import hero.roland.bnsim.BattleSimulator;
import hero.roland.bnsim.PlacedUnit;
import hero.roland.bnsim.Unit;

/**
 * Hosts the {@link BattleField} together with the overlaid battle controls:
 * a "Start Battle" button (bottom-right) in setup mode, an "End Battle" button
 * (top-left) in battle mode, and a panel listing the selected unit's attacks
 * grouped by weapon.
 */
public class ArenaPane extends JLayeredPane {

	private final BattleField field;
	private final JButton startButton = new JButton("Start Battle");
	private final JButton endButton = new JButton("End Battle");
	private final JPanel attackPanel = new JPanel();

	private Consumer<Boolean> onBattleModeChanged;

	public ArenaPane(BattleSimulator sim) {
		field = new BattleField(sim);
		add(field, JLayeredPane.DEFAULT_LAYER);

		startButton.addActionListener(e -> setBattleMode(true));
		endButton.addActionListener(e -> setBattleMode(false));
		endButton.setVisible(false);
		add(startButton, JLayeredPane.PALETTE_LAYER);
		add(endButton, JLayeredPane.PALETTE_LAYER);

		attackPanel.setOpaque(true);
		attackPanel.setBackground(new Color(255, 255, 255, 220));
		attackPanel.setBorder(BorderFactory.createLineBorder(new Color(54, 66, 96)));
		attackPanel.setVisible(false);
		add(attackPanel, JLayeredPane.PALETTE_LAYER);

		field.setAttackerSelectedListener(this::showAttacks);
	}

	public BattleField getField() {
		return field;
	}

	/** Called with {@code true} when entering battle mode, {@code false} when leaving. */
	public void setOnBattleModeChanged(Consumer<Boolean> listener) {
		this.onBattleModeChanged = listener;
	}

	private void setBattleMode(boolean battle) {
		field.setBattleMode(battle);
		startButton.setVisible(!battle);
		endButton.setVisible(battle);
		if (!battle)
			attackPanel.setVisible(false);
		if (onBattleModeChanged != null)
			onBattleModeChanged.accept(battle);
		revalidate();
		repaint();
	}

	/** Rebuilds the attack panel for the selected unit (null clears it). */
	private void showAttacks(PlacedUnit unit) {
		attackPanel.removeAll();
		if (unit == null) {
			attackPanel.setVisible(false);
			revalidate();
			repaint();
			return;
		}

		attackPanel.setLayout(new BoxLayout(attackPanel, BoxLayout.Y_AXIS));
		attackPanel.add(new JLabel(unit.getUnit().getName()));

		ButtonGroup group = new ButtonGroup();
		boolean[] selectedFirst = { false };
		for (Unit.Weapon weapon : unit.getUnit().getWeapons()) {
			if ("none".equals(weapon.getTag()))
				continue;
			JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
			row.setOpaque(false);
			row.add(new JLabel(weapon.getName() + ":"));
			boolean anyAttack = false;
			for (Unit.Attack attack : weapon.getAttacks()) {
				if (attack.getAbility() == Ability.NO_ABILITY)
					continue;
				anyAttack = true;
				JToggleButton button = new JToggleButton(attack.getName());
				group.add(button);
				button.addActionListener(e -> field.setSelectedAttack(attack));
				if (!selectedFirst[0]) {
					button.setSelected(true);
					field.setSelectedAttack(attack);
					selectedFirst[0] = true;
				}
				row.add(button);
			}
			if (anyAttack)
				attackPanel.add(row);
		}

		attackPanel.setVisible(true);
		revalidate();
		repaint();
	}

	@Override
	public void doLayout() {
		int w = getWidth();
		int h = getHeight();
		field.setBounds(0, 0, w, h);

		Dimension start = startButton.getPreferredSize();
		startButton.setBounds(w - start.width - 16, h - start.height - 16,
				start.width, start.height);

		Dimension end = endButton.getPreferredSize();
		endButton.setBounds(16, 16, end.width, end.height);

		if (attackPanel.isVisible()) {
			Dimension ap = attackPanel.getPreferredSize();
			int width = Math.min(ap.width, w - 32);
			attackPanel.setBounds((w - width) / 2, h - ap.height - 64,
					width, ap.height);
		}
	}
}
