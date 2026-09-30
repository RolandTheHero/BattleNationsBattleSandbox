/*
 * The BC7, DXT1 and DXT5 decoding is written from the format specifications.
 * The packed BC7 three-subset partition table follows the layout used by detex
 * (https://github.com/hglm/detex), Copyright (c) 2015 Harm Hanemaaijer, ISC
 * License, and the DXT rounding matches AssetStudio
 * (https://github.com/Perfare/AssetStudio), Copyright (c) 2016 Radu and
 * (c) 2016-2020 Perfare, MIT License. The full notices are in
 * THIRD_PARTY_NOTICES.md.
 */

package hero.roland.bnsim.gamefiles.newformat;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.stream.IntStream;

import hero.roland.bnsim.util.FileFormatException;

/**
 * Decodes the pixel data of Unity {@code Texture2D} assets into images. Supports
 * the uncompressed formats Alpha8, RGB24, RGBA32, ARGB32, RGB565, RGBA4444 and
 * BGRA32, and the block-compressed formats DXT1 (BC1), DXT5 (BC3) and BC7.
 */
final class TextureDecoder {
	/** Unity {@code TextureFormat} values. */
	static final int ALPHA8 = 1, RGB24 = 3, RGBA32 = 4, ARGB32 = 5, RGB565 = 7, DXT1 = 10, DXT5 = 12,
			RGBA4444 = 13, BGRA32 = 14, BC7 = 25;

	private TextureDecoder() {
	}

	/** Decodes mip level 0 of a Unity texture into a TYPE_INT_ARGB image, top row first (Unity stores rows bottom-up, so flip). */
	static BufferedImage decode(int unityFormat, int width, int height, byte[] data) throws FileFormatException {
		if (width <= 0 || height <= 0 || (long) width * height > Integer.MAX_VALUE)
			throw new FileFormatException("Invalid texture size " + width + "x" + height);
		long needed;
		int bw = (width + 3) / 4, bh = (height + 3) / 4;
		switch (unityFormat) {
		case ALPHA8: needed = (long) width * height; break;
		case RGB565: case RGBA4444: needed = 2L * width * height; break;
		case RGB24: needed = 3L * width * height; break;
		case RGBA32: case ARGB32: case BGRA32: needed = 4L * width * height; break;
		case DXT1: needed = 8L * bw * bh; break;
		case DXT5: case BC7: needed = 16L * bw * bh; break;
		default: throw new FileFormatException("Unsupported texture format " + unityFormat);
		}
		if (data.length < needed)
			throw new FileFormatException("Texture data too short: " + data.length + " bytes, need " + needed);
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		int[] out = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
		switch (unityFormat) {
		case DXT1: case DXT5: case BC7:
			IntStream rows = IntStream.range(0, bh);
			if ((long) bw * bh >= 4096)
				rows = rows.parallel();
			rows.forEach(by -> decodeBlockRow(unityFormat, width, height, data, out, by));
			break;
		default:
			decodeUncompressed(unityFormat, width, height, data, out);
		}
		return img;
	}

