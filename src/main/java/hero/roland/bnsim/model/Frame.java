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
	private double tintRes;
	/**
	 * Resolution (device pixels per point) {@link #spriteRef} was rendered at, so
	 * a zoomed or HiDPI field gets a sprite cached at its own pixel density instead
	 * of a 1x image stretched up. See {@link #resolutionFor}.
	 */
	private double spriteRes;
	/** This frame has no visible polygons, so there is nothing to draw or cache. */
	private boolean spriteEmpty;
	/** Lowest resolution at which the frame proved too large to cache; at or above
	 *  it, the frame falls back to drawing its polygons directly. */
	private double tooBigRes = Double.POSITIVE_INFINITY;
	/** Skip caching frames larger than this (~16 MB as ARGB) to bound memory. */
	private static final long MAX_SPRITE_PIXELS = 4_000_000L;
	/** Highest resolution sprites are cached at; beyond it they are upscaled. */
	private static final double MAX_SPRITE_RES = 6;
	/** Cache resolutions are rounded up to multiples of 1/RES_STEPS. */
	private static final double RES_STEPS = 4;

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
		BufferedImage img = cachedSprite(texture, scale, resolutionFor(g));
		if (spriteEmpty)
			return;
		if (img != null) {
			blit(g, img, x, y, spriteRes);
			return;
		}
		// Frame too large to cache: fall back to drawing its polygons directly.
		AffineTransform old = g.getTransform();
		g.translate(x, y);
		g.setPaint(texture);
		draw(g);
		g.setTransform(old);
	}

	/**
	 * The resolution, in device pixels per point, to cache sprites at for drawing
	 * through {@code g}: the scale of its transform (field zoom times any HiDPI
	 * display scale), rounded up to the next {@code 1/RES_STEPS} so small zoom
	 * changes reuse the cache, and clamped to [1, {@link #MAX_SPRITE_RES}].
	 * Zoomed-out views keep the 1x cache and are filtered down when blitted.
	 */
	public static double resolutionFor(Graphics2D g) {
		AffineTransform t = g.getTransform();
		double sx = Math.hypot(t.getScaleX(), t.getShearY());
		double sy = Math.hypot(t.getShearX(), t.getScaleY());
		double s = Math.max(sx, sy);
		if (!(s > 1)) // also catches NaN
			return 1;
		return Math.min(MAX_SPRITE_RES, Math.ceil(s * RES_STEPS - 1e-6) / RES_STEPS);
	}

	/**
	 * This frame's sprite cached at {@code res}, rendering it if the cache is
	 * missing, was reclaimed, or is for another texture or resolution. A frame too
	 * large to cache at {@code res} steps down to the highest resolution that fits;
	 * {@link #spriteRes} gives the resolution of the returned image. Returns
	 * {@code null} when the frame is empty (setting {@link #spriteEmpty}) or too
	 * large to cache even at 1x.
	 */
	private BufferedImage cachedSprite(TexturePaint texture, double scale, double res) {
		while (true) {
			if (res >= tooBigRes) {
				if (tooBigRes <= 1)
					return null;
				res = Math.max(1, tooBigRes - 1 / RES_STEPS);
			}
			BufferedImage img = (spriteRef != null) ? spriteRef.get() : null;
			if (img != null && texture == spriteTexture && res == spriteRes)
				return img;
			img = renderSprite(texture, scale, res);
			if (img != null) {
				spriteRef = new SoftReference<>(img);
				spriteTexture = texture;
				spriteRes = res;
				return img;
			}
			if (spriteEmpty)
				return null;
			// Too big at res (tooBigRes is now res): retry a step lower.
		}
	}

	/**
	 * Draws a cached sprite (rendered at {@code res} device pixels per point) at
	 * ({@code x}, {@code y}), scaling it back to point size. Filtered, so the
	 * small down-scale from the rounded-up resolution, or a zoomed-out field,
	 * stays smooth.
	 */
	private void blit(Graphics2D g, BufferedImage img, double x, double y, double res) {
		Object oldInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		if (res == 1) {
			g.drawImage(img, (int) Math.round(x) + spriteOx,
					(int) Math.round(y) + spriteOy, null);
		} else {
			// Snap the origin to the cache's pixel grid so the sprite stays crisp.
			AffineTransform at = AffineTransform.getTranslateInstance(
					(Math.round(x * res) + spriteOx) / res,
					(Math.round(y * res) + spriteOy) / res);
			at.scale(1 / res, 1 / res);
			g.drawImage(img, at, null);
		}
		if (oldInterp != null)
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
	}

	/**
	 * Draws this frame recoloured to {@code color} and masked by its own shape, at
	 * {@code strength} opacity, over ({@code x}, {@code y}) — the same blit
	 * {@link #drawCached} performs, but tinted, so a unit's sprite itself can pulse
	 * with a status colour. Does nothing for empty or uncacheable frames.
	 */
	public void drawCachedTinted(Graphics2D g, double x, double y,
			TexturePaint texture, double scale, Color color, float strength) {
		if (spriteEmpty)
			return;
		BufferedImage base = cachedSprite(texture, scale, resolutionFor(g));
		if (base == null) // empty or too big to cache
			return;
		BufferedImage tint = tintedSprite(base, texture, spriteRes, color);
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, strength));
		blit(g, tint, x, y, spriteRes);
		g.setComposite(old);
	}

	/**
	 * Returns {@code base} recoloured to {@code color} with its alpha preserved,
	 * caching one image per colour for the current texture and resolution.
	 */
	private BufferedImage tintedSprite(BufferedImage base, TexturePaint texture,
			double res, Color color) {
		if (tintCache == null || texture != tintTexture || res != tintRes) {
			tintCache = new HashMap<>();
			tintTexture = texture;
			tintRes = res;
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
	 * Renders this frame's polygons into a fresh image sized to its bounds at
	 * {@code res} pixels per point, also recording the offset (in those pixels)
	 * from the draw origin to the image's top-left. Returns {@code null} (and sets
	 * {@link #spriteEmpty} or {@link #tooBigRes}) when the frame has nothing to
	 * draw or is too large to be worth caching at this resolution.
	 */
	private BufferedImage renderSprite(TexturePaint texture, double scale, double res) {
		if (xMax < xMin) { // no visible polygons
			spriteEmpty = true;
			return null;
		}
		double s = scale * res;
		int ox = (int) Math.floor(xMin * s) - 1;
		int oy = (int) Math.floor(yMin * s) - 1;
		int w = (int) Math.ceil((xMax + 1) * s) - ox + 1;
		int h = (int) Math.ceil((yMax + 1) * s) - oy + 1;
		if (w <= 0 || h <= 0 || (long) w * h > MAX_SPRITE_PIXELS) {
			tooBigRes = Math.min(tooBigRes, res);
			return null;
		}
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
		g.translate(-ox, -oy);
		g.scale(res, res);
		g.setPaint(texture);
		draw(g);
		g.dispose();
		spriteOx = ox;
		spriteOy = oy;
		return img;
	}

}