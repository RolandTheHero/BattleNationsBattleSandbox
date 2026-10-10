/*
 * Battle Nations Battle Sandbox
 *
 * Adapted from Battle Nations Animation Grabber (BaNG),
 * https://github.com/bobmath/BattleNationsAnimation
 * Copyright (C) 2014 Robert Mathews. Licensed under the GNU General Public
 * License version 2; see the LICENSE file.
 *
 * Modified 2026 by RolandTheHero; the git history records each change and
 * its date.
 */

package hero.roland.bnsim.gamefiles.oldformat;

import java.awt.Polygon;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.imageio.ImageIO;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import hero.roland.bnsim.gamefiles.AbstractGameFiles;
import hero.roland.bnsim.model.Ability;
import hero.roland.bnsim.model.Bitmap;
import hero.roland.bnsim.model.Frame;
import hero.roland.bnsim.model.Sound;
import hero.roland.bnsim.model.StatusEffect;
import hero.roland.bnsim.model.Timeline;
import hero.roland.bnsim.model.Unit;
import hero.roland.bnsim.util.FileFormatException;
import hero.roland.bnsim.util.GlobFilter;
import hero.roland.bnsim.util.LittleEndianInputStream;

/**
 * Loads a bundle in the old game-file format: a folder of loose JSON files with
 * string ids and camelCase fields (e.g. {@code BattleUnits.json}), binary
 * {@code _Timeline.bin} animations, {@code .z2raw} sprite sheets, and plain
 * image and sound files.
 */
public class OldGameFiles extends AbstractGameFiles {
	public OldGameFiles(File bundleFolder) { super(bundleFolder); }

	@Override
	protected void loadText() throws IOException {
		// Drop any previously loaded text so re-running for a new language doesn't
		// leave strings from the old one behind.
		text.clear();
		try {
			loadTextFile(language.filename());
			loadTextFile(language.deltaFilename());
		} catch (JSONException e) {
			throw new IllegalArgumentException("Json type error", e);
		}
	}

	private void loadTextFile(String filename) throws IOException {
		JSONObject json = readJson(filename);
		for (String key : json.keySet())
			text.put(key.toLowerCase(), json.getString(key));
	}

	@Override
	protected void loadStatusFamilies() throws IOException {
		JSONObject json = readJson("StatusEffectFamiliesConfig.json");
		for (String key : json.keySet()) {
			JSONObject family = json.getJSONObject(key);
			statusFamilies.put(key, new StatusEffect.StatusFamily(
					family.optString("colorHex", "#FFFFFF"),
					family.optString("displayName", "seUnknown"),
					family.optString("effectIcon", "suppressor_firemod_icon") + "@2x.png",
					family.optDouble("pulseSpeed", 1d),
					family.optString("sound", null),
					family.optString("uiIcon", "BN_iconFireMod") + "@2x.png"));
		}
	}

	@Override
	protected void loadStatusEffects() throws IOException {
		statusEffects.put("suppression", new StatusEffect.Suppression());
		try {
			JSONObject json = readJson("StatusEffectsConfig.json");
			for (String key : json.keySet())
				statusEffects.put(key, parseStatusEffect(json.getJSONObject(key)));
		} catch (JSONException e) {
			throw new IllegalArgumentException("Json type error", e);
		}
	}

