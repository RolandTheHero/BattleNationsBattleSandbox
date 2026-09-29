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

import hero.roland.bnsim.model.Ability;
import hero.roland.bnsim.model.Animation;
import hero.roland.bnsim.model.Bitmap;
import hero.roland.bnsim.model.Sound;
import hero.roland.bnsim.model.StatusEffect;
import hero.roland.bnsim.model.Text;
import hero.roland.bnsim.model.Timeline;
import hero.roland.bnsim.model.Unit;

/**
 * A loaded set of game files: the units, abilities, status effects, text and
 * sprite assets the simulator draws on. Different game versions store these
 * differently, so each version is a separate implementation that holds its own
 * data and parses its own files into the model classes; see
 * {@link AbstractGameFiles}, {@link OldGameFiles} and {@link NewGameFiles}.
 *
 * <p>One bundle is "active" at a time (see {@link #active()}). The model classes'
 * static lookups ({@code Unit.get}, {@code Animation.get}, ...) delegate here, so
 * loading a different bundle swaps the whole game's data over.
 */
public interface GameFiles {

	// --- Game data -----------------------------------------------------------

	/** The localized string for {@code key} (case-insensitive), or {@code null}. */
	String getText(String key);

	/** The unit with the given id, or {@code null}. */
	Unit getUnit(String id);

	/** Every loaded unit, sorted (by name then id). */
	Unit[] getUnits();

	Unit.UnitTag getUnitTag(String name);

	/** The ability with the given tag, or {@code null}. */
	Ability getAbility(String tag);

	/** The status effect with the given id, or {@code null}. */
	StatusEffect getStatusEffect(String id);

	/** The status-effect family with the given id, or {@code null}. */
	StatusEffect.StatusFamily getStatusFamily(String id);

	/** The ids of every loaded status effect, sorted. */
	String[] getStatusEffectIds();

	/** The language the bundle's text is currently loaded in. */
	Text.Language getLanguage();

	/**
	 * Reloads the bundle's text in the given language, replacing the previously
	 * loaded text. Only the localized strings are re-read; already-built units and
	 * abilities keep the names they resolved at load time.
	 */
	void setLanguage(Text.Language language) throws IOException;

	// --- Sprites (loaded lazily, cached) ------------------------------------

	/** The animation timeline with the given name, or {@code null}. */
	Timeline getTimeline(String name) throws IOException;

	/** A new {@link Animation} for the given timeline name, or {@code null}. */
	Animation getAnimation(String name) throws IOException;

	/** The bitmap (sprite sheet) with the given name. */
	Bitmap getBitmap(String name) throws IOException;

	// --- Battlefield backgrounds (loaded lazily, cached in memory) -----------

	/** The names of the available battlefield backgrounds, the default first. */
	String[] getBackgroundNames() throws IOException;

	/** The battlefield background with the given name, or {@code null} if there is none. */
	BufferedImage getBackground(String name) throws IOException;

	// --- Images and sounds (loaded lazily, cached in memory) -----------------
	// Names are the old format's file names (e.g. damageBullet@2x.png); the
	// remaster's loader maps them to its own assets. Anything missing or
	// unreadable comes back null, and callers carry on without it.

	/** The image with the given name, or {@code null}. */
	BufferedImage getImage(String name);

	/** The sound with the given name (the extension may be left off), or {@code null}. */
	Sound getSound(String name);

	/** The "Pass" button background image, or {@code null}. */
	BufferedImage getPassButton();

	/** The image marking units that cannot be targeted, or {@code null}. */
	BufferedImage getDoNotTargetCircle();

	/** The splat drawn behind a critical hit's damage number, or {@code null}. */
	BufferedImage getCritTab();

	BufferedImage getMagGlass();

	BufferedImage getFightButtonInactive();

	BufferedImage getFightButtonActive();

	BufferedImage getUnitInfoButton();

	BufferedImage getAOETargetCircle();

	BufferedImage getRankInsignia();

	// --- Active bundle + loading --------------------------------------------

	/** The bundle currently loaded, or {@code null} before any has loaded. */
	static GameFiles active() {
		return Holder.instance;
	}

	/** Makes {@code gf} the active bundle. */
	static void setActive(GameFiles gf) {
		Holder.instance = gf;
	}

	/**
	 * Loads the game files in {@code folder}, makes the result the active bundle,
	 * and returns it. A Unity remaster install (or its bundle folder) is read with
	 * {@link NewGameFiles}; anything else is taken to be an old-format bundle
	 * folder and read with {@link OldGameFiles}.
	 */
	static GameFiles load(File folder) throws IOException {
		AbstractGameFiles gf = NewGameFiles.findBundleFolder(folder) != null
				? new NewGameFiles(folder)
				: new OldGameFiles(folder);
		gf.loadAll();
		setActive(gf);
		return gf;
	}

	/** Holds the single active bundle (an interface cannot have a mutable field). */
	final class Holder {
		private Holder() {}
		private static GameFiles instance;
	}
}
