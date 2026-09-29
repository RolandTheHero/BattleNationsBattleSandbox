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

package hero.roland.bnsim.gamefiles;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import hero.roland.bnsim.model.Ability;
import hero.roland.bnsim.model.Animation;
import hero.roland.bnsim.model.Bitmap;
import hero.roland.bnsim.model.Sound;
import hero.roland.bnsim.model.StatusEffect;
import hero.roland.bnsim.model.Text;
import hero.roland.bnsim.model.Timeline;
import hero.roland.bnsim.model.Unit;

/**
 * Base class for a loaded bundle. Owns all of a bundle's data — the game-data
 * maps and the lazily-built sprite, image and sound caches (all in memory); the
 * actual parsing of each kind of data is left to {@code protected} hooks so a
 * game version with a different file format can override only what differs.
 *
 * <p>The eager game data is filled in once by {@link #loadAll()} (called by
 * {@link GameFiles#load}); sprites, images and sounds are read on demand and
 * cached.
 */
public abstract class AbstractGameFiles implements GameFiles {
	protected final File bundleFolder;

	// Eagerly-loaded game data (filled in by loadAll(); subclasses populate these
	// in their load* hooks).
	// The language loadText() reads; changed via setLanguage() to reload the text.
	protected Text.Language language = Text.Language.EN;
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
	private List<String> backgroundNames;
	// Softly held: a large background is ~50 MB, so the JVM may drop unused ones.
	private final Map<String, SoftReference<BufferedImage>> backgroundCache = new HashMap<>();
	// Images and sounds by requested name, softly held; names known to be missing
	// are remembered so they aren't looked up again.
	private final Map<String, SoftReference<BufferedImage>> imageCache = new HashMap<>();
	private final Map<String, SoftReference<Sound>> soundCache = new HashMap<>();
	private final Set<String> missingImages = new HashSet<>(), missingSounds = new HashSet<>();

	protected AbstractGameFiles(File bundleFolder) {
		this.bundleFolder = bundleFolder;
	}

	/**
	 * Loads all eager game data (in dependency order). Sprite data is left to
	 * load lazily.
	 */
	void loadAll() throws IOException {
		loadText();
		loadStatusFamilies();
		loadStatusEffects();
		loadUnitTags();
		loadAbilities();
		loadUnits();
	}

	// Version-specific parsing steps, called by loadAll() in dependency order.
	// Each fills in the corresponding map above.
	protected abstract void loadText() throws IOException;
	protected abstract void loadStatusFamilies() throws IOException;
	protected abstract void loadStatusEffects() throws IOException;
	protected abstract void loadAbilities() throws IOException;
	protected abstract void loadUnits() throws IOException;
	protected abstract void loadUnitTags() throws IOException;

	// Version-specific sprite reading, called lazily and cached by the getters.

	/** Maps every animation name (lowercased) to the package that holds it. */
	protected abstract Map<String, String> buildTimelineIndex() throws IOException;
	/** Reads every timeline in a package, keyed by lowercased name. */
	protected abstract Map<String, Timeline> readTimelinePackage(String pack) throws IOException;
	/** Reads a package's sprite sheet. */
	protected abstract Bitmap readBitmap(String pack) throws IOException;
	/** The names of the battlefield backgrounds, the default first. */
	protected abstract List<String> listBackgrounds() throws IOException;
	/** Reads a battlefield background, or returns {@code null} if there is none by that name. */
	protected abstract BufferedImage readBackground(String name) throws IOException;
	/** Reads the image with the given (old-format) name, or returns {@code null} if there is none. */
	protected abstract BufferedImage readImage(String name) throws IOException;
	/** Reads the sound with the given (old-format) name, or returns {@code null} if there is none. */
	protected abstract Sound readSound(String name) throws IOException;

	// --- Game-data getters ---------------------------------------------------

	@Override
	public String getText(String key) {
		if (key == null) return null;
		String t = text.get(key.toLowerCase());
		if (t == null) return key;
		return t;
	}

	@Override
	public Text.Language getLanguage() {
		return language;
	}

	@Override
	public void setLanguage(Text.Language language) throws IOException {
		this.language = language;
		loadText();
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
			timelinePackageIndex = buildTimelineIndex();
		String lc = name.toLowerCase();
		if (!timelineCache.containsKey(lc)) {
			String pack = timelinePackageIndex.get(lc);
			if (pack == null) return null;
			// Mark as attempted so a name missing from its package is not re-read every call.
			timelineCache.put(lc, null);
			timelineCache.putAll(readTimelinePackage(pack));
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
			bmp = readBitmap(name);
			bitmapCache.put(lc, new SoftReference<>(bmp));
		}
		return bmp;
	}

	// --- Background getters --------------------------------------------------

	@Override
	public synchronized String[] getBackgroundNames() throws IOException {
		if (backgroundNames == null)
			backgroundNames = List.copyOf(listBackgrounds());
		return backgroundNames.toArray(new String[0]);
	}

	@Override
	public synchronized BufferedImage getBackground(String name) throws IOException {
		if (name == null) return null;
		SoftReference<BufferedImage> ref = backgroundCache.get(name);
		BufferedImage image = ref == null ? null : ref.get();
		if (image == null) {
			image = readBackground(name);
			if (image != null)
				backgroundCache.put(name, new SoftReference<>(image));
		}
		return image;
	}

	// --- Image and sound getters --------------------------------------------

	@Override
	public synchronized BufferedImage getImage(String name) {
		return cached(name, imageCache, missingImages, this::readImage);
	}

	@Override
	public synchronized Sound getSound(String name) {
		return cached(name, soundCache, missingSounds, this::readSound);
	}

	/** Reads one asset by name. */
	private interface Reader<T> {
		T read(String name) throws IOException;
	}

	/**
	 * The cached asset for {@code name}, reading it on a miss. Unreadable assets
	 * count as missing: the program treats images and sounds as optional.
	 */
	private static <T> T cached(String name, Map<String, SoftReference<T>> cache, Set<String> missing,
			Reader<T> reader) {
		if (name == null || missing.contains(name))
			return null;
		SoftReference<T> ref = cache.get(name);
		T value = ref == null ? null : ref.get();
		if (value == null) {
			try {
				value = reader.read(name);
			} catch (IOException | RuntimeException e) {
				System.err.println("Could not load " + name + ": " + e);
				value = null;
			}
			if (value == null)
				missing.add(name);
			else
				cache.put(name, new SoftReference<>(value));
		}
		return value;
	}

	// The standalone UI images, by their old-format file names; a subclass's
	// readImage() maps them to its own assets, or it overrides these outright.

	@Override
	public BufferedImage getPassButton() {
		return getImage("button_passInactive@2x.png");
	}

	@Override
	public BufferedImage getDoNotTargetCircle() {
		return getImage("doNotTarget_circle@2x.png");
	}

	@Override
	public BufferedImage getCritTab() {
		return getImage("CritTab@2x.png");
	}

	@Override
	public BufferedImage getMagGlass() {
		return getImage("magGlass@2x.png");
	}

	@Override
	public BufferedImage getFightButtonInactive() {
		return getImage("fightInactive@2x.png");
	}

	@Override
	public BufferedImage getFightButtonActive() {
		return getImage("fightActive@2x.png");
	}

	@Override
	public BufferedImage getUnitInfoButton() {
		return getImage("bs_main_unit_info_icon@2x.png");
	}

	@Override
	public BufferedImage getAOETargetCircle() {
		return getImage("battle_view_AOE@2x.png");
	}

	@Override
	public BufferedImage getRankInsignia() {
		return getImage("icon_spSmall@2x~ipad.png");
	}
}
