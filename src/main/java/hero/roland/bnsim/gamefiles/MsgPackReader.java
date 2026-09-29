package hero.roland.bnsim.gamefiles;

import java.nio.charset.StandardCharsets;

import hero.roland.bnsim.util.FileFormatException;

/**
 * A minimal streaming reader for MessagePack data (the format of the Unity
 * remaster's animation timelines). Values are read in order with the typed
 * methods, so large arrays of numbers are never boxed.
 */
final class MsgPackReader {
	private final byte[] data;
	private int pos;

	MsgPackReader(byte[] data) {
		this.data = data;
	}

	int position() {
		return pos;
	}

	/** The number of entries in the map that starts here. */
	int readMapHeader() throws FileFormatException {
		int b = next();
		if ((b & 0xF0) == 0x80) return b & 0x0F;
		if (b == 0xDE) return readUInt(2);
		if (b == 0xDF) return readUInt(4);
		throw unexpected(b, "map");
	}

	/** The number of elements in the array that starts here. */
	int readArrayHeader() throws FileFormatException {
		int b = next();
		if ((b & 0xF0) == 0x90) return b & 0x0F;
		if (b == 0xDC) return readUInt(2);
		if (b == 0xDD) return readUInt(4);
		throw unexpected(b, "array");
	}

	String readString() throws FileFormatException {
		int b = next();
		int length;
		if ((b & 0xE0) == 0xA0) length = b & 0x1F;
		else if (b == 0xD9) length = readUInt(1);
		else if (b == 0xDA) length = readUInt(2);
		else if (b == 0xDB) length = readUInt(4);
		else throw unexpected(b, "string");
		need(length);
		String s = new String(data, pos, length, StandardCharsets.UTF_8);
		pos += length;
		return s;
	}

	/** Any number (integer or float) as a double. */
	double readDouble() throws FileFormatException {
		int b = next();
		if (b <= 0x7F) return b;
		if (b >= 0xE0) return (byte) b;
		switch (b) {
		case 0xCA: return Float.intBitsToFloat((int) readLong(4));
		case 0xCB: return Double.longBitsToDouble(readLong(8));
		case 0xCC: return readLong(1);
		case 0xCD: return readLong(2);
		case 0xCE: return readLong(4);
		case 0xCF: return readLong(8);
		case 0xD0: return (byte) readLong(1);
		case 0xD1: return (short) readLong(2);
		case 0xD2: return (int) readLong(4);
		case 0xD3: return readLong(8);
		default: throw unexpected(b, "number");
		}
	}

	int readInt() throws FileFormatException {
		return (int) readDouble();
	}

	/** Skips one value of any type, including everything nested in it. */
	void skip() throws FileFormatException {
		int b = data[pos] & 0xFF;
		if ((b & 0xF0) == 0x80 || b == 0xDE || b == 0xDF) {
			for (int n = readMapHeader() * 2; n > 0; n--) skip();
		} else if ((b & 0xF0) == 0x90 || b == 0xDC || b == 0xDD) {
			for (int n = readArrayHeader(); n > 0; n--) skip();
		} else if ((b & 0xE0) == 0xA0 || (b >= 0xD9 && b <= 0xDB)) {
			readString();
		} else if (b == 0xC0 || b == 0xC2 || b == 0xC3) {
			pos++; // nil, false, true
		} else {
			readDouble();
		}
	}

	private int next() throws FileFormatException {
		need(1);
		return data[pos++] & 0xFF;
	}

	private int readUInt(int bytes) throws FileFormatException {
		long v = readLong(bytes);
		if (v > Integer.MAX_VALUE)
			throw new FileFormatException("MessagePack length too large");
		return (int) v;
	}

	/** A big-endian unsigned value of 1-8 bytes. */
	private long readLong(int bytes) throws FileFormatException {
		need(bytes);
		long v = 0;
		for (int i = 0; i < bytes; i++)
			v = (v << 8) | (data[pos++] & 0xFF);
		return v;
	}

	private void need(int bytes) throws FileFormatException {
		if (pos + bytes > data.length)
			throw new FileFormatException("Truncated MessagePack data");
	}

	private FileFormatException unexpected(int b, String expected) {
		return new FileFormatException(String.format(
				"MessagePack: expected %s at %d, found type byte 0x%02X", expected, pos - 1, b));
	}
}
