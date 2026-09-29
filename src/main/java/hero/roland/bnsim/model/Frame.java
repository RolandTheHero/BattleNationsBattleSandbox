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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.Map;

public class Frame {
	private final AffineTransform[] transforms;
	private final Polygon[] polys;
	private AlphaComposite[] alpha;
	private final int xMin, xMax, yMin, yMax;

	/**
	 * Cache of this frame rendered to an image once, so repeated draws (idle loops,
	 * many units sharing a frame, replayed attacks/impacts) become a cheap blit
	 * instead of re-filling every textured polygon. A frame's pixels never change
	 * (the source texture is immutable), so the only cache key is the texture
	 * identity. Held softly so the JVM can reclaim sprites under memory pressure.
	 */
	private SoftReference<BufferedImage> spriteRef;
	private TexturePaint spriteTexture;
	private int spriteOx, spriteOy;
	/**
	 * Cache of the sprite recoloured to a solid tint with its alpha preserved, one
	 * entry per tint colour, so a pulsing status overlay is a cheap blit rather than
	 * a per-pixel rebuild every frame. Tied to {@link #spriteRef}'s texture; dropped
	 * when the texture changes. Held softly so the JVM can reclaim it.
	 */
	private Map<Integer, SoftReference<BufferedImage>> tintCache;
	private TexturePaint tintTexture;
	/** This frame has no visible polygons, so there is nothing to draw or cache. */
	private boolean spriteEmpty;
	/** Frame too large to cache; it falls back to drawing its polygons directly. */
	private boolean spriteTooBig;
	/** Skip caching frames larger than this (~16 MB as ARGB) to bound memory. */
	private static final long MAX_SPRITE_PIXELS = 4_000_000L;

