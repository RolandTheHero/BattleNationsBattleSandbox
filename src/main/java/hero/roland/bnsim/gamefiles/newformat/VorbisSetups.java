package hero.roland.bnsim.gamefiles.newformat;

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
 * game's own copy of FMOD (inside {@code UnityPlayer.dll} on Windows,
 * {@code UnityPlayer.dylib} on Mac, {@code UnityPlayer.so} on Linux). A bank
 * names its setup packet by the
 * packet's CRC32, which is how a packet is recognised here: a candidate is only
 * ever returned if its CRC32 matches, so a wrong packet can't come out.
 *
 * <p>Most packets are stored whole, right after a {@code "\x05vorbis"} marker.
 * FMOD stores some as a patch instead: another whole packet with a short run of
 * bytes (the rate-dependent residue settings) replaced. Those are rebuilt from
 * FMOD's table of setups in the library, whose entries are 32 bytes:
 * {@code patchLength, patchPointer(8), packetLength, crc32, basePointer(8),
 * patchOffset} (little-endian). In a Windows PE image the pointers are absolute
 * addresses; in a Mac Mach-O image they are dyld chained-fixup pointers, whose
 * low 36 bits are the target address; in a Linux ELF image they are zero in the
 * file, and the address is the addend of the relative relocation that fills
 * them in at load time. The patch's last byte may be only partly
 * used, so each split of it is tried against the CRC32.
 */
final class VorbisSetups {
	private static final byte[] MARKER = { 5, 'v', 'o', 'r', 'b', 'i', 's' };
	/** Longer than any Vorbis setup packet FMOD uses (about 3-7 KB). */
	private static final int MAX_SETUP_SIZE = 16 * 1024;

	private final File library;
	/** The bytes starting at each marker in the library, up to MAX_SETUP_SIZE. */
	private final List<byte[]> candidates = new ArrayList<>();
	private final Map<Integer, byte[]> found = new HashMap<>();