	private StatusEffect parseStatusEffect(JSONObject stats) {
		StatusEffect.Definition def = new StatusEffect.Definition();
		def.duration = stats.optInt("duration", 1);
		def.diminishing = stats.optBoolean("dot_Diminishing", true);
		def.abilityDamageMultiplier = stats.optDouble("dot_AbilityDamageMult", 0d);
		String damageType = stats.optString("dot_DamageType", null);
		if (damageType != null)
			def.damageType = Ability.DamageType.fromString(damageType);
		def.family = getStatusFamily(stats.optString("family", null));
		def.armorPiercingRate = stats.optDouble("dot_apPercent", 0);
		def.bonusDamage = stats.optInt("dot_BonusDamage", 0);

		// Stun/Freeze
		def.blockAction = stats.optBoolean("stun_BlockAction", false);
		def.blockMovement = stats.optBoolean("stun_BlockMovement", false);
		def.damageBreak = stats.optBoolean("stun_DamageBreak", false);
		readDamageMods(stats.optJSONObject("stun_DamageMods"), def.damageMods, 1);
		readDamageMods(stats.optJSONObject("stun_ArmorDamageMods"), def.armorDamageMods, 1);
		if (def.blockAction) def.duration--; // Bandaid fix for recent game update changing how durations works
		return new StatusEffect(def);
	}

	@Override
	protected void loadAbilities() throws IOException {
		JSONObject damageAnim = readJson("DamageAnimConfig.json");
		JSONObject json = readJson("BattleAbilities.json");
		for (String key : json.keySet())
			abilities.put(key, parseAbility(key, json.getJSONObject(key), damageAnim));
	}

	private Ability parseAbility(String tag, JSONObject json, JSONObject damageAnim) {
		Ability.Definition def = new Ability.Definition();
		def.nameId = json.optString("name", null);
		def.icon = json.getString("icon");
		if (!def.icon.endsWith(".png")) def.icon += "@2x.png";
		def.infantryHitSound = json.optString("inf_hitsound", null);
		def.vehicleHitSound = json.optString("veh_hitsound", null);
		String animType = json.optString("damageAnimationType", null);
		if (animType != null) {
			JSONObject anim = damageAnim.getJSONObject(animType);
			def.frontAnimName = anim.optString("front", null);
			def.backAnimName = anim.optString("back", null);
		}

		JSONObject stats = json.getJSONObject("stats");
		def.damageBonus = stats.optInt("damage", 0);
		def.damageFromWeapon = stats.optDouble("damageFromWeapon", 1);
		def.damageFromUnit = stats.optDouble("damageFromUnit", 1);
		def.minRange = stats.optInt("minRange", 1);
		def.maxRange = stats.optInt("maxRange", 1);
		def.shotsPerAttack = stats.optInt("shotsPerAttack", 1);
		def.attacksPerUse = stats.optInt("attacksPerUse", 1);
		def.lineOfFire = stats.optInt("lineOfFire", 0);
		def.capture = stats.getBoolean("capture");
		def.minHpPercent = stats.optDouble("minHPPercent", Double.NEGATIVE_INFINITY);
		def.attackDirection = stats.optString("attackDirection", "front").equalsIgnoreCase("front")
				? Ability.AttackDirection.FRONT : Ability.AttackDirection.BACK;
		def.damageType = Ability.DamageType.fromString(stats.getJSONArray("damageType").getString(0));
		def.damageArea = parseArea(stats.optJSONObject("damageArea"), false);
		JSONObject targ = stats.optJSONObject("targetArea");
		if (targ != null) {
			def.targetType = Ability.TargetType.fromString(targ.optString("type", "Target"));
			def.randomTarget = targ.getBoolean("random");
			def.aoeDelay = (int) Math.round(targ.optDouble("aoeOrderDelay", 0) * 20); // seconds -> frames
			def.targetArea = parseArea(targ, def.randomTarget);
		}
		def.armorPiercingRate = stats.optDouble("armorPiercingPercent", 0);
		// "statusEffects" maps each effect id to its chance (percent) of applying.
		JSONObject effects = stats.optJSONObject("statusEffects");
		if (effects != null) {
			for (String effectId : effects.keySet())
				def.statusEffects.add(new Ability.StatusEffectChance(
						getStatusEffect(effectId), effects.optDouble(effectId, 0d) / 100));
		}
		// Base critical-hit chance is authored as a percent. "criticalBonuses" maps a
		// unit-type name (a UnitTag, e.g. "Tank") to extra chance (percent) added when
		// the target has that tag — see Ability.getCriticalRate.
		def.baseCritical = stats.optDouble("criticalHitPercent", 5d) / 100;
		JSONObject bonuses = stats.optJSONObject("criticalBonuses");
		if (bonuses != null) {
			for (String key : bonuses.keySet()) {
				double bonus = bonuses.getDouble(key);
				if (key.equals("Battleships")) key = "Battleship"; // Game file typo
				Unit.UnitTag unitTag = getUnitTag(key);
				if (unitTag == null)
					throw new IllegalArgumentException("Unknown unit tag in ability " + tag + ": " + key);
				def.criticalBonuses.put(unitTag, bonus / 100);
			}
		}
		def.cooldown = stats.optInt("abilityCooldown", 0);
		def.globalCooldown = stats.optInt("globalCooldown", 0);
		def.ammoRequired = stats.optInt("ammoRequired", 0);
		def.prepTime = stats.optInt("chargeTime", 0);
		JSONArray targets = stats.optJSONArray("targets");
		if (targets != null) {
			for (int i = 0; i < targets.length(); i++) {
				Unit.UnitTag unitTag = getUnitTag(targets.getString(i));
				if (unitTag != null)
					def.targetableTags.add(unitTag);
			}
		}
		def.attack = stats.optInt("attack", 0);
		def.secondaryDamageRatio = stats.optDouble("secondaryDamagePercent", 0d) / 100;
		def.damageDistraction = stats.optDouble("damage_distraction", 0d);
		def.damageDistractionBonus = stats.optInt("damage_distractionBonus", 0);
		return new Ability(this, tag, def);
	}

