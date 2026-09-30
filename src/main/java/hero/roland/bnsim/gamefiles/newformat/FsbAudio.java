/*
 * The FSB5 header parsing follows python-fsb5
 * (https://github.com/HearthSim/python-fsb5), Copyright (c) 2016 Simon Pinfold,
 * MIT License. FSB Vorbis details (fixed block sizes, end of data) come from
 * vgmstream (https://github.com/vgmstream/vgmstream). Decoding uses JOrbis
 * (JCraft, Inc., GNU LGPL 2.1). The full notices are in THIRD_PARTY_NOTICES.md.
 */

package hero.roland.bnsim.gamefiles.newformat;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.jcraft.jogg.Packet;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

import hero.roland.bnsim.util.FileFormatException;

/**
 * Decodes the FMOD FSB5 sound banks that the Unity remaster's AudioClips hold.
 * Vorbis banks are decoded with JOrbis: FSB strips the three Vorbis header
 * packets, so the identification and comment headers are rebuilt, and the setup
 * header is looked up by its CRC32 in the game's own FMOD library (see
 * {@link VorbisSetups}).
 */
final class FsbAudio {
	private static final int MODE_PCM8 = 1, MODE_PCM16 = 2, MODE_VORBIS = 15;
	private static final int CHUNK_CHANNELS = 1, CHUNK_FREQUENCY = 2, CHUNK_VORBIS_DATA = 11;
	private static final int[] FREQUENCIES = {0, 8000, 11000, 11025, 16000, 22050, 24000, 32000, 44100, 48000};
	/** FSB Vorbis always uses 256- and 2048-sample blocks. */
	private static final int SHORT_BLOCK_EXP = 8, LONG_BLOCK_EXP = 11;
	private static final byte[] VORBIS = "vorbis".getBytes(StandardCharsets.US_ASCII);
	private static final int WAV_HEADER_SIZE = 44;

	/** The first sample of a bank. {@code frames} is the length in sample frames,
	 * and the sample's data is {@code bank[start, end)}. */
	private record Sample(int mode, int rate, int channels, int frames, Integer setupCrc, int start, int end) {}

	private FsbAudio() {}

	/**
	 * Decodes the first sample of an FSB5 bank to a 16-bit little-endian PCM WAV
	 * file's bytes, taking Vorbis setup packets from {@code setups}.
	 */
	static byte[] toWav(byte[] fsb5, VorbisSetups setups) throws FileFormatException {
		Sample sample;
		try {
			sample = readSample(fsb5);
		} catch (BufferUnderflowException | IndexOutOfBoundsException e) {
			throw new FileFormatException("Truncated FSB5 header", e);
		}
		return switch (sample.mode) {
			case MODE_VORBIS -> decodeVorbis(fsb5, sample, setups);
			case MODE_PCM16, MODE_PCM8 -> decodePcm(fsb5, sample);
			default -> throw new FileFormatException("Unsupported FSB5 sound format " + sample.mode);
		};
	}

	private static Sample readSample(byte[] fsb5) throws FileFormatException {
		ByteBuffer buf = ByteBuffer.wrap(fsb5).order(ByteOrder.LITTLE_ENDIAN);
		if (fsb5.length < 60 || buf.getInt(0) != 0x35425346)
			throw new FileFormatException("Not an FSB5 bank");
		int version = buf.getInt(4), numSamples = buf.getInt(8);
		int sampleHeadersSize = buf.getInt(12), nameTableSize = buf.getInt(16), dataSize = buf.getInt(20);
		int mode = buf.getInt(24);
		if (numSamples < 1)
			throw new FileFormatException("FSB5 bank has no samples");
		int headerSize = version == 0 ? 64 : 60;
		int dataStart = headerSize + sampleHeadersSize + nameTableSize;

		buf.position(headerSize);
		long header = buf.getLong();
		boolean moreChunks = (header & 1) != 0;
		int frequency = (int) (header >>> 1 & 0xF);
		int rate = frequency < FREQUENCIES.length ? FREQUENCIES[frequency] : 0;
		int channels = (int) (header >>> 5 & 1) + 1;
		long dataOffset = (header >>> 6 & 0xFFFFFFF) * 16;
		int frames = (int) (header >>> 34 & 0x3FFFFFFF);
		Integer setupCrc = null;
		while (moreChunks) {
			int chunk = buf.getInt();
			moreChunks = (chunk & 1) != 0;
			int size = chunk >>> 1 & 0xFFFFFF, type = chunk >>> 25 & 0x7F, start = buf.position();
			switch (type) {
				case CHUNK_CHANNELS -> channels = buf.get(start) & 0xFF;
				case CHUNK_FREQUENCY -> rate = buf.getInt(start);
				case CHUNK_VORBIS_DATA -> setupCrc = buf.getInt(start);
				default -> {}
			}
			buf.position(start + size);
		}
		long end = dataStart + (long) dataSize;
		if (numSamples > 1)
			end = dataStart + (buf.getLong() >>> 6 & 0xFFFFFFF) * 16;
		long start = dataStart + dataOffset;
		end = Math.min(end, fsb5.length);
		if (rate <= 0 || channels < 1 || start > end)
			throw new FileFormatException("Invalid FSB5 sample header");
		return new Sample(mode, rate, channels, frames, setupCrc, (int) start, (int) end);
	}

	private static byte[] decodePcm(byte[] fsb5, Sample sample) {
		int bytesPerSample = sample.mode == MODE_PCM16 ? 2 : 1;
		int frames = (sample.end - sample.start) / (bytesPerSample * sample.channels);
		if (sample.frames > 0)
			frames = Math.min(frames, sample.frames);
		WavWriter wav = new WavWriter(sample.rate, sample.channels, frames);
		int count = frames * sample.channels;
		if (bytesPerSample == 2) {
			System.arraycopy(fsb5, sample.start, wav.out, WAV_HEADER_SIZE, count * 2);
			wav.length += count * 2;
		} else {
			for (int i = 0; i < count; i++)
				wav.put(((fsb5[sample.start + i] & 0xFF) - 128) << 8);
		}
		return wav.finish();
	}

	private static byte[] decodeVorbis(byte[] fsb5, Sample sample, VorbisSetups setups) throws FileFormatException {
		if (sample.setupCrc == null)
			throw new FileFormatException("FSB5 Vorbis sample has no setup CRC");
		byte[] setup = setups.get(sample.setupCrc);
		if (setup == null)
			throw new FileFormatException(String.format(
					"Vorbis setup %08x isn't in the game's FMOD library", sample.setupCrc));

		ByteBuffer id = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
		id.put((byte) 1).put(VORBIS).putInt(0).put((byte) sample.channels).putInt(sample.rate)
				.putInt(0).putInt(0).putInt(0).put((byte) (SHORT_BLOCK_EXP | LONG_BLOCK_EXP << 4)).put((byte) 1);
		ByteBuffer comment = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
		comment.put((byte) 3).put(VORBIS).putInt(0).putInt(0).put((byte) 1);

		Info info = new Info();
		Comment vorbisComment = new Comment();
		info.init();
		vorbisComment.init();
		long packetNo = 0;
		for (byte[] header : new byte[][] {id.array(), comment.array(), setup}) {
			if (info.synthesis_headerin(vorbisComment, packet(header, 0, header.length, packetNo++)) < 0)
				throw new FileFormatException("Invalid FSB5 Vorbis header " + packetNo);
		}

		DspState dsp = new DspState();
		dsp.synthesis_init(info);
		Block block = new Block(dsp);
		int channels = info.channels;
		float[][][] pcmOut = new float[1][][];
		int[] offsets = new int[channels];
		int limit = sample.frames > 0 ? sample.frames : Integer.MAX_VALUE;
		WavWriter wav = new WavWriter(sample.rate, channels, sample.frames);

		int pos = sample.start, frames = 0;
		int size = packetSize(fsb5, pos, sample.end);
		while (size > 0 && frames < limit) {
			Packet packet = packet(fsb5, pos + 2, size, packetNo++);
			pos += 2 + size;
			size = packetSize(fsb5, pos, sample.end);
			packet.e_o_s = size > 0 ? 0 : 1;
			if (block.synthesis(packet) == 0)
				dsp.synthesis_blockin(block);
			int n;
			while ((n = dsp.synthesis_pcmout(pcmOut, offsets)) > 0) {
				float[][] pcm = pcmOut[0];
				int count = Math.min(n, limit - frames);
				for (int i = 0; i < count; i++) {
					for (int c = 0; c < channels; c++) {
						int v = (int) (pcm[c][offsets[c] + i] * 32768f); // truncating, like FMOD
						wav.put(Math.max(-32768, Math.min(32767, v)));
					}
				}
				frames += count;
				dsp.synthesis_read(n);
			}
		}
		return wav.finish();
	}

	/** Returns the size of the FSB Vorbis packet at {@code pos}, or 0 at the end of the data. */
	private static int packetSize(byte[] data, int pos, int end) {
		if (pos + 2 > end)
			return 0;
		int size = data[pos] & 0xFF | (data[pos + 1] & 0xFF) << 8;
		return size == 0xFFFF || pos + 2 + size > end ? 0 : size;
	}

	private static Packet packet(byte[] data, int offset, int length, long packetNo) {
		Packet packet = new Packet();
		packet.packet_base = data;
		packet.packet = offset;
		packet.bytes = length;
		packet.b_o_s = packetNo == 0 ? 1 : 0;
		packet.granulepos = packetNo < 3 ? 0 : -1;
		packet.packetno = packetNo;
		return packet;
	}

	/** Accumulates 16-bit samples after a WAV header. */
	private static final class WavWriter {
		private final int rate, channels;
		byte[] out;
		int length = WAV_HEADER_SIZE;

		WavWriter(int rate, int channels, int expectedFrames) {
			this.rate = rate;
			this.channels = channels;
			int frames = Math.min(Math.max(expectedFrames, 1024), (1 << 26) / channels);
			out = new byte[WAV_HEADER_SIZE + frames * channels * 2];
		}

		void put(int sample) {
			if (length + 2 > out.length)
				out = Arrays.copyOf(out, out.length * 2);
			out[length++] = (byte) sample;
			out[length++] = (byte) (sample >> 8);
		}

		byte[] finish() {
			byte[] wav = out.length == length ? out : Arrays.copyOf(out, length);
			int dataSize = length - WAV_HEADER_SIZE;
			ByteBuffer header = ByteBuffer.wrap(wav, 0, WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
			header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataSize)
					.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
					.putShort((short) 1).putShort((short) channels).putInt(rate)
					.putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
					.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataSize);
			return wav;
		}
	}
}