	/** Scans {@code library} (the game's UnityPlayer.dll, .dylib or .so) for setup packets. */
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
		ImageMap image = ImageMap.read(data);
		if (image == null)
			return null;
		// Look at every place the CRC appears as a table entry's fifth word.
		for (int at = 16; at + 16 <= data.length; at += 4) {
			if (le.getInt(at) != crc)
				continue;
			int entry = at - 16;
			int patchLength = le.getInt(entry), packetLength = le.getInt(entry + 12);
			int patchOffset = le.getInt(entry + 28);
			if (patchLength <= 0 || patchOffset < 0 || packetLength > MAX_SETUP_SIZE
					|| patchOffset + patchLength > packetLength)
				continue;
			int patch = image.fileOffset(le.getLong(entry + 4), entry + 4);
			int base = image.fileOffset(le.getLong(entry + 20), entry + 20);
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

	/** A library's layout in memory, to turn the pointers in FMOD's table into file offsets. */
	private interface ImageMap {
		/**
		 * The file offset {@code pointer} points to, or -1 if it isn't in the file.
		 * {@code at} is the file offset the pointer was read from, which picks the
		 * slice of a universal Mach-O library and the relocation in an ELF one.
		 */
		int fileOffset(long pointer, int at);

		/** The map of a Windows PE, Mac Mach-O or Linux ELF library, or {@code null} if it is none. */
		static ImageMap read(byte[] data) {
			ByteBuffer le = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
			try {
				if (le.getShort(0) == 0x5A4D) // "MZ"
					return PeImage.read(le);
				if (le.getInt(0) == ElfImage.ELF_MAGIC)
					return ElfImage.read(le);
				return MachOImage.read(le);
			} catch (IndexOutOfBoundsException e) {
				return null;
			}
		}
	}

	/** A Windows PE image: its base address and section table. */
	private record PeImage(long imageBase, int[] address, int[] size, int[] fileOffset) implements ImageMap {
		static PeImage read(ByteBuffer le) {
			int pe = le.getInt(0x3C);
			if (le.getInt(pe) != 0x00004550) // "PE\0\0"
				return null;
			int count = le.getShort(pe + 6) & 0xFFFF;
			int optional = pe + 24;
			long imageBase = le.getShort(optional) == 0x20B // PE32+
					? le.getLong(optional + 24) : le.getInt(optional + 28) & 0xFFFFFFFFL;
			int table = optional + (le.getShort(pe + 20) & 0xFFFF);
			int[] address = new int[count], size = new int[count], fileOffset = new int[count];
			for (int i = 0; i < count; i++) {
				int s = table + 40 * i;
				size[i] = Math.max(le.getInt(s + 8), le.getInt(s + 16)); // virtual, raw size
				address[i] = le.getInt(s + 12);
				fileOffset[i] = le.getInt(s + 20);
			}
			return new PeImage(imageBase, address, size, fileOffset);
		}

		@Override
		public int fileOffset(long pointer, int at) {
			long rva = pointer - imageBase;
			for (int i = 0; i < address.length; i++)
				if (rva >= address[i] && rva - address[i] < size[i])
					return (int) (fileOffset[i] + rva - address[i]);
			return -1;
		}
	}

	/** A Mac Mach-O image, possibly universal: each slice's segments. */
	private record MachOImage(List<Slice> slices) implements ImageMap {
		private static final int FAT_MAGIC = 0xCAFEBABE, MH_MAGIC_64 = 0xFEEDFACF, LC_SEGMENT_64 = 0x19;
		/** Chained-fixup pointers keep the target address in their low 36 bits. */
		private static final long TARGET_MASK = (1L << 36) - 1;

		/** One architecture's image, at {@code offset} in the file. */
		private record Slice(int offset, int size, List<long[]> segments) {}

		static MachOImage read(ByteBuffer le) {
			ByteBuffer be = le.duplicate().order(ByteOrder.BIG_ENDIAN); // the universal header
			List<Slice> slices = new ArrayList<>();
			if (be.getInt(0) == FAT_MAGIC) {
				int count = be.getInt(4);
				for (int i = 0; i < count; i++)
					slices.add(readSlice(le, be.getInt(8 + 20 * i + 8), be.getInt(8 + 20 * i + 12)));
			} else {
				slices.add(readSlice(le, 0, le.capacity()));
			}
			slices.removeIf(slice -> slice.segments == null);
			return slices.isEmpty() ? null : new MachOImage(slices);
		}

		/** A 64-bit slice's segments as {vmaddr, vmsize, fileoff, filesize}, or null segments if it isn't one. */
		private static Slice readSlice(ByteBuffer le, int offset, int size) {
			if (le.getInt(offset) != MH_MAGIC_64)
				return new Slice(offset, size, null);
			List<long[]> segments = new ArrayList<>();
			int commands = le.getInt(offset + 16);
			for (int i = 0, at = offset + 32; i < commands; i++, at += le.getInt(at + 4))
				if (le.getInt(at) == LC_SEGMENT_64)
					segments.add(new long[] { le.getLong(at + 24), le.getLong(at + 32), le.getLong(at + 40),
							le.getLong(at + 48) });
			return new Slice(offset, size, segments);
		}

		@Override
		public int fileOffset(long pointer, int at) {
			long address = pointer & TARGET_MASK;
			for (Slice slice : slices) {
				if (at < slice.offset || at - slice.offset >= slice.size)
					continue;
				for (long[] segment : slice.segments)
					if (address >= segment[0] && address - segment[0] < Math.min(segment[1], segment[3]))
						return (int) (slice.offset + segment[2] + address - segment[0]);
			}
			return -1;
		}
	}

	/**
	 * A Linux ELF64 image: its loaded segments as {vaddr, filesize, fileoff}, and
	 * the addend of each relative relocation by the address it fills in. LLD
	 * leaves those addresses zero in the file, so the addend is the pointer.
	 */
	private record ElfImage(List<long[]> segments, Map<Long, Long> relocations) implements ImageMap {
		/** {@code "\x7fELF"}, read little-endian. */
		static final int ELF_MAGIC = 0x464C457F;
		private static final int ELFCLASS64 = 2, EM_AARCH64 = 183;
		private static final int PT_LOAD = 1, PT_DYNAMIC = 2;
		private static final long DT_NULL = 0, DT_RELA = 7, DT_RELASZ = 8, DT_RELAENT = 9;
		private static final long R_X86_64_RELATIVE = 8, R_AARCH64_RELATIVE = 1027;

		static ElfImage read(ByteBuffer le) {
			if (le.get(4) != ELFCLASS64)
				return null;
			long relative = (le.getShort(0x12) & 0xFFFF) == EM_AARCH64 ? R_AARCH64_RELATIVE : R_X86_64_RELATIVE;
			int phoff = (int) le.getLong(0x20);
			int phentsize = le.getShort(0x36) & 0xFFFF, phnum = le.getShort(0x38) & 0xFFFF;
			List<long[]> segments = new ArrayList<>();
			int dynamic = -1;
			for (int i = 0; i < phnum; i++) {
				int p = phoff + phentsize * i;
				int type = le.getInt(p);
				if (type == PT_LOAD)
					segments.add(new long[] { le.getLong(p + 16), le.getLong(p + 32), le.getLong(p + 8) });
				else if (type == PT_DYNAMIC)
					dynamic = (int) le.getLong(p + 8);
			}
			ElfImage image = new ElfImage(segments, new HashMap<>());
			if (dynamic < 0)
				return image;
			long rela = -1, relaSize = 0, relaEntry = 24;
			for (int at = dynamic; le.getLong(at) != DT_NULL; at += 16) {
				long tag = le.getLong(at), value = le.getLong(at + 8);
				if (tag == DT_RELA)
					rela = value;
				else if (tag == DT_RELASZ)
					relaSize = value;
				else if (tag == DT_RELAENT)
					relaEntry = value;
			}
			int table = rela < 0 ? -1 : image.toFileOffset(rela);
			if (table < 0 || relaEntry < 24)
				return image;
			for (long r = 0; r + relaEntry <= relaSize; r += relaEntry) {
				int at = (int) (table + r);
				if ((le.getLong(at + 8) & 0xFFFFFFFFL) == relative)
					image.relocations.put(le.getLong(at), le.getLong(at + 16));
			}
			return image;
		}

		@Override
		public int fileOffset(long pointer, int at) {
			Long address = null;
			for (long[] segment : segments)
				if (at >= segment[2] && at - segment[2] < segment[1])
					address = relocations.get(segment[0] + at - segment[2]);
			return toFileOffset(address != null ? address : pointer);
		}

		/** The file offset of the loaded address {@code address}, or -1 if it isn't in the file. */
		private int toFileOffset(long address) {
			for (long[] segment : segments)
				if (address >= segment[0] && address - segment[0] < segment[1])
					return (int) (segment[2] + address - segment[0]);
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
