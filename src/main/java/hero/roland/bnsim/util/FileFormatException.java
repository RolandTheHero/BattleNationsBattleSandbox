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

package hero.roland.bnsim.util;

import java.io.IOException;

public class FileFormatException extends IOException {

	private static final long serialVersionUID = 1L;

	public FileFormatException() {
	}

	public FileFormatException(String message) {
		super(message);
	}

	public FileFormatException(Throwable cause) {
		super(cause);
	}

	public FileFormatException(String message, Throwable cause) {
		super(message, cause);
	}

}