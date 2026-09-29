package hero.roland.bnsim.gamefiles;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import hero.roland.bnsim.model.Ability;
import hero.roland.bnsim.model.Bitmap;
import hero.roland.bnsim.model.Frame;
import hero.roland.bnsim.model.Sound;
import hero.roland.bnsim.model.StatusEffect;
import hero.roland.bnsim.model.Timeline;
import hero.roland.bnsim.model.Unit;
import hero.roland.bnsim.util.FileFormatException;

/**
 * Loads the game data of the Unity remaster, read straight from its Addressables
 * asset bundles ({@code BattleNations_Data/StreamingAssets/aa/StandaloneWindows64}).
 *
 * <p>The data is JSON much like the old format's, packed as TextAssets in the
 * {@code configs} bundle, with three differences this class handles:
 * <ul>
 * <li>fields are snake_case ({@code short_name}, not {@code shortName});
 * <li>units, abilities, status effects and families are keyed by number, and
 * enum values (tags, sides, damage types, ...) are stored as numbers — see the
 * tables below;
 * <li>a unit is a list of typed components ({@code battle_unit_stats_config},
 * ...) rather than one object.
 * </ul>
 * Text comes from Unity Localization string tables, one bundle per language plus
 * a shared bundle mapping each entry id to its key.
 *
 * <p>Animations are MessagePack TextAssets ({@code <package>_timeline}) drawn
 * from BC7/RGBA sprite sheets ({@code <package>_texture}); both are read into the
 * same {@link Timeline}/{@link Bitmap} model as the old format, as are the
 * battlefield backgrounds. Icons and sounds are looked up by their old-format
 * names and decoded in memory (sounds to WAV); nothing is written to disk.
 */
public class NewGameFiles extends AbstractGameFiles {

	// Numeric enum values, indexed by value. Names come from the game's C# enums
	// (UnitTag, UnitSide, DamageType, WeaponType) and are spelled as in the old
	// files where those differ in case (e.g. VRB, not Vrb).

	private static final String[] UNIT_TAGS = {
		null, "SeaDefense", "Legend", "Artillery", "Bigfoot", "VRB", "Soldier", "MechArtillery", "Sub",
		"LTA", "Ani", "Vehicle", "Hunter", "Submersible", "Ancient", "Sea", "SRB", "Battleship",
		"Defense", "Sol", "Tank", "Sealife", "Grouper", "Helicopter", "Ground", "FlyingCritter", "Metal",
		"Ignorable", "Crossover2", "Ship", "I17Ancient", "Wimp", "Fast", "Zombie", "Spiderwasp", "Inf",
		"Fighter", "Destroyer", "Critter", "Air", "Unicorn", "Civilian", "ZombieCandidate", "Airc", "Veh",
		"MissileStrike", "Sniper", "Gunboat", "Crossover", "Hospital", "Personnel", "Unit", "Aircraft",
		"Drone", "Bomber", "Structure", "SeaStructure", "NonCom", "Wall", "SeaWall", "Armored",
		"Biological", "Elite", "Immobile", "Mechanical", "Raider", "Slow", "Stealth", "usesCover",
	};

	private static final String[] SIDES = {
		null, "Player", "Hostile", "Neutral", "Hero", "Villain", "Test",
	};

	/** Values 8 (Melee) and 9 (Projectile) exist in the game but no data uses
	 * them, and the simulator has no equivalent. */
	private static final Ability.DamageType[] DAMAGE_TYPES = {
		null, Ability.DamageType.PIERCING, Ability.DamageType.COLD, Ability.DamageType.CRUSHING,
		Ability.DamageType.EXPLOSIVE, Ability.DamageType.FIRE, Ability.DamageType.TORPEDO,
		Ability.DamageType.DEPTH_CHARGE,
	};

	private static final String[] WEAPON_SLOTS = {
		null, "primary", "secondary", "special", "melee",
	};

	/** Status effects have no name in the new files, only a number; these are the
	 * names they had in the old files (the numbers follow their sorted order). The
	 * names matter: environment effects are recognised by "env" in the id. */
	private static final String[] STATUS_EFFECTS = {
		null, "cold_env_DOT", "cold_env_DOT_avg", "cold_env_DOT_easy", "cold_env_DOT_strong",
		"cold_env_DOT_weak", "dot_breach_3_turn", "dot_breach_4_turn", "dot_fire_2_turn",
		"dot_fire_3_turn", "dot_fire_5_turn", "dot_fire_6_turn_sticky", "dot_plasma_2_turn",
		"dot_poison_2_turn", "dot_poison_3_turn", "dot_poison_4_turn", "dot_poison_5_turn",
		"fire_env_DOT", "fire_env_DOT_avg", "fire_env_DOT_easy", "fire_env_DOT_strong",
		"fire_env_DOT_weak", "firemod_env_DOT", "flammable_3_turn", "frozen_2_turn", "frozen_3_turn",
		"plague_env_DOT_avg", "plague_env_DOT_easy", "plague_env_DOT_strong", "plague_env_DOT_weak",
		"poison_env_DOT", "quake_env_dot_avg", "quake_env_dot_easy", "quake_env_dot_strong",
		"quake_env_dot_weak", "shatter_3_turn", "shell_explosive", "shell_piercing", "shell_unstable",
		"stun_1_turn", "stun_2_turn", "stun_3_turn", "stun_4_turn",
	};

	private static final int TARGET_TYPE_WEAPON = 1; // 2 = Target
	private static final int ATTACK_DIRECTION_BACK = 2; // 1 = Front

	/** A SmartFormat placeholder such as {@code {0:cstring(d)}}. */
	private static final Pattern SMART_FORMAT = Pattern.compile("\\{\\d+:cstring\\((\\+?)([a-z])\\)\\}");

	/** Bundles holding animations, sprite sheets, images and sounds, searched in order. */
	private static final String[] TIMELINE_BUNDLES = {
		"timelinesbattle_assets_all", "timelines_assets_all", "timelinesland_assets_all",
	};
	private static final String[] TEXTURE_BUNDLES = {
		"texturesbattle_assets_all", "textures_assets_all", "texturesland_assets_all",
	};
	private static final String[] IMAGE_BUNDLES = {
		"abilities_assets_all", "damageicons_assets_all", "statuseffects_assets_all",
		"unitstats_assets_all", "ui_assets_all",
		"uniticons_assets_all", "uniticonsback_assets_all", "resources_assets_all",
	};
	private static final String[] BACKGROUND_BUNDLES = { "battlebackgrounds_assets_all" };
	/** The background listed first (the old format's BattleMap.png). */
	private static final String DEFAULT_BACKGROUND = "battle_map";
	private static final String[] AUDIO_BUNDLES = { "sfx_assets_all", "music_assets_all" };