	private static void decodeUncompressed(int format, int width, int height, byte[] d, int[] out) {
		int i = 0;
		for (int y = height - 1; y >= 0; y--) {
			int o = y * width, end = o + width;
			switch (format) {
			case ALPHA8:
				for (; o < end; o++)
					out[o] = (d[i++] & 0xff) << 24;
				break;
			case RGB24:
				for (; o < end; o++, i += 3)
					out[o] = 0xff000000 | (d[i] & 0xff) << 16 | (d[i + 1] & 0xff) << 8 | d[i + 2] & 0xff;
				break;
			case RGBA32:
				for (; o < end; o++, i += 4)
					out[o] = (d[i + 3] & 0xff) << 24 | (d[i] & 0xff) << 16 | (d[i + 1] & 0xff) << 8 | d[i + 2] & 0xff;
				break;
			case ARGB32:
				for (; o < end; o++, i += 4)
					out[o] = (d[i] & 0xff) << 24 | (d[i + 1] & 0xff) << 16 | (d[i + 2] & 0xff) << 8 | d[i + 3] & 0xff;
				break;
			case BGRA32:
				for (; o < end; o++, i += 4)
					out[o] = (d[i + 3] & 0xff) << 24 | (d[i + 2] & 0xff) << 16 | (d[i + 1] & 0xff) << 8 | d[i] & 0xff;
				break;
			case RGB565:
				for (; o < end; o++, i += 2)
					out[o] = rgb565(d[i] & 0xff | (d[i + 1] & 0xff) << 8);
				break;
			case RGBA4444:
				for (; o < end; o++, i += 2) {
					int v = d[i] & 0xff | (d[i + 1] & 0xff) << 8;
					out[o] = (v & 0xf) * 0x11000000 | (v >> 12) * 0x110000 | (v >> 8 & 0xf) * 0x1100 | (v >> 4 & 0xf) * 0x11;
				}
				break;
			}
		}
	}

	/** Converts an RGB565 value to opaque ARGB, scaling like Pillow (as used by UnityPy) does. */
	private static int rgb565(int v) {
		int r = v >> 11, g = v >> 5 & 0x3f, b = v & 0x1f;
		return 0xff000000 | r * 255 / 31 << 16 | g * 255 / 63 << 8 | b * 255 / 31;
	}

	/** Decodes one row of 4x4 blocks (row {@code by} in Unity's bottom-up order). */
	private static void decodeBlockRow(int format, int width, int height, byte[] d, int[] out, int by) {
		int bw = (width + 3) / 4;
		int blockSize = format == DXT1 ? 8 : 16;
		int[] px = new int[16], scratch = new int[56];
		int off = by * bw * blockSize;
		int rows = Math.min(4, height - by * 4);
		for (int bx = 0; bx < bw; bx++, off += blockSize) {
			switch (format) {
			case DXT1: decodeBc1(d, off, px); break;
			case DXT5: decodeBc3(d, off, px); break;
			default: decodeBc7(d, off, px, scratch);
			}
			int x = bx * 4, cols = Math.min(4, width - x);
			for (int r = 0; r < rows; r++)
				System.arraycopy(px, r * 4, out, (height - 1 - (by * 4 + r)) * width + x, cols);
		}
	}

	private static void decodeBc1(byte[] d, int off, int[] px) {
		int q0 = d[off] & 0xff | (d[off + 1] & 0xff) << 8, q1 = d[off + 2] & 0xff | (d[off + 3] & 0xff) << 8;
		int r0 = q0 >> 11, g0 = q0 >> 5 & 0x3f, b0 = q0 & 0x1f;
		r0 = r0 << 3 | r0 >> 2; g0 = g0 << 2 | g0 >> 4; b0 = b0 << 3 | b0 >> 2;
		int r1 = q1 >> 11, g1 = q1 >> 5 & 0x3f, b1 = q1 & 0x1f;
		r1 = r1 << 3 | r1 >> 2; g1 = g1 << 2 | g1 >> 4; b1 = b1 << 3 | b1 >> 2;
		int c0 = 0xff000000 | r0 << 16 | g0 << 8 | b0, c1 = 0xff000000 | r1 << 16 | g1 << 8 | b1, c2, c3;
		if (q0 > q1) {
			c2 = 0xff000000 | (r0 * 2 + r1) / 3 << 16 | (g0 * 2 + g1) / 3 << 8 | (b0 * 2 + b1) / 3;
			c3 = 0xff000000 | (r0 + r1 * 2) / 3 << 16 | (g0 + g1 * 2) / 3 << 8 | (b0 + b1 * 2) / 3;
		} else {
			c2 = 0xff000000 | (r0 + r1) / 2 << 16 | (g0 + g1) / 2 << 8 | (b0 + b1) / 2;
			c3 = 0xff000000;
		}
		int idx = d[off + 4] & 0xff | (d[off + 5] & 0xff) << 8 | (d[off + 6] & 0xff) << 16 | (d[off + 7] & 0xff) << 24;
		for (int i = 0; i < 16; i++, idx >>>= 2) {
			int s = idx & 3;
			px[i] = s == 0 ? c0 : s == 1 ? c1 : s == 2 ? c2 : c3;
		}
	}

