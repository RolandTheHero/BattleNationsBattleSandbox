package hero.roland.bnsim;

import java.io.ByteArrayInputStream;

import hero.roland.bnsim.model.Sound;

/**
 * Plays an MP3 or 16-bit PCM WAV sound on a background daemon thread, looping
 * until stopped, at the shared {@link SoundPlayer} master volume. Used for the
 * battle background music. Playback is best-effort: a missing or unreadable
 * sound is silently ignored.
 */
public class MusicPlayer {

	private final Object lock = new Object();
	private Thread thread;
	private volatile boolean running;

	/** Starts looping the given sound, replacing any current playback. */
	public void loop(Sound sound) {
		stop();
		if (sound == null)
			return;
		synchronized (lock) {
			running = true;
			thread = new Thread(() -> playLoop(sound), "battle-music");
			thread.setDaemon(true);
			thread.start();
		}
	}

	private void playLoop(Sound sound) {
		while (running) {
			try {
				if (sound.format().equals("wav"))
					SoundPlayer.streamWav(sound.data(), () -> running);
				else
					SoundPlayer.streamMp3(new ByteArrayInputStream(sound.data()), () -> running);
			} catch (Exception e) {
				System.err.println("Could not play music: " + e);
				break; // unreadable/undecodable sound: give up
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