	// Battle UI button sizes in pixels. The UI draws these images at their native
	// size, and the remaster's sprites are several times larger than the old ones.
	/** The old button_passInactive@2x.png's size. */
	private static final int PASS_WIDTH = 120, PASS_HEIGHT = 50;
	/** The remaster's Fight button's proportions. */
	private static final int FIGHT_WIDTH = 160, FIGHT_HEIGHT = 52;
	/** The remaster's battle UI art is 4x the size of the old @2x images. */
	private static final int UI_ART_SCALE = 4;
	/** The player's built-in asset file, holding the battle HUD's textures. */
	private static final String BUILTIN_ASSETS = "sharedassets0.assets";
	/** A bundle whose Texture2D type tree can decode the built-in textures. */
	private static final String TEXTURE_TYPE_SOURCE = "damageicons_assets_all";
	/** The game's engine library, which contains FMOD and its Vorbis setup packets. */
	private static final String FMOD_LIBRARY = "UnityPlayer.dll";

	/** File extensions {@link #assetKey} drops; the requested one picks the asset kind. */
	private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg");
	private static final Set<String> AUDIO_EXTENSIONS = Set.of("wav", "mp3", "caf", "ogg");

	/** The configs bundle's TextAssets (name to JSON text), held only while loading. */
	private Map<String, String> configs;
	/** Unit tags by number, including any the tables above don't name. */
	private final Map<Integer, Unit.UnitTag> tagsById = new HashMap<>();

	/** Open bundles by file, so each is opened once. */
	private final Map<File, UnityBundle> openBundles = new HashMap<>();
	/** Animation name (lowercased) to its package, from the animation_file_map config. */
	private Map<String, String> animationPackages;

	/** An object in one of the bundles, with its (index) name. */
	private record Asset(UnityBundle bundle, UnityBundle.ObjectRef ref, String name) {}

	/** Assets by lowercased name, indexed on first use. */
	private Map<String, Asset> timelineAssets, textureAssets, imageAssets, audioAssets, backgroundAssets,
			builtinTextures;
	private VorbisSetups vorbisSetups;

	/**
	 * @param folder the game's install folder, its {@code BattleNations_Data}
	 * folder, or the bundle folder itself
	 */
	public NewGameFiles(File folder) {
		super(Objects.requireNonNullElse(findBundleFolder(folder), folder));
	}

	/**
	 * The folder holding the game's asset bundles, looked for in and below
	 * {@code folder}, or {@code null} if it isn't a new-format install.
	 */
	public static File findBundleFolder(File folder) {
		String[] candidates = {
			"", "aa/StandaloneWindows64", "StreamingAssets/aa/StandaloneWindows64",
			"BattleNations_Data/StreamingAssets/aa/StandaloneWindows64",
		};
		for (String path : candidates) {
			File dir = path.isEmpty() ? folder : new File(folder, path);
			if (findBundle(dir, "configs_assets_all") != null)
				return dir;
		}
		return null;
	}

