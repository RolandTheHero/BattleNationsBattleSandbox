package hero.roland.bnsim.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import hero.roland.bnsim.PlacedUnit;
import hero.roland.bnsim.gamefiles.GameFiles;
import hero.roland.bnsim.model.Ability;
import hero.roland.bnsim.model.Ability.DamageType;
import hero.roland.bnsim.model.Ability.StatusEffectChance;
import hero.roland.bnsim.model.StatusEffect;
import hero.roland.bnsim.model.Unit;

/**
 * A window showing a unit's full stats, resistances, weapons and abilities for a
 * chosen rank. Opened from the "!" button in the weapons box.
 *
 * <p>The rank can be stepped up and down with the &minus;/+ buttons to preview how
 * the unit's stats and attack damage scale; it opens on the placed unit's current
 * rank, but stepping here is display-only and never changes the placed unit's real
 * rank, stats or health.
 */
final class UnitInfoDialog extends JDialog {
	private final Unit unit;
	private final boolean hasRanks;
	private final int maxRank;
	private int rank;

	private final JLabel rankLabel = new JLabel("", SwingConstants.CENTER);
	private final JButton minusButton = new JButton("-"); // minus sign
	private final JButton plusButton = new JButton("+");
	private final JLabel info = new JLabel();

	private UnitInfoDialog(Window owner, PlacedUnit placed) {
		super(owner);
		this.unit = placed.getUnit();
		this.hasRanks = unit.getMaxRank() >= 1;
		this.maxRank = Math.max(1, unit.getMaxRank());
		this.rank = Math.min(maxRank, Math.max(1, placed.getRank()));
		setTitle(unit.getName());
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		build();
		refresh();
	}

	/** Opens the info window for the given placed unit, centred on the screen. */
	static void show(Component parent, PlacedUnit placed) {
		Window owner = SwingUtilities.getWindowAncestor(parent);
		UnitInfoDialog dialog = new UnitInfoDialog(owner, placed);
		dialog.setSize(480, 560);
		dialog.setMinimumSize(new Dimension(360, 320));
		dialog.setLocationRelativeTo(null); // centre on the screen
		dialog.setVisible(true);
	}