	private static void decodeBc3(byte[] d, int off, int[] px) {
		decodeBc1(d, off + 8, px);
		int a0 = d[off] & 0xff, a1 = d[off + 1] & 0xff;
		long idx = 0;
		for (int i = 7; i >= 2; i--)
			idx = idx << 8 | d[off + i] & 0xff;
		for (int i = 0; i < 16; i++, idx >>>= 3) {
			int s = (int) idx & 7, a;
			if (s == 0)
				a = a0;
			else if (s == 1)
				a = a1;
			else if (a0 > a1)
				a = (a0 * (8 - s) + a1 * (s - 1)) / 7;
			else if (s < 6)
				a = (a0 * (6 - s) + a1 * (s - 1)) / 5;
			else
				a = s == 6 ? 0 : 255;
			px[i] = px[i] & 0xffffff | a << 24;
		}
	}

	// BC7 mode properties: subsets, partition bits, rotation bits, index selection bit, color bits,
	// alpha bits, per-endpoint p-bits, shared p-bits, index bits, secondary index bits
	private static final int[] NS = { 3, 2, 3, 2, 1, 1, 1, 2 }, PB = { 4, 6, 6, 6, 0, 0, 0, 6 },
			RB = { 0, 0, 0, 0, 2, 2, 0, 0 }, ISB = { 0, 0, 0, 0, 1, 0, 0, 0 }, CB = { 4, 6, 5, 7, 5, 7, 7, 5 },
			AB = { 0, 0, 0, 0, 6, 8, 7, 5 }, EPB = { 1, 0, 0, 1, 0, 0, 1, 1 }, SPB = { 0, 1, 0, 0, 0, 0, 0, 0 },
			IB = { 3, 3, 2, 2, 2, 2, 4, 2 }, IB2 = { 0, 0, 0, 0, 3, 2, 0, 0 };

	private static final int[][] WEIGHTS = { null, null, { 0, 21, 43, 64 }, { 0, 9, 18, 27, 37, 46, 55, 64 },
			{ 0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64 } };

