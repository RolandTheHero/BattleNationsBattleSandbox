package hero.roland.bnsim.gamefiles;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import hero.roland.bnsim.model.Ability;
import hero.roland.bnsim.model.Animation;
import hero.roland.bnsim.model.Bitmap;
import hero.roland.bnsim.model.StatusEffect;
import hero.roland.bnsim.model.Timeline;
import hero.roland.bnsim.model.Unit;
import hero.roland.bnsim.util.FileFormatException;
import hero.roland.bnsim.util.GlobFilter;

/**
 * Base class for a loaded bundle. Owns all of a bundle's data — the game-data
 * maps and the lazily-built sprite caches — and the raw file access; the actual
 * parsing of each kind of data is left to {@code protected} hooks so a game
 * version with a different file format can override only what differs.
 *
 * <p>The eager game data is filled in once by {@link #loadAll()} (called by
 * {@link GameFiles#load}); the sprite layer (timelines, bitmaps) is read on
 * demand and cached.
 */
public abstract class AbstractGameFiles implements GameFiles {
	protected final File bundleFolder;
	private File passButton;
	private File doNotTargetCircle;
	private File critTab;
	private File magGlass;
	private File fightButtonInactive;
	private File fightButtonActive;

	// Eagerly-loaded game data (filled in by loadAll(); subclasses populate these
	// in their load* hooks).
	protected final Map<String, String> text = new HashMap<>();
	protected final Map<String, Unit> units = new HashMap<>();
	protected final Map<String, Unit.UnitTag> unitTags = new HashMap<>();
	protected final Map<String, Ability> abilities = new HashMap<>();
	protected final Map<String, StatusEffect> statusEffects = new HashMap<>();
	protected final Map<String, StatusEffect.StatusFamily> statusFamilies = new HashMap<>();

	// Lazily-loaded sprite layer.
	private Map<String, String> timelinePackageIndex;          // animation name (lc) -> package
	private final Map<String, Timeline> timelineCache = new HashMap<>();
	private final Map<String, SoftReference<Bitmap>> bitmapCache = new HashMap<>();

	protected AbstractGameFiles(File bundleFolder) {
		this.bundleFolder = bundleFolder;
	}

	/**
	 * Loads all eager game data (in dependency order) and records the standalone
	 * asset files. Sprite data is left to load lazily.
	 */
	void loadAll() throws IOException {
		loadText();
		loadStatusFamilies();
		loadStatusEffects();
		loadUnitTags();
		loadAbilities();
		loadUnits();
		passButton = new File(bundleFolder, "button_passInactive@2x.png");
		doNotTargetCircle = new File(bundleFolder, "doNotTarget_circle@2x.png");
		critTab = new File(bundleFolder, "CritTab@2x.png");
		magGlass = new File(bundleFolder, "magGlass@2x.png");
		fightButtonInactive = new File(bundleFolder, "fightInactive@2x.png");
		fightButtonActive = new File(bundleFolder, "fightActive@2x.png");
	}

	// Version-specific parsing steps, called by loadAll() in dependency order.
	// Each fills in the corresponding map above.
	protected abstract void loadText() throws IOException;
	protected abstract void loadStatusFamilies() throws IOException;
	protected abstract void loadStatusEffects() throws IOException;
	protected abstract void loadAbilities() throws IOException;
	protected abstract void loadUnits() throws IOException;
	protected abstract void loadUnitTags() throws IOException;

	// --- Game-data getters ---------------------------------------------------

	@Override
	public String getText(String key) {
		if (key == null) return null;
		return text.get(key.toLowerCase());
	}

	@Override
	public Unit getUnit(String id) {
		return units.get(id);
	}

	@Override
	public Unit.UnitTag getUnitTag(String name) {
		return unitTags.get(name);
	}

	@Override
	public Unit[] getUnits() {
		Unit[] all = units.values().toArray(new Unit[0]);
		Arrays.sort(all);
		return all;
	}

	@Override
	public Ability getAbility(String tag) {
		return abilities.get(tag);
	}

	@Override
	public StatusEffect getStatusEffect(String id) {
		return statusEffects.get(id);
	}

	@Override
	public StatusEffect.StatusFamily getStatusFamily(String id) {
		return statusFamilies.get(id);
	}

	@Override
	public String[] getStatusEffectIds() {
		String[] ids = statusEffects.keySet().toArray(new String[0]);
		Arrays.sort(ids);
		return ids;
	}

	// --- Sprite getters ------------------------------------------------------

	@Override
	public Timeline getTimeline(String name) throws IOException {
		if (name == null) return null;
		if (timelinePackageIndex == null)
			timelinePackageIndex = Timeline.buildPackageIndex(this);
		String lc = name.toLowerCase();
		if (!timelineCache.containsKey(lc)) {
			String pack = timelinePackageIndex.get(lc);
			if (pack == null) return null;
			// Mark as attempted so a name missing from its package is not re-read every call.
			timelineCache.put(lc, null);
			timelineCache.putAll(Timeline.readPackage(this, pack));
		}
		return timelineCache.get(lc);
	}

	@Override
	public Animation getAnimation(String name) throws IOException {
		Timeline timeline = getTimeline(name);
		if (timeline == null) return null;
		return new Animation(timeline, this);
	}

	@Override
	public Bitmap getBitmap(String name) throws IOException {
		String lc = name.toLowerCase();
		Bitmap bmp = null;
		SoftReference<Bitmap> ref = bitmapCache.get(lc);
		if (ref != null)
			bmp = ref.get();
		if (bmp == null) {
			bmp = Bitmap.read(this, name);
			bitmapCache.put(lc, new SoftReference<>(bmp));
		}
		return bmp;
	}

	// --- Raw file access -----------------------------------------------------

	@Override
	public JSONObject readJson(String filename) throws IOException {
		File file = new File(bundleFolder, filename);
		String content = Files.readString(file.toPath());
		return new JSONObject(content);
	}

	@Override
	public JSONObject readJson(InputStream in) throws IOException {
		try {
			InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
			return new JSONObject(new JSONTokener(reader));
		} catch (JSONException e) {
			throw new FileFormatException("Json parse error", e);
		} finally {
			in.close();
		}
	}

	@Override
	public InputStream open(String filename) throws IOException {
		return new FileInputStream(new File(bundleFolder, filename));
	}

	@Override
	public File file(String filename) {
		return new File(bundleFolder, filename);
	}

	@Override
	public File getPassButton() {
		return passButton;
	}

	@Override
	public File getDoNotTargetCircle() {
		return doNotTargetCircle;
	}

	@Override
	public File getCritTab() {
		return critTab;
	}

	@Override
	public File getMagGlass() {
		return magGlass;
	}

	@Override
	public File getFightButtonInactive() {
		return fightButtonInactive;
	}

	@Override
	public File getFightButtonActive() {
		return fightButtonActive;
	}

	@Override
	public File[] glob(String pat) {
		FilenameFilter filter = new GlobFilter(pat);
		Map<String, File> files = new HashMap<>();
		addFiles(files, bundleFolder.listFiles(filter));
		String[] names = files.keySet().toArray(new String[0]);
		Arrays.sort(names);
		File[] result = new File[names.length];
		for (int i = 0; i < names.length; i++)
			result[i] = files.get(names[i]);
		return result;
	}

	private static void addFiles(Map<String, File> dest, File[] src) {
		if (src != null)
			for (File file : src)
				dest.put(file.getName().toLowerCase(), file);
	}
}
