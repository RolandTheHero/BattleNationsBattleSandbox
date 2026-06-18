package hero.roland.bnsim;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.SourceDataLine;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/**
 * Plays short one-shot sound effects and (via {@link #streamMp3}) streamed
 * music, each on a daemon thread so several can overlap. MP3 is decoded with
 * JLayer and scaled by the {@linkplain #setVolume master volume} so volume
 * changes apply live; WAV/AU/AIFF use Java Sound. Playback is best-effort: a
 * missing or unsupported file is silently ignored.
 */
public final class SoundPlayer {

	/** Master volume, 0 (silent) to 1 (full); applied to all playback. */
	private static volatile float volume = 1.0f;

	private SoundPlayer() {
	}

	public static void setVolume(float value) {
		volume = Math.max(0f, Math.min(1f, value));
	}

	public static float getVolume() {
		return volume;
	}

	/** Plays the given sound file once, asynchronously. */
	public static void play(File file) {
		if (file == null || !file.isFile())
			return;
		Thread thread = new Thread(() -> playBlocking(file), "sound-fx");
		thread.setDaemon(true);
		thread.start();
	}

	private static void playBlocking(File file) {
		try {
			if (file.getName().toLowerCase().endsWith(".mp3")) {
				try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
					streamMp3(in, () -> true);
				}
			} else {
				playClip(file);
			}
		} catch (Exception e) {
			// Best-effort: ignore unreadable or unsupported audio.
		}
	}

	private static void playClip(File file) throws Exception {
		try (AudioInputStream audio = AudioSystem.getAudioInputStream(file)) {
			Clip clip = AudioSystem.getClip();
			CountDownLatch done = new CountDownLatch(1);
			clip.addLineListener(event -> {
				if (event.getType() == LineEvent.Type.STOP)
					done.countDown();
			});
			clip.open(audio);
			applyVolume(clip);
			clip.start();
			done.await();
			clip.close();
		}
	}

	/**
	 * Decodes an MP3 stream and plays it, scaling each sample by the current
	 * master volume (so volume changes apply live). Stops early once
	 * {@code running} returns {@code false}.
	 */
	static void streamMp3(InputStream in, BooleanSupplier running) throws Exception {
		Bitstream bitstream = new Bitstream(in);
		Decoder decoder = new Decoder();
		SourceDataLine line = null;
		try {
			Header header;
			while (running.getAsBoolean() && (header = bitstream.readFrame()) != null) {
				SampleBuffer buffer = (SampleBuffer) decoder.decodeFrame(header, bitstream);
				if (line == null) {
					AudioFormat format = new AudioFormat(decoder.getOutputFrequency(),
							16, decoder.getOutputChannels(), true, false);
					line = AudioSystem.getSourceDataLine(format);
					line.open(format);
					line.start();
				}
				byte[] bytes = scale(buffer.getBuffer(), buffer.getBufferLength(), volume);
				line.write(bytes, 0, bytes.length);
				bitstream.closeFrame();
			}
		} finally {
			if (line != null) {
				if (running.getAsBoolean())
					line.drain(); // finished naturally: let the buffer play out
				line.close();
			}
			bitstream.close();
		}
	}

	/** Converts 16-bit samples to little-endian bytes, scaled by volume. */
	private static byte[] scale(short[] samples, int length, float gain) {
		byte[] bytes = new byte[length * 2];
		for (int i = 0; i < length; i++) {
			int s = Math.round(samples[i] * gain);
			if (s > Short.MAX_VALUE)
				s = Short.MAX_VALUE;
			else if (s < Short.MIN_VALUE)
				s = Short.MIN_VALUE;
			bytes[i * 2] = (byte) (s & 0xff);
			bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
		}
		return bytes;
	}

	private static void applyVolume(Line line) {
		if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN))
			return;
		FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
		float db = volume <= 0f ? gain.getMinimum() : (float) (20.0 * Math.log10(volume));
		gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
	}
}