	/** 2-subset partitions; bit i is the subset of pixel i. */
	private static final int[] PARTITIONS2 = {
		0xcccc, 0x8888, 0xeeee, 0xecc8, 0xc880, 0xfeec, 0xfec8, 0xec80, 0xc800, 0xffec, 0xfe80, 0xe800, 0xffe8,
		0xff00, 0xfff0, 0xf000, 0xf710, 0x008e, 0x7100, 0x08ce, 0x008c, 0x7310, 0x3100, 0x8cce, 0x088c, 0x3110,
		0x6666, 0x366c, 0x17e8, 0x0ff0, 0x718e, 0x399c, 0xaaaa, 0xf0f0, 0x5a5a, 0x33cc, 0x3c3c, 0x55aa, 0x9696,
		0xa55a, 0x73ce, 0x13c8, 0x324c, 0x3bdc, 0x6996, 0xc33c, 0x9966, 0x0660, 0x0272, 0x04e4, 0x4e40, 0x2720,
		0xc936, 0x936c, 0x39c6, 0x639c, 0x9336, 0x9cc6, 0x817e, 0xe718, 0xccf0, 0x0fcc, 0x7744, 0xee22,
	};
	/** 3-subset partitions; bits 2i and 2i+1 are the subset of pixel i. */
	private static final int[] PARTITIONS3 = {
		0xaa685050, 0x6a5a5040, 0x5a5a4200, 0x5450a0a8, 0xa5a50000, 0xa0a05050, 0x5555a0a0, 0x5a5a5050,
		0xaa550000, 0xaa555500, 0xaaaa5500, 0x90909090, 0x94949494, 0xa4a4a4a4, 0xa9a59450, 0x2a0a4250,
		0xa5945040, 0x0a425054, 0xa5a5a500, 0x55a0a0a0, 0xa8a85454, 0x6a6a4040, 0xa4a45000, 0x1a1a0500,
		0x0050a4a4, 0xaaa59090, 0x14696914, 0x69691400, 0xa08585a0, 0xaa821414, 0x50a4a450, 0x6a5a0200,
		0xa9a58000, 0x5090a0a8, 0xa8a09050, 0x24242424, 0x00aa5500, 0x24924924, 0x24499224, 0x50a50a50,
		0x500aa550, 0xaaaa4444, 0x66660000, 0xa5a0a5a0, 0x50a050a0, 0x69286928, 0x44aaaa44, 0x66666600,
		0xaa444444, 0x54a854a8, 0x95809580, 0x96969600, 0xa85454a8, 0x80959580, 0xaa141414, 0x96960000,
		0xaaaa1414, 0xa05050a0, 0xa0a5a5a0, 0x96000000, 0x40804080, 0xa9a8a9a8, 0xaaaaaa44, 0x2a4a5254,
	};
	/** Anchor pixel of subset 1 for 2-subset partitions. */
	private static final byte[] ANCHOR2 = {
		15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 2, 8, 2, 2, 8, 8, 15, 2, 8, 2, 2, 8, 8, 2, 2,
		15, 15, 6, 8, 2, 8, 15, 15, 2, 8, 2, 2, 2, 15, 15, 6, 6, 2, 6, 8, 15, 15, 2, 2, 15, 15, 15, 15, 15, 2, 2, 15,
	};
	/** Anchor pixels of subsets 1 and 2 for 3-subset partitions. */
	private static final byte[] ANCHOR3A = {
		3, 3, 15, 15, 8, 3, 15, 15, 8, 8, 6, 6, 6, 5, 3, 3, 3, 3, 8, 15, 3, 3, 6, 10, 5, 8, 8, 6, 8, 5, 15, 15,
		8, 15, 3, 5, 6, 10, 8, 15, 15, 3, 15, 5, 15, 15, 15, 15, 3, 15, 5, 5, 5, 8, 5, 10, 5, 10, 8, 13, 15, 12, 3, 3,
	}, ANCHOR3B = {
		15, 8, 8, 3, 15, 15, 3, 8, 15, 15, 15, 15, 15, 15, 15, 8, 15, 8, 15, 3, 15, 8, 15, 8, 3, 15, 6, 10, 15, 15, 10, 8,
		15, 3, 15, 10, 10, 8, 9, 10, 6, 15, 8, 15, 3, 6, 6, 8, 15, 3, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 3, 15, 15, 8,
	};

	/** Reads {@code n} (at most 32) bits at bit position {@code pos} of the 128-bit value {@code hi:lo}. */
	private static int bits(long lo, long hi, int pos, int n) {
		long v;
		if (pos >= 64)
			v = hi >>> (pos - 64);
		else if (pos + n <= 64)
			v = lo >>> pos;
		else
			v = lo >>> pos | hi << (64 - pos);
		return (int) v & ((1 << n) - 1);
	}

	private static long le64(byte[] d, int off) {
		long v = 0;
		for (int i = 7; i >= 0; i--)
			v = v << 8 | d[off + i] & 0xff;
		return v;
	}

