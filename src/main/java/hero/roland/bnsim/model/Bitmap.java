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

import java.awt.TexturePaint;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import hero.roland.bnsim.gamefiles.GameFiles;

/**
 * A sprite sheet that {@link Frame}s cut their quads from. The texture spans
 * {@code 0x8000} texture units on each axis, whatever its pixel size.
 */
public class Bitmap {

	/** Bitmaps whose texture has been replaced in memory, held from GC so the
	 *  change survives (the bundle's cache holds only soft references). */
	private static Set<Bitmap> modified = new HashSet<Bitmap>();

	private final String name;
	private final int width, height, bits;
	private TexturePaint texture;
	private final TexturePaint originalTexture;

	/** The bitmap with the given name from the active bundle (cached there). */
	public static Bitmap get(String name) throws IOException {
		return GameFiles.active().getBitmap(name);
	}

	/** A sprite sheet with the given image; {@code bits} is the colour depth per
	 *  pixel it was stored with (informational). */
	public Bitmap(String name, BufferedImage image, int bits) {
		this.name = name;
		this.width = image.getWidth();
		this.height = image.getHeight();
		this.bits = bits;
		texture = new TexturePaint(image, new Rectangle2D.Double(0, 0, 0x8000, 0x8000));
		originalTexture = texture;
	}

	public String getName() {
		return name;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public int getBits() {
		return bits;
	}

	public TexturePaint getTexture() {
		return texture;
	}

	public void replaceTexture(BufferedImage im) {
		texture = new TexturePaint(im, new Rectangle2D.Double(0, 0, 0x8000, 0x8000));
		modified.add(this);  // keep change in memory
	}

	public void restoreTexture() {
		texture = originalTexture;
		modified.remove(this);
	}

}
