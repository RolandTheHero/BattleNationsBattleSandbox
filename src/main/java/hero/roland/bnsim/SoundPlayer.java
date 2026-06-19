package hero.roland.bnsim;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
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
 * JLayer and {@code .caf} files with a built-in decoder: their codec is read
 * from the CAF audio description, IMA4 ADPCM is decoded in-process and AAC via
 * JAAD. All decoded audio is scaled by the {@linkplain #setVolume master
 * volume} so volume changes apply live. WAV/AU/AIFF use Java Sound. Playback is
 * best-effort: a missing or unsupported file is silently ignored.
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
	 * Plays an Apple Core Audio Format file by reading its codec from the audio
	 * description and decoding to 16-bit PCM: IMA4 ADPCM in-process, AAC via
	 * JAAD. Other codecs are ignored (best-effort).
	 */
	private static void playCaf(File file) throws Exception {
		byte[] bytes = Files.readAllBytes(file.toPath());
		CafInfo caf = parseCaf(bytes);
		if (caf == null || caf.dataOffset < 0 || caf.channels < 1)
			return;
		Pcm pcm = decodeCaf(bytes, caf);
		if (pcm != null && pcm.samples().length > 0)
			playPcm(pcm);
	}

	/** Decodes a parsed CAF to PCM by codec, or {@code null} if unsupported. */
	private static Pcm decodeCaf(byte[] bytes, CafInfo caf) throws Exception {
		switch (caf.formatId) {
			case 0x696d6134: // "ima4" — IMA ADPCM
				if (caf.bytesPerPacket < caf.channels)
					return null;
				return new Pcm(decodeIma4(bytes, caf.dataOffset, caf.dataLength,
						caf.channels, caf.bytesPerPacket, caf.framesPerPacket),
						(float) caf.sampleRate, caf.channels);
			case 0x61616320: // "aac " — Advanced Audio Coding
				return decodeAac(bytes, caf);
			default:
				return null; // unsupported codec (e.g. lpcm, alac) — silently skipped
		}
	}

	/** Writes decoded PCM to a line, scaled by the live master volume. */
	private static void playPcm(Pcm pcm) throws Exception {
		short[] samples = pcm.samples();
		AudioFormat format = new AudioFormat(pcm.sampleRate(), 16, pcm.channels(), true, false);
		SourceDataLine line = AudioSystem.getSourceDataLine(format);
		try {
			line.open(format);
			line.start();
			for (int i = 0; i < samples.length; i += 4096) {
				int len = Math.min(4096, samples.length - i);
				byte[] out = scale(samples, i, len, volume);
				line.write(out, 0, out.length);
			}
			line.drain();
		} finally {
			line.close();
		}
	}

	/**
	 * Parses a CAF container: its audio description plus the offsets of the
	 * {@code kuki} (codec config), {@code pakt} (packet table) and {@code data}
	 * chunks. Returns {@code null} if the file is not a CAF. All values are
	 * big-endian.
	 */
	private static CafInfo parseCaf(byte[] bytes) {
		ByteBuffer buf = ByteBuffer.wrap(bytes); // CAF is big-endian
		if (buf.remaining() < 8 || buf.getInt() != 0x63616666) // "caff"
			return null;
		buf.getShort(); // file version
		buf.getShort(); // file flags

		CafInfo caf = new CafInfo();
		while (buf.remaining() >= 12) {
			int type = buf.getInt();
			long size = buf.getLong();
			int body = buf.position();
			switch (type) {
				case 0x64657363 -> { // "desc" — audio description
					caf.sampleRate = buf.getDouble();
					caf.formatId = buf.getInt();
					buf.getInt(); // format flags
					caf.bytesPerPacket = buf.getInt();
					caf.framesPerPacket = buf.getInt();
					caf.channels = buf.getInt();
				}
				case 0x6b756b69 -> { // "kuki" — magic cookie (AAC config)
					caf.kukiOffset = body;
					caf.kukiLength = (int) size;
				}
				case 0x70616b74 -> { // "pakt" — variable packet-size table
					caf.paktOffset = body;
					caf.paktLength = (int) size;
				}
				case 0x64617461 -> { // "data"
					long len = size < 0 ? bytes.length - body : size;
					caf.dataOffset = body + 4; // skip leading mEditCount
					caf.dataLength = (int) (len - 4);
				}
				default -> {
					// other chunks (info, free, chan, ...) are not needed
				}
			}
			if (size < 0)
				break; // an unbounded chunk runs to EOF, so it must be last
			buf.position((int) (body + size));
		}
		return caf;
	}

	/** Parsed CAF container: audio description plus chunk locations. */
	private static final class CafInfo {
		double sampleRate;
		int formatId, channels, bytesPerPacket, framesPerPacket;
		int kukiOffset = -1, kukiLength;
		int paktOffset = -1, paktLength;
		int dataOffset = -1, dataLength;
	}

	/** Decoded interleaved signed-16-bit PCM, ready to play. */
	private record Pcm(short[] samples, float sampleRate, int channels) {
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
	 * Decodes the AAC packets of a CAF {@code data} chunk to interleaved 16-bit
	 * PCM with JAAD. The decoder is configured from the AudioSpecificConfig in
	 * the {@code kuki} chunk (synthesised from the description if absent), and
	 * each variable-length packet's byte size comes from the {@code pakt} table.
	 */
	private static Pcm decodeAac(byte[] bytes, CafInfo caf) throws Exception {
		byte[] asc = caf.kukiOffset >= 0
				? extractAudioSpecificConfig(bytes, caf.kukiOffset, caf.kukiLength)
				: null;
		if (asc == null)
			asc = synthesiseAsc(caf.sampleRate, caf.channels);
		int[] sizes = parsePacketSizes(bytes, caf.paktOffset, caf.paktLength);
		if (sizes.length == 0)
			return null;

		net.sourceforge.jaad.aac.Decoder decoder = new net.sourceforge.jaad.aac.Decoder(asc);
		net.sourceforge.jaad.aac.SampleBuffer buffer = new net.sourceforge.jaad.aac.SampleBuffer();
		buffer.setBigEndian(false); // little-endian to match the playback format
		ByteArrayOutputStream pcm = new ByteArrayOutputStream();
		int offset = caf.dataOffset, end = caf.dataOffset + caf.dataLength;
		int sampleRate = (int) caf.sampleRate, channels = caf.channels;
		for (int size : sizes) {
			if (size <= 0 || offset + size > end)
				break;
			decoder.decodeFrame(Arrays.copyOfRange(bytes, offset, offset + size), buffer);
			byte[] frame = buffer.getData();
			pcm.write(frame, 0, frame.length);
			sampleRate = buffer.getSampleRate();
			channels = buffer.getChannels();
			offset += size;
		}

		byte[] data = pcm.toByteArray();
		short[] samples = new short[data.length / 2];
		for (int i = 0; i < samples.length; i++)
			samples[i] = (short) ((data[i * 2] & 0xff) | (data[i * 2 + 1] << 8)); // little-endian
		return new Pcm(samples, sampleRate, channels);
	}

	/**
	 * Reads the per-packet byte sizes from a CAF {@code pakt} chunk. Assumes a
	 * variable byte size with constant frames per packet (as AAC uses), so each
	 * table entry is a single base-128 variable-length integer.
	 */
	private static int[] parsePacketSizes(byte[] bytes, int paktOffset, int paktLength) {
		if (paktOffset < 0 || paktLength < 24)
			return new int[0];
		ByteBuffer buf = ByteBuffer.wrap(bytes, paktOffset, paktLength); // big-endian
		int count = (int) buf.getLong(); // mNumberPackets
		buf.getLong();                    // mNumberValidFrames
		buf.getInt();                     // mPrimingFrames
		buf.getInt();                     // mRemainderFrames
		int[] sizes = new int[Math.max(0, count)];
		for (int i = 0; i < sizes.length && buf.hasRemaining(); i++) {
			int value = 0, b;
			do {
				b = buf.get() & 0xff;
				value = (value << 7) | (b & 0x7f);
			} while ((b & 0x80) != 0 && buf.hasRemaining());
			sizes[i] = value;
		}
		return sizes;
	}

	/**
	 * Pulls the AudioSpecificConfig (MPEG-4 DecoderSpecificInfo, tag {@code 0x05})
	 * out of a CAF {@code kuki} cookie, which holds an ES descriptor tree.
	 */
	private static byte[] extractAudioSpecificConfig(byte[] bytes, int offset, int length) {
		return findDescriptor(bytes, offset, offset + length, 0x05);
	}

	/** Walks the MPEG-4 descriptor tree, descending containers, for {@code tag}. */
	private static byte[] findDescriptor(byte[] d, int start, int end, int tag) {
		int i = start;
		while (i < end) {
			int t = d[i++] & 0xff;
			int size = 0, b, count = 0;
			do { // expandable size: 7 bits per byte, high bit continues
				b = d[i++] & 0xff;
				size = (size << 7) | (b & 0x7f);
			} while ((b & 0x80) != 0 && ++count < 4 && i < end);
			int body = i;
			if (t == tag)
				return Arrays.copyOfRange(d, body, Math.min(end, body + size));
			if (t == 0x03) { // ES_Descriptor: ES_ID(2) + flags(1) [+ optional fields]
				int p = body + 2;
				int flags = d[p++] & 0xff;
				if ((flags & 0x80) != 0)
					p += 2; // dependsOn ES_ID
				if ((flags & 0x40) != 0)
					p += 1 + (d[p] & 0xff); // URL string
				if ((flags & 0x20) != 0)
					p += 2; // OCR ES_ID
				byte[] r = findDescriptor(d, p, body + size, tag);
				if (r != null)
					return r;
			} else if (t == 0x04) { // DecoderConfigDescriptor: 13-byte header, then nested
				byte[] r = findDescriptor(d, body + 13, body + size, tag);
				if (r != null)
					return r;
			}
			i = body + size;
		}
		return null;
	}

	/** Sample rates for the AAC frequency index, per ISO/IEC 14496-3. */
	private static final int[] AAC_RATES = {
			96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
			16000, 12000, 11025, 8000, 7350 };

	/** Builds a 2-byte AAC-LC AudioSpecificConfig as a fallback when no cookie. */
	private static byte[] synthesiseAsc(double sampleRate, int channels) {
		int freqIndex = 4; // default to 44100 Hz
		for (int i = 0; i < AAC_RATES.length; i++)
			if (AAC_RATES[i] == (int) sampleRate)
				freqIndex = i;
		int value = (2 << 11) | (freqIndex << 7) | ((channels & 0x0f) << 3); // AOT 2 = AAC-LC
		return new byte[] { (byte) (value >> 8), (byte) value };
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
