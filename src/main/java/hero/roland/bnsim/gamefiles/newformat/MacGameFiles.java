package hero.roland.bnsim.gamefiles.newformat;

import java.io.File;

/**
 * Loads the Mac build of the Madrona (Unity) remaster. Its files are in exactly
 * the same formats as the Windows build's, so everything is read as in
 * {@link NewGameFiles}; only where they are differs:
 * <ul>
 * <li>the data folder is {@code BattleNations.app/Contents/Resources/Data}
 * rather than {@code BattleNations_Data}, and its bundles are in
 * {@code StreamingAssets/aa/StandaloneOSX};
 * <li>FMOD is in {@code Contents/Frameworks/UnityPlayer.dylib} rather than
 * {@code UnityPlayer.dll}.
 * </ul>
 */
public final class MacGameFiles extends NewGameFiles {
	/** The Mac build's engine library, relative to the app's {@code Contents} folder. */
	private static final String FMOD_LIBRARY = "Frameworks/UnityPlayer.dylib";
	/** Where the Mac build's asset bundles are, relative to the folders a user might pick. */
	private static final String[] MAC_BUNDLE_FOLDERS = {
		"", "aa/StandaloneOSX", "StreamingAssets/aa/StandaloneOSX",
		"Data/StreamingAssets/aa/StandaloneOSX",
		"Resources/Data/StreamingAssets/aa/StandaloneOSX",
		"Contents/Resources/Data/StreamingAssets/aa/StandaloneOSX",
		"BattleNations.app/Contents/Resources/Data/StreamingAssets/aa/StandaloneOSX",
	};

	/**
	 * @param folder the folder holding {@code BattleNations.app}, the app itself,
	 * its {@code Contents/Resources/Data} folder, or the bundle folder itself
	 */
	public MacGameFiles(File folder) {
		super(folder, MAC_BUNDLE_FOLDERS);
	}

	@Override
	protected File findFmodLibrary() {
		// Walking up from the bundle folder reaches Contents, which holds Frameworks.
		return findAbove(FMOD_LIBRARY);
	}
}
