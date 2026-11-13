package hero.roland.bnsim.gamefiles.newformat;

import java.io.File;

/**
 * Loads the Linux build of the Madrona (Unity) remaster. Its files are in exactly
 * the same formats and folders as the Windows build's, so everything is read as
 * in {@link NewGameFiles}; only these names differ:
 * <ul>
 * <li>the bundles are in {@code BattleNations_Data/StreamingAssets/aa/StandaloneLinux64}
 * rather than {@code .../StandaloneWindows64};
 * <li>FMOD is in {@code UnityPlayer.so} rather than {@code UnityPlayer.dll}.
 * </ul>
 */
public final class LinuxGameFiles extends NewGameFiles {
	/** The Linux build's engine library, in the install folder. */
	private static final String FMOD_LIBRARY = "UnityPlayer.so";
	/** Where the Linux build's asset bundles are, relative to the folders a user might pick. */
	private static final String[] LINUX_BUNDLE_FOLDERS = {
		"", "aa/StandaloneLinux64", "StreamingAssets/aa/StandaloneLinux64",
		"BattleNations_Data/StreamingAssets/aa/StandaloneLinux64",
	};

	/**
	 * @param folder the game's install folder, its {@code BattleNations_Data}
	 * folder, or the bundle folder itself
	 */
	public LinuxGameFiles(File folder) {
		super(folder, LINUX_BUNDLE_FOLDERS);
	}

	@Override
	protected File findFmodLibrary() {
		// Walking up from the bundle folder reaches the install folder.
		return findAbove(FMOD_LIBRARY);
	}
}