	/** An ability's target or damage area, or {@code null} if it has none. In a
	 * random area each square's weight is its chance of being picked. */
	private static Ability.TargetSquare[] parseArea(JSONObject area, boolean random) {
		if (area == null) return null;
		JSONArray data = area.optJSONArray("data");
		if (data == null) return null;

		double weight = 0;
		if (random) {
			for (int i = 0; i < data.length(); i++)
				weight += data.getJSONObject(i).optDouble("weight", 0);
		}

		Ability.TargetSquare[] squares = new Ability.TargetSquare[data.length()];
		for (int i = 0; i < squares.length; i++) {
			JSONObject square = data.getJSONObject(i);
			JSONObject pos = square.optJSONObject("pos");
			int x = pos == null ? 0 : pos.optInt("x", 0);
			int y = pos == null ? 0 : pos.optInt("y", 0);
			if (weight == 0) {
				squares[i] = new Ability.TargetSquare(x, y, square.optInt("order", 0),
						square.optDouble("damagePercent", 100d) / 100, 1);
			} else {
				double chance = square.optDouble("weight", 0) / weight;
				squares[i] = new Ability.TargetSquare(x, y, 0, chance, chance);
			}
		}
		return squares;
	}

	@Override
	protected void loadUnits() throws IOException {
		try {
			JSONObject json = readJson("BattleUnits.json");
			for (String key : json.keySet())
				units.put(key, parseUnit(key, json.getJSONObject(key)));
		} catch (JSONException e) {
			throw new FileFormatException("Json type error", e);
		}
	}

