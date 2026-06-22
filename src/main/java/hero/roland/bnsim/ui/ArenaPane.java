package hero.roland.bnsim.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;

import hero.roland.bnsim.Ability;
import hero.roland.bnsim.BattleSimulator;
import hero.roland.bnsim.GameFiles;
import hero.roland.bnsim.MusicPlayer;
import hero.roland.bnsim.PlacedUnit;
import hero.roland.bnsim.Side;
import hero.roland.bnsim.SoundPlayer;
import hero.roland.bnsim.Unit;

/**
 * Hosts the {@link BattleField} together with the overlaid battle controls:
 * a "Start Battle" button (bottom-right) in setup mode, "End Battle" and
 * "Pass Turn" buttons (top-left) in battle mode, and a panel listing the
 * selected unit's attacks grouped by weapon.
 */
public class ArenaPane extends JLayeredPane {

	private final BattleField field;
	private final JButton startButton = new JButton("Start Battle");
	private final JButton endButton = new JButton("End Battle");
	private final JButton passButton = new JButton("Pass Turn");
	private final JToggleButton viewEnemyButton = new JToggleButton("View Enemy");
	private final JPanel attackPanel = new JPanel();
	private final JPanel volumePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
	private final JSlider volumeSlider = new JSlider(0, 100, 100);
	private final MusicPlayer music = new MusicPlayer();
	/** Attack icons loaded during the current battle, cleared when it ends. */
	private final Map<String, ImageIcon> iconCache = new HashMap<>();

	private Consumer<Boolean> onBattleModeChanged;
	/** Side of the unit shown in the info panel; decides which edge it anchors to. */
	private Side panelSide = Side.PLAYER;

