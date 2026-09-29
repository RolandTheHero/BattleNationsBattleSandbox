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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.TexturePaint;
import java.awt.geom.Rectangle2D;
import java.io.IOException;

import hero.roland.bnsim.gamefiles.GameFiles;

/**
 * One animation: a sequence of {@link Frame}s drawn from the sprite sheet
 * ({@link Bitmap}) of its package. The same {@code Frame} object may appear
 * more than once in the sequence where a frame repeats.
 */
public class Timeline {

	private final String packageName, name;
	private final int xMin, xMax, yMin, yMax;
	private final Frame[] frames;
	private final double scale;

	/** The timeline with the given name from the active bundle, or {@code null}. */
	public static Timeline get(String name) throws IOException {
		return GameFiles.active().getTimeline(name);
	}

	/**
	 * A timeline in the package (sprite sheet) {@code packageName}. The bounds
	 * are in the frames' coordinate units, which {@code scale} converts to
	 * points (see {@link Frame}).
	 */
	public Timeline(String packageName, String name, Frame[] frames,
			int xMin, int xMax, int yMin, int yMax, double scale) {
		this.packageName = packageName;
		this.name = name;
		this.frames = frames.clone();
		this.xMin = xMin;
		this.xMax = xMax;
		this.yMin = yMin;
		this.yMax = yMax;
		this.scale = scale;
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

}
