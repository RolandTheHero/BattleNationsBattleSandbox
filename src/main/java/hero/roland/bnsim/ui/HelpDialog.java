package hero.roland.bnsim.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * A modeless window listing sandbox tips, styled like {@link UnitInfoDialog}.
 * Opened from the help button below the volume slider; the rest of the app stays
 * usable while it is open.
 */
final class HelpDialog extends JDialog {
	/** The open help window, if any, so repeated clicks bring it forward instead of stacking copies. */
	private static HelpDialog open;

	private HelpDialog(Window owner) {
		super(owner);
		setTitle("Help");
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		build();
	}

	/** Opens the help window centred on the screen, or brings it forward if already open. */
	static void show(Component parent) {
		if (open != null && open.isDisplayable()) {
			open.toFront();
			return;
		}
		open = new HelpDialog(SwingUtilities.getWindowAncestor(parent));
		// Fit the width to the tips text (plus room for the vertical scrollbar, which
		// appears once the height is capped), so nothing is cut off horizontally.
		open.pack();
		int scrollbar = UIManager.getInt("ScrollBar.width");
		open.setSize(open.getWidth() + (scrollbar > 0 ? scrollbar : 17),
				Math.min(open.getHeight(), 360));
		open.setMinimumSize(new Dimension(320, 220));
		open.setLocationRelativeTo(null); // centre on the screen
		open.setVisible(true);
	}

	private void build() {
		JPanel root = new JPanel(new BorderLayout(0, 8));
		root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		root.setBackground(Color.WHITE);

		JLabel title = new JLabel("Sandbox Tips", SwingConstants.CENTER);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
		root.add(title, BorderLayout.NORTH);

		// Body: scrollable list of tips.
		JLabel info = new JLabel(buildHtml());
		info.setVerticalAlignment(SwingConstants.TOP);
		info.setOpaque(true);
		info.setBackground(Color.WHITE);
		info.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		JScrollPane scroll = new JScrollPane(info,
				ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
				ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createLineBorder(new Color(54, 66, 96)));
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		root.add(scroll, BorderLayout.CENTER);

		// Footer: a close button.
		JButton close = new JButton("Close");
		close.addActionListener(e -> dispose());
		JPanel footer = new JPanel(new FlowLayout(FlowLayout.CENTER));
		footer.setOpaque(false);
		footer.add(close);
		root.add(footer, BorderLayout.SOUTH);

		setContentPane(root);
	}

	private static String buildHtml() {
		return "<html><body style='width:360px; font-family:sans-serif; font-size:11px;'>"
				+ "<p>Most buttons, settings and dropdowns have tooltips that explain what it does.</p><br>"
				+ "<p>Unit search: double left-click and double right-click adds the selected unit"
				+ " to the player side and enemy side respectively.</p><br>"
				+ "<p>Right clicking on a unit on the battlefield outside of battle cycles through"
				+ " their ranks.</p><br>"
				+ "<p>Mousewheel cycles through a selected unit's abilities.</p><br>"
				+ "<p>Hold the middle mouse button and drag to move the battlefield around."
				+ " The Reset View button returns it to its default position.</p>"
				+ "</body></html>";
	}
}