	/** Decodes a BC7 block; {@code ep} is scratch space of at least 56 ints (24 endpoint components, 2x16 indices). */
	private static void decodeBc7(byte[] d, int off, int[] px, int[] ep) {
		long lo = le64(d, off), hi = le64(d, off + 8);
		int mode = Long.numberOfTrailingZeros(lo);
		if (mode >= 8) {
			for (int i = 0; i < 16; i++)
				px[i] = 0;
			return;
		}
		int pos = mode + 1;
		int partition = bits(lo, hi, pos, PB[mode]);
		pos += PB[mode];
		int rotation = bits(lo, hi, pos, RB[mode]);
		pos += RB[mode];
		int idxSel = bits(lo, hi, pos, ISB[mode]);
		pos += ISB[mode];

		// endpoints: ep[e * 4 + c] for endpoint e (2 per subset), channel c (RGBA)
		int ns = NS[mode], ne = ns * 2, cb = CB[mode], ab = AB[mode];
		for (int c = 0; c < 3; c++)
			for (int e = 0; e < ne; e++, pos += cb)
				ep[e * 4 + c] = bits(lo, hi, pos, cb);
		if (ab > 0)
			for (int e = 0; e < ne; e++, pos += ab)
				ep[e * 4 + 3] = bits(lo, hi, pos, ab);
		int cp = cb, ap = ab;
		if (EPB[mode] != 0 || SPB[mode] != 0) {
			cp++;
			if (ab > 0)
				ap++;
			for (int e = 0; e < ne; e++) {
				int p = EPB[mode] != 0 ? bits(lo, hi, pos + e, 1) : bits(lo, hi, pos + (e >> 1), 1);
				for (int c = 0; c < 4; c++)
					ep[e * 4 + c] = ep[e * 4 + c] << 1 | p;
			}
			pos += EPB[mode] != 0 ? ne : ns;
		}
		for (int e = 0; e < ne; e++) {
			for (int c = 0; c < 3; c++) {
				int v = ep[e * 4 + c] << (8 - cp);
				ep[e * 4 + c] = v | v >> cp;
			}
			if (ab > 0) {
				int v = ep[e * 4 + 3] << (8 - ap);
				ep[e * 4 + 3] = v | v >> ap;
			} else {
				ep[e * 4 + 3] = 255;
			}
		}

		// indices
		int subsets = 0, a1 = 16, a2 = 16;
		if (ns == 2) {
			subsets = PARTITIONS2[partition];
			a1 = ANCHOR2[partition];
		} else if (ns == 3) {
			subsets = PARTITIONS3[partition];
			a1 = ANCHOR3A[partition];
			a2 = ANCHOR3B[partition];
		}
		int ib = IB[mode], ib2 = IB2[mode];
		for (int i = 0; i < 16; i++) {
			int n = i == 0 || i == a1 || i == a2 ? ib - 1 : ib;
			ep[24 + i] = bits(lo, hi, pos, n);
			pos += n;
		}
		if (ib2 > 0) {
			for (int i = 0; i < 16; i++) {
				int n = i == 0 ? ib2 - 1 : ib2;
				ep[40 + i] = bits(lo, hi, pos, n);
				pos += n;
			}
		}

		int[] cw, aw;
		int ci, ai;
		if (ib2 == 0) {
			cw = aw = WEIGHTS[ib];
			ci = ai = 24;
		} else if (idxSel == 0) {
			cw = WEIGHTS[ib];
			aw = WEIGHTS[ib2];
			ci = 24;
			ai = 40;
		} else {
			cw = WEIGHTS[ib2];
			aw = WEIGHTS[ib];
			ci = 40;
			ai = 24;
		}
		for (int i = 0; i < 16; i++) {
			int s = ns == 1 ? 0 : ns == 2 ? subsets >> i & 1 : subsets >>> (i * 2) & 3;
			int e0 = s * 8, e1 = e0 + 4;
			int w = cw[ep[ci + i]], iw = 64 - w, wa = aw[ep[ai + i]];
			int r = (ep[e0] * iw + ep[e1] * w + 32) >> 6;
			int g = (ep[e0 + 1] * iw + ep[e1 + 1] * w + 32) >> 6;
			int b = (ep[e0 + 2] * iw + ep[e1 + 2] * w + 32) >> 6;
			int a = (ep[e0 + 3] * (64 - wa) + ep[e1 + 3] * wa + 32) >> 6;
			switch (rotation) {
			case 1: { int t = a; a = r; r = t; break; }
			case 2: { int t = a; a = g; g = t; break; }
			case 3: { int t = a; a = b; b = t; break; }
			}
			px[i] = a << 24 | r << 16 | g << 8 | b;
		}
	}
}
