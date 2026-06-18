package hero.roland.bnsim;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

public class Text {
    private Text() {}
    public enum Language {
        EN("BattleNations_en.json", "Delta_en.json"),
        //ENPL("BattleNations_enpl.json", "Delta_enpl.json"),
        DE("BattleNations_de.json", "Delta_de.json"),
        ES("BattleNations_es.json", "Delta_es.json"),
        FR("BattleNations_fr.json", "Delta_fr.json"),
        IT("BattleNations_it.json", "Delta_it.json"),
        JA("BattleNations_ja.json", "Delta_ja.json"),
        KO("BattleNations_ko.json", "Delta_ko.json"),
        RU("BattleNations_ru.json", "Delta_ru.json"),
        ZH_HANS("BattleNations_zh-Hans.json", "Delta_zh-Hans.json"),
        ZH_HANT("BattleNations_zh-Hant.json", "Delta_zh-Hant.json");

        private final String filename;
        private final String deltaFilename;

        Language(String filename, String deltaFilename) {
            this.filename = filename;
            this.deltaFilename = deltaFilename;
        }

        public String filename() { return filename; }
        public String deltaFilename() { return deltaFilename; }
    }

	private static Map<String, String> text = new HashMap<String, String>();

	public static void load() throws IOException {
		try {
			loadJson(Language.EN.filename());
			loadJson(Language.EN.deltaFilename());
		} catch (JSONException e) {
			throw new IllegalArgumentException("Json type error", e);
		}
	}

	private static void loadJson(String file) throws IOException {
        JSONObject json = GameFiles.readJson(file);
		for (String key : json.keySet()) {
			String str = json.getString(key);
			text.put(key.toLowerCase(), str);
		}
	}

	public static String get(String key) {
		if (key == null) return null;
		return text.get(key.toLowerCase());
	}
}