	public ArenaPane(BattleSimulator sim) {
		field = new BattleField(sim);
		add(field, JLayeredPane.DEFAULT_LAYER);

		startButton.addActionListener(e -> setBattleMode(true));
		endButton.addActionListener(e -> setBattleMode(false));
		endButton.setVisible(false);
		passButton.addActionListener(e -> field.passTurn());
		stylePassButton();
		passButton.setVisible(false);
		viewEnemyButton.setToolTipText(
				"Show enemy units' health, abilities and target area (view-only)");
		viewEnemyButton.addActionListener(
				e -> field.setEnemyViewEnabled(viewEnemyButton.isSelected()));
		viewEnemyButton.setVisible(false);
		add(startButton, JLayeredPane.PALETTE_LAYER);
		add(endButton, JLayeredPane.PALETTE_LAYER);
		add(passButton, JLayeredPane.PALETTE_LAYER);
		add(viewEnemyButton, JLayeredPane.PALETTE_LAYER);

		attackPanel.setOpaque(true);
		attackPanel.setBackground(new Color(255, 255, 255, 220));
		attackPanel.setBorder(BorderFactory.createLineBorder(new Color(54, 66, 96)));
		attackPanel.setVisible(false);
		add(attackPanel, JLayeredPane.PALETTE_LAYER);

		// Master-volume slider (top-right), always visible.
		volumeSlider.setPreferredSize(new Dimension(120, 20));
		volumeSlider.setOpaque(false);
		volumeSlider.addChangeListener(e -> SoundPlayer.setVolume(volumeSlider.getValue() / 100f));
		volumePanel.setOpaque(false);
		volumePanel.add(new JLabel("Volume"));
		volumePanel.add(volumeSlider);
		add(volumePanel, JLayeredPane.PALETTE_LAYER);

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
		passButton.setVisible(battle);
		// Enemy viewing starts off each battle (and is hidden outside battle mode).
		viewEnemyButton.setSelected(false);
		viewEnemyButton.setVisible(battle);
		if (battle)
			music.loop(GameFiles.file("battle_01.mp3"));
		else {
			music.stop();
			attackPanel.setVisible(false);
			iconCache.clear(); // battle over: release the cached attack icons
		}
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

		// Player units anchor the panel to the left edge, enemy units to the right.
		panelSide = unit.getSide();
		attackPanel.setLayout(new BoxLayout(attackPanel, BoxLayout.Y_AXIS));

		// Unit name and health/armor bar, both centered at the top of the panel.
		JLabel name = new JLabel(unit.getUnit().getName(), JLabel.CENTER);
		name.setAlignmentX(CENTER_ALIGNMENT);
		name.setMaximumSize(new Dimension(Integer.MAX_VALUE, name.getPreferredSize().height));
		attackPanel.add(name);
		attackPanel.add(Box.createVerticalStrut(4));
		attackPanel.add(new HealthArmorBar(unit));
		attackPanel.add(Box.createVerticalStrut(6));

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
				JToggleButton button = makeAttackButton(attack);
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

	/** Largest dimension (px) an attack icon is scaled to. */
	private static final int ICON_SIZE = 44;
	/** Border shown around the currently selected attack button. */
	private static final Color SELECT_OUTLINE = new Color(245, 205, 70);

	/**
	 * Builds the toggle button for an attack, showing the ability's icon (or its
	 * name if the icon is missing). The button gains a highlighted border while
	 * selected.
	 */
	private JToggleButton makeAttackButton(Unit.Attack attack) {
		JToggleButton button = new JToggleButton();
		button.setToolTipText(attack.getName());
		ImageIcon icon = attackIcon(attack.getAbility());
		if (icon != null) {
			button.setIcon(icon);
			button.setMargin(new Insets(2, 2, 2, 2));
		} else {
			button.setText(attack.getName());
		}
		// Highlight the selected attack with a coloured border; keep the layout
		// stable by using a same-thickness empty border when unselected.
		Runnable applyBorder = () -> button.setBorder(button.isSelected()
				? BorderFactory.createLineBorder(SELECT_OUTLINE, 3)
				: BorderFactory.createEmptyBorder(3, 3, 3, 3));
		button.addItemListener(e -> applyBorder.run());
		applyBorder.run();
		return button;
	}

	/**
	 * Builds an ability's button icon: its base icon with the damage-type icon
	 * overlaid in the bottom-right corner. Cached for the battle (see
	 * {@link #iconCache}); returns null if the base icon cannot be loaded.
	 */
	private ImageIcon attackIcon(Ability ability) {
		String key = ability.getIcon();
		if (key == null)
			return null;
		ImageIcon cached = iconCache.get(key);
		if (cached != null)
			return cached;
		BufferedImage base = loadImage(ability.getIcon(), ICON_SIZE);
		if (base == null)
			return null;

		BufferedImage out = base;
		Ability.DamageType type = ability.getDamageType();
		BufferedImage badge = (type == null) ? null
				: loadImage(type.getIcon(), Math.max(1, base.getWidth() * 35 / 100));
		if (badge != null) {
			out = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = out.createGraphics();
			g.drawImage(base, 0, 0, null);
			// Bottom-right corner.
			g.drawImage(badge, out.getWidth() - badge.getWidth(),
					out.getHeight() - badge.getHeight(), null);
			g.dispose();
		}
		ImageIcon icon = new ImageIcon(out);
		iconCache.put(key, icon);
		return icon;
	}

	/**
	 * Loads a bundle image, scaled down so its largest side is at most
	 * {@code maxSize} (kept as-is if already smaller), as an ARGB
	 * {@link BufferedImage}, or null if it cannot be read.
	 */
	/**
	 * Gives the Pass button the {@link GameFiles#getPassButton()} image as its
	 * background, with "Pass" centred on top of it and the default button chrome
	 * removed. Falls back to the plain "Pass Turn" text button if the image is
	 * missing.
	 */
	private void stylePassButton() {
		BufferedImage img = loadPassButtonImage();
		if (img == null)
			return;
		passButton.setText("Pass");
		passButton.setIcon(new ImageIcon(img));
		// Darken the image while the button is held down for press feedback.
		passButton.setPressedIcon(new ImageIcon(darken(img, 0.7f)));
		passButton.setHorizontalTextPosition(SwingConstants.CENTER);
		passButton.setVerticalTextPosition(SwingConstants.CENTER);
		passButton.setBorderPainted(false);
		passButton.setContentAreaFilled(false);
		passButton.setFocusPainted(false);
		passButton.setBorder(BorderFactory.createEmptyBorder());
		passButton.setMargin(new Insets(0, 0, 0, 0));
		// Size the label to fill most of the button face (leaving a margin for the
		// image's border) rather than using a fixed point size.
		Font base = passButton.getFont().deriveFont(Font.BOLD);
		passButton.setFont(fitFont(base, "Pass",
				Math.round(img.getWidth() * 0.7f), Math.round(img.getHeight() * 0.5f)));
	}

	/**
	 * The given font scaled so {@code text} fits within {@code maxWidth} by
	 * {@code maxHeight} pixels (using its cap height for the vertical extent).
	 */
	private Font fitFont(Font base, String text, int maxWidth, int maxHeight) {
		FontMetrics fm = passButton.getFontMetrics(base);
		int width = fm.stringWidth(text);
		int height = fm.getAscent();
		if (width <= 0 || height <= 0)
			return base;
		float scale = Math.min(maxWidth / (float) width, maxHeight / (float) height);
		return base.deriveFont(base.getSize2D() * scale);
	}

	/**
	 * A copy of {@code src} with its colours multiplied by {@code factor}
	 * (&lt; 1 darkens), leaving each pixel's alpha untouched so transparent areas
	 * stay transparent.
	 */
	private static BufferedImage darken(BufferedImage src, float factor) {
		int w = src.getWidth();
		int h = src.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = src.getRGB(x, y);
				int a = (argb >>> 24) & 0xFF;
				int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * factor));
				int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * factor));
				int b = Math.min(255, (int) ((argb & 0xFF) * factor));
				out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
			}
		}
		return out;
	}

	/** Loads the Pass button image at its native size, or null if it can't be read. */
	private BufferedImage loadPassButtonImage() {
		File file = GameFiles.getPassButton();
		if (file == null || !file.isFile())
			return null;
		try {
			return ImageIO.read(file);
		} catch (IOException e) {
			return null;
		}
	}

	private BufferedImage loadImage(String name, int maxSize) {
		if (name == null)
			return null;
		File file = GameFiles.file(name);
		if (!file.isFile())
			return null;
		try {
			BufferedImage img = ImageIO.read(file);
			if (img == null)
				return null;
			int max = Math.max(img.getWidth(), img.getHeight());
			if (max <= maxSize)
				return img;
			double scale = (double) maxSize / max;
			int w = (int) Math.round(img.getWidth() * scale);
			int h = (int) Math.round(img.getHeight() * scale);
			BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = scaled.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(img, 0, 0, w, h, null);
			g.dispose();
			return scaled;
		} catch (IOException e) {
			return null;
		}
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

		Dimension pass = passButton.getPreferredSize();
		passButton.setBounds(16, 16 + end.height + 8, pass.width, pass.height);

		Dimension view = viewEnemyButton.getPreferredSize();
		viewEnemyButton.setBounds(16, 16 + end.height + 8 + pass.height + 8,
				view.width, view.height);

		Dimension vol = volumePanel.getPreferredSize();
		volumePanel.setBounds(w - vol.width - 16, 12, vol.width, vol.height);

		if (attackPanel.isVisible()) {
			Dimension ap = attackPanel.getPreferredSize();
			int width = Math.min(ap.width, w - 32);
			// Player units anchor bottom-left, enemy units bottom-right.
			int x = (panelSide == Side.ENEMY) ? (w - width - 16) : 16;
			attackPanel.setBounds(x, h - ap.height - 64, width, ap.height);
		}
	}

	/**
	 * A large health/armor bar for the selected unit: a green HP segment and a
	 * cyan armor segment (sized against the combined maximum, matching the
	 * in-field bars), with the current and maximum values drawn inside.
	 */
	private static final class HealthArmorBar extends JComponent {
		private static final Color HP_COLOR = new Color(70, 210, 60);
		private static final Color ARMOR_COLOR = new Color(0, 200, 255);
		private static final Color BACK = new Color(20, 20, 20);

		private final PlacedUnit unit;

		HealthArmorBar(PlacedUnit unit) {
			this.unit = unit;
			Dimension size = new Dimension(240, 26);
			setPreferredSize(size);
			setMinimumSize(size);
			setMaximumSize(size);
			setAlignmentX(CENTER_ALIGNMENT);
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth(), h = getHeight();
			int maxHp = unit.getMaxHp(), maxArmor = unit.getMaxArmor();
			int maxTotal = Math.max(1, maxHp + maxArmor);
			int curHp = Math.max(0, unit.getCurrentHp());
			int curArmor = Math.max(0, unit.getCurrentArmor());

			g2.setColor(BACK);
			g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);

			int innerW = w - 4;
			int hpLen = (int) Math.round(innerW * (double) curHp / maxTotal);
			int armorLen = (int) Math.round(innerW * (double) curArmor / maxTotal);
			g2.setColor(HP_COLOR);
			g2.fillRect(2, 2, hpLen, h - 4);
			g2.setColor(ARMOR_COLOR);
			g2.fillRect(2 + hpLen, 2, armorLen, h - 4);
			// Black divider between the HP and armor segments.
			if (hpLen > 0 && armorLen > 0) {
				g2.setColor(Color.BLACK);
				g2.fillRect(2 + hpLen, 2, 2, h - 4);
			}

			String text = maxArmor > 0
					? "HP " + curHp + "/" + maxHp + "    ARM " + curArmor + "/" + maxArmor
					: "HP " + curHp + "/" + maxHp;
			g2.setFont(getFont().deriveFont(Font.BOLD, 13f));
			FontMetrics fm = g2.getFontMetrics();
			int tx = (w - fm.stringWidth(text)) / 2;
			int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
			g2.setColor(Color.BLACK);
			g2.drawString(text, tx + 1, ty + 1);
			g2.setColor(Color.WHITE);
			g2.drawString(text, tx, ty);
			g2.dispose();
		}
	}
}
