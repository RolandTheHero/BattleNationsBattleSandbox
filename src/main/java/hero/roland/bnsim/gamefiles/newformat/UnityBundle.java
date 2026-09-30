package hero.roland.bnsim.gamefiles.newformat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import hero.roland.bnsim.util.FileFormatException;

/**
 * A minimal reader for Unity asset bundles ({@code UnityFS}), as shipped by the
 * Unity remaster of the game. It supports just what {@link NewGameFiles} needs:
 * uncompressed or LZ4/LZ4HC-compressed bundles, and objects whose serialized
 * layout is described by the type trees embedded in the bundle.
 *
 * <p>The bundle is read lazily: only the compressed blocks a request touches are
 * decompressed (a few are cached), so even the largest bundles are never held in
 * memory whole.
 *
 * <p>Objects are decoded generically from their type tree into plain Java
 * values: a composite becomes a {@code Map<String, Object>} (in field order),
 * an array a {@code List<Object>}, a string a {@code String} (or its raw bytes,
 * on request), and a number or boolean its boxed type. Decoding stops at a
 * {@code ManagedReferencesRegistry} field (always last), whose contents are not
 * needed.
 */
final class UnityBundle {
	/** Unity class ids of the object types read here. */
	static final int CLASS_TEXTURE_2D = 28, CLASS_TEXT_ASSET = 49, CLASS_AUDIO_CLIP = 83,
			CLASS_MONO_BEHAVIOUR = 114, CLASS_SPRITE = 213;

	private static final int ALIGN_FLAG = 0x4000;
	private static final int CACHED_BLOCKS = 16;

	/** Unity's built-in type-tree string table (Unity 2019+), in buffer order, as
	 * listed by UnityPy (https://github.com/K0lb3/UnityPy, MIT License). A node
	 * string offset with the high bit set indexes into this table. */
	private static final String[] COMMON_STRINGS = {
		"AABB", "AnimationClip", "AnimationCurve", "AnimationState", "Array", "Base", "BitField",
		"bitset", "bool", "char", "ColorRGBA", "Component", "data", "deque", "double",
		"dynamic_array", "FastPropertyName", "first", "float", "Font", "GameObject", "Generic Mono",
		"GradientNEW", "GUID", "GUIStyle", "int", "list", "long long", "map", "Matrix4x4f", "MdFour",
		"MonoBehaviour", "MonoScript", "m_ByteSize", "m_Curve", "m_EditorClassIdentifier",
		"m_EditorHideFlags", "m_Enabled", "m_ExtensionPtr", "m_GameObject", "m_Index", "m_IsArray",
		"m_IsStatic", "m_MetaFlag", "m_Name", "m_ObjectHideFlags", "m_PrefabInternal",
		"m_PrefabParentObject", "m_Script", "m_StaticEditorFlags", "m_Type", "m_Version", "Object",
		"pair", "PPtr<Component>", "PPtr<GameObject>", "PPtr<Material>", "PPtr<MonoBehaviour>",
		"PPtr<MonoScript>", "PPtr<Object>", "PPtr<Prefab>", "PPtr<Sprite>", "PPtr<TextAsset>",
		"PPtr<Texture>", "PPtr<Texture2D>", "PPtr<Transform>", "Prefab", "Quaternionf", "Rectf",
		"RectInt", "RectOffset", "second", "set", "short", "size", "SInt16", "SInt32", "SInt64",
		"SInt8", "staticvector", "string", "TextAsset", "TextMesh", "Texture", "Texture2D",
		"Transform", "TypelessData", "UInt16", "UInt32", "UInt64", "UInt8", "unsigned int",
		"unsigned long long", "unsigned short", "vector", "Vector2f", "Vector3f", "Vector4f",
		"m_ScriptingClassIdentifier", "Gradient", "Type*", "int2_storage", "int3_storage",
		"BoundsInt", "m_CorrespondingSourceObject", "m_PrefabInstance", "m_PrefabAsset", "FileSize",
		"Hash128", "RenderingLayerMask", "fixed_array", "EntityId",
	};
	private static final Map<Integer, String> COMMON_STRING_OFFSETS = new HashMap<>();
	static {
		int offset = 0;
		for (String s : COMMON_STRINGS) {
			COMMON_STRING_OFFSETS.put(offset, s);
			offset += s.length() + 1;
		}
	}

