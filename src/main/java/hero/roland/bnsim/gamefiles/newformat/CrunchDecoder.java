/*
 * This file is a Java port of crn_decomp.h, crn_defs.h and crnlib.h from Unity's
 * fork of crunch (https://github.com/Unity-Technologies/crunch, branch "unity").
 * ALTERED SOURCE VERSION: translated from C++ to Java and cut down to decoding
 * the first mip level of DXT1/DXT5 textures (by RolandTheHero, 2026). It is not
 * the original software. The original notice follows, unaltered.
 *
 * crn_decomp.h uses the ZLIB license:
 * http://opensource.org/licenses/Zlib
 *
 * Copyright (c) 2010-2016 Richard Geldreich, Jr. and Binomial LLC
 *
 * This software is provided 'as-is', without any express or implied
 * warranty.  In no event will the authors be held liable for any damages
 * arising from the use of this software.
 *
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 *
 * 1. The origin of this software must not be misrepresented; you must not
 * claim that you wrote the original software. If you use this software
 * in a product, an acknowledgment in the product documentation would be
 * appreciated but is not required.
 *
 * 2. Altered source versions must be plainly marked as such, and must not be
 * misrepresented as being the original software.
 *
 * 3. This notice may not be removed or altered from any source distribution.
 */

package hero.roland.bnsim.gamefiles.newformat;

import java.util.Arrays;

import hero.roland.bnsim.util.FileFormatException;

/**
 * Decoder for crunch ({@code .crn}) textures, as found in Unity's DXT1Crunched
 * and DXT5Crunched Texture2D formats. It turns the first mip level back into
 * plain DXT blocks. This is a port of {@code crn_decomp.h} from Unity's fork of
 * crunch (which differs from upstream crunch in how selectors and block
 * references are encoded), restricted to the DXT1 and DXT5 formats.
 */
final class CrunchDecoder {
	/** Decoded mip level 0 of a crunched texture. {@code dxt5} is false for DXT1 (8 bytes/block), true for DXT5 (16 bytes/block). Blocks are row-major, ceil(w/4) x ceil(h/4), in the same byte layout as a standard .dds DXT1/DXT5 surface. */
	record Result(int width, int height, boolean dxt5, byte[] blocks) {}

	private static final int SIGNATURE = 'H' << 8 | 'x', HEADER_SIZE = 74;
	private static final int FORMAT_DXT1 = 0, FORMAT_DXT5 = 2;
	private static final int MAX_CODE_SIZE = 16;
	/** The order in which code length code sizes are sent. */
	private static final int[] CODE_LENGTH_ORDER = {17, 18, 19, 20, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15, 16};
	private static final int[] DXT5_FROM_LINEAR = {0, 2, 3, 4, 5, 6, 7, 1};

	private final byte[] data;
	private int pos, end, bitBuf, bitCount;

	private CrunchDecoder(byte[] data) {
		this.data = data;
	}

	static Result unpackLevel0(byte[] crn) throws FileFormatException {
		try {
			return new CrunchDecoder(crn).unpack();
		} catch (ArrayIndexOutOfBoundsException e) {
			throw new FileFormatException("Corrupt crunch data", e);
		}
	}

