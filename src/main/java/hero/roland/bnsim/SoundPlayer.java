package hero.roland.bnsim;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
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
 * JLayer and {@code .caf} (Apple IMA4 ADPCM) with a built-in decoder; both are
 * scaled by the {@linkplain #setVolume master volume} so volume changes apply
 * live. WAV/AU/AIFF use Java Sound. Playback is best-effort: a missing or
 * unsupported file is silently ignored.
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
			String name = file.getName().toLowerCase();
			if (name.endsWith(".mp3")) {
				try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
					streamMp3(in, () -> true);
				}
			} else if (name.endsWith(".caf")) {
				playCaf(file);
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

	// IMA ADPCM step-size and index-adjustment tables (shared by all IMA variants).
	private static final int[] STEP_TABLE = {
			7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
			50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
			253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
			1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
			3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
			12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767 };
	private static final int[] INDEX_TABLE = { -1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8 };

	/**
	 * Plays an Apple Core Audio Format file containing IMA4 ADPCM, decoding it to
	 * 16-bit PCM and writing it scaled by the current master volume so volume
	 * changes apply live. Non-IMA4 {@code .caf} files are ignored (best-effort).
	 */
	private static void playCaf(File file) throws Exception {
		byte[] bytes = Files.readAllBytes(file.toPath());
		ByteBuffer buf = ByteBuffer.wrap(bytes); // CAF is big-endian
		if (buf.remaining() < 8 || buf.getInt() != 0x63616666) // "caff"
			return;
		buf.getShort(); // file version
		buf.getShort(); // file flags

		double sampleRate = 0;
		int formatId = 0, channels = 0, bytesPerPacket = 0, framesPerPacket = 0;
		int dataOffset = -1, dataLength = 0;
		while (buf.remaining() >= 12) {
			int type = buf.getInt();
			long size = buf.getLong();
			int body = buf.position();
			if (type == 0x64657363) { // "desc" — audio description
				sampleRate = buf.getDouble();
				formatId = buf.getInt();
				buf.getInt(); // format flags
				bytesPerPacket = buf.getInt();
				framesPerPacket = buf.getInt();
				channels = buf.getInt();
			} else if (type == 0x64617461) { // "data"
				long len = size < 0 ? bytes.length - body : size;
				dataOffset = body + 4; // skip leading mEditCount
				dataLength = (int) (len - 4);
			}
			if (size < 0)
				break; // an unbounded chunk runs to EOF, so it must be last
			buf.position((int) (body + size));
		}

		if (formatId != 0x696d6134 || dataOffset < 0 || channels < 1 || bytesPerPacket < channels)
			return; // only IMA4 ADPCM is supported

		short[] pcm = decodeIma4(bytes, dataOffset, dataLength, channels, bytesPerPacket, framesPerPacket);
		AudioFormat format = new AudioFormat((float) sampleRate, 16, channels, true, false);
		SourceDataLine line = AudioSystem.getSourceDataLine(format);
		try {
			line.open(format);
			line.start();
			for (int i = 0; i < pcm.length; i += 4096) {
				int len = Math.min(4096, pcm.length - i);
				byte[] out = scale(pcm, i, len, volume);
				line.write(out, 0, out.length);
			}
			line.drain();
		} finally {
			line.close();
		}
	}

	/**
	 * Decodes Apple IMA4 ADPCM to interleaved signed 16-bit PCM. Each packet holds
	 * {@code channels} sub-blocks of {@code bytesPerPacket / channels} bytes; every
	 * sub-block is a 2-byte preamble (initial predictor in the top 9 bits, step
	 * index in the low 7) followed by 4-bit nibbles, low nibble first.
	 */
	static short[] decodeIma4(byte[] data, int offset, int length, int channels,
			int bytesPerPacket, int framesPerPacket) {
		int perChannel = bytesPerPacket / channels;
		int packets = length / bytesPerPacket;
		short[] pcm = new short[packets * framesPerPacket * channels];
		for (int p = 0; p < packets; p++) {
			for (int ch = 0; ch < channels; ch++) {
				int base = offset + p * bytesPerPacket + ch * perChannel;
				int preamble = ((data[base] & 0xff) << 8) | (data[base + 1] & 0xff);
				int predictor = (short) (preamble & 0xff80);
				int index = Math.min(preamble & 0x7f, 88);
				int frame = p * framesPerPacket;
				for (int b = 2; b < perChannel; b++) {
					int both = data[base + b] & 0xff;
					for (int half = 0; half < 2; half++) {
						int nibble = half == 0 ? both & 0x0f : both >> 4;
						int step = STEP_TABLE[index];
						int diff = step >> 3;
						if ((nibble & 1) != 0)
							diff += step >> 2;
						if ((nibble & 2) != 0)
							diff += step >> 1;
						if ((nibble & 4) != 0)
							diff += step;
						predictor += (nibble & 8) != 0 ? -diff : diff;
						if (predictor > Short.MAX_VALUE)
							predictor = Short.MAX_VALUE;
						else if (predictor < Short.MIN_VALUE)
							predictor = Short.MIN_VALUE;
						index += INDEX_TABLE[nibble];
						if (index < 0)
							index = 0;
						else if (index > 88)
							index = 88;
						pcm[frame * channels + ch] = (short) predictor;
						frame++;
					}
				}
			}
		}
		return pcm;
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
				byte[] bytes = scale(buffer.getBuffer(), 0, buffer.getBufferLength(), volume);
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

	/** Converts {@code length} 16-bit samples from {@code offset} to little-endian bytes, scaled by volume. */
	private static byte[] scale(short[] samples, int offset, int length, float gain) {
		byte[] bytes = new byte[length * 2];
		for (int i = 0; i < length; i++) {
			int s = Math.round(samples[offset + i] * gain);
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
