package hero.roland.bnsim.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import hero.roland.bnsim.Cell;

/**
 * A sandbox setup saved as JSON: the grid shape, the player side, and every enemy
 * wave with its subwaves. The file holds {@code "rows"}, {@code "cols"} and
 * {@code "backRowCols"}; {@code "player"}, one side layout; and {@code "1"},
 * {@code "2"}, ... one per wave, each an array of side layouts (one per
 * subwave). A side layout is an object whose {@code "units"} array holds each
 * unit's {@code "id"}, {@code "col"}, {@code "row"} and {@code "rank"}.
 */
final class SetupFile {

	/** A unit on a side: its id, cell and rank. */
	record Placement(String unitId, Cell cell, int rank) {}

	/** A whole setup, the waves holding their subwaves' layouts in fighting order. */
	record Setup(int rows, int cols, int backRowCols, List<Placement> player,
			List<List<List<Placement>>> waves) {}

	private SetupFile() {}

	/** The setup as indented JSON text. */
	static String write(Setup setup) {
		JSONObject root = new JSONObject();
		root.put("rows", setup.rows());
		root.put("cols", setup.cols());
		root.put("backRowCols", setup.backRowCols());
		root.put("player", writeSide(setup.player()));
		List<List<List<Placement>>> waves = setup.waves();
		for (int w = 0; w < waves.size(); w++) {
			JSONArray subwaves = new JSONArray();
			for (List<Placement> subwave : waves.get(w))
				subwaves.put(writeSide(subwave));
			root.put(Integer.toString(w + 1), subwaves);
		}
		return root.toString(2);
	}

	/** A side layout object holding its units. */
	private static JSONObject writeSide(List<Placement> units) {
		JSONArray array = new JSONArray();
		for (Placement p : units)
			array.put(new JSONObject()
					.put("id", p.unitId())
					.put("col", p.cell().col())
					.put("row", p.cell().row())
					.put("rank", p.rank()));
		return new JSONObject().put("units", array);
	}

	/**
	 * Reads a setup from JSON text. Waves are taken from the whole-number keys in
	 * numeric order (other keys are ignored), and a unit with no rank is rank 1.
	 *
	 * @throws JSONException if the text isn't a setup
	 */
	static Setup read(String text) {
		JSONObject root = new JSONObject(text);
		List<String> waveKeys = new ArrayList<>();
		for (String key : root.keySet())
			if (key.matches("\\d{1,9}"))
				waveKeys.add(key);
		waveKeys.sort(Comparator.comparingInt(Integer::parseInt));
		List<List<List<Placement>>> waves = new ArrayList<>();
		for (String key : waveKeys) {
			JSONArray array = root.getJSONArray(key);
			List<List<Placement>> subwaves = new ArrayList<>();
			for (int s = 0; s < array.length(); s++)
				subwaves.add(readSide(array.getJSONObject(s)));
			waves.add(subwaves);
		}
		return new Setup(root.getInt("rows"), root.getInt("cols"), root.getInt("backRowCols"),
				readSide(root.getJSONObject("player")), waves);
	}

	private static List<Placement> readSide(JSONObject side) {
		JSONArray array = side.getJSONArray("units");
		List<Placement> units = new ArrayList<>();
		for (int i = 0; i < array.length(); i++) {
			JSONObject unit = array.getJSONObject(i);
			units.add(new Placement(unit.getString("id"),
					new Cell(unit.getInt("col"), unit.getInt("row")), unit.optInt("rank", 1)));
		}
		return units;
	}
}