	private Result unpack() throws FileFormatException {
		if (data.length < HEADER_SIZE || be(0, 2) != SIGNATURE)
			throw new FileFormatException("Not a crunch file");
		if (be(2, 2) < HEADER_SIZE || Integer.compareUnsigned(data.length, be(6, 4)) < 0)
			throw new FileFormatException("Truncated crunch file");
		int width = be(12, 2), height = be(14, 2), levels = be(16, 1), format = be(18, 1);
		if (format != FORMAT_DXT1 && format != FORMAT_DXT5)
			throw new FileFormatException("Unsupported crunch format " + format);
		boolean dxt5 = format == FORMAT_DXT5;
		int colorEndpointsNum = be(39, 2), alphaEndpointsNum = be(55, 2);
		if (colorEndpointsNum == 0 || dxt5 && alphaEndpointsNum == 0)
			throw new FileFormatException("Crunch file has no endpoints");

		start(be(67, 3), be(65, 2));
		Model referenceModel = receiveModel();
		Model colorEndpointDelta = receiveModel(), colorSelectorDelta = receiveModel();
		Model alphaEndpointDelta = null, alphaSelectorDelta = null;
		if (alphaEndpointsNum != 0) {
			alphaEndpointDelta = receiveModel();
			alphaSelectorDelta = receiveModel();
		}

		int[] colorEndpoints = decodeColorEndpoints(be(33, 3), be(36, 3), colorEndpointsNum);
		int[] colorSelectors = decodeColorSelectors(be(41, 3), be(44, 3), be(47, 2));
		int[] alphaEndpoints = null;
		byte[] alphaSelectors = null;
		if (alphaEndpointsNum != 0) {
			alphaEndpoints = decodeAlphaEndpoints(be(49, 3), be(52, 3), alphaEndpointsNum);
			alphaSelectors = decodeAlphaSelectors(be(57, 3), be(60, 3), be(63, 2));
		}

		int levelStart = be(70, 4), levelEnd = levels > 1 ? be(74, 4) : data.length;
		start(levelStart, levelEnd - levelStart);
		int blocksX = (width + 3) >> 2, blocksY = (height + 3) >> 2, blockSize = dxt5 ? 16 : 8;
		byte[] out = new byte[blocksX * blocksY * blockSize];
		// Blocks are coded in 2x2 groups; each group sends one symbol holding the
		// endpoint references of its blocks, the lower row's kept in the buffer.
		int paddedX = blocksX + 1 & ~1, paddedY = blocksY + 1 & ~1;
		int[] bufferReference = new int[paddedX], bufferColor = new int[paddedX], bufferAlpha = new int[paddedX];
		int colorIndex = 0, alphaIndex = 0, referenceGroup = 0;
		for (int y = 0; y < paddedY; y++) {
			for (int x = 0; x < paddedX; x++) {
				if (((x | y) & 1) == 0)
					referenceGroup = decode(referenceModel) & 0xFF;
				int reference;
				if ((y & 1) != 0) {
					reference = bufferReference[x];
				} else {
					reference = referenceGroup & 3;
					bufferReference[x] = referenceGroup >> 2 & 3;
					referenceGroup >>= 4;
				}
				if (reference == 0) {
					colorIndex += decode(colorEndpointDelta);
					if (colorIndex >= colorEndpoints.length)
						colorIndex -= colorEndpoints.length;
					bufferColor[x] = colorIndex;
					if (dxt5) {
						alphaIndex += decode(alphaEndpointDelta);
						if (alphaIndex >= alphaEndpoints.length)
							alphaIndex -= alphaEndpoints.length;
						bufferAlpha[x] = alphaIndex;
					}
				} else if (reference == 1) {
					bufferColor[x] = colorIndex;
					bufferAlpha[x] = alphaIndex;
				} else {
					colorIndex = bufferColor[x];
					alphaIndex = bufferAlpha[x];
				}
				int colorSelector = decode(colorSelectorDelta);
				int alphaSelector = dxt5 ? decode(alphaSelectorDelta) : 0;
				if (x < blocksX && y < blocksY) {
					int o = (y * blocksX + x) * blockSize;
					if (dxt5) {
						putShort(out, o, alphaEndpoints[alphaIndex]);
						System.arraycopy(alphaSelectors, alphaSelector * 6, out, o + 2, 6);
						o += 8;
					}
					putInt(out, o, colorEndpoints[colorIndex]);
					putInt(out, o + 4, colorSelectors[colorSelector]);
				}
			}
		}
		return new Result(width, height, dxt5, out);
	}

