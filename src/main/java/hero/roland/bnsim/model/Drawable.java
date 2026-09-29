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

import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

public interface Drawable {
	public void drawFrame(int frame, Graphics2D g);
	public double getSortPosition();
	public Rectangle2D.Double getBounds();
	public Rectangle2D.Double getBounds(int frame);
	public int getEndFrame();
}
