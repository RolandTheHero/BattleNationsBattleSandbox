package hero.roland.bnsim.gamefiles;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import org.json.JSONObject;

import hero.roland.bnsim.model.Ability;
import hero.roland.bnsim.model.Animation;
import hero.roland.bnsim.model.Bitmap;
import hero.roland.bnsim.model.StatusEffect;
import hero.roland.bnsim.model.Text;
import hero.roland.bnsim.model.Timeline;
import hero.roland.bnsim.model.Unit;

/**
 * A loaded set of game files: the units, abilities, status effects, text and
 * sprite assets the simulator draws on. Different game versions store these
 * differently, so each version is a separate implementation that holds its own
 * data and parses its own files; see {@link AbstractGameFiles} and
 * {@link OldGameFiles}.
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

	// --- Raw file access -----------------------------------------------------

	/** Reads and parses a JSON file from the bundle folder. */
	JSONObject readJson(String filename) throws IOException;

	/** Reads and parses JSON from a stream, closing it afterwards. */
	JSONObject readJson(InputStream in) throws IOException;

	/** Opens a file in the bundle folder for reading. */
	InputStream open(String filename) throws IOException;

	/** A file inside the loaded bundle folder (may not exist). */
	File file(String filename);

	/** The bundle files whose names match the given glob pattern. */
	File[] glob(String pat);

	/** The "Pass" button background image (an {@code @2x} asset; may not exist). */
	File getPassButton();

	/** The image marking units that cannot be targeted (an {@code @2x} asset; may not exist). */
	File getDoNotTargetCircle();

	File getCritTab();

	File getMagGlass();

	File getFightButtonInactive();

	File getFightButtonActive();

	File getUnitInfoButton();

	File getAOETargetCircle();

	File getRankInsignia();

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
	 * and returns it. The implementation chosen determines how the files are
	 * parsed, so differently-formatted game versions can be supported here.
	 */
	static GameFiles load(File folder) throws IOException {
		// TODO: detect the game version (e.g. by probing file names/formats) and
		// pick a matching AbstractGameFiles subclass. Only the current format exists
		// today, so always use StandardGameFiles.
		AbstractGameFiles gf = new OldGameFiles(folder);
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