	/** Decodes the color endpoint palette: pairs of RGB565 colors, delta coded per channel. */
	private int[] decodeColorEndpoints(int offset, int size, int num) throws FileFormatException {
		start(offset, size);
		Model m0 = receiveModel(), m1 = receiveModel();
		int[] endpoints = new int[num];
		int a = 0, b = 0, c = 0, d = 0, e = 0, f = 0;
		for (int i = 0; i < num; i++) {
			a = a + decode(m0) & 31;
			b = b + decode(m1) & 63;
			c = c + decode(m0) & 31;
			d = d + decode(m0) & 31;
			e = e + decode(m1) & 63;
			f = f + decode(m0) & 31;
			endpoints[i] = c | b << 5 | a << 11 | f << 16 | e << 21 | d << 27;
		}
		return endpoints;
	}

	/** Decodes the color selector palette: 2-bit selectors, XOR-delta coded in linear order. */
	private int[] decodeColorSelectors(int offset, int size, int num) throws FileFormatException {
		start(offset, size);
		Model m = receiveModel();
		int[] selectors = new int[num];
		int s = 0;
		for (int i = 0; i < num; i++) {
			for (int j = 0; j < 32; j += 4)
				s ^= decode(m) << j;
			selectors[i] = (s ^ s << 1) & 0xAAAAAAAA | s >>> 1 & 0x55555555;
		}
		return selectors;
	}

	private int[] decodeAlphaEndpoints(int offset, int size, int num) throws FileFormatException {
		start(offset, size);
		Model m = receiveModel();
		int[] endpoints = new int[num];
		int a = 0, b = 0;
		for (int i = 0; i < num; i++) {
			a = a + decode(m) & 255;
			b = b + decode(m) & 255;
			endpoints[i] = a | b << 8;
		}
		return endpoints;
	}

	/** Decodes the alpha selector palette into the 6 selector bytes of a DXT5 alpha block per entry. */
	private byte[] decodeAlphaSelectors(int offset, int size, int num) throws FileFormatException {
		start(offset, size);
		Model m = receiveModel();
		int[] fromLinear = new int[64];
		for (int i = 0; i < 64; i++)
			fromLinear[i] = DXT5_FROM_LINEAR[i & 7] | DXT5_FROM_LINEAR[i >> 3] << 3;
		byte[] selectors = new byte[num * 6];
		int[] linear = new int[2];
		for (int i = 0; i < num; i++) {
			for (int half = 0; half < 2; half++) {
				int s = 0;
				for (int j = 0; j < 24; j += 6) {
					linear[half] ^= decode(m) << j;
					s |= fromLinear[linear[half] >>> j & 0x3F] << j;
				}
				int o = i * 6 + half * 3;
				selectors[o] = (byte) s;
				selectors[o + 1] = (byte) (s >> 8);
				selectors[o + 2] = (byte) (s >> 16);
			}
		}
		return selectors;
	}

	/** A canonical Huffman code, as sent by {@link #receiveModel()}. */
	private static final class Model {
		/** Per code length: the largest code of that length (or -1 if none), and the index of its code 0 in {@link #symbols}. */
		final int[] maxCode = new int[MAX_CODE_SIZE + 1], symbolBase = new int[MAX_CODE_SIZE + 1];
		/** Symbols sorted by code. */
		final int[] symbols;

		Model(int[] codeSizes) {
			int[] count = new int[MAX_CODE_SIZE + 1];
			for (int size : codeSizes)
				count[size]++;
			int[] next = new int[MAX_CODE_SIZE + 1];
			int code = 0, used = 0;
			for (int len = 1; len <= MAX_CODE_SIZE; len++) {
				next[len] = used;
				maxCode[len] = count[len] == 0 ? -1 : code + count[len] - 1;
				symbolBase[len] = used - code;
				code = code + count[len] << 1;
				used += count[len];
			}
			symbols = new int[used];
			for (int sym = 0; sym < codeSizes.length; sym++)
				if (codeSizes[sym] != 0)
					symbols[next[codeSizes[sym]]++] = sym;
		}
	}

