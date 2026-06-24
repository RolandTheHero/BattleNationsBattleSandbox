package hero.roland.bnsim.model;

import hero.roland.bnsim.gamefiles.GameFiles;

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

	/** The localized string for {@code key} from the active bundle, or {@code null}. */
	public static String get(String key) {
		return GameFiles.active().getText(key);
	}
}