	/**
	 * A frame made of textured quads. Each quad {@code polys[i]} is given in
	 * texture coordinates (the sheet spans 0 to {@code 0x8000} on both axes, see
	 * {@link Bitmap}), and {@code transforms[i]} maps it to screen points.
	 * {@code alpha} holds each quad's opacity, or is {@code null} when all are
	 * opaque. The bounds cover the visible quads in the timeline's coordinate
	 * units (screen points divided by its scale); {@code xMax < xMin} means the
	 * frame shows nothing.
	 */
	public Frame(AffineTransform[] transforms, Polygon[] polys, float[] alpha,
			int xMin, int xMax, int yMin, int yMax) {
		this.transforms = transforms.clone();
		this.polys = polys.clone();
		if (alpha != null) {
			this.alpha = new AlphaComposite[alpha.length];
			for (int i = 0; i < alpha.length; i++)
				this.alpha[i] = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha[i]);
		}
		this.xMin = xMin;
		this.xMax = xMax;
		this.yMin = yMin;
		this.yMax = yMax;
	}

	public Rectangle2D.Double getBounds() {
		if (xMax < xMin) return null;
		return new Rectangle2D.Double(xMin, yMin, xMax-xMin+1, yMax-yMin+1);
	}

	public void draw(Graphics2D g) {
		AffineTransform oldTrans = g.getTransform();
		if (alpha == null) {
			for (int i = 0; i < polys.length; i++) {
				g.transform(transforms[i]);
				g.fillPolygon(polys[i]);
				g.setTransform(oldTrans);
			}
		}
		else {
			Composite oldComp = g.getComposite();
			for (int i = 0; i < polys.length; i++) {
				g.transform(transforms[i]);
				g.setComposite(alpha[i]);
				g.fillPolygon(polys[i]);
				g.setTransform(oldTrans);
			}
			g.setComposite(oldComp);
		}
	}

	/**
	 * Draws this frame at ({@code x}, {@code y}) using a cached image of its
	 * rendered polygons, rendering that image once on first use and blitting it
	 * thereafter. Equivalent in appearance to translating by ({@code x}, {@code y})
	 * and calling {@link #draw} with {@code texture} set as the paint, but far
	 * cheaper for frames that are drawn repeatedly. Any alpha on the target
	 * graphics (e.g. a fading unit) still applies, as it multiplies the blit.
	 */
	public void drawCached(Graphics2D g, double x, double y,
			TexturePaint texture, double scale) {
		if (spriteEmpty)
			return;
		if (!spriteTooBig) {
			BufferedImage img = (spriteRef != null) ? spriteRef.get() : null;
			if (img == null || texture != spriteTexture) {
				img = renderSprite(texture, scale);
				if (spriteEmpty)
					return;
				if (img != null) {
					spriteRef = new SoftReference<>(img);
					spriteTexture = texture;
				}
			}
			if (img != null) {
				g.drawImage(img, (int) Math.round(x) + spriteOx,
						(int) Math.round(y) + spriteOy, null);
				return;
			}
		}
		// Frame too large to cache: fall back to drawing its polygons directly.
		AffineTransform old = g.getTransform();
		g.translate(x, y);
		g.setPaint(texture);
		draw(g);
		g.setTransform(old);
	}

	/**
	 * Draws this frame recoloured to {@code color} and masked by its own shape, at
	 * {@code strength} opacity, over ({@code x}, {@code y}) — the same blit
	 * {@link #drawCached} performs, but tinted, so a unit's sprite itself can pulse
	 * with a status colour. Does nothing for empty or uncacheable frames.
	 */
	public void drawCachedTinted(Graphics2D g, double x, double y,
			TexturePaint texture, double scale, Color color, float strength) {
		if (spriteEmpty || spriteTooBig)
			return;
		BufferedImage base = (spriteRef != null) ? spriteRef.get() : null;
		if (base == null || texture != spriteTexture) {
			base = renderSprite(texture, scale);
			if (base == null) // became empty or too big to cache
				return;
			spriteRef = new SoftReference<>(base);
			spriteTexture = texture;
		}
		BufferedImage tint = tintedSprite(base, texture, color);
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, strength));
		g.drawImage(tint, (int) Math.round(x) + spriteOx,
				(int) Math.round(y) + spriteOy, null);
		g.setComposite(old);
	}

	/**
	 * Returns {@code base} recoloured to {@code color} with its alpha preserved,
	 * caching one image per colour for the current texture.
	 */
	private BufferedImage tintedSprite(BufferedImage base, TexturePaint texture,
			Color color) {
		if (tintCache == null || texture != tintTexture) {
			tintCache = new HashMap<>();
			tintTexture = texture;
		}
		int key = color.getRGB();
		SoftReference<BufferedImage> ref = tintCache.get(key);
		BufferedImage tint = (ref != null) ? ref.get() : null;
		if (tint == null) {
			tint = recolour(base, color);
			tintCache.put(key, new SoftReference<>(tint));
		}
		return tint;
	}

	/** A copy of {@code src} with every pixel's RGB set to {@code color} and its
	 *  original alpha kept, giving a solid-colour silhouette of the sprite. */
	private static BufferedImage recolour(BufferedImage src, Color color) {
		int w = src.getWidth(), h = src.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		int rgb = color.getRGB() & 0x00FFFFFF;
		int[] row = new int[w];
		for (int y = 0; y < h; y++) {
			src.getRGB(0, y, w, 1, row, 0, w);
			for (int x = 0; x < w; x++)
				row[x] = (row[x] & 0xFF000000) | rgb;
			out.setRGB(0, y, w, 1, row, 0, w);
		}
		return out;
	}

	/**
	 * Renders this frame's polygons into a fresh image sized to its bounds, also
	 * recording the offset from the draw origin to the image's top-left. Returns
	 * {@code null} (and sets {@link #spriteEmpty} or {@link #spriteTooBig}) when
	 * the frame has nothing to draw or is too large to be worth caching.
	 */
	private BufferedImage renderSprite(TexturePaint texture, double scale) {
		if (xMax < xMin) { // no visible polygons
			spriteEmpty = true;
			return null;
		}
		int ox = (int) Math.floor(xMin * scale) - 1;
		int oy = (int) Math.floor(yMin * scale) - 1;
		int w = (int) Math.ceil((xMax + 1) * scale) - ox + 1;
		int h = (int) Math.ceil((yMax + 1) * scale) - oy + 1;
		if (w <= 0 || h <= 0 || (long) w * h > MAX_SPRITE_PIXELS) {
			spriteTooBig = true;
			return null;
		}
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
		g.translate(-ox, -oy);
		g.setPaint(texture);
		draw(g);
		g.dispose();
		spriteOx = ox;
		spriteOy = oy;
		return img;
	}

}