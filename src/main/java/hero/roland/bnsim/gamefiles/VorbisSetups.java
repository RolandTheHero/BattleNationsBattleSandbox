package hero.roland.bnsim.gamefiles;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * The Vorbis setup header packets that FSB5 sound banks leave out, found in the
 * game's own copy of FMOD (inside {@code UnityPlayer.dll}). A bank names its
 * setup packet by the packet's CRC32, which is how a packet is recognised here:
 * a candidate is only ever returned if its CRC32 matches, so a wrong packet
 * can't come out.
 *
 * <p>Most packets are stored whole, right after a {@code "\x05vorbis"} marker.
 * FMOD stores some as a patch instead: another whole packet with a short run of
 * bytes (the rate-dependent residue settings) replaced. Those are rebuilt from
 * FMOD's table of setups in the library, whose entries are 32 bytes:
 * {@code patchLength, patchAddress, 1, packetLength, crc32, baseAddress, 1,
 * patchOffset} (little-endian; addresses are image-relative with the top bit
 * set). The patch's last byte may be only partly used, so each split of it is
 * tried against the CRC32.
 */
final class VorbisSetups {
	private static final byte[] MARKER = { 5, 'v', 'o', 'r', 'b', 'i', 's' };
	/** Longer than any Vorbis setup packet FMOD uses (about 3-7 KB). */
	private static final int MAX_SETUP_SIZE = 16 * 1024;

	private final File library;
	/** The bytes starting at each marker in the library, up to MAX_SETUP_SIZE. */
	private final List<byte[]> candidates = new ArrayList<>();
	private final Map<Integer, byte[]> found = new HashMap<>();

	/** Scans {@code library} (the game's UnityPlayer.dll) for setup packets. */
	VorbisSetups(File library) throws IOException {
		this.library = library;
		byte[] data = Files.readAllBytes(library.toPath());
		for (int i = indexOf(data, 0); i >= 0; i = indexOf(data, i + 1))
			candidates.add(Arrays.copyOfRange(data, i, Math.min(data.length, i + MAX_SETUP_SIZE)));
	}

	/** The setup packet whose CRC32 is {@code crc}, or {@code null} if the library doesn't hold it. */
	synchronized byte[] get(int crc) {
		if (found.containsKey(crc))
			return found.get(crc);
		byte[] setup = findWhole(crc);
		if (setup == null) {
			try {
				// Rare (music only), so the library is read again rather than kept in memory.
				setup = rebuildPatched(crc, Files.readAllBytes(library.toPath()));
			} catch (IOException e) {
				setup = null;
			}
		}
		found.put(crc, setup);
		return setup;
	}

	/** A packet stored whole: the bytes after a marker whose CRC32 matches. */
	private byte[] findWhole(int crc) {
		CRC32 checksum = new CRC32();
		for (byte[] candidate : candidates) {
			checksum.reset();
			for (int length = 1; length <= candidate.length; length++) {
				checksum.update(candidate[length - 1]);
				if ((int) checksum.getValue() == crc)
					return Arrays.copyOf(candidate, length);
			}
		}
		return null;
	}

	/** A packet stored as a patch to another, rebuilt from its entry in FMOD's table. */
	private static byte[] rebuildPatched(int crc, byte[] data) {
		ByteBuffer le = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		PeSections sections = PeSections.read(le);
		if (sections == null)
			return null;
		// Look at every place the CRC appears as a table entry's fifth word.
		for (int at = 16; at + 16 <= data.length; at += 4) {
			if (le.getInt(at) != crc)
				continue;
			int entry = at - 16;
			int patchLength = le.getInt(entry), patchAddress = le.getInt(entry + 4);
			int packetLength = le.getInt(entry + 12), baseAddress = le.getInt(entry + 20);
			int patchOffset = le.getInt(entry + 28);
			if (le.getInt(entry + 8) != 1 || le.getInt(entry + 24) != 1 || patchAddress >= 0 || baseAddress >= 0
					|| patchLength <= 0 || patchOffset < 0 || packetLength > MAX_SETUP_SIZE
					|| patchOffset + patchLength > packetLength)
				continue;
			int patch = sections.fileOffset(patchAddress & 0x7FFFFFFF);
			int base = sections.fileOffset(baseAddress & 0x7FFFFFFF);
			if (patch < 0 || base < 0 || patch + patchLength > data.length || base + packetLength > data.length)
				continue;
			byte[] setup = Arrays.copyOfRange(data, base, base + packetLength);
			System.arraycopy(data, patch, setup, patchOffset, patchLength - 1);
			// The patch's last byte may cover only its low bits; keep the base's
			// high bits and take whichever split gives the named packet.
			int last = patchOffset + patchLength - 1;
			int patchByte = data[patch + patchLength - 1] & 0xFF, baseByte = setup[last] & 0xFF;
			for (int bits = 8; bits >= 0; bits--) {
				int mask = (1 << bits) - 1;
				setup[last] = (byte) ((patchByte & mask) | (baseByte & ~mask));
				if (crc32(setup) == crc)
					return setup;
			}
		}
		return null;
	}

	private static int crc32(byte[] data) {
		CRC32 checksum = new CRC32();
		checksum.update(data);
		return (int) checksum.getValue();
	}

	/** A Windows PE image's section table, to turn image addresses into file offsets. */
	private record PeSections(int[] address, int[] size, int[] fileOffset) {
		static PeSections read(ByteBuffer le) {
			try {
				int pe = le.getInt(0x3C);
				if (le.getInt(pe) != 0x00004550) // "PE\0\0"
					return null;
				int count = le.getShort(pe + 6) & 0xFFFF;
				int table = pe + 24 + (le.getShort(pe + 20) & 0xFFFF);
				int[] address = new int[count], size = new int[count], fileOffset = new int[count];
				for (int i = 0; i < count; i++) {
					int s = table + 40 * i;
					size[i] = Math.max(le.getInt(s + 8), le.getInt(s + 16)); // virtual, raw size
					address[i] = le.getInt(s + 12);
					fileOffset[i] = le.getInt(s + 20);
				}
				return new PeSections(address, size, fileOffset);
			} catch (IndexOutOfBoundsException e) {
				return null;
			}
		}

		/** The file offset of an image-relative address, or -1 if no section holds it. */
		int fileOffset(int rva) {
			for (int i = 0; i < address.length; i++)
				if (rva >= address[i] && rva - address[i] < size[i])
					return fileOffset[i] + rva - address[i];
			return -1;
		}
	}

	private static int indexOf(byte[] data, int from) {
		outer:
		for (int i = from; i <= data.length - MARKER.length; i++) {
			for (int j = 0; j < MARKER.length; j++)
				if (data[i + j] != MARKER[j])
					continue outer;
			return i;
		}
		return -1;
	}
}
