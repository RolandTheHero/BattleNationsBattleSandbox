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

import java.io.File;
import java.io.FilenameFilter;
import java.util.regex.Pattern;

public class GlobFilter implements FilenameFilter {
	private Pattern regex;

	public GlobFilter(String pattern) {
		regex = globToPattern(pattern);
	}

	public static Pattern globToPattern(String in) {
		StringBuilder out = new StringBuilder();
		out.append('^');
		for (int i = 0, len = in.length(); i < len; i++) {
			char c = in.charAt(i);
			switch (c) {
			case '*': out.append(".*"); break;
			case '.': out.append("\\."); break;
			case '?': out.append('.'); break;
			case ',': out.append("$|^"); break;
			default: out.append(c); break;
			}
		}
		out.append('$');
		return Pattern.compile(out.toString(), Pattern.CASE_INSENSITIVE);
	}

	@Override
	public boolean accept(File dir, String name) {
		return regex.matcher(name).matches();
	}

}