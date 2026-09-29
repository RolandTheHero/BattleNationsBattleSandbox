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
import java.awt.geom.Rectangle2D;
import java.io.IOException;

import hero.roland.bnsim.GridPoint;
import hero.roland.bnsim.gamefiles.GameFiles;

public class Animation implements Drawable {
	private Timeline timeline;
	private Bitmap bitmap;
	private int numFrames, delay;
	private double xPos, yPos;
	private boolean loop;

	/** A new animation for the named timeline from the active bundle, or {@code null}. */
	public static Animation get(String name) throws IOException {
		return GameFiles.active().getAnimation(name);
	}

	public Animation(Timeline timeline, GameFiles gf) throws IOException {
		this.timeline = timeline;
		this.numFrames = timeline.getNumFrames();
		bitmap = gf.getBitmap(timeline.getPackageName());
	}

	public String getName() {
		return timeline.getName();
	}

	public Timeline getTimeline() {
		return timeline;
	}

	public Bitmap getBitmap() {
		return bitmap;
	}

	public int getNumFrames() {
		return numFrames;
	}

	public Frame getFrame(int num) {
		num -= delay;
		if (num < 0 || num >= numFrames)
			return null;
		return timeline.getFrame(num);
	}

	public double getX() {
		return xPos;
	}

	public double getY() {
		return yPos;
	}

	public void setPosition(double x, double y) {
		xPos = x;
		yPos = y;
	}

	public void setGridPosition(int x, int y) {
		GridPoint p = new GridPoint(x, y);
		xPos = p.x;
		yPos = p.y + GridPoint.GRID_Y;
	}

	public int getDelay() {
		return delay;
	}

	public void setDelay(int delay) {
		this.delay = delay;
	}

	@Override
	public int getEndFrame() {
		return numFrames + delay;
	}

	public void earlyStop(int frame) {
		frame -= delay;
		// Some animations only move on even-numbered frames, so leave 2
		if (frame < 2) frame = 2;
		Frame first = timeline.getFrame(0);
		while (frame < numFrames) {
			if (timeline.getFrame(frame) == first) {
				numFrames = frame;
				break;
			}
			frame++;
		}
	}

	public void singleFrame() {
		numFrames = 1;
		loop = true;
	}

	public void setLoop(boolean loop) {
		this.loop = loop;
	}

	@Override
	public Rectangle2D.Double getBounds() {
		Rectangle2D.Double bounds = timeline.getBounds();
		bounds.x += xPos;
		bounds.y += yPos;
		return bounds;
	}

	@Override
	public Rectangle2D.Double getBounds(int frame) {
		frame -= delay;
		if (frame < 0 || frame >= numFrames)
			return null;
		Rectangle2D.Double bounds = timeline.getBounds(frame);
		if (bounds != null) {
			bounds.x += xPos;
			bounds.y += yPos;
		}
		return bounds;
	}

	@Override
	public void drawFrame(int num, Graphics2D g) {
		num -= delay;
		if (num < 0)
			return;
		if (loop)
			num %= numFrames;
		else if (num >= numFrames)
			return;
		timeline.drawFrameCached(num, g, xPos, yPos, bitmap.getTexture());
	}

	/**
	 * Draws frame {@code num} recoloured to {@code color} at {@code strength}
	 * opacity, masked by the sprite's shape, at the animation's current position.
	 * Pairs with {@link #drawFrame} to pulse a status tint over the unit itself.
	 */
	public void drawFrameTinted(int num, Graphics2D g, Color color, float strength) {
		num -= delay;
		if (num < 0)
			return;
		if (loop)
			num %= numFrames;
		else if (num >= numFrames)
			return;
		timeline.drawFrameCachedTinted(num, g, xPos, yPos, bitmap.getTexture(),
				color, strength);
	}

	@Override
	public double getSortPosition() {
		return yPos;
	}

}
