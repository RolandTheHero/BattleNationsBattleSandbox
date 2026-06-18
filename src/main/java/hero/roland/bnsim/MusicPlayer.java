package hero.roland.bnsim;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

import javazoom.jl.player.Player;

/**
 * Plays an MP3 file on a background daemon thread, looping until stopped.
 * Used for the battle background music. All playback is best-effort: a missing
 * or unreadable file simply results in silence rather than an error.
 */
public class MusicPlayer {

	private final Object lock = new Object();
	private Player player;
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
				Player p = new Player(in);
				synchronized (lock) {
					if (!running)
						break;
					player = p;
				}
				p.play(); // blocks until the track ends or the player is closed
			} catch (Exception e) {
				break; // unreadable/undecodable file: give up silently
			}
		}
	}

	/** Stops playback if any. */
	public void stop() {
		synchronized (lock) {
			running = false;
			if (player != null) {
				player.close();
				player = null;
			}
			thread = null;
		}
	}
}