	private Unit parseUnit(String id, JSONObject json) {
		Unit.Definition def = new Unit.Definition();
		def.nameId = json.optString("name", null);
		def.shortNameId = json.optString("shortName", null);
		def.side = json.optString("side", "Other");
		def.backAnimName = json.optString("backIdleAnimation", null);
		def.frontAnimName = json.optString("frontIdleAnimation", null);
		def.deathAnimName = json.optString("deathAnimationName", "troopdeath");
		def.blocking = json.optInt("blocking", Unit.NONE);
		def.deathSpawnedUnit = json.optString("deathSpawnedUnit", null);
		JSONArray tags = json.optJSONArray("tags");
		if (tags != null) {
			for (int i = 0; i < tags.length(); i++)
				def.tags.add(getUnitTag(tags.getString(i)));
		}
		JSONArray immunities = json.optJSONArray("statusEffectImmunities");
		if (immunities != null) {
			for (int i = 0; i < immunities.length(); i++) {
				StatusEffect.StatusFamily family = getStatusFamily(immunities.getString(i));
				if (family != null)
					def.statusEffectImmunities.add(family);
			}
		}
		JSONArray stats = json.getJSONArray("stats");
		for (int i = 0; i < stats.length(); i++)
			def.ranks.add(parseRank(stats.getJSONObject(i)));

		// Weapons are listed primary, secondary, then any others alphabetically.
		JSONObject weapons = json.optJSONObject("weapons");
		if (weapons != null) {
			Map<String, Unit.WeaponDefinition> sorted = new TreeMap<>();
			for (String key : weapons.keySet()) {
				String sortKey = switch (key) {
					case "primary" -> "1primary";
					case "secondary" -> "2secondary";
					default -> key;
				};
				sorted.put(sortKey, parseWeapon(key, weapons.getJSONObject(key)));
			}
			def.weapons.addAll(sorted.values());
		}
		return new Unit(this, id, def);
	}

	private static Unit.Rank parseRank(JSONObject json) {
		Map<Ability.DamageType, Double> damageMods = new EnumMap<>(Ability.DamageType.class);
		Map<Ability.DamageType, Double> armorDamageMods = new EnumMap<>(Ability.DamageType.class);
		readDamageMods(json.optJSONObject("damageMods"), damageMods, 1);
		readDamageMods(json.optJSONObject("armorDamageMods"), armorDamageMods, 1);
		return new Unit.Rank(
				json.optInt("power", 0),
				json.optInt("accuracy", 0),
				json.optInt("bravery", 0),
				json.optInt("critical", 0),
				json.optInt("defense", 0),
				json.optInt("hp", 10),
				json.optInt("armorHp", 0),
				json.optInt("dodge", 0),
				damageMods, armorDamageMods);
	}

	private static Unit.WeaponDefinition parseWeapon(String tag, JSONObject json) {
		Unit.WeaponDefinition def = new Unit.WeaponDefinition();
		def.tag = tag;
		def.nameId = json.optString("name", null);
		def.frontAnimName = json.optString("frontattackAnimation", null);
		def.backAnimName = json.optString("backattackAnimation", null);
		def.firesoundFrame = json.optInt("firesoundFrame", 0);
		def.hitDelay = json.optInt("damageAnimationDelay", 0) + def.firesoundFrame;
		def.firesound = json.optString("firesound", null);
		JSONObject stats = json.optJSONObject("stats");
		if (stats != null) {
			def.minDamage = stats.optInt("base_damage_min", 0);
			def.maxDamage = stats.optInt("base_damage_max", 0);
			def.rangeBonus = stats.optInt("rangeBonus", 0);
			def.ammo = stats.optInt("ammo", -1);
			def.reloadTime = stats.optInt("reloadTime", 0);
			def.baseAttack = stats.optInt("base_ATK", 0);
			def.baseCritRate = stats.optDouble("base_critPercent", 0d) / 100;
		}
		JSONArray abilities = json.getJSONArray("abilities");
		for (int i = 0; i < abilities.length(); i++)
			def.abilities.add(abilities.optString(i, null));
		return def;
	}

	/** Copies a damage-type-name to multiplier map into {@code dest}. */
	private static void readDamageMods(JSONObject json, Map<Ability.DamageType, Double> dest, double defaultMod) {
		if (json == null) return;
		for (String key : json.keySet())
			dest.put(Ability.DamageType.fromString(key), json.optDouble(key, defaultMod));
	}

	@Override
	protected void loadUnitTags() throws IOException {
		JSONObject json = readJson("BattleConfig.json");
		loadUnitTags(json.getJSONObject("tags"), null);
	}