	private void build() {
		JPanel root = new JPanel(new BorderLayout(0, 8));
		root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		root.setBackground(Color.WHITE);

		// Header: unit name (large) over its id (small, muted) above a
		// [ - Rank X / Y + ] stepper row.
		JLabel title = new JLabel(unit.getName(), SwingConstants.CENTER);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
		title.setAlignmentX(Component.CENTER_ALIGNMENT);

		JLabel idLabel = new JLabel(unit.getId(), SwingConstants.CENTER);
		idLabel.setFont(idLabel.getFont().deriveFont(Font.PLAIN, 11f));
		idLabel.setForeground(new Color(0x66, 0x66, 0x66));
		idLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		rankLabel.setFont(rankLabel.getFont().deriveFont(Font.BOLD, 14f));
		rankLabel.setPreferredSize(new Dimension(110, 24));
		styleStepButton(minusButton);
		styleStepButton(plusButton);
		minusButton.setToolTipText("Preview a lower rank");
		plusButton.setToolTipText("Preview a higher rank");
		minusButton.addActionListener(e -> changeRank(-1));
		plusButton.addActionListener(e -> changeRank(+1));

		JPanel rankRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
		rankRow.setOpaque(false);
		rankRow.add(minusButton);
		rankRow.add(rankLabel);
		rankRow.add(plusButton);
		rankRow.setAlignmentX(Component.CENTER_ALIGNMENT);

		JPanel header = new JPanel();
		header.setOpaque(false);
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.add(title);
		if (unit.getId() != null && !unit.getId().isBlank()) {
			header.add(Box.createVerticalStrut(2));
			header.add(idLabel);
		}
		header.add(Box.createVerticalStrut(6));
		header.add(rankRow);
		root.add(header, BorderLayout.NORTH);

		// Body: scrollable stats and abilities.
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

	private static void styleStepButton(JButton button) {
		button.setFont(button.getFont().deriveFont(Font.BOLD, 16f));
		button.setMargin(new Insets(2, 12, 2, 12));
		button.setFocusable(false);
	}

	private void changeRank(int delta) {
		int next = Math.min(maxRank, Math.max(1, rank + delta));
		if (next != rank) {
			rank = next;
			refresh();
		}
	}

	/** Updates the rank label, stepper buttons and the info body for the current rank. */
	private void refresh() {
		rankLabel.setText("Rank " + rank + " / " + maxRank);
		minusButton.setEnabled(rank > 1);
		plusButton.setEnabled(rank < maxRank);
		info.setText(buildHtml());
	}

	private String buildHtml() {
		StringBuilder sb = new StringBuilder();
		sb.append("<html><body style='width:410px; font-family:sans-serif; font-size:11px;'>");
		GameFiles gf = GameFiles.active();

		if (hasRanks) {
			Unit.Rank r = unit.getRank(rank);

			// Core stats, in two columns.
			//sb.append(sectionHeader("Stats"));
			sb.append("<table cellspacing='0' cellpadding='2'>");
			sb.append(statRow(gf.getText("hinthp"), Integer.toString(r.hp()),
					gf.getText("armor"), Integer.toString(r.armorHp())));
			sb.append(statRow("Bonus Power", Integer.toString(r.power()),
					"Bonus Critical", r.critical() + "%"));
			sb.append(statRow(gf.getText("defense"), Integer.toString(r.defense()),
					gf.getText("dodge"), Integer.toString(r.dodge())));
			sb.append(statRow(gf.getText("bravery"), Integer.toString(r.bravery()),
					gf.getText("blocking"), blockingString(unit.getBlocking())));
			sb.append("</table>");

			// The unit's own tags (Ground, Tank, ...), shown only when it has any.
			String tags = buildTags(unit);
			sb.append("<div style='margin-top:8px;'>").append(tags).append("</div>");

			// Resistances: the damage types whose Base (HP) / Armor modifier differs
			// from 1, plus the status-effect families the unit is immune to, shown as
			// side-by-side Base, Armor and Status columns.
			String baseResist = buildResistances(r, false);
			String armorResist = buildResistances(r, true);
			String statusResist = buildStatusResistances(unit);
			if (!baseResist.isEmpty() || !armorResist.isEmpty() || !statusResist.isEmpty()) {
				sb.append(sectionHeader("Resistances"));
				sb.append("<table cellspacing='0' cellpadding='0'><tr>");
				appendResistColumn(sb, gf.getText("bodydmgmods"), baseResist, false);
				appendResistColumn(sb, gf.getText("armor"), armorResist, !baseResist.isEmpty());
				appendResistColumn(sb, "Immunities", statusResist,
						!baseResist.isEmpty() || !armorResist.isEmpty());
				sb.append("</tr></table>");
			}
		}

		// Weapons and their abilities, shown only when the unit has at least one.
		StringBuilder weapons = new StringBuilder();
		for (Unit.Weapon weapon : unit.getWeapons()) {
			if ("none".equals(weapon.getTag()))
				continue;
			StringBuilder abilities = new StringBuilder();
			for (Unit.Attack attack : weapon.getAttacks()) {
				Ability ability = attack.getAbility();
				if (ability == Ability.NO_ABILITY)
					continue;
				abilities.append(buildAttack(weapon, attack, ability));
			}
			if (abilities.length() == 0)
				continue;

			String weaponInfo = "";
			if (weapon.getAmmo() >= 0)
				weaponInfo = gf.getText("ammo") + " " + weapon.getAmmo();
			if (weapon.getReloadTime() > 0)
				weaponInfo += (weaponInfo.isEmpty() ? "" : " · ")
					+ gf.getText("reload") + " " + weapon.getReloadTime();
			weapons.append("<div style='margin-top:8px;'><font size='4'><b>")
					.append(esc(weapon.getName())).append("</b></font>");
			if (!weaponInfo.isEmpty())
				weapons.append("&nbsp;&nbsp;").append(muted(weaponInfo));
			weapons.append("</div>");
			weapons.append(abilities);
		}
		if (weapons.length() > 0) {
			sb.append(sectionHeader("Weapons & Abilities"));
			sb.append(weapons);
		}

		sb.append("</body></html>");
		return sb.toString();
	}

	/**
	 * Table rows for each damage type whose modifier differs from 1, each labelled
	 * with the damage type's icon and its modifier as a percentage (e.g. a fire
	 * modifier of 1.2 shows as "120%"). When {@code armor} is {@code true} the armor
	 * damage modifier is used, otherwise the HP damage modifier.
	 */
	private static String buildResistances(Unit.Rank r, boolean armor) {
		StringBuilder sb = new StringBuilder();
		for (DamageType type : DamageType.values()) {
			double mod = armor ? r.armorDamageMod(type) : r.damageMod(type);
			if (mod == 1.0)
				continue;
			sb.append("<tr><td valign='middle'>").append(damageTypeIcon(type)).append("</td>")
					.append("<td>&nbsp;&nbsp;</td><td valign='middle'>").append(pct(mod)).append("</td></tr>");
		}
		return sb.toString();
	}

	/**
	 * Table rows for each status-effect family the unit is immune to, each labelled
	 * with the family's UI icon on the left and its display name. Families are listed
	 * in display-name order so the column is stable from one open to the next.
	 */
	private static String buildStatusResistances(Unit unit) {
		StringBuilder sb = new StringBuilder();
		for (StatusEffect.StatusFamily family : unit.getStatusImmunities()) {
			sb.append("<tr><td valign='middle'>").append(statusFamilyIcon(family)).append("</td>")
				.append("<td>&nbsp;&nbsp;</td><td valign='middle'>")
				.append(esc(familyName(family))).append("</td></tr>");
		}
		return sb.toString();
	}

	/**
	 * The unit's own tags as a comma-separated list of names in file order, or an
	 * empty string when it has none. {@code null} entries (a tag name that did not
	 * resolve in the bundle) are skipped.
	 */
	private static String buildTags(Unit unit) {
		StringBuilder sb = new StringBuilder();
		for (Unit.UnitTag tag : unit.getTags()) {
			if (tag == null)
				continue;
			if (sb.length() > 0)
				sb.append(", ");
			sb.append("<b>").append(esc(tag.name())).append("</b>");
		}
		return "Tags: " + sb.toString();
	}

	/** One ability's block: name plus a key/value table of its combat figures. */
	private String buildAttack(Unit.Weapon weapon, Unit.Attack attack, Ability ability) {
		StringBuilder rows = new StringBuilder();
		GameFiles gf = GameFiles.active();
		String number;
		if (hasRanks) {
			int shots = ability.getShotsPerAttack() * ability.getAttacksPerUse();
			int min = attack.getMinDamage(rank);
			int max = attack.getMaxDamage(rank);
			number = (min == max ? Integer.toString(min) : min + "-" + max);
			if (shots > 1) number += " (x" + shots + ")";
		} else {
			number = "—";
		}
		rows.append(row(gf.getText("damage"), damageTypeIcon(ability.getDamageType()), number));
		// Total offense: the unit's rank accuracy plus the ability's own offense.
		if (hasRanks)
			rows.append(kv(gf.getText("accuracy"),
					Integer.toString(unit.getRank(rank).accuracy() + ability.getAttack())));
		rows.append(kv(gf.getText("range"), attack.getMinRange() + "-" + attack.getMaxRange()));
		if (ability.getArmorPiercingRate() > 0)
			rows.append(kv(gf.getText("armorpiercing"), pct(ability.getArmorPiercingRate())));
		if (ability.getCooldown() > 0)
			rows.append(kv(gf.getText("cooldown"), turns(ability.getCooldown())));
		if (ability.getPrepTime() > 0)
			rows.append(kv(gf.getText("chargetime"), turns(ability.getPrepTime())));
		if (ability.getAmmoRequired() > 0)
			rows.append(kv(gf.getText("ammorequired"), Integer.toString(ability.getAmmoRequired())));

		StringBuilder effects = new StringBuilder();
		for (StatusEffectChance sc : ability.getStatusEffects()) {
			if (effects.length() > 0)
				effects.append(", ");
			effects.append(esc(effectName(sc.effect())))
					.append(' ').append(muted("(" + pct(sc.chance()) + ")"));
		}
		if (effects.length() > 0)
			rows.append(kv("Effects", effects.toString()));
		// An ability's critical chance stacks on top of the unit's own critical
		// stat (an integer percent) and the weapon's base critical, so show the
		// combined figure for this rank.
		double unitCrit = hasRanks ? unit.getRank(rank).critical() / 100.0 : 0;
		double baseCrit = unitCrit + weapon.getBaseCritical();
		rows.append(kv(gf.getText("criticalpercent"), pct(baseCrit + ability.getBaseCritical())));
		appendCriticalBonuses(rows, ability, baseCrit);

		// Header row: the ability's icon to the left of its name, both cells
		// middle-aligned so the name sits vertically centred against the (taller)
		// icon.
		String header = "<table cellspacing='0' cellpadding='0'><tr>"
				+ "<td valign='middle'>" + abilityIcon(ability) + "</td>"
				+ "<td valign='middle'>&nbsp;<b>" + esc(ability.getName()) + "</b></td>"
				+ "</tr></table>";
		return "<div style='margin-left:10px; margin-top:4px;'>"
				+ header
				+ "<table cellspacing='0' cellpadding='1'>" + rows + "</table></div>";
	}

	/**
	 * Appends a "Crit vs. <type>" row for each unit type the ability has a
	 * critical bonus against, showing the full chance for that type: the combined
	 * unit and weapon base critical ({@code baseCrit}) plus the ability's base
	 * critical plus the type's bonus. Rows are listed in tag-name order so they
	 * are stable from one open to the next; nothing is written when the ability
	 * has no tag-specific bonuses.
	 */
	private static void appendCriticalBonuses(StringBuilder rows, Ability ability, double baseCrit) {
		GameFiles gf = GameFiles.active();
		List<Map.Entry<Unit.UnitTag, Double>> bonuses =
			new ArrayList<>(ability.getCriticalBonuses().entrySet());
		bonuses.sort(Comparator.comparing(e -> e.getKey().name()));
		for (Map.Entry<Unit.UnitTag, Double> bonus : bonuses) {
			// The string is "Crit%% vs. %@""
			String critText = gf.getText("critpertag")
				.replace("%%", "%")
				.replace("%@", esc(bonus.getKey().name()));
			rows.append(kv(critText,
				pct(baseCrit + ability.getBaseCritical() + bonus.getValue())));
		}
	}

	// --- HTML helpers ------------------------------------------------------

	private static String sectionHeader(String text) {
		return "<div style='margin-top:6px;'><font color='#36425f' size='4'><b>"
				+ esc(text) + "</b></font></div><hr>";
	}

	/** A bold subheading inside a section, matching the weapon-name styling. */
	private static String resistSubheader(String text) {
		return "<div style='margin-top:8px;'><font size='4'><b>" + esc(text) + "</b></font></div>";
	}

	/** One resistance column: a {@code heading} subheading above a table of {@code rows}. */
	private static String resistColumn(String heading, String rows) {
		return resistSubheader(heading)
				+ "<table cellspacing='0' cellpadding='2'>" + rows + "</table>";
	}

	/**
	 * Appends a resistance column cell to {@code sb}, skipping empty columns. When
	 * the column has content and {@code gap} is true (a column already precedes it),
	 * a spacer cell is written first so the columns sit side by side.
	 */
	private static void appendResistColumn(StringBuilder sb, String heading, String rows, boolean gap) {
		if (rows.isEmpty())
			return;
		if (gap)
			sb.append("<td valign='top'>&nbsp;&nbsp;&nbsp;&nbsp;</td>");
		sb.append("<td valign='top'>").append(resistColumn(heading, rows)).append("</td>");
	}

	private static String statRow(String l1, String v1, String l2, String v2) {
		return "<tr>"
				+ "<td>" + muted(l1) + "</td><td><b>" + v1 + "</b></td>"
				+ "<td>&nbsp;&nbsp;&nbsp;</td>"
				+ "<td>" + muted(l2) + "</td><td><b>" + v2 + "</b></td>"
				+ "</tr>";
	}

	/**
	 * A label / value table row. {@code icon} is an optional cell drawn between the
	 * gap and the value (empty string for none); every cell is middle-aligned so an
	 * icon sits vertically centred against the label and value text.
	 */
	private static String row(String label, String icon, String value) {
		return "<tr>"
				+ "<td valign='middle'>" + muted(label) + "</td>"
				+ "<td>&nbsp;&nbsp;</td>"
				+ "<td valign='middle'>" + icon + "</td>"
				+ "<td valign='middle'>&nbsp;" + value + "</td>"
				+ "</tr>";
	}

	private static String kv(String label, String value) {
		return row(label, "", value);
	}

	private static String muted(String s) {
		return "<font color='#666666'>" + s + "</font>";
	}

	private static String pct(double fraction) {
		return Math.round(fraction * 100) + "%";
	}

	private static String turns(int n) {
		GameFiles gf = GameFiles.active();
		return String.format(gf.getText("chargestats"), n);
	}

	private static String blockingString(int blocking) {
		GameFiles gf = GameFiles.active();
		return switch (blocking) {
			case 0 -> "-";
			case 1 -> gf.getText("partial");
			case 2 -> gf.getText("blocking");
			default -> "???";
		};
	}

	/** Largest side (px) a damage-type icon is drawn at inline. */
	private static final int DAMAGE_ICON_SIZE = 18;

	/**
	 * Inline markup for a damage type: its bundle icon scaled to
	 * {@link #DAMAGE_ICON_SIZE}, or its parenthesised text label when the icon
	 * cannot be found.
	 */
	private static String damageTypeIcon(DamageType type) {
		String url = iconUrl(type == null ? null : type.getIcon());
		if (url == null)
			return muted("(" + damageTypeLabel(type) + ")");
		return iconImg(url);
	}

	/** Side (px) the ability's icon is drawn at in its section header. */
	private static final int ABILITY_ICON_SIZE = 28;

	/**
	 * Inline markup for an ability's icon scaled to {@link #ABILITY_ICON_SIZE},
	 * or an empty string when the icon cannot be found. Placed in the section
	 * header's left, middle-aligned cell (see {@link #buildAttack}).
	 */
	private static String abilityIcon(Ability ability) {
		String url = iconUrl(ability == null ? null : ability.getIcon());
		if (url == null)
			return "";
		return "<img src='" + url + "' align='middle' width='" + ABILITY_ICON_SIZE
				+ "' height='" + ABILITY_ICON_SIZE + "'>";
	}

	/**
	 * Inline markup for a status family's UI icon scaled to {@link #DAMAGE_ICON_SIZE},
	 * or an empty string when the icon cannot be found.
	 */
	private static String statusFamilyIcon(StatusEffect.StatusFamily family) {
		String url = iconUrl(family == null ? null : family.getUiIcon());
		return url == null ? "" : iconImg(url);
	}

	/** An {@code <img>} tag for {@code url}, sized to {@link #DAMAGE_ICON_SIZE}. */
	private static String iconImg(String url) {
		// align='middle' centres the icon vertically on the text line.
		return "<img src='" + url + "' align='middle' width='" + DAMAGE_ICON_SIZE
				+ "' height='" + DAMAGE_ICON_SIZE + "'>";
	}

	/** A {@code file:} URL for a bundle icon file, or {@code null} if missing. */
	private static String iconUrl(String filename) {
		if (filename == null)
			return null;
		File file = GameFiles.active().file(filename);
		if (file == null || !file.isFile())
			return null;
		// Encodes spaces in the path (e.g. "Folder of Everything") for a valid URL.
		return file.toURI().toString();
	}

	/** A damage type's enum name as a spaced, title-cased label (e.g. "Depth Charge"). */
	private static String damageTypeLabel(DamageType type) {
		if (type == null)
			return "—";
		StringBuilder sb = new StringBuilder();
		for (String word : type.name().toLowerCase().split("_")) {
			if (word.isEmpty())
				continue;
			if (sb.length() > 0)
				sb.append(' ');
			sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return sb.toString();
	}

	/** A readable name for a status effect, resolved from its family's display text. */
	private static String effectName(StatusEffect effect) {
		if (effect == null)
			return "Effect";
		String name = familyDisplayName(effect.getFamily());
		if (name != null)
			return name;
		if (effect.getDamageType() != null)
			return damageTypeLabel(effect.getDamageType()) + " effect";
		return "Effect";
	}

	/** A readable name for a status family, falling back to a generic label. */
	private static String familyName(StatusEffect.StatusFamily family) {
		String name = familyDisplayName(family);
		return name != null ? name : "Effect";
	}

	/** A status family's display name, resolved from its text key, or {@code null}. */
	private static String familyDisplayName(StatusEffect.StatusFamily family) {
		if (family == null)
			return null;
		String key = family.getDisplayName();
		String text = GameFiles.active().getText(key);
		if (text != null && !text.isBlank())
			return text;
		if (key != null && !key.isBlank())
			return key;
		return null;
	}

	private static String esc(String s) {
		if (s == null)
			return "";
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