	/** The bundle in {@code dir} whose name starts with {@code prefix}, or {@code null}. */
	private static File findBundle(File dir, String prefix) {
		File[] files = dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".bundle"));
		return files == null || files.length == 0 ? null : files[0];
	}

	/** The bundle in the bundle folder whose name starts with {@code prefix}. */
	private UnityBundle bundle(String prefix) throws IOException {
		File file = findBundle(bundleFolder, prefix);
		if (file == null)
			throw new FileFormatException("Missing bundle " + prefix + "*.bundle in " + bundleFolder);
		return open(file);
	}

	private synchronized UnityBundle open(File file) throws IOException {
		UnityBundle bundle = openBundles.get(file);
		if (bundle == null) {
			bundle = UnityBundle.open(file);
			openBundles.put(file, bundle);
		}
		return bundle;
	}

	@Override
	void loadAll() throws IOException {
		try {
			super.loadAll();
			animationPackages = new HashMap<>();
			JSONObject map = config("animation_file_map");
			for (String anim : map.keySet())
				animationPackages.put(anim.toLowerCase(), map.getJSONObject(anim).getString("file"));
		} catch (JSONException | ClassCastException e) {
			throw new FileFormatException("Unexpected game data format", e);
		} finally {
			configs = null; // only needed while loading
		}
	}

	/** Parses one of the configs bundle's TextAssets (e.g. {@code battle_units}). */
	private JSONObject config(String name) throws IOException {
		if (configs == null) {
			configs = new HashMap<>();
			for (Map<String, Object> asset : bundle("configs_assets_all").read(UnityBundle.CLASS_TEXT_ASSET))
				configs.put((String) asset.get("m_Name"), (String) asset.get("m_Script"));
		}
		String json = configs.get(name);
		if (json == null)
			throw new FileFormatException("Missing config " + name);
		return new JSONObject(json);
	}

	// --- Text ----------------------------------------------------------------

	@Override
	protected void loadText() throws IOException {
		text.clear();
		try {
			// Each shared table maps entry ids to keys for one table collection.
			Map<String, Map<Long, String>> keysByCollection = new HashMap<>();
			for (Map<String, Object> shared : bundle("localization-assets-shared")
					.read(UnityBundle.CLASS_MONO_BEHAVIOUR)) {
				Map<Long, String> keys = new HashMap<>();
				for (Object o : (List<?>) shared.get("m_Entries")) {
					Map<?, ?> entry = (Map<?, ?>) o;
					keys.put((Long) entry.get("m_Id"), (String) entry.get("m_Key"));
				}
				keysByCollection.put((String) shared.get("m_TableCollectionName"), keys);
			}

			// The language's tables are named <collection>_<locale>, e.g. GameText_en.
			String suffix = "_" + language.localeCode();
			for (Map<String, Object> table : open(localeBundle()).read(UnityBundle.CLASS_MONO_BEHAVIOUR)) {
				String name = (String) table.get("m_Name");
				if (!name.endsWith(suffix)) continue;
				Map<Long, String> keys = keysByCollection.get(name.substring(0, name.length() - suffix.length()));
				if (keys == null) continue;
				for (Object o : (List<?>) table.get("m_TableData")) {
					Map<?, ?> entry = (Map<?, ?>) o;
					String key = keys.get((Long) entry.get("m_Id"));
					String value = (String) entry.get("m_Localized");
					// Some keys appear in several tables, blank in all but one.
					if (key != null && !value.isEmpty())
						text.putIfAbsent(key.toLowerCase(), toOldPlaceholders(value));
				}
			}
		} catch (ClassCastException | NullPointerException e) {
			throw new FileFormatException("Unexpected localization table format", e);
		}
		// Renamed key the UI still looks up by its old name.
		String lineOfFire = text.get("lineoffire");
		if (lineOfFire != null)
			text.putIfAbsent("line of fire", lineOfFire);
	}

	private File localeBundle() throws FileFormatException {
		String end = "(" + language.localeCode().toLowerCase() + ")_assets_all.bundle";
		File[] files = bundleFolder.listFiles((d, name) ->
				name.startsWith("localization-string-tables-") && name.toLowerCase().endsWith(end));
		if (files == null || files.length == 0)
			throw new FileFormatException("Missing string tables for " + language.localeCode());
		return files[0];
	}

	/**
	 * Rewrites SmartFormat placeholders ({@code {0:cstring(s)}}) as the old files'
	 * printf-style ones ({@code %@} for strings, {@code %d} for numbers), which is
	 * what the UI substitutes. As in the old files, a string with placeholders has
	 * its literal {@code %} signs doubled.
	 */
	private static String toOldPlaceholders(String s) {
		if (!SMART_FORMAT.matcher(s).find())
			return s;
		return SMART_FORMAT.matcher(s.replace("%", "%%"))
				.replaceAll(m -> m.group(2).equals("s") ? "%@" : "%" + m.group(1) + m.group(2));
	}

	// --- Status effects ------------------------------------------------------

	@Override
	protected void loadStatusFamilies() throws IOException {
		JSONObject json = config("status_effect_families");
		for (String key : json.keySet()) {
			JSONObject family = json.getJSONObject(key);
			statusFamilies.put(key, new StatusEffect.StatusFamily(
					family.optString("color_hex", "#FFFFFF"),
					family.optString("display_name", "se_unknown"),
					family.optString("effect_icon", "suppressor_firemod_icon"),
					family.optDouble("pulse_speed", 1d),
					family.optString("sound", null),
					family.optString("ui_icon", "bn_icon_fire_mod")));
		}
	}

	@Override
	protected void loadStatusEffects() throws IOException {
		statusEffects.put("suppression", new StatusEffect.Suppression());
		JSONObject json = config("status_effects");
		for (String key : json.keySet())
			statusEffects.put(statusEffectId(key), parseStatusEffect(json.getJSONObject(key)));
	}

	private StatusEffect parseStatusEffect(JSONObject stats) {
		StatusEffect.Definition def = new StatusEffect.Definition();
		def.duration = stats.optInt("duration", 1);
		def.diminishing = stats.optBoolean("dot_diminishing", true);
		def.abilityDamageMultiplier = stats.optDouble("dot_ability_damage_mult", 0d);
		if (stats.has("dot_damage_type"))
			def.damageType = damageType(stats.getInt("dot_damage_type"));
		if (stats.has("family"))
			def.family = getStatusFamily(Integer.toString(stats.getInt("family")));
		def.armorPiercingRate = stats.optDouble("dot_ap_percent", 0);
		def.bonusDamage = stats.optInt("dot_bonus_damage", 0);

		// Stun/Freeze
		def.blockAction = stats.optBoolean("stun_block_action", false);
		def.blockMovement = stats.optBoolean("stun_block_movement", false);
		def.damageBreak = stats.optBoolean("stun_damage_break", false);
		readNumberedDamageMods(stats.optJSONObject("stun_damage_mods"), def.damageMods);
		readNumberedDamageMods(stats.optJSONObject("stun_armor_damage_mods"), def.armorDamageMods);
		return new StatusEffect(def);
	}

	// --- Unit tags -----------------------------------------------------------

	@Override
	protected void loadUnitTags() throws IOException {
		// tag_hierarchy maps a parent tag to its children; tags not listed as
		// anyone's child are roots.
		JSONObject hierarchy = config("battle_config").getJSONObject("tag_hierarchy");
		Map<Integer, Integer> parents = new HashMap<>();
		for (String parent : hierarchy.keySet()) {
			JSONArray children = hierarchy.getJSONArray(parent);
			for (int i = 0; i < children.length(); i++)
				parents.put(children.getInt(i), Integer.parseInt(parent));
		}
		for (int id = 1; id < UNIT_TAGS.length; id++)
			defineTag(id, parents);
		for (int id : parents.keySet())
			defineTag(id, parents);
	}

	private Unit.UnitTag defineTag(int id, Map<Integer, Integer> parents) {
		Unit.UnitTag tag = tagsById.get(id);
		if (tag == null) {
			Integer parentId = parents.get(id);
			Unit.UnitTag parent = parentId == null ? null : defineTag(parentId, parents);
			tag = new Unit.UnitTag(name(UNIT_TAGS, id), parent);
			tagsById.put(id, tag);
			unitTags.put(tag.name(), tag);
		}
		return tag;
	}

	/** The unit tag with the given number (one outside the hierarchy is a root). */
	private Unit.UnitTag tag(int id) {
		return defineTag(id, Map.of());
	}

	// --- Abilities -----------------------------------------------------------

	@Override
	protected void loadAbilities() throws IOException {
		JSONObject damageAnim = config("damage_anim_config");
		JSONObject json = config("battle_abilities");
		for (String key : json.keySet())
			abilities.put(key, parseAbility(key, json.getJSONObject(key), damageAnim));
	}

	private Ability parseAbility(String tag, JSONObject json, JSONObject damageAnim) {
		Ability.Definition def = new Ability.Definition();
		def.nameId = json.optString("name", null);
		def.icon = json.optString("icon", null);
		def.infantryHitSound = json.optString("inf_hitsound", null);
		def.vehicleHitSound = json.optString("veh_hitsound", null);
		String animType = json.optString("damage_animation_type", null);
		JSONObject anim = animType == null ? null : damageAnim.optJSONObject(animType);
		if (anim != null) {
			def.frontAnimName = anim.optString("front", null);
			def.backAnimName = anim.optString("back", null);
		}

		JSONObject stats = json.getJSONObject("stats");
		def.damageBonus = stats.optInt("damage", 0);
		def.damageFromWeapon = stats.optDouble("damage_from_weapon", 1);
		def.damageFromUnit = stats.optDouble("damage_from_unit", 1);
		def.minRange = stats.optInt("min_range", 1);
		def.maxRange = stats.optInt("max_range", 1);
		def.shotsPerAttack = stats.optInt("shots_per_attack", 1);
		def.attacksPerUse = stats.optInt("attacks_per_use", 1);
		def.lineOfFire = stats.optInt("line_of_fire", 0);
		def.capture = stats.optBoolean("capture", false);
		def.attackDirection = stats.optInt("attack_direction", 1) == ATTACK_DIRECTION_BACK
				? Ability.AttackDirection.BACK : Ability.AttackDirection.FRONT;
		def.damageType = damageType(stats.optInt("damage_type", 0));
		// Unlike the target area, the damage area is a bare list of squares.
		def.damageArea = parseArea(stats.optJSONArray("damage_area"), false);
		JSONObject targ = stats.optJSONObject("target_area");
		if (targ != null) {
			def.targetType = targ.optInt("target_type", 0) == TARGET_TYPE_WEAPON
					? Ability.TargetType.WEAPON : Ability.TargetType.TARGET;
			def.randomTarget = targ.optBoolean("random", false);
			def.aoeDelay = (int) Math.round(targ.optDouble("aoe_order_delay", 0) * 20); // seconds -> frames
			def.targetArea = parseArea(targ.optJSONArray("data"), def.randomTarget);
		}
		def.armorPiercingRate = stats.optDouble("armor_piercing_percent", 0);
		// "status_effects" maps each effect's number to its chance (percent) of applying.
		JSONObject effects = stats.optJSONObject("status_effects");
		if (effects != null) {
			for (String key : effects.keySet())
				def.statusEffects.add(new Ability.StatusEffectChance(
						getStatusEffect(statusEffectId(key)), effects.optDouble(key, 0d) / 100));
		}
		def.baseCritical = stats.optDouble("critical_hit_percent", 5d) / 100;
		// "critical_bonuses" maps a unit tag's number to extra chance (percent).
		JSONObject bonuses = stats.optJSONObject("critical_bonuses");
		if (bonuses != null) {
			for (String key : bonuses.keySet())
				def.criticalBonuses.put(tag(Integer.parseInt(key)), bonuses.getDouble(key) / 100);
		}
		def.cooldown = stats.optInt("ability_cooldown", 0);
		def.globalCooldown = stats.optInt("global_cooldown", 0);
		def.ammoRequired = stats.optInt("ammo_required", 0);
		def.prepTime = stats.optInt("charge_time", 0);
		JSONArray targets = stats.optJSONArray("targets");
		if (targets != null) {
			for (int i = 0; i < targets.length(); i++)
				def.targetableTags.add(tag(targets.getInt(i)));
		}
		def.attack = stats.optInt("attack", 0);
		def.secondaryDamageRatio = stats.optDouble("secondary_damage_percent", 0d) / 100;
		def.damageDistraction = stats.optDouble("damage_distraction", 0d);
		def.damageDistractionBonus = stats.optInt("damage_distraction_bonus", 0);
		return new Ability(this, tag, def);
	}

	/** An ability's target or damage area, or {@code null} if it has none. In a
	 * random area each square's weight is its chance of being picked. */
	private static Ability.TargetSquare[] parseArea(JSONArray data, boolean random) {
		if (data == null) return null;

		double weight = 0;
		if (random) {
			for (int i = 0; i < data.length(); i++)
				weight += data.getJSONObject(i).optDouble("weight", 0);
		}

		Ability.TargetSquare[] squares = new Ability.TargetSquare[data.length()];
		for (int i = 0; i < squares.length; i++) {
			JSONObject square = data.getJSONObject(i);
			// Zero coordinates are left out of "pos".
			JSONObject pos = square.optJSONObject("pos");
			int x = pos == null ? 0 : pos.optInt("x", 0);
			int y = pos == null ? 0 : pos.optInt("y", 0);
			if (weight == 0) {
				squares[i] = new Ability.TargetSquare(x, y, square.optInt("order", 0),
						square.optDouble("damage_percent", 100d) / 100, 1);
			} else {
				double chance = square.optDouble("weight", 0) / weight;
				squares[i] = new Ability.TargetSquare(x, y, 0, chance, chance);
			}
		}
		return squares;
	}

	// --- Units ---------------------------------------------------------------

	@Override
	protected void loadUnits() throws IOException {
		JSONObject json = config("battle_units");
		for (String key : json.keySet())
			units.put(key, parseUnit(key, json.getJSONArray(key)));
	}

	private Unit parseUnit(String id, JSONArray components) {
		// A unit is a list of components, each tagged with its type in "_t".
		Map<String, JSONObject> parts = new HashMap<>();
		for (int i = 0; i < components.length(); i++) {
			JSONObject part = components.getJSONObject(i);
			parts.put(part.optString("_t"), part);
		}
		JSONObject identity = parts.getOrDefault("battle_unit_identity_config", new JSONObject());
		JSONObject animation = parts.getOrDefault("battle_unit_animation_config", new JSONObject());
		JSONObject stats = parts.getOrDefault("battle_unit_stats_config", new JSONObject());
		JSONObject weapons = parts.getOrDefault("battle_unit_weapons_config", new JSONObject());

		Unit.Definition def = new Unit.Definition();
		def.nameId = identity.optString("name", null);
		def.shortNameId = identity.optString("short_name", null);
		def.side = identity.has("side") ? name(SIDES, identity.getInt("side")) : "Other";
		JSONArray tags = identity.optJSONArray("tags");
		if (tags != null) {
			for (int i = 0; i < tags.length(); i++)
				def.tags.add(tag(tags.getInt(i)));
		}

		def.backAnimName = animation.optString("back_idle", null);
		def.frontAnimName = animation.optString("front_idle", null);
		def.deathAnimName = animation.optString("death", "troopdeath");

		def.blocking = stats.optInt("blocking", Unit.NONE);
		if (stats.has("death_spawned_unit"))
			def.deathSpawnedUnit = Integer.toString(stats.getInt("death_spawned_unit"));
		JSONArray immunities = stats.optJSONArray("status_effect_immunities");
		if (immunities != null) {
			for (int i = 0; i < immunities.length(); i++) {
				StatusEffect.StatusFamily family = getStatusFamily(Integer.toString(immunities.getInt(i)));
				if (family != null)
					def.statusEffectImmunities.add(family);
			}
		}
		JSONArray ranks = stats.optJSONArray("stats");
		if (ranks != null) {
			for (int i = 0; i < ranks.length(); i++)
				def.ranks.add(parseRank(ranks.getJSONObject(i)));
		}

		// Weapons are keyed by slot number (1 = primary, ...), listed in that order.
		JSONObject weaponMap = weapons.optJSONObject("weapons");
		if (weaponMap != null) {
			Map<Integer, Unit.WeaponDefinition> sorted = new TreeMap<>();
			for (String key : weaponMap.keySet()) {
				int slot = Integer.parseInt(key);
				sorted.put(slot, parseWeapon(name(WEAPON_SLOTS, slot), weaponMap.getJSONObject(key)));
			}
			def.weapons.addAll(sorted.values());
		}
		return new Unit(this, id, def);
	}

	private static Unit.Rank parseRank(JSONObject json) {
		Map<Ability.DamageType, Double> damageMods = new HashMap<>();
		Map<Ability.DamageType, Double> armorDamageMods = new HashMap<>();
		readNamedDamageMods(json.optJSONObject("damage_mods"), damageMods);
		readNamedDamageMods(json.optJSONObject("armor_damage_mods"), armorDamageMods);
		return new Unit.Rank(
				json.optInt("power", 0),
				json.optInt("accuracy", 0),
				json.optInt("bravery", 0),
				json.optInt("critical", 0),
				json.optInt("defense", 0),
				json.optInt("hp", 10),
				json.optInt("armor_hp", 0),
				json.optInt("dodge", 0),
				damageMods, armorDamageMods);
	}

	private static Unit.WeaponDefinition parseWeapon(String slot, JSONObject json) {
		Unit.WeaponDefinition def = new Unit.WeaponDefinition();
		def.tag = slot;
		def.nameId = json.optString("name", null);
		def.frontAnimName = json.optString("frontattack_animation", null);
		def.backAnimName = json.optString("backattack_animation", null);
		def.firesoundFrame = json.optInt("firesound_frame", 0);
		def.hitDelay = json.optInt("damage_animation_delay", 0) + def.firesoundFrame;
		def.firesound = json.optString("firesound", null);
		JSONObject stats = json.optJSONObject("stats");
		if (stats != null) {
			def.minDamage = stats.optInt("base_damage_min", 0);
			def.maxDamage = stats.optInt("base_damage_max", 0);
			def.rangeBonus = stats.optInt("range_bonus", 0);
			def.ammo = stats.optInt("ammo", -1);
			def.reloadTime = stats.optInt("reload_time", 0);
			def.baseAttack = stats.optInt("base_atk", 0);
			def.baseCritRate = stats.optDouble("base_crit_percent", 0d) / 100;
		}
		JSONArray abilities = json.optJSONArray("abilities");
		if (abilities != null) {
			for (int i = 0; i < abilities.length(); i++)
				def.abilities.add(Integer.toString(abilities.getInt(i)));
		}
		return def;
	}

	// --- Sprites -------------------------------------------------------------

	/** The animation_file_map config's packages, keeping those that have a timeline. */
	@Override
	protected Map<String, String> buildTimelineIndex() throws IOException {
		Map<String, Asset> assets = timelineAssets();
		Map<String, String> index = new HashMap<>();
		for (Map.Entry<String, String> e : animationPackages.entrySet())
			if (assets.containsKey(e.getValue().toLowerCase()))
				index.put(e.getKey(), e.getValue());
		return index;
	}

	/**
	 * Reads a package's timeline: a MessagePack map from animation name to a
	 * one-element list holding the list of frames.
	 */
	@Override
	protected Map<String, Timeline> readTimelinePackage(String pack) throws IOException {
		Asset asset = timelineAssets().get(pack.toLowerCase());
		if (asset == null)
			throw new FileFormatException("No timeline for package " + pack);
		byte[] data = (byte[]) asset.bundle.readFields(asset.ref, Set.of("m_Script"), true).get("m_Script");
		MsgPackReader in = new MsgPackReader(data);
		Map<String, Timeline> result = new HashMap<>();
		for (int n = in.readMapHeader(); n > 0; n--) {
			String name = in.readString();
			result.put(name.toLowerCase(), readTimeline(in, data, pack, name));
		}
		return result;
	}

	/** Identifies a frame by its encoded bytes, so repeats share one {@link Frame}. */
	private record FrameKey(byte[] data, int from, int to) {
		@Override public boolean equals(Object o) {
			return o instanceof FrameKey k && Arrays.equals(data, from, to, k.data, k.from, k.to);
		}
		@Override public int hashCode() {
			int h = 1;
			for (int i = from; i < to; i++)
				h = 31 * h + data[i];
			return h;
		}
	}

	private static Timeline readTimeline(MsgPackReader in, byte[] data, String pack, String name)
			throws FileFormatException {
		List<Frame> frames = new ArrayList<>();
		// Repeated frames share one object, as in the old format's frame sequences
		// (Animation.earlyStop relies on this to find where a loop restarts).
		Map<FrameKey, Frame> distinct = new HashMap<>();
		int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE, yMin = Integer.MAX_VALUE, yMax = Integer.MIN_VALUE;
		for (int lists = in.readArrayHeader(); lists > 0; lists--) {
			for (int n = in.readArrayHeader(); n > 0; n--) {
				int start = in.position();
				Frame parsed = readFrame(in);
				Frame frame = distinct.computeIfAbsent(new FrameKey(data, start, in.position()), k -> parsed);
				frames.add(frame);
				Rectangle2D.Double b = frame.getBounds();
				if (b != null) {
					xMin = Math.min(xMin, (int) b.x);
					xMax = Math.max(xMax, (int) (b.x + b.width) - 1);
					yMin = Math.min(yMin, (int) b.y);
					yMax = Math.max(yMax, (int) (b.y + b.height) - 1);
				}
			}
		}
		if (xMax < xMin) // nothing visible
			xMin = yMin = 0;
		return new Timeline(pack, name, frames.toArray(new Frame[0]),
				xMin, Math.max(xMax, xMin - 1), yMin, Math.max(yMax, yMin - 1), 1.0 / FRAME_UNITS);
	}

	/** Frame bounds are kept in 1/32 points, as in the old format. */
	private static final int FRAME_UNITS = 32;

	/**
	 * Reads one frame: {@code [transforms, quads, alphas]}. Each transform is
	 * {@code [a, b, tx, c, d, ty]}, mapping a quad's texture coordinates to screen
	 * points ({@code x' = a*u + b*v + tx}); each quad is {@code [[u0..u3], [v0..v3]]}
	 * in the sheet's 0-0x8000 texture space; alphas are empty (all opaque) or one
	 * {@code [alpha, flags]} per quad.
	 */
	private static Frame readFrame(MsgPackReader in) throws FileFormatException {
		if (in.readArrayHeader() != 3)
			throw new FileFormatException("Unexpected timeline frame layout");
		int n = in.readArrayHeader();
		AffineTransform[] transforms = new AffineTransform[n];
		for (int i = 0; i < n; i++) {
			if (in.readArrayHeader() != 6)
				throw new FileFormatException("Unexpected timeline transform");
			double a = in.readDouble(), b = in.readDouble(), tx = in.readDouble();
			double c = in.readDouble(), d = in.readDouble(), ty = in.readDouble();
			transforms[i] = new AffineTransform(a, c, b, d, tx, ty);
		}
		if (in.readArrayHeader() != n)
			throw new FileFormatException("Timeline frame has mismatched quads");
		Polygon[] polys = new Polygon[n];
		for (int i = 0; i < n; i++) {
			in.readArrayHeader();
			polys[i] = new Polygon(readCorners(in), readCorners(in), 4);
		}
		float[] alpha = null;
		int alphas = in.readArrayHeader();
		if (alphas > 0) {
			if (alphas != n)
				throw new FileFormatException("Timeline frame has mismatched alphas");
			alpha = new float[n];
			for (int i = 0; i < n; i++) {
				int fields = in.readArrayHeader();
				alpha[i] = (float) in.readDouble();
				for (int j = 1; j < fields; j++)
					in.skip();
			}
		}

		// Bounds of the visible quads' screen corners.
		double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
		Point2D.Double p = new Point2D.Double();
		for (int i = 0; i < n; i++) {
			if (alpha != null && alpha[i] < 0.5f / 255)
				continue;
			for (int k = 0; k < 4; k++) {
				p.setLocation(polys[i].xpoints[k], polys[i].ypoints[k]);
				transforms[i].transform(p, p);
				x0 = Math.min(x0, p.x);
				x1 = Math.max(x1, p.x);
				y0 = Math.min(y0, p.y);
				y1 = Math.max(y1, p.y);
			}
		}
		if (x1 < x0) // nothing visible
			return new Frame(transforms, polys, alpha, 0, -1, 0, -1);
		return new Frame(transforms, polys, alpha,
				(int) Math.floor(x0 * FRAME_UNITS), (int) Math.ceil(x1 * FRAME_UNITS),
				(int) Math.floor(y0 * FRAME_UNITS), (int) Math.ceil(y1 * FRAME_UNITS));
	}

	private static int[] readCorners(MsgPackReader in) throws FileFormatException {
		if (in.readArrayHeader() != 4)
			throw new FileFormatException("Unexpected timeline quad");
		int[] v = new int[4];
		for (int i = 0; i < 4; i++)
			v[i] = (int) Math.round(in.readDouble());
		return v;
	}

	/** Reads a package's sprite sheet ({@code <package>_texture}). */
	@Override
	protected Bitmap readBitmap(String pack) throws IOException {
		Asset asset = textureAssets().get(pack.toLowerCase());
		if (asset == null)
			throw new FileFormatException("No sprite sheet for package " + pack);
		return new Bitmap(pack, readTexture(asset.bundle, asset.ref), 32);
	}

	/**
	 * The battle maps' sprite names ({@code battle_map}, {@code battle_map_arena},
	 * ...), sorted with the default first.
	 */
	@Override
	protected List<String> listBackgrounds() throws IOException {
		// Each sprite is indexed as both battle_map_0 and battle_map; list the shorter.
		Map<Asset, String> shortest = new HashMap<>();
		for (Map.Entry<String, Asset> e : backgroundAssets().entrySet())
			shortest.merge(e.getValue(), e.getKey(), (a, b) -> a.length() <= b.length() ? a : b);
		List<String> names = new ArrayList<>(shortest.values());
		names.sort(Comparator.comparing((String name) -> !name.equals(DEFAULT_BACKGROUND))
				.thenComparing(Comparator.naturalOrder()));
		return names;
	}

	/** Decodes a battle map straight from its bundle, without writing any file. */
	@Override
	protected BufferedImage readBackground(String name) throws IOException {
		Asset asset = backgroundAssets().get(name.toLowerCase());
		if (asset == null)
			return null;
		BufferedImage decoded = readSprite(asset);
		// The decoder writes straight into the image's pixel array, which stops
		// Java2D from caching it for fast drawing, and the battlefield redraws the
		// background (scaled) every frame: ~50 ms a frame instead of ~5. A fresh
		// opaque copy can be cached.
		BufferedImage image = new BufferedImage(decoded.getWidth(), decoded.getHeight(),
				BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.drawImage(decoded, 0, 0, null);
		g.dispose();
		return image;
	}

	/** Decodes a Texture2D (top row first). */
	private static BufferedImage readTexture(UnityBundle bundle, UnityBundle.ObjectRef ref) throws IOException {
		Map<String, Object> texture = bundle.read(ref, false);
		int width = number(texture, "m_Width").intValue();
		int height = number(texture, "m_Height").intValue();
		int format = number(texture, "m_TextureFormat").intValue();
		byte[] data = (byte[]) texture.get("image data");
		// Large textures keep their pixels in the bundle's .resS stream instead.
		Map<String, Object> stream = map(texture, "m_StreamData");
		int size = number(stream, "size").intValue();
		if (size > 0)
			data = bundle.readResource((String) stream.get("path"), number(stream, "offset").longValue(), size);
		if (format == 28 || format == 29) { // DXT1/DXT5, crunch-compressed
			CrunchDecoder.Result dxt = CrunchDecoder.unpackLevel0(data);
			return TextureDecoder.decode(dxt.dxt5() ? 12 : 10, dxt.width(), dxt.height(), dxt.blocks());
		}
		return TextureDecoder.decode(format, width, height, data);
	}

	/** A Sprite's image: its rectangle of its texture. */
	private BufferedImage readSprite(Asset sprite) throws IOException {
		Map<String, Object> fields = sprite.bundle.read(sprite.ref, false);
		Map<String, Object> texturePtr = map(map(fields, "m_RD"), "texture");
		UnityBundle.ObjectRef textureRef = number(texturePtr, "m_FileID").intValue() == 0
				? sprite.bundle.object(number(texturePtr, "m_PathID").longValue()) : null;
		if (textureRef == null)
			throw new FileFormatException("Sprite " + sprite.name + " has no texture in its bundle");
		BufferedImage texture = readTexture(sprite.bundle, textureRef);

		// The rectangle's origin is the texture's bottom-left corner.
		Map<String, Object> rect = map(fields, "m_Rect");
		int w = Math.round(number(rect, "width").floatValue());
		int h = Math.round(number(rect, "height").floatValue());
		int x = Math.round(number(rect, "x").floatValue());
		int y = texture.getHeight() - Math.round(number(rect, "y").floatValue()) - h;
		x = Math.max(0, x);
		y = Math.max(0, y);
		w = Math.min(w, texture.getWidth() - x);
		h = Math.min(h, texture.getHeight() - y);
		if (x == 0 && y == 0 && w == texture.getWidth() && h == texture.getHeight())
			return texture;
		return texture.getSubimage(x, y, w, h);
	}

	/** An AudioClip's FSB5 sound bank, from the bundle's .resource stream. */
	private static byte[] readAudio(Asset clip) throws IOException {
		Map<String, Object> resource = map(clip.bundle.read(clip.ref, false), "m_Resource");
		return clip.bundle.readResource((String) resource.get("m_Source"),
				number(resource, "m_Offset").longValue(), number(resource, "m_Size").intValue());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Map<String, Object> fields, String name) throws FileFormatException {
		Object value = fields.get(name);
		if (!(value instanceof Map))
			throw new FileFormatException("Missing field " + name);
		return (Map<String, Object>) value;
	}

	private static Number number(Map<String, Object> fields, String name) throws FileFormatException {
		Object value = fields.get(name);
		if (!(value instanceof Number))
			throw new FileFormatException("Missing field " + name);
		return (Number) value;
	}

	// --- Asset indexes -------------------------------------------------------

	private synchronized Map<String, Asset> timelineAssets() throws IOException {
		if (timelineAssets == null)
			timelineAssets = index(TIMELINE_BUNDLES, UnityBundle.CLASS_TEXT_ASSET, "_timeline");
		return timelineAssets;
	}

	private synchronized Map<String, Asset> textureAssets() throws IOException {
		if (textureAssets == null)
			textureAssets = index(TEXTURE_BUNDLES, UnityBundle.CLASS_TEXTURE_2D, "_texture");
		return textureAssets;
	}

	private synchronized Map<String, Asset> imageAssets() throws IOException {
		if (imageAssets == null)
			imageAssets = index(IMAGE_BUNDLES, UnityBundle.CLASS_SPRITE, "");
		return imageAssets;
	}

	private synchronized Map<String, Asset> backgroundAssets() throws IOException {
		if (backgroundAssets == null)
			backgroundAssets = index(BACKGROUND_BUNDLES, UnityBundle.CLASS_SPRITE, "");
		return backgroundAssets;
	}

	private synchronized Map<String, Asset> audioAssets() throws IOException {
		if (audioAssets == null)
			audioAssets = index(AUDIO_BUNDLES, UnityBundle.CLASS_AUDIO_CLIP, "");
		return audioAssets;
	}

	/**
	 * The objects of a class in the given bundles, keyed by lowercased name with
	 * {@code suffix} removed (objects without it are left out). The first bundle
	 * listed wins when names clash. A name ending {@code _0} (Unity's name for a
	 * texture's first sprite, e.g. {@code battle_map_0}) is also listed without it.
	 */
	private Map<String, Asset> index(String[] prefixes, int classId, String suffix) throws IOException {
		Map<String, Asset> index = new HashMap<>();
		for (String prefix : prefixes) {
			File file = findBundle(bundleFolder, prefix);
			if (file == null) continue;
			UnityBundle bundle = open(file);
			for (UnityBundle.ObjectRef ref : bundle.objects(classId)) {
				String name = bundle.name(ref);
				if (name == null || !name.toLowerCase().endsWith(suffix)) continue;
				name = name.toLowerCase().substring(0, name.length() - suffix.length());
				Asset asset = new Asset(bundle, ref, name);
				index.putIfAbsent(name, asset);
				if (name.endsWith("_0"))
					index.putIfAbsent(name.substring(0, name.length() - 2), asset);
			}
		}
		return index;
	}

	// --- Images and sounds ---------------------------------------------------

	/**
	 * The sprite matching an image name. Old-format names work too: case,
	 * {@code @2x}, {@code ~ipad} and the extension are ignored, and camelCase
	 * matches snake_case ({@code damageBullet@2x.png} finds {@code damage_bullet}).
	 */
	@Override
	protected BufferedImage readImage(String name) throws IOException {
		String key = assetKey(new File(name).getName());
		Asset image = find(imageAssets(), key, key + "_icon");
		return image == null ? null : readSprite(image);
	}

	/**
	 * The audio clip matching a sound name (matched like {@link #readImage}'s,
	 * and the {@code _sfx}/{@code _music} suffix may be left off), decoded to WAV.
	 */
	@Override
	protected Sound readSound(String name) throws IOException {
		String key = assetKey(new File(name).getName());
		Asset clip = find(audioAssets(), key, key + "_sfx", key + "_music");
		return clip == null ? null : new Sound("wav", FsbAudio.toWav(readAudio(clip), vorbisSetups()));
	}

	private static Asset find(Map<String, Asset> assets, String... names) {
		for (String name : names) {
			Asset asset = name == null ? null : assets.get(name);
			if (asset != null)
				return asset;
		}
		return null;
	}

	/** The Vorbis setup packets in the game's FMOD library, scanned on first use. */
	private synchronized VorbisSetups vorbisSetups() throws IOException {
		if (vorbisSetups == null) {
			File library = findAbove(FMOD_LIBRARY);
			if (library == null)
				throw new FileFormatException("Can't find " + FMOD_LIBRARY + " above " + bundleFolder);
			vorbisSetups = new VorbisSetups(library);
		}
		return vorbisSetups;
	}

	/** The file with the given name in the bundle folder or the nearest folder above it, or {@code null}. */
	private File findAbove(String name) {
		for (File dir = bundleFolder; dir != null; dir = dir.getParentFile())
			if (new File(dir, name).isFile())
				return new File(dir, name);
		return null;
	}

	// --- Battle UI images ----------------------------------------------------
	// The old format's standalone UI images, mapped to the remaster's sprites.

	/** An orange button stretched to the old Pass button's shape. */
	@Override
	public BufferedImage getPassButton() {
		return uiImage("pass_button", () -> nineSlice(sprite("button_default"), PASS_WIDTH, PASS_HEIGHT));
	}

	/**
	 * The green action button with the localized "Fight" label on it. The old
	 * image had its label baked in and the UI draws it as-is, so the remaster's
	 * button (which the game labels at run time) gets its text drawn on here, in
	 * the current language.
	 */
	@Override
	public BufferedImage getFightButtonInactive() {
		String label = getText("fight");
		return uiImage("fight_button_" + language.localeCode(),
				() -> labelled(nineSlice(sprite("button_action"), FIGHT_WIDTH, FIGHT_HEIGHT), label));
	}

	/** No pressed image exists; the UI darkens {@link #getFightButtonInactive} instead. */
	@Override
	public BufferedImage getFightButtonActive() {
		return null;
	}

	// These are textures built into the player (sharedassets0.assets), shrunk to
	// the old images' sizes, which the UI's scale factors are tuned for.

	@Override
	public BufferedImage getMagGlass() {
		return uiImage("mag_glass", () -> oldSize(builtinTexture("mag_glass")));
	}

	@Override
	public BufferedImage getUnitInfoButton() {
		return uiImage("unit_info_button", () -> oldSize(builtinTexture("bs_main_unit_info_icon")));
	}

	/** The red splat behind a critical hit's number. */
	@Override
	public BufferedImage getCritTab() {
		return uiImage("crit_tab", () -> oldSize(builtinTexture("crit_tab")));
	}

	@Override
	public BufferedImage getAOETargetCircle() {
		return uiImage("aoe_target_circle", () -> oldSize(builtinTexture("battle_view_aoe")));
	}

	// The UI scales these two itself, so they are used at full size.

	@Override
	public BufferedImage getRankInsignia() {
		return getImage("icon_sp_small");
	}

	@Override
	public BufferedImage getDoNotTargetCircle() {
		return getImage("do_not_target_circle");
	}

	/** The player's built-in texture with the given name, decoded. */
	private BufferedImage builtinTexture(String name) throws IOException {
		Asset asset = builtinTextures().get(name);
		if (asset == null)
			throw new FileFormatException("No built-in texture " + name);
		return readTexture(asset.bundle, asset.ref);
	}

	/**
	 * The textures in the player's built-in {@code sharedassets0.assets} (found in
	 * {@code BattleNations_Data}, above the bundle folder), by lowercased name.
	 * Empty if the file isn't there.
	 */
	private synchronized Map<String, Asset> builtinTextures() throws IOException {
		if (builtinTextures == null) {
			builtinTextures = new HashMap<>();
			File file = findAbove(BUILTIN_ASSETS);
			if (file != null) {
				UnityBundle assets = UnityBundle.openSerializedFile(file, bundle(TEXTURE_TYPE_SOURCE));
				for (UnityBundle.ObjectRef ref : assets.objects(UnityBundle.CLASS_TEXTURE_2D)) {
					if (ref.typeTree() == null)
						continue; // no matching layout to decode it with
					String name = assets.name(ref);
					if (name != null)
						builtinTextures.putIfAbsent(name.toLowerCase(), new Asset(assets, ref, name.toLowerCase()));
				}
			}
		}
		return builtinTextures;
	}

	/** {@code image} shrunk from the remaster's UI art size to the old images' size. */
	private static BufferedImage oldSize(BufferedImage image) {
		return resize(image, Math.max(1, Math.round(image.getWidth() / (float) UI_ART_SCALE)),
				Math.max(1, Math.round(image.getHeight() / (float) UI_ART_SCALE)));
	}

	/** Produces a UI image. */
	private interface ImageSource {
		BufferedImage get() throws IOException;
	}

	/** Built UI images by name; empty for one that couldn't be built. */
	private final Map<String, Optional<BufferedImage>> uiImages = new HashMap<>();

	/** A UI image built from sprites, built once; {@code null} if it can't be (the UI falls back to text). */
	private synchronized BufferedImage uiImage(String name, ImageSource source) {
		Optional<BufferedImage> image = uiImages.get(name);
		if (image == null) {
			try {
				image = Optional.of(source.get());
			} catch (IOException | RuntimeException e) {
				image = Optional.empty();
			}
			uiImages.put(name, image);
		}
		return image.orElse(null);
	}

	private Asset sprite(String name) throws IOException {
		Asset asset = imageAssets().get(name);
		if (asset == null)
			throw new FileFormatException("No sprite " + name);
		return asset;
	}

	/**
	 * A sprite resized to {@code width} x {@code height} as Unity's UI would draw a
	 * sliced sprite: the corners keep their shape and the edges and centre stretch.
	 * Borders too big for the target are first drawn larger, then scaled down.
	 */
	private BufferedImage nineSlice(Asset sprite, int width, int height) throws IOException {
		BufferedImage src = readSprite(sprite);
		// m_Border is left, bottom, right, top.
		Map<String, Object> border = map(sprite.bundle.readFields(sprite.ref, Set.of("m_Border"), false), "m_Border");
		int left = Math.round(number(border, "x").floatValue());
		int bottom = Math.round(number(border, "y").floatValue());
		int right = Math.round(number(border, "z").floatValue());
		int top = Math.round(number(border, "w").floatValue());
		// Leave the stretched middle at least a fifth of each side.
		double scale = Math.max(1, 1.25 * Math.max((left + right) / (double) width, (top + bottom) / (double) height));
		int w = (int) Math.ceil(width * scale), h = (int) Math.ceil(height * scale);
		int sw = src.getWidth(), sh = src.getHeight();
		int[] sx = { 0, left, sw - right, sw }, sy = { 0, top, sh - bottom, sh };
		int[] dx = { 0, left, w - right, w }, dy = { 0, top, h - bottom, h };

		BufferedImage sliced = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = sliced.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (int row = 0; row < 3; row++)
			for (int col = 0; col < 3; col++)
				g.drawImage(src, dx[col], dy[row], dx[col + 1], dy[row + 1],
						sx[col], sy[row], sx[col + 1], sy[row + 1], null);
		g.dispose();
		return resize(sliced, width, height);
	}

	/** Fonts tried in order for button labels: bold slab serifs like the game's. */
	private static final String[] LABEL_FONTS = { "Rockwell Extra Bold", "Rockwell", Font.SERIF };

	/**
	 * A copy of {@code button} with {@code text} centred on it in white with a
	 * dark outline, sized to fill most of the face.
	 */
	private static BufferedImage labelled(BufferedImage button, String text) {
		BufferedImage out = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(button, 0, 0, null);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		Font font = null;
		for (String family : LABEL_FONTS) {
			font = new Font(family, Font.BOLD, 20);
			if (font.getFamily().equals(family) || family.equals(Font.SERIF))
				break; // installed (Font falls back to Dialog when it isn't)
		}
		// Fit the label to 75% of the width and 50% of the height (by cap height).
		FontRenderContext frc = g.getFontRenderContext();
		Rectangle2D bounds = new TextLayout(text, font, frc).getBounds();
		float size = (float) (20 * Math.min(out.getWidth() * 0.75 / bounds.getWidth(),
				out.getHeight() * 0.5 / bounds.getHeight()));
		TextLayout layout = new TextLayout(text, font.deriveFont(size), frc);
		bounds = layout.getBounds();
		Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(
				(out.getWidth() - bounds.getWidth()) / 2 - bounds.getX(),
				(out.getHeight() - bounds.getHeight()) / 2 - bounds.getY()));
		g.setStroke(new BasicStroke(Math.max(2f, size / 8), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(0x1E4A0C));
		g.draw(outline);
		g.setColor(Color.WHITE);
		g.fill(outline);
		g.dispose();
		return out;
	}

	/** {@code image} resized to {@code width} x {@code height}, halving in steps for a smooth result. */
	private static BufferedImage resize(BufferedImage image, int width, int height) {
		BufferedImage current = image;
		int w = image.getWidth(), h = image.getHeight();
		do {
			w = Math.max(width, w / 2);
			h = Math.max(height, h / 2);
			BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = next.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(current, 0, 0, w, h, null);
			g.dispose();
			current = next;
		} while (w != width || h != height);
		return current;
	}

	/** A file name's lowercased extension, or "" if it has none. */
	private static String extension(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
	}

	/**
	 * The asset name a file name refers to: without extension, {@code @2x} or
	 * {@code ~ipad}, with camelCase words split by underscores, lowercased
	 * ({@code icon_spSmall@2x~ipad.png} gives {@code icon_sp_small}).
	 */
	static String assetKey(String name) {
		String ext = extension(name);
		if (IMAGE_EXTENSIONS.contains(ext) || AUDIO_EXTENSIONS.contains(ext))
			name = name.substring(0, name.length() - ext.length() - 1);
		name = name.replace("@2x", "").replace("~ipad", "").replace("~iphone", "");
		StringBuilder key = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isUpperCase(c) && i > 0) {
				char prev = name.charAt(i - 1);
				if (Character.isLowerCase(prev) || Character.isDigit(prev))
					key.append('_');
			}
			key.append(Character.toLowerCase(c));
		}
		return key.toString().replaceAll("_+", "_");
	}

	// --- Enum lookups --------------------------------------------------------

	/** {@code table[id]}, or the number itself if the table doesn't name it. */
	private static String name(String[] table, int id) {
		return id >= 0 && id < table.length && table[id] != null ? table[id] : Integer.toString(id);
	}

	/** The simulator's damage type for a numbered one, or {@code null} if it has none. */
	private static Ability.DamageType damageType(int id) {
		return id >= 0 && id < DAMAGE_TYPES.length ? DAMAGE_TYPES[id] : null;
	}

	/** The id a numbered status effect is loaded under. */
	private static String statusEffectId(String number) {
		return name(STATUS_EFFECTS, Integer.parseInt(number));
	}

	/** Copies a map keyed by damage-type name (e.g. {@code depth_charge}) into
	 * {@code dest}, skipping types the simulator doesn't have. */
	private static void readNamedDamageMods(JSONObject json, Map<Ability.DamageType, Double> dest) {
		if (json == null) return;
		for (String key : json.keySet()) {
			for (Ability.DamageType type : Ability.DamageType.values()) {
				if (type.name().replace("_", "").equalsIgnoreCase(key.replace("_", "")))
					dest.put(type, json.getDouble(key));
			}
		}
	}

	/** Copies a map keyed by damage-type number into {@code dest}, skipping types
	 * the simulator doesn't have. */
	private static void readNumberedDamageMods(JSONObject json, Map<Ability.DamageType, Double> dest) {
		if (json == null) return;
		for (String key : json.keySet()) {
			Ability.DamageType type = damageType(Integer.parseInt(key));
			if (type != null)
				dest.put(type, json.getDouble(key));
		}
	}
}