	private void loadUnitTags(JSONObject json, Unit.UnitTag parent) {
		for (String key : json.keySet()) {
			Unit.UnitTag t = new Unit.UnitTag(key, parent);
			unitTags.put(key, t);
			loadUnitTags(json.getJSONObject(key), t);
		}
	}

	// --- Sprites -------------------------------------------------------------

	/** Reads every {@code <package>_Metadata.json}, which lists the package's animations. */
	@Override
	protected Map<String, String> buildTimelineIndex() throws IOException {
		Map<String, String> index = new HashMap<>();
		try {
			for (File file : glob("*_Metadata.json")) {
				String pack = file.getName();
				pack = pack.substring(0, pack.length() - "_Metadata.json".length());
				JSONObject meta = readJson(new FileInputStream(file));
				for (Object name : meta.getJSONArray("animationNames"))
					index.put(((String) name).toLowerCase(), pack);
			}
		} catch (ClassCastException | JSONException e) {
			throw new FileFormatException("Json type error", e);
		}
		return index;
	}

	/** Reads a package's {@code _Timeline.bin}. */
	@Override
	protected Map<String, Timeline> readTimelinePackage(String pack) throws IOException {
		Map<String, Timeline> result = new HashMap<>();
		try (LittleEndianInputStream in = new LittleEndianInputStream(open(pack + "_Timeline.bin"), 256)) {
			int ver = in.readShort();
			if (ver != 4 && ver != 6 && ver != 8)
				throw new FileFormatException("Unknown version");
			in.readByte();
			int num = in.readShort();
			in.readShort();
			for (int i = 0; i < num; i++) {
				Timeline timeline = readTimeline(in, ver, pack);
				result.put(timeline.getName().toLowerCase(), timeline);
			}
		} catch (ArrayIndexOutOfBoundsException e) {
			throw new FileFormatException("Invalid array index", e);
		}
		return result;
	}

	/** A timeline's vertex: its position on screen (1) and in the texture (2). */
	private static class Vertex {
		int x1, y1, x2, y2;
		float alpha = 1;
	}

	private static Timeline readTimeline(LittleEndianInputStream in, int ver, String pack) throws IOException {
		String name = in.readCString(256);
		in.readShort();

		int numPoints = in.readUnsignedShort();
		int alpha = 0;
		if (ver == 8) {
			switch (in.readShort()) {
			case 0: break;
			case 1: alpha = 1; break;
			case 0x101: alpha = 4; break;
			default: throw new FileFormatException("Unknown point size");
			}
		}
		Vertex[] coords = new Vertex[numPoints];
		for (int i = 0; i < numPoints; i++) {
			Vertex point = new Vertex();
			point.x1 = in.readShort();
			point.y1 = in.readShort();
			if (ver == 4) in.readShort();
			point.x2 = in.readShort();
			point.y2 = in.readShort();
			for (int j = 0; j < alpha; j++)
				point.alpha = Math.min(point.alpha, in.readFloat());
			coords[i] = point;
		}

		int xMin = in.readShort();
		int xMax = in.readShort();
		int yMin = in.readShort();
		int yMax = in.readShort();

		double scale = (ver > 4) ? 1.0/32 : 1;
		Frame[] frames = new Frame[in.readUnsignedShort()];
		for (int i = 0; i < frames.length; i++)
			frames[i] = readFrame(in, ver, coords, scale);

		// Later versions play the frames in an explicit order, repeating some.
		if (ver > 4) {
			int numSeq = in.readUnsignedShort();
			if (numSeq <= 0)
				throw new FileFormatException("Invalid sequence count");
			in.readShort();
			Frame[] sequence = new Frame[numSeq];
			for (int i = 0; i < numSeq; i++)
				sequence[i] = frames[in.readUnsignedShort()];
			frames = sequence;
		}
		return new Timeline(pack, name, frames, xMin, xMax, yMin, yMax, scale);
	}