	/** Reads a Huffman code, sent as code sizes that are themselves Huffman and run-length coded. */
	private Model receiveModel() throws FileFormatException {
		int numSyms = decodeBits(14);
		if (numSyms == 0)
			return null;
		int numCodeLengthCodes = decodeBits(5);
		if (numCodeLengthCodes < 1 || numCodeLengthCodes > CODE_LENGTH_ORDER.length)
			throw new FileFormatException("Corrupt crunch Huffman table");
		int[] codeLengthSizes = new int[CODE_LENGTH_ORDER.length];
		for (int i = 0; i < numCodeLengthCodes; i++)
			codeLengthSizes[CODE_LENGTH_ORDER[i]] = decodeBits(3);
		Model codeLengthModel = new Model(codeLengthSizes);

		int[] sizes = new int[numSyms];
		for (int ofs = 0; ofs < numSyms;) {
			int code = decode(codeLengthModel), len;
			if (code <= 16) {
				sizes[ofs++] = code;
				continue;
			} else if (code == 17) {
				len = decodeBits(3) + 3;
			} else if (code == 18) {
				len = decodeBits(7) + 11;
			} else {
				len = code == 19 ? decodeBits(2) + 3 : decodeBits(6) + 7;
				if (ofs == 0 || sizes[ofs - 1] == 0 || len > numSyms - ofs)
					throw new FileFormatException("Corrupt crunch Huffman table");
				Arrays.fill(sizes, ofs, ofs + len, sizes[ofs - 1]);
			}
			if (len > numSyms - ofs)
				throw new FileFormatException("Corrupt crunch Huffman table");
			ofs += len;
		}
		return new Model(sizes);
	}

	private void start(int offset, int size) throws FileFormatException {
		if (size <= 0 || offset < 0 || offset > data.length - size)
			throw new FileFormatException("Crunch data out of bounds");
		pos = offset;
		end = offset + size;
		bitBuf = 0;
		bitCount = 0;
	}

	private int nextByte() {
		return pos < end ? data[pos++] & 0xFF : 0;
	}

	private int decodeBits(int n) {
		if (n > 16)
			return getBits(n - 16) << 16 | getBits(16);
		return n == 0 ? 0 : getBits(n);
	}

	/** Reads 1 to 16 bits, MSB first; reads past the end of the stream yield zeros. */
	private int getBits(int n) {
		while (bitCount < n) {
			bitCount += 8;
			bitBuf |= nextByte() << (32 - bitCount);
		}
		int result = bitBuf >>> (32 - n);
		bitBuf <<= n;
		bitCount -= n;
		return result;
	}

	private int decode(Model m) throws FileFormatException {
		if (m == null)
			throw new FileFormatException("Missing crunch Huffman table");
		if (bitCount < 16) {
			int c = nextByte() << 8;
			c |= nextByte();
			bitCount += 16;
			bitBuf |= c << (32 - bitCount);
		} else if (bitCount < 24) {
			bitCount += 8;
			bitBuf |= nextByte() << (32 - bitCount);
		}
		for (int len = 1; len <= MAX_CODE_SIZE; len++) {
			int code = bitBuf >>> (32 - len);
			if (code <= m.maxCode[len]) {
				bitBuf <<= len;
				bitCount -= len;
				return m.symbols[m.symbolBase[len] + code];
			}
		}
		throw new FileFormatException("Corrupt crunch Huffman code");
	}

	/** Reads an unsigned big-endian header field. */
	private int be(int offset, int n) {
		int v = 0;
		for (int i = 0; i < n; i++)
			v = v << 8 | data[offset + i] & 0xFF;
		return v;
	}

	private static void putShort(byte[] b, int o, int v) {
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
	}

	private static void putInt(byte[] b, int o, int v) {
		putShort(b, o, v);
		putShort(b, o + 2, v >> 16);
	}
}