	/** One node of a type tree: a field's type and name, and its children. */
	private record Node(String type, String name, int metaFlag, List<Node> children) {
		boolean aligned() { return (metaFlag & ALIGN_FLAG) != 0; }
	}

	/** An object in the bundle's serialized file, not yet decoded. */
	record ObjectRef(long pathId, int classId, Node typeTree, long start, int size) {}

	/** A type in the serialized file: its class, layout hash and type tree (if stored). */
	private record TypeInfo(int classId, byte[] hash, Node tree) {}

	/** A file inside the bundle, as a range of the uncompressed data. */
	private record DirNode(long offset, long size) {}

	private final File file;
	private final FileChannel channel;
	// Compressed blocks: where each starts in the file and in the uncompressed data.
	private long[] blockFileOffset, blockDataOffset;
	private int[] blockCompressedSize, blockSize, blockFlags;
	private final Map<Integer, byte[]> blockCache =
			new LinkedHashMap<>(CACHED_BLOCKS, 0.75f, true) {
				@Override protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
					return size() > CACHED_BLOCKS;
				}
			};
	private final Map<String, DirNode> nodes = new HashMap<>();

	private long serializedFileOffset;
	private ByteOrder order = ByteOrder.LITTLE_ENDIAN;
	private final List<TypeInfo> types = new ArrayList<>();
	private final List<ObjectRef> objects = new ArrayList<>();
	private final Map<Long, ObjectRef> objectsById = new HashMap<>();
	/** A plain serialized file rather than a UnityFS bundle: its data is the file
	 * itself and its resource streams are files beside it. */
	private final boolean plainFile;
	/** Where to find type trees the file doesn't store, or {@code null}. */
	private final UnityBundle typeSource;

	private UnityBundle(File file, boolean plainFile, UnityBundle typeSource) throws IOException {
		this.file = file;
		this.plainFile = plainFile;
		this.typeSource = typeSource;
		this.channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
	}

	/** Opens a bundle and indexes the objects in its serialized file. The file
	 * stays open for reading for the life of the program. */
	static UnityBundle open(File file) throws IOException {
		return open(new UnityBundle(file, false, null));
	}

	/**
	 * Opens a plain serialized file, such as the player's built-in
	 * {@code sharedassets0.assets}. Player builds don't store type trees, so
	 * each type's tree is taken from {@code typeSource} (a bundle built by the
	 * same Unity version) when it has a type with the same class and layout hash;
	 * objects of other types can't be decoded.
	 */
	static UnityBundle openSerializedFile(File file, UnityBundle typeSource) throws IOException {
		return open(new UnityBundle(file, true, typeSource));
	}

	private static UnityBundle open(UnityBundle bundle) throws IOException {
		try {
			if (!bundle.plainFile)
				bundle.readContainer();
			bundle.readSerializedFile();
		} catch (RuntimeException e) {
			bundle.channel.close();
			throw new FileFormatException("Could not read Unity file " + bundle.file.getName(), e);
		} catch (IOException e) {
			bundle.channel.close();
			throw e;
		}
		return bundle;
	}

	/** This file's type tree for {@code classId} with the given layout hash, or {@code null}. */
	private Node typeTree(int classId, byte[] hash) {
		for (TypeInfo type : types)
			if (type.classId == classId && Arrays.equals(type.hash, hash) && type.tree != null)
				return type.tree;
		return null;
	}

	File getFile() {
		return file;
	}

	/** Every object of the given Unity class id, in file order. */
	List<ObjectRef> objects(int classId) {
		List<ObjectRef> result = new ArrayList<>();
		for (ObjectRef obj : objects)
			if (obj.classId == classId)
				result.add(obj);
		return result;
	}

	/** The object with the given path id, or {@code null}. */
	ObjectRef object(long pathId) {
		return objectsById.get(pathId);
	}

	/** Decodes every object of the given Unity class id, in file order. */
	List<Map<String, Object>> read(int classId) throws IOException {
		List<Map<String, Object>> result = new ArrayList<>();
		for (ObjectRef obj : objects(classId))
			result.add(read(obj, false));
		return result;
	}

	/**
	 * Decodes an object. With {@code rawStrings}, string fields come back as
	 * {@code byte[]} instead of being decoded as UTF-8 (for binary TextAssets).
	 */
	Map<String, Object> read(ObjectRef obj, boolean rawStrings) throws IOException {
		return readFields(obj, null, rawStrings);
	}

	/**
	 * Decodes an object's top-level fields up to and including the last of
	 * {@code wanted}, skipping the rest; cheap when the wanted fields come first
	 * (e.g. {@code m_Name}). {@code null} means every field.
	 */
	Map<String, Object> readFields(ObjectRef obj, Set<String> wanted, boolean rawStrings) throws IOException {
		if (obj.typeTree == null)
			throw new FileFormatException("Bundle has no type tree for class " + obj.classId);
		ByteBuffer data = ByteBuffer.wrap(readData(serializedFileOffset + obj.start, obj.size)).order(order);
		Map<String, Object> fields = new LinkedHashMap<>();
		try {
			int remaining = wanted == null ? Integer.MAX_VALUE : wanted.size();
			for (Node child : obj.typeTree.children) {
				if (remaining == 0 || child.type.equals("ManagedReferencesRegistry"))
					break; // SerializeReference payloads: not needed, and always last
				fields.put(child.name, readValue(data, child, rawStrings));
				if (wanted != null && wanted.contains(child.name))
					remaining--;
			}
		} catch (RuntimeException e) {
			throw new FileFormatException("Could not decode Unity object " + obj.pathId, e);
		}
		return fields;
	}

	/** An object's {@code m_Name}, decoding nothing else if it is the first field. */
	String name(ObjectRef obj) throws IOException {
		Object name = readFields(obj, Set.of("m_Name"), false).get("m_Name");
		return name instanceof String ? (String) name : null;
	}

	/**
	 * Reads a range of a resource file stored alongside the serialized file (a
	 * texture's {@code .resS} or an audio clip's {@code .resource}), named by a
	 * path such as {@code archive:/CAB-.../CAB-....resS}.
	 */
	byte[] readResource(String path, long offset, int size) throws IOException {
		String name = path.substring(path.lastIndexOf('/') + 1);
		if (plainFile) {
			try (FileChannel resource = FileChannel.open(new File(file.getParentFile(), name).toPath(),
					StandardOpenOption.READ)) {
				if (offset < 0 || offset + size > resource.size())
					throw new FileFormatException("Resource range out of bounds in " + name);
				ByteBuffer buf = ByteBuffer.allocate(size);
				while (buf.hasRemaining())
					if (resource.read(buf, offset + buf.position()) < 0)
						throw new FileFormatException("Unexpected end of " + name);
				return buf.array();
			}
		}
		DirNode node = nodes.get(name);
		if (node == null)
			throw new FileFormatException("Bundle " + file.getName() + " has no resource " + path);
		if (offset < 0 || offset + size > node.size)
			throw new FileFormatException("Resource range out of bounds in " + path);
		return readData(node.offset + offset, size);
	}

	// --- Bundle container ----------------------------------------------------

	/** Reads the UnityFS header, block list and directory. */
	private void readContainer() throws IOException {
		ByteBuffer in = ByteBuffer.wrap(readFile(0, (int) Math.min(4096, channel.size())))
				.order(ByteOrder.BIG_ENDIAN);
		String signature = readCString(in);
		if (!signature.equals("UnityFS"))
			throw new FileFormatException("Not a UnityFS bundle: " + signature);
		int formatVersion = in.getInt();
		readCString(in); // player version
		readCString(in); // engine version
		in.getLong(); // bundle size
		int compressedInfoSize = in.getInt();
		int uncompressedInfoSize = in.getInt();
		int flags = in.getInt();
		if (formatVersion >= 7)
			align(in, 0, 16);

		long infoOffset, dataStart;
		if ((flags & 0x80) != 0) { // blocks info at the end of the file
			infoOffset = channel.size() - compressedInfoSize;
			dataStart = in.position();
		} else {
			infoOffset = in.position();
			dataStart = infoOffset + compressedInfoSize;
		}
		if ((flags & 0x200) != 0) // block data needs padding at start
			dataStart = (dataStart + 15) & ~15L;
		ByteBuffer info = ByteBuffer.wrap(decompress(readFile(infoOffset, compressedInfoSize),
				uncompressedInfoSize, flags)).order(ByteOrder.BIG_ENDIAN);

		info.position(16); // skip the uncompressed data hash
		int blockCount = info.getInt();
		blockFileOffset = new long[blockCount];
		blockDataOffset = new long[blockCount];
		blockCompressedSize = new int[blockCount];
		blockSize = new int[blockCount];
		blockFlags = new int[blockCount];
		long filePos = dataStart, dataPos = 0;
		for (int i = 0; i < blockCount; i++) {
			blockSize[i] = info.getInt();
			blockCompressedSize[i] = info.getInt();
			blockFlags[i] = info.getShort() & 0xFFFF;
			blockFileOffset[i] = filePos;
			blockDataOffset[i] = dataPos;
			filePos += blockCompressedSize[i];
			dataPos += blockSize[i];
		}
		int nodeCount = info.getInt();
		for (int i = 0; i < nodeCount; i++) {
			long offset = info.getLong();
			long size = info.getLong();
			info.getInt(); // node flags
			nodes.put(readCString(info), new DirNode(offset, size));
		}
	}

	/** Reads {@code length} bytes of the uncompressed data at {@code offset}. */
	private byte[] readData(long offset, int length) throws IOException {
		if (plainFile)
			return readFile(offset, length);
		byte[] out = new byte[length];
		int block = findBlock(offset);
		int done = 0;
		while (done < length) {
			if (block >= blockSize.length)
				throw new FileFormatException("Read past the end of " + file.getName());
			byte[] data = block(block);
			int from = (int) (offset + done - blockDataOffset[block]);
			int n = Math.min(length - done, data.length - from);
			System.arraycopy(data, from, out, done, n);
			done += n;
			block++;
		}
		return out;
	}

	/** The index of the block holding uncompressed offset {@code offset}. */
	private int findBlock(long offset) {
		int lo = 0, hi = blockDataOffset.length - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (blockDataOffset[mid] <= offset) lo = mid;
			else hi = mid - 1;
		}
		return lo;
	}

	private byte[] block(int index) throws IOException {
		synchronized (blockCache) {
			byte[] data = blockCache.get(index);
			if (data == null) {
				data = decompress(readFile(blockFileOffset[index], blockCompressedSize[index]),
						blockSize[index], blockFlags[index]);
				blockCache.put(index, data);
			}
			return data;
		}
	}

	private byte[] readFile(long position, int length) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(length);
		while (buf.hasRemaining()) {
			if (channel.read(buf, position + buf.position()) < 0)
				throw new FileFormatException("Unexpected end of " + file.getName());
		}
		return buf.array();
	}

	private static byte[] decompress(byte[] src, int uncompressedSize, int flags) throws FileFormatException {
		switch (flags & 0x3F) {
		case 0:
			return src;
		case 2: case 3: // LZ4, LZ4HC
			return lz4Decompress(src, uncompressedSize);
		default:
			throw new FileFormatException("Unsupported bundle compression type " + (flags & 0x3F));
		}
	}

	/** Decodes a raw LZ4 block. */
	private static byte[] lz4Decompress(byte[] src, int uncompressedSize) {
		byte[] dst = new byte[uncompressedSize];
		int s = 0, d = 0;
		while (s < src.length) {
			int token = src[s++] & 0xFF;
			int literals = token >>> 4;
			if (literals == 15) {
				int b;
				do { b = src[s++] & 0xFF; literals += b; } while (b == 255);
			}
			System.arraycopy(src, s, dst, d, literals);
			s += literals;
			d += literals;
			if (s >= src.length)
				break; // the last sequence has literals only
			int offset = (src[s] & 0xFF) | (src[s + 1] & 0xFF) << 8;
			s += 2;
			int matchLength = token & 0xF;
			if (matchLength == 15) {
				int b;
				do { b = src[s++] & 0xFF; matchLength += b; } while (b == 255);
			}
			matchLength += 4;
			for (int from = d - offset, end = d + matchLength; d < end; )
				dst[d++] = dst[from++]; // byte by byte: the match may overlap its output
		}
		return dst;
	}

	// --- Serialized file ------------------------------------------------------

	private void readSerializedFile() throws IOException {
		if (!plainFile) {
			// The serialized file is the node that isn't a resource stream (.resS/.resource).
			serializedFileOffset = nodes.entrySet().stream()
					.filter(e -> !e.getKey().contains("."))
					.map(e -> e.getValue().offset)
					.findFirst()
					.orElseThrow(() -> new FileFormatException("Bundle has no serialized file"));
		}

		ByteBuffer header = ByteBuffer.wrap(readData(serializedFileOffset, 48)).order(ByteOrder.BIG_ENDIAN);
		header.getInt(); // legacy metadata size
		header.getInt(); // legacy file size
		int version = header.getInt();
		header.getInt(); // legacy data offset
		if (version < 22)
			throw new FileFormatException("Unsupported serialized file version " + version);
		boolean bigEndian = header.get() != 0;
		header.position(header.position() + 3); // reserved
		int metadataSize = header.getInt();
		header.getLong(); // file size
		long dataOffset = header.getLong();
		order = bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;

		// Positions in the metadata count from the start of the serialized file.
		ByteBuffer in = ByteBuffer.wrap(readData(serializedFileOffset, 48 + metadataSize)).order(order);
		in.position(48);
		readCString(in); // Unity version
		in.getInt(); // target platform
		boolean hasTypeTrees = in.get() != 0;
		int typeCount = in.getInt();
		for (int i = 0; i < typeCount; i++) {
			int classId = in.getInt();
			in.get(); // is stripped
			in.getShort(); // script type index
			if (classId == CLASS_MONO_BEHAVIOUR)
				in.position(in.position() + 16); // script id
			byte[] hash = new byte[16]; // identifies the type's serialized layout
			in.get(hash);
			Node tree = null;
			if (hasTypeTrees) {
				tree = readTypeTree(in);
				int dependencies = in.getInt();
				in.position(in.position() + 4 * dependencies);
			} else if (typeSource != null) {
				tree = typeSource.typeTree(classId, hash);
			}
			types.add(new TypeInfo(classId, hash, tree));
		}

		int objectCount = in.getInt();
		for (int i = 0; i < objectCount; i++) {
			align(in, 0, 4);
			long pathId = in.getLong();
			long start = in.getLong() + dataOffset;
			int size = in.getInt();
			TypeInfo type = types.get(in.getInt());
			ObjectRef obj = new ObjectRef(pathId, type.classId, type.tree, start, size);
			objects.add(obj);
			objectsById.put(pathId, obj);
		}
	}

	private static Node readTypeTree(ByteBuffer in) {
		int nodeCount = in.getInt();
		int stringBufferSize = in.getInt();
		int[] levels = new int[nodeCount], typeOffsets = new int[nodeCount],
				nameOffsets = new int[nodeCount], metaFlags = new int[nodeCount];
		for (int i = 0; i < nodeCount; i++) {
			in.getShort(); // version
			levels[i] = in.get() & 0xFF;
			in.get(); // type flags
			typeOffsets[i] = in.getInt();
			nameOffsets[i] = in.getInt();
			in.getInt(); // byte size
			in.getInt(); // index
			metaFlags[i] = in.getInt();
			in.getLong(); // ref type hash
		}
		byte[] strings = new byte[stringBufferSize];
		in.get(strings);

		// Rebuild the tree from the depth-first node list.
		List<Node> stack = new ArrayList<>();
		Node root = null;
		for (int i = 0; i < nodeCount; i++) {
			Node node = new Node(treeString(strings, typeOffsets[i]), treeString(strings, nameOffsets[i]),
					metaFlags[i], new ArrayList<>());
			while (stack.size() > levels[i])
				stack.remove(stack.size() - 1);
			if (stack.isEmpty())
				root = node;
			else
				stack.get(stack.size() - 1).children.add(node);
			stack.add(node);
		}
		return root;
	}

	private static String treeString(byte[] strings, int offset) {
		if (offset < 0) // high bit set: an offset into the common string table
			return COMMON_STRING_OFFSETS.getOrDefault(offset & 0x7FFFFFFF, Integer.toString(offset & 0x7FFFFFFF));
		int end = offset;
		while (strings[end] != 0) end++;
		return new String(strings, offset, end - offset, StandardCharsets.UTF_8);
	}

	// --- Type-tree decoding --------------------------------------------------

	/** Reads one value described by {@code node} at the current position of
	 * {@code data}, which starts at the object's first byte. */
	private static Object readValue(ByteBuffer data, Node node, boolean rawStrings) {
		Object value;
		switch (node.type) {
		case "bool": value = data.get() != 0; break;
		case "SInt8": value = data.get(); break;
		case "UInt8": case "char": value = data.get() & 0xFF; break;
		case "SInt16": case "short": value = data.getShort(); break;
		case "UInt16": case "unsigned short": value = data.getShort() & 0xFFFF; break;
		case "SInt32": case "int": case "Type*": value = data.getInt(); break;
		case "UInt32": case "unsigned int": value = data.getInt() & 0xFFFFFFFFL; break;
		case "SInt64": case "long long": case "UInt64": case "unsigned long long": case "FileSize":
			value = data.getLong(); break;
		case "float": value = data.getFloat(); break;
		case "double": value = data.getDouble(); break;
		case "string": case "TypelessData": {
			byte[] bytes = new byte[data.getInt()];
			data.get(bytes);
			value = rawStrings || node.type.equals("TypelessData")
					? bytes : new String(bytes, StandardCharsets.UTF_8);
			if (!node.children.isEmpty() && node.children.get(0).aligned())
				align(data, 0, 4);
			break;
		}
		default:
			if (node.type.equals("Array")) {
				value = readArray(data, node, rawStrings);
			} else if (node.children.size() == 1 && node.children.get(0).type.equals("Array")) {
				value = readArray(data, node.children.get(0), rawStrings);
			} else {
				Map<String, Object> fields = new LinkedHashMap<>();
				for (Node child : node.children)
					fields.put(child.name, readValue(data, child, rawStrings));
				value = fields;
			}
		}
		if (node.aligned())
			align(data, 0, 4);
		return value;
	}

	private static List<Object> readArray(ByteBuffer data, Node array, boolean rawStrings) {
		int size = data.getInt();
		Node element = array.children.get(1);
		List<Object> list = new ArrayList<>(size);
		for (int i = 0; i < size; i++)
			list.add(readValue(data, element, rawStrings));
		if (array.aligned())
			align(data, 0, 4);
		return Collections.unmodifiableList(list);
	}

	// --- Helpers -------------------------------------------------------------

	private static String readCString(ByteBuffer in) {
		int start = in.position();
		while (in.get() != 0) {}
		byte[] bytes = new byte[in.position() - start - 1];
		in.position(start);
		in.get(bytes);
		in.get(); // terminator
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/** Advances {@code in} to the next multiple of {@code n} counted from {@code base}. */
	private static void align(ByteBuffer in, int base, int n) {
		int rel = in.position() - base;
		int pad = (n - rel % n) % n;
		in.position(in.position() + pad);
	}
}