	/** Reads one frame: quads given as six vertex indices (two triangles). */
	private static Frame readFrame(LittleEndianInputStream in, int ver, Vertex[] coords, double scale)
			throws IOException {
		int numPts = in.readUnsignedShort();
		if (numPts < 0 || numPts % 6 != 0)
			throw new FileFormatException("Unexpected frame size");
		int numPolys = numPts / 6;
		Polygon[] polys = new Polygon[numPolys];
		AffineTransform[] transforms = new AffineTransform[numPolys];
		float[] alpha = new float[numPolys];
		if (ver > 4) in.readByte();

		int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE;
		int yMin = Integer.MAX_VALUE, yMax = Integer.MIN_VALUE;
		boolean hasAlpha = false;
		int[] p = new int[6];
		for (int i = 0; i < numPolys; i++) {
			for (int j = 0; j < 6; j++)
				p[j] = in.readUnsignedShort();
			if (p[3] != p[0] || p[4] != p[2])
				throw new FileFormatException("Unexpected frame arrangement");
			Vertex p0 = coords[p[0]], p1 = coords[p[1]], p2 = coords[p[2]], p3 = coords[p[5]];

			if (p0.alpha != 1) hasAlpha = true;
			alpha[i] = p0.alpha;
			if (p0.alpha >= 0.5f / 255) {
				for (Vertex v : new Vertex[] { p0, p1, p2, p3 }) {
					xMin = Math.min(xMin, v.x1);
					xMax = Math.max(xMax, v.x1);
					yMin = Math.min(yMin, v.y1);
					yMax = Math.max(yMax, v.y1);
				}
			}

			// Maps the quad's texture corners onto its screen corners.
			AffineTransform t = new AffineTransform(
					(p1.x1 - p0.x1) * scale, (p1.y1 - p0.y1) * scale,
					(p2.x1 - p0.x1) * scale, (p2.y1 - p0.y1) * scale,
					p0.x1 * scale, p0.y1 * scale);
			AffineTransform t2 = new AffineTransform(
					p1.x2 - p0.x2, p1.y2 - p0.y2,
					p2.x2 - p0.x2, p2.y2 - p0.y2,
					p0.x2, p0.y2);
			try {
				t.concatenate(t2.createInverse());
			} catch (NoninvertibleTransformException e) {
				throw new FileFormatException("Bad transform", e);
			}
			transforms[i] = t;
			polys[i] = new Polygon(new int[] { p0.x2, p1.x2, p2.x2, p3.x2 },
					new int[] { p0.y2, p1.y2, p2.y2, p3.y2 }, 4);
		}
		return new Frame(transforms, polys, hasAlpha ? alpha : null, xMin, xMax, yMin, yMax);
	}

	/** {@code BattleMap.png} (the default), then any other {@code BattleMap*.png} files. */
	@Override
	protected List<String> listBackgrounds() {
		Set<String> names = new LinkedHashSet<>();
		names.add("BattleMap.png");
		for (File f : glob("BattleMap*.png"))
			names.add(f.getName());
		return new ArrayList<>(names);
	}

	@Override
	protected BufferedImage readBackground(String name) throws IOException {
		return readImage(name);
	}

	// --- Images and sounds ---------------------------------------------------

	/** An image file in the bundle folder. */
	@Override
	protected BufferedImage readImage(String name) throws IOException {
		File file = file(name);
		return file.isFile() ? ImageIO.read(file) : null;
	}

	/** Extensions tried, in order, for a sound named without one. */
	private static final String[] SOUND_EXTENSIONS = { "mp3", "wav", "caf" };

