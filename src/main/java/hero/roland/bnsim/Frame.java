package hero.roland.bnsim;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.ref.SoftReference;

import hero.roland.bnsim.util.FileFormatException;
import hero.roland.bnsim.util.LittleEndianInputStream;

public class Frame {
	private AffineTransform[] transforms;
	private Polygon[] polys;
	private AlphaComposite[] alpha;
	private int xMin, xMax, yMin, yMax;

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
	/** This frame has no visible polygons, so there is nothing to draw or cache. */
	private boolean spriteEmpty;
	/** Frame too large to cache; it falls back to drawing its polygons directly. */
	private boolean spriteTooBig;
	/** Skip caching frames larger than this (~16 MB as ARGB) to bound memory. */
	private static final long MAX_SPRITE_PIXELS = 4_000_000L;

	protected Frame() {
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

	protected void read(LittleEndianInputStream in, int ver,
			Timeline.Vertex[] coords) throws IOException {
		int numPts = in.readUnsignedShort();
		if (numPts < 0 || numPts % 6 != 0)
			throw new FileFormatException("Unexpected frame size");
		int numPolys = numPts / 6;
		polys = new Polygon[numPolys];
		transforms = new AffineTransform[numPolys];
		alpha = new AlphaComposite[numPolys];
		if (ver > 4) in.readByte();

		xMin = Integer.MAX_VALUE;
		xMax = Integer.MIN_VALUE;
		yMin = Integer.MAX_VALUE;
		yMax = Integer.MIN_VALUE;

		double scale = (ver > 4) ? 1.0/32 : 1;
		boolean hasAlpha = false;
		int[] p = new int[6];
		int[] x = new int[4];
		int[] y = new int[4];
		for (int i = 0; i < numPolys; i++) {
			for (int j = 0; j < 6; j++)
				p[j] = in.readUnsignedShort();
			if (p[3] != p[0] || p[4] != p[2])
				throw new FileFormatException("Unexpected frame arrangement");
			Timeline.Vertex p0 = coords[p[0]], p1 = coords[p[1]],
					p2 = coords[p[2]], p3 = coords[p[5]];

			if (p0.alpha != 1) hasAlpha = true;
			alpha[i] = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, p0.alpha);

			if (p0.alpha >= 0.5f / 255) {
				stretchBounds(p0.x1, p0.y1);
				stretchBounds(p1.x1, p1.y1);
				stretchBounds(p2.x1, p2.y1);
				stretchBounds(p3.x1, p3.y1);
			}

			AffineTransform t = new AffineTransform(
					(p1.x1 - p0.x1) * scale, (p1.y1 - p0.y1) * scale,
					(p2.x1 - p0.x1) * scale, (p2.y1 - p0.y1) * scale,
					p0.x1 * scale, p0.y1 * scale);
			AffineTransform t2 = new AffineTransform(
					p1.x2 - p0.x2, p1.y2 - p0.y2,
					p2.x2 - p0.x2, p2.y2 - p0.y2,
					p0.x2, p0.y2);
			try {
				t.concatenate(t2.createInverse());
			}
			catch (NoninvertibleTransformException e) {
				throw new FileFormatException("Bad transform", e);
			}
			transforms[i] = t;

			x[0] = p0.x2;  y[0] = p0.y2;
			x[1] = p1.x2;  y[1] = p1.y2;
			x[2] = p2.x2;  y[2] = p2.y2;
			x[3] = p3.x2;  y[3] = p3.y2;
			polys[i] = new Polygon(x, y, 4);
		}

		if (!hasAlpha)
			alpha = null;
	} // read

	private void stretchBounds(int x, int y) {
		if (x < xMin) xMin = x;
		if (x > xMax) xMax = x;
		if (y < yMin) yMin = y;
		if (y > yMax) yMax = y;
	}

}