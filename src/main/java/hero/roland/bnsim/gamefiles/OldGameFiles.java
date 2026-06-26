package hero.roland.bnsim.gamefiles;

import java.io.File;
import java.io.IOException;

import org.json.JSONException;
import org.json.JSONObject;

import hero.roland.bnsim.model.Ability;
import hero.roland.bnsim.model.StatusEffect;
import hero.roland.bnsim.model.Unit;
import hero.roland.bnsim.util.FileFormatException;

/**
 * Loads a bundle in the old game-file format.
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
		for (String key : json.keySet())
			statusFamilies.put(key, new StatusEffect.StatusFamily(json.getJSONObject(key)));
	}

	@Override
	protected void loadStatusEffects() throws IOException {
		statusEffects.put("suppression", new StatusEffect.Suppression());
		try {
			JSONObject json = readJson("StatusEffectsConfig.json");
			for (String key : json.keySet())
				statusEffects.put(key, new StatusEffect(this, json.getJSONObject(key)));
		} catch (JSONException e) {
			throw new IllegalArgumentException("Json type error", e);
		}
	}

	protected void loadAbilities() throws IOException {
		JSONObject damageAnim = readJson("DamageAnimConfig.json");
		JSONObject json = readJson("BattleAbilities.json");
		for (String key : json.keySet())
			abilities.put(key, new Ability(this, key, json.getJSONObject(key), damageAnim));
	}

	@Override
	protected void loadUnits() throws IOException {
		try {
			JSONObject json = readJson("BattleUnits.json");
			for (String key : json.keySet())
				units.put(key, new Unit(this, key, json.getJSONObject(key)));
		} catch (JSONException e) {
			throw new FileFormatException("Json type error", e);
		}
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
}