	/** A sound file in the bundle folder, named with or without its extension. */
	@Override
	protected Sound readSound(String name) throws IOException {
		File file = file(name);
		if (file.isFile()) {
			int dot = name.lastIndexOf('.');
			String format = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
			return new Sound(format, Files.readAllBytes(file.toPath()));
		}
		for (String ext : SOUND_EXTENSIONS) {
			file = file(name + "." + ext);
			if (file.isFile())
				return new Sound(ext, Files.readAllBytes(file.toPath()));
		}
		return null;
	}

	// --- Files in the bundle folder ------------------------------------------

	private File file(String filename) {
		return new File(bundleFolder, filename);
	}

	private InputStream open(String filename) throws IOException {
		return new FileInputStream(file(filename));
	}

	private JSONObject readJson(String filename) throws IOException {
		return new JSONObject(Files.readString(file(filename).toPath()));
	}

	/** Parses JSON from a stream, closing it afterwards. */
	private static JSONObject readJson(InputStream in) throws IOException {
		try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			return new JSONObject(new JSONTokener(reader));
		} catch (JSONException e) {
			throw new FileFormatException("Json parse error", e);
		}
	}

	/** The files in the bundle folder matching a glob pattern, sorted by name (ignoring case). */
	private File[] glob(String pattern) {
		File[] files = bundleFolder.listFiles(new GlobFilter(pattern));
		if (files == null)
			return new File[0];
		Arrays.sort(files, Comparator.comparing((File f) -> f.getName().toLowerCase()));
		return files;
	}

	/** Reads a package's {@code _0.z2raw} sprite sheet: raw or palette + RLE pixels. */
	@Override
	protected Bitmap readBitmap(String pack) throws IOException {
		try (LittleEndianInputStream in = new LittleEndianInputStream(open(pack + "_0.z2raw"))) {
			int ver = in.readInt();
			if (ver < 0 || ver > 1)
				throw new FileFormatException("Unrecognized version");
			int width = in.readInt();
			int height = in.readInt();
			int bits = in.readInt();
			BufferedImage im = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			if (ver == 0) {
				for (int y = 0; y < height; y++)
					for (int x = 0; x < width; x++)
						im.setRGB(x, y, readPixel(in, bits));
			} else {
				readRLE(im, in, bits);
			}
			return new Bitmap(pack, im, bits);
		} catch (ArrayIndexOutOfBoundsException e) {
			throw new FileFormatException("Invalid array index", e);
		}
	}

	private static void readRLE(BufferedImage im, LittleEndianInputStream in, int bits) throws IOException {
		in.readInt(); // length
		int palSize = in.readInt();
		if (palSize < 1 || palSize > 256)
			throw new FileFormatException("Invalid palette size");
		int[] pal = new int[palSize];
		for (int i = 0; i < palSize; i++)
			pal[i] = readPixel(in, bits);

		int width = im.getWidth(), height = im.getHeight();
		int x = 0, y = 0;
		while (y < height) {
			int c = in.readByte();
			int num = (c >> 1) + 1;
			if ((c & 1) == 0) {
				for (int i = 0; i < num; i++) {
					im.setRGB(x, y, pal[in.readByte()]);
					if (++x >= width) { x = 0; y++; }
				}
			} else {
				int pix = pal[in.readByte()];
				for (int i = 0; i < num; i++) {
					im.setRGB(x, y, pix);
					if (++x >= width) { x = 0; y++; }
				}
			}
		}
	}

	/** One pixel as ARGB, stored as 16-bit RGBA4444 ({@code bits == 4}) or 32-bit RGBA. */
	private static int readPixel(LittleEndianInputStream in, int bits) throws IOException {
		int r, g, b, a;
		if (bits == 4) {
			int p = in.readByte();
			a = (p & 0xf) * 0x11;
			b = ((p >> 4) & 0xf) * 0x11;
			p = in.readByte();
			g = (p & 0xf) * 0x11;
			r = ((p >> 4) & 0xf) * 0x11;
		} else {
			r = in.readByte();
			g = in.readByte();
			b = in.readByte();
			a = in.readByte();
		}
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
}
