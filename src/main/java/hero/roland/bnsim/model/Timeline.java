package hero.roland.bnsim.model;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.TexturePaint;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import hero.roland.bnsim.gamefiles.GameFiles;
import hero.roland.bnsim.util.FileFormatException;
import hero.roland.bnsim.util.LittleEndianInputStream;

public class Timeline {

	private String packageName, name;
	private int xMin, xMax, yMin, yMax;
	private Frame[] frames;
	private double scale;

	/** The timeline with the given name from the active bundle, or {@code null}. */
	public static Timeline get(String name) throws IOException {
		return GameFiles.active().getTimeline(name);
	}

	private Timeline(String name) {
		packageName = name;
	}

	public String getPackageName() {
		return packageName;
	}

	public String getName() {
		return name;
	}

	public Rectangle2D.Double getBounds() {
		return new Rectangle2D.Double(xMin * scale, yMin * scale,
				(xMax-xMin+1) * scale, (yMax-yMin+1) * scale);
	}

	public Rectangle2D.Double getBounds(int frame) {
		Rectangle2D.Double bounds = frames[frame].getBounds();
		if (bounds != null) {
			bounds.x *= scale;
			bounds.y *= scale;
			bounds.width *= scale;
			bounds.height *= scale;
		}
		return bounds;
	}

	public int getNumFrames() {
		return frames.length;
	}

	public Frame getFrame(int num) {
		return frames[num];
	}

	public void drawFrame(int num, Graphics2D g) {
		frames[num].draw(g);
	}

	/** Draws a frame at ({@code x}, {@code y}) via its cached sprite image. */
	public void drawFrameCached(int num, Graphics2D g, double x, double y,
			TexturePaint texture) {
		frames[num].drawCached(g, x, y, texture, scale);
	}

	/** Draws a frame at ({@code x}, {@code y}) recoloured to {@code color} at
	 *  {@code strength} opacity, masked by the frame's own shape. */
	public void drawFrameCachedTinted(int num, Graphics2D g, double x, double y,
			TexturePaint texture, Color color, float strength) {
		frames[num].drawCachedTinted(g, x, y, texture, scale, color, strength);
	}

	/**
	 * Builds the animation-name &rarr; package index by reading every
	 * {@code *_Metadata.json} in the bundle. Called once by the bundle the first
	 * time a timeline is requested.
	 */
	public static Map<String, String> buildPackageIndex(GameFiles gf) throws IOException {
		Map<String, String> index = new HashMap<String, String>();
		try {
			for (File file : gf.glob("*_Metadata.json")) {
				String pack = file.getName();
				pack = pack.substring(0, pack.length() - 14);
				JSONObject meta = gf.readJson(new FileInputStream(file));
				JSONArray names = meta.getJSONArray("animationNames");
				for (Object animname : names) {
					String animstr = (String) animname;
					index.put(animstr.toLowerCase(), pack);
				}
			}
		}
		catch (ClassCastException | JSONException e) {
			throw new FileFormatException("Json type error", e);
		}
		return index;
	}

	/**
	 * Reads every timeline in the named package's {@code _Timeline.bin}, returning
	 * them keyed by lowercased name for the bundle to cache.
	 */
	public static Map<String, Timeline> readPackage(GameFiles gf, String packname)
			throws IOException {
		Map<String, Timeline> result = new HashMap<String, Timeline>();
		LittleEndianInputStream in = new LittleEndianInputStream(
				gf.open(packname + "_Timeline.bin"), 256);
		try {
			int ver = in.readShort();
			if (ver != 4 && ver != 6 && ver != 8)
				throw new FileFormatException("Unknown version");
			in.readByte();
			int num = in.readShort();
			in.readShort();
			for (int i = 0; i < num; i++)
				new Timeline(packname).read(in, ver, result);
		}
		catch (ArrayIndexOutOfBoundsException e) {
			throw new FileFormatException("Invalid array index", e);
		}
		finally {
			in.close();
		}
		return result;
	}

	private void read(LittleEndianInputStream in, int ver,
			Map<String, Timeline> out) throws IOException {
		name = in.readCString(256);
		out.put(name.toLowerCase(), this);
		in.readShort();
		readFrames(in, ver);
		if (ver > 4)
			readSequence(in);
		scale = (ver > 4) ? 1.0/32 : 1;
	}

	private void readFrames(LittleEndianInputStream in, int ver)
			throws IOException {
		int numPoints = in.readUnsignedShort();
		int alpha = 0;
		if (ver == 8) {
			switch (in.readShort()) {
			case 0: break;
			case 1: alpha = 1; break;
			case 0x101: alpha = 4; break;
			default: throw new FileFormatException("Unknown point size");
			}
		}

		Vertex[] coords = new Vertex[numPoints];
		for (int i = 0; i < numPoints; i++) {
			Vertex point = new Vertex();
			point.x1 = in.readShort();
			point.y1 = in.readShort();
			if (ver == 4) in.readShort();
			point.x2 = in.readShort();
			point.y2 = in.readShort();
			for (int j = 0; j < alpha; j++)
				point.alpha = Math.min(point.alpha, in.readFloat());
			coords[i] = point;
		}

		xMin = in.readShort();
		xMax = in.readShort();
		yMin = in.readShort();
		yMax = in.readShort();

		int numFrames = in.readUnsignedShort();
		frames = new Frame[numFrames];
		for (int i = 0; i < numFrames; i++) {
			frames[i] = new Frame();
			frames[i].read(in, ver, coords);
		}
	}

	private void readSequence(LittleEndianInputStream in)
			throws IOException {
		int numSeq = in.readUnsignedShort();
		if (numSeq <= 0)
			throw new FileFormatException("Invalid sequence count");
		in.readShort();
		Frame[] sequence = new Frame[numSeq];
		for (int i = 0; i < numSeq; i++)
			sequence[i] = frames[in.readUnsignedShort()];
		frames = sequence;
	}

	protected class Vertex {
		protected int x1, y1, x2, y2;
		protected float alpha = 1;
	}

}

