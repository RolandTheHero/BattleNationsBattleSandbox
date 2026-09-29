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

package hero.roland.bnsim.model;

public class Text {
    private Text() {}
    public enum Language {
        EN("BattleNations_en.json", "Delta_en.json", "en"),
        //ENPL("BattleNations_enpl.json", "Delta_enpl.json"),
        DE("BattleNations_de.json", "Delta_de.json", "de"),
        ES("BattleNations_es.json", "Delta_es.json", "es"),
        FR("BattleNations_fr.json", "Delta_fr.json", "fr"),
        IT("BattleNations_it.json", "Delta_it.json", "it"),
        JA("BattleNations_ja.json", "Delta_ja.json", "ja"),
        KO("BattleNations_ko.json", "Delta_ko.json", "ko"),
        RU("BattleNations_ru.json", "Delta_ru.json", "ru"),
        ZH_HANS("BattleNations_zh-Hans.json", "Delta_zh-Hans.json", "zh-Hans"),
        ZH_HANT("BattleNations_zh-Hant.json", "Delta_zh-Hant.json", "zh-Hant");

        private final String filename;
        private final String deltaFilename;
        private final String localeCode;

        Language(String filename, String deltaFilename, String localeCode) {
            this.filename = filename;
            this.deltaFilename = deltaFilename;
            this.localeCode = localeCode;
        }

        /** The old-format text file for this language. */
        public String filename() { return filename; }
        /** The old-format text file with late additions for this language. */
        public String deltaFilename() { return deltaFilename; }
        /** The Unity locale code (e.g. {@code zh-Hans}) of this language's string tables. */
        public String localeCode() { return localeCode; }
    }
}
