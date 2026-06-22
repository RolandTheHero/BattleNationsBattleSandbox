package hero.roland.bnsim;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import hero.roland.bnsim.util.FileFormatException;
import hero.roland.bnsim.util.GlobFilter;

public class GameFiles {
    private static File BUNDLE_FOLDER;
	private static File PASS_BUTTON;

    public static void load(File folder) throws IOException {
        BUNDLE_FOLDER = folder;
        Text.load();
		StatusEffect.StatusFamily.load();
		StatusEffect.load();
        Ability.load();
        Unit.load();
        // Timeline data is loaded lazily, on demand, by Timeline.get(...).
		PASS_BUTTON = new File(BUNDLE_FOLDER, "button_passInactive@2x.png");
    }
    public static JSONObject readJson(String filename) throws IOException {
        File file = new File(BUNDLE_FOLDER, filename);
        String content = Files.readString(file.toPath());
        return new JSONObject(content);
    }
    public static JSONObject readJson(InputStream in) throws IOException {
		try {
			InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
			return new JSONObject(new JSONTokener(reader));
		} catch (JSONException e) {
			throw new FileFormatException("Json parse error", e);
		} finally {
			in.close();
		}
	}

    public static FileInputStream open(String filename) throws IOException {
        return new FileInputStream(new File(BUNDLE_FOLDER, filename));
	}

    /** A file inside the loaded bundle folder (may not exist). */
    public static File file(String filename) {
        return new File(BUNDLE_FOLDER, filename);
    }

    /** The "Pass" button background image (an {@code @2x} asset; may not exist). */
    public static File getPassButton() {
        return PASS_BUTTON;
    }

    public static File[] glob(String pat) {
		FilenameFilter filter = new GlobFilter(pat);
		Map<String, File> files = new HashMap<>();
		addFiles(files, BUNDLE_FOLDER.listFiles(filter));
		//addFiles(files, updateDir.listFiles(filter));
		String[] names = new String[files.size()];
		names = files.keySet().toArray(names);
		Arrays.sort(names);
		File[] result = new File[names.length];
		for (int i = 0; i < names.length; i++)
			result[i] = files.get(names[i]);
		return result;
	}
    private static void addFiles(Map<String,File> dest, File[] src) {
		if (src != null)
			for (File file : src)
				dest.put(file.getName().toLowerCase(), file);
	}
}
