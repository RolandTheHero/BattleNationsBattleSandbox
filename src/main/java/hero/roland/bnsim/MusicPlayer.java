package hero.roland.bnsim;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Plays an MP3 file on a background daemon thread, looping until stopped, at the
 * shared {@link SoundPlayer} master volume. Used for the battle background
 * music. Playback is best-effort: a missing or unreadable file is silently
 * ignored.
 */
public class MusicPlayer {

	private final Object lock = new Object();
	private Thread thread;
	private volatile boolean running;

	/** Starts looping the given MP3 file, replacing any current playback. */
	public void loop(File file) {
		stop();
		if (file == null || !file.isFile())
			return;
		synchronized (lock) {
			running = true;
			thread = new Thread(() -> playLoop(file), "battle-music");
			thread.setDaemon(true);
			thread.start();
		}
	}

	private void playLoop(File file) {
		while (running) {
			try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
				SoundPlayer.streamMp3(in, () -> running);
			} catch (Exception e) {
				break; // unreadable/undecodable file: give up silently
			}
		}
	}

	/** Stops playback if any. */
	public void stop() {
		synchronized (lock) {
			running = false;
			thread = null;
		}
	}
}
