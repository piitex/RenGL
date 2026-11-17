package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.ui.color.Color;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.lwjgl.assimp.Assimp.*;

/**
 * A 3D model file turned into scene content, using the Assimp library: glTF 2.0 (.gltf and .glb), OBJ, FBX, COLLADA,
 * and the many other formats Assimp reads. {@link #root()} is a node tree that keeps the file's hierarchy and
 * transforms; add it to a scene, or copy it with {@link Node3D#copy()} to add the model more than once.
 * <pre>
 * Model model = Model.load(Path.of("assets/ship.glb"));
 * scene.add(model.root());
 * orbit.frame(model.root().bounds());
 * </pre>
 * What carries over: geometry, normals (made when the file has none), texture coordinates, the base colour and its
 * texture, transparency, two-sided surfaces; for glTF the metallic-roughness material with its normal,
 * metallic-roughness, occlusion and emissive maps; skeletons (up to four bones per vertex, {@link Skin#MAX_JOINTS} per
 * mesh), morph targets ({@link Mesh#setMorphTargets}) and animations ({@link #animations()}, including those of
 * morph weights). Textures come from beside the file, or from inside it for .glb and
 * embedded images. What doesn't: lights and cameras from the file. The model's own units and up axis are
 * kept (glTF is Y-up, which is what the scene uses).
 * <p>
 * Loading reads files and decodes images but never touches the GPU, so it can run on a background thread. The mesh and
 * textures are uploaded the first time the scene draws them.
 */
public final class Model {
    private static final Logger log = LogManager.getLogger("Engine");

    private final Node3D root;
    private final List<Mesh> meshes;
    private final List<Material> materials;
    private final List<AnimationClip> animations;

    private Model(Node3D root, List<Mesh> meshes, List<Material> materials, List<AnimationClip> animations) {
        this.root = root;
        this.meshes = meshes;
        this.materials = materials;
        this.animations = animations;
    }

    /**
     * The animations in the file, each moving nodes of {@link #root()} itself (not of a {@link Node3D#copy() copy}).
     */
    public List<AnimationClip> animations() {
        return animations;
    }

    /**
     * A player for this model's animations; register it with {@link Scene3D#animate} and call {@code play}.
     */
    public AnimationPlayer player() {
        return new AnimationPlayer(animations);
    }

    public Node3D root() {
        return root;
    }

    /**
     * Builds the search structure that picking uses for each mesh, which is otherwise built the first time the mesh is
     * picked: for a big model that is a pause under the mouse. It touches nothing but the model, so it can run on a
     * background thread, before the model is in a scene. A skinned mesh has none, because it is searched as posed.
     */
    public void prepareForPicking() {
        for (Mesh mesh : meshes) {
            if (!mesh.isSkinned()) mesh.bvh();
        }
    }

    /**
     * The distinct meshes in the file; a mesh used by several nodes is listed once.
     */
    public List<Mesh> meshes() {
        return meshes;
    }

    public List<Material> materials() {
        return materials;
    }

    /**
     * The box around the whole model in its own space, or null for a model with no geometry.
     */
    public Bounds bounds() {
        return root.bounds();
    }

    /**
     * Loads a model file. Textures the file points at are looked up beside it.
     *
     * @throws IOException when the file can't be read or isn't a model Assimp understands
     */
    public static Model load(Path file) throws IOException {
        return load(file, null);
    }

    /**
     * Loads a model file and makes levels of detail for every mesh in it ({@link Mesh#generateLods(LodSettings)}), with the
     * full meshes reordered to draw faster too when the settings {@linkplain LodSettings#optimize optimize}. With
     * {@code lods} null it is {@link #load(Path)}.
     * <p>
     * Levels the file has made by hand are used either way, in place of the made level with the same number: nodes named
     * like {@code rock_LOD0}, {@code rock_LOD1} (the Unity and Unreal way; the first becomes {@code rock} and the others are
     * taken out of the tree), and glTF's {@code MSFT_lod} extension, whose {@code MSFT_screencoverage} gives the size on a
     * 1080 pixel high screen at which each level starts, and the smallest size ({@link Node3D#setCullScreenSize}).
     */
    public static Model load(Path file, LodSettings lods) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("No model file at " + file);
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean gltf = lower.endsWith(".gltf") || lower.endsWith(".glb");
        byte[] bytes = gltf ? Files.readAllBytes(file) : null;
        // A glTF file whose levels of detail are nodes outside its scene is read with them added to it, or Assimp skips them.
        byte[] withLevels = gltf ? GltfLods.withLevelsInScene(bytes) : null;
        // From the path, not from memory, so Assimp's own file access finds the .mtl, .bin and texture files beside it.
        AIScene scene = withLevels == null ? aiImportFile(file.toAbsolutePath().toString(), IMPORT_FLAGS) : importReplacing(file.toAbsolutePath(), withLevels);
        return convert(scene, file.getFileName().toString(), file.toAbsolutePath().getParent(),
                gltf ? GltfTextureInfo.read(bytes) : List.of(), gltf ? GltfAnimations.read(bytes, file.toAbsolutePath().getParent()) : List.of(),
                gltf ? GltfLods.read(withLevels != null ? withLevels : bytes) : List.of(), lods);
    }

    /**
     * Imports {@code file} through Assimp's own file access, with {@code content} read in place of the file itself; every
     * other file it opens (buffers, textures) is read from the disk as usual.
     */
    private static AIScene importReplacing(Path file, byte[] content) {
        Map<Long, ByteBuffer> open = new HashMap<>();
        AIFileReadProc read = AIFileReadProc.create((pFile, pBuffer, size, count) -> {
            ByteBuffer data = open.get(pFile);
            if (data == null || size <= 0) return 0;
            long items = Math.min(count, data.remaining() / size);
            int bytes = (int) (items * size);
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), pBuffer, bytes);
            data.position(data.position() + bytes);
            return items;
        });
        AIFileWriteProc write = AIFileWriteProc.create((pFile, pBuffer, size, count) -> 0);
        AIFileTellProc tell = AIFileTellProc.create(pFile -> open.containsKey(pFile) ? open.get(pFile).position() : 0);
        AIFileTellProc length = AIFileTellProc.create(pFile -> open.containsKey(pFile) ? open.get(pFile).limit() : 0);
        AIFileSeek seek = AIFileSeek.create((pFile, offset, origin) -> {
            ByteBuffer data = open.get(pFile);
            if (data == null) return aiReturn_FAILURE;
            long to = switch (origin) {
                case aiOrigin_CUR -> data.position() + offset;
                case aiOrigin_END -> data.limit() + offset;
                default -> offset;
            };
            if (to < 0 || to > data.limit()) return aiReturn_FAILURE;
            data.position((int) to);
            return aiReturn_SUCCESS;
        });
        AIFileFlushProc flush = AIFileFlushProc.create(pFile -> {
        });
        AIFileOpenProc openProc = AIFileOpenProc.create((pFileIO, fileName, openMode) -> {
            Path path = Path.of(MemoryUtil.memUTF8(fileName));
            if (!path.isAbsolute()) path = file.getParent().resolve(path);
            byte[] bytes;
            try {
                bytes = path.toAbsolutePath().normalize().equals(file.normalize()) ? content : Files.readAllBytes(path);
            } catch (IOException | RuntimeException e) {
                return 0L;
            }
            ByteBuffer data = MemoryUtil.memAlloc(Math.max(1, bytes.length));
            data.put(bytes).flip();
            AIFile handle = AIFile.calloc().ReadProc(read).WriteProc(write).TellProc(tell).FileSizeProc(length).SeekProc(seek).FlushProc(flush);
            open.put(handle.address(), data);
            return handle.address();
        });
        AIFileCloseProc closeProc = AIFileCloseProc.create((pFileIO, pFile) -> {
            ByteBuffer data = open.remove(pFile);
            if (data != null) MemoryUtil.memFree(data);
            AIFile.create(pFile).free();
        });
        AIFileIO io = AIFileIO.calloc().OpenProc(openProc).CloseProc(closeProc);
        try {
            return aiImportFileEx(file.toString(), IMPORT_FLAGS, io);
        } finally {
            io.free();
            for (Map.Entry<Long, ByteBuffer> left : open.entrySet()) {
                MemoryUtil.memFree(left.getValue());
                AIFile.create(left.getKey()).free();
            }
            read.free();
            write.free();
            tell.free();
            length.free();
            seek.free();
            flush.free();
            openProc.free();
            closeProc.free();
        }
    }

    /**
     * Loads a model from memory. This works for self-contained files, such as .glb and .gltf with embedded data, and
     * not for ones that point at other files. Handy for a model bundled in the jar.
     *
     * @param name the file name, whose ending tells Assimp the format, such as {@code "ship.glb"}
     */
    public static Model load(String name, byte[] data) throws IOException {
        return load(name, data, null);
    }

    /**
     * Loads a model from memory and makes levels of detail for its meshes, as {@link #load(Path, LodSettings)} does.
     */
    public static Model load(String name, byte[] data, LodSettings lods) throws IOException {
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String lower = name.toLowerCase(Locale.ROOT);
        boolean gltf = lower.endsWith(".gltf") || lower.endsWith(".glb");
        byte[] withLevels = gltf ? GltfLods.withLevelsInScene(data) : null;
        byte[] imported = withLevels != null ? withLevels : data;
        ByteBuffer buffer = MemoryUtil.memAlloc(imported.length);
        AIScene scene;
        try {
            buffer.put(imported).flip();
            scene = aiImportFileFromMemory(buffer, IMPORT_FLAGS, ext);
        } finally {
            MemoryUtil.memFree(buffer);
        }
        return convert(scene, name, null, gltf ? GltfTextureInfo.read(data) : List.of(), gltf ? GltfAnimations.read(data, null) : List.of(),
                gltf ? GltfLods.read(imported) : List.of(), lods);
    }

    /**
     * Makes levels of detail for every mesh of the model, as {@link Mesh#generateLods(LodSettings)} does; levels from the
     * file are kept. Like loading, it touches nothing but the model, so it can run on a worker.
     */
    public Model generateLods(LodSettings settings) {
        for (Mesh mesh : meshes) {
            if (mesh.isLines()) continue;
            mesh.generateLods(settings);
            if (settings.clusters() && !mesh.isSkinned() && !mesh.isMorphed()) mesh.buildClusters();
        }
        return this;
    }

    private static final int IMPORT_FLAGS = aiProcess_Triangulate | aiProcess_JoinIdenticalVertices | aiProcess_GenSmoothNormals
            | aiProcess_GenUVCoords | aiProcess_SortByPType | aiProcess_ImproveCacheLocality | aiProcess_LimitBoneWeights
            // Assimp hands out OpenGL-style texture coordinates for every format; undo that for top-first images.
            | aiProcess_FlipUVs;

    private static Model convert(AIScene scene, String name, Path directory, List<GltfTextureInfo.Material> placing,
                                 List<List<GltfAnimations.Channel>> exact, List<GltfLods.Lod> fileLods, LodSettings lods) throws IOException {
        if (scene == null || scene.mRootNode() == null) {
            throw new IOException("Could not load the model " + name + ": " + aiGetErrorString());
        }
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            return new Converter(scene, name, directory, lower.endsWith(".gltf") || lower.endsWith(".glb"), placing, exact, fileLods, lods).convert();
        } finally {
            aiReleaseImport(scene);
        }
    }

    private static final class Converter {
        final AIScene scene;
        final String name;
        final Path directory;
        final boolean linearColors;
        final List<GltfTextureInfo.Material> placing;
        final List<List<GltfAnimations.Channel>> exactChannels;
        final List<GltfLods.Lod> fileLods;
        final LodSettings lods;
        /** Meshes that became a level of detail of another, which the model doesn't list as meshes of its own. */
        final java.util.Set<Mesh> levelMeshes = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        /** The number of the Assimp mesh each node that draws one draws. */
        final Map<Node3D, Integer> meshIndexOf = new java.util.IdentityHashMap<>();
        final Map<String, TextureData> textures = new HashMap<>();
        final List<Mesh> meshes = new ArrayList<>();
        final List<Material> materials = new ArrayList<>();
        Mesh[] meshByIndex;
        Material[] materialByIndex;
        final Map<String, Node3D> nodesByName = new HashMap<>();
        /** Every node that got a mesh, with the number of the Assimp mesh, to attach skins once all nodes exist. */
        final List<Object[]> meshNodes = new ArrayList<>();

        Converter(AIScene scene, String name, Path directory, boolean linearColors, List<GltfTextureInfo.Material> placing,
                  List<List<GltfAnimations.Channel>> exactChannels, List<GltfLods.Lod> fileLods, LodSettings lods) {
            this.fileLods = fileLods;
            this.lods = lods;
            this.placing = placing;
            this.exactChannels = exactChannels;
            this.scene = scene;
            this.name = name;
            this.directory = directory;
            this.linearColors = linearColors;
        }

        Model convert() {
            int materialCount = scene.mNumMaterials();
            materialByIndex = new Material[materialCount];
            PointerBuffer materialPointers = scene.mMaterials();
            for (int i = 0; i < materialCount; i++) {
                materialByIndex[i] = convertMaterial(AIMaterial.create(materialPointers.get(i)));
                if (i < placing.size()) place(materialByIndex[i], placing.get(i));
                materials.add(materialByIndex[i]);
            }

            int meshCount = scene.mNumMeshes();
            meshByIndex = new Mesh[meshCount];
            PointerBuffer meshPointers = scene.mMeshes();
            for (int i = 0; i < meshCount; i++) {
                meshByIndex[i] = convertMesh(AIMesh.create(meshPointers.get(i)));
                if (meshByIndex[i] != null && lods != null && lods.optimize()) meshByIndex[i] = LodGenerator.optimize(meshByIndex[i]);
                if (meshByIndex[i] != null) meshes.add(meshByIndex[i]);
            }
            Node3D root = convertNode(scene.mRootNode());
            attachSkins(root);
            namedLevels(root);
            fileLevels(root);
            meshes.removeIf(levelMeshes::contains);
            if (lods != null) {
                for (Mesh mesh : meshes) {
                    mesh.generateLods(lods);
                    if (lods.clusters() && !mesh.isLines() && !mesh.isSkinned() && !mesh.isMorphed()) mesh.buildClusters();
                }
            }
            return new Model(root, meshes, materials, convertAnimations());
        }

        private static final java.util.regex.Pattern LOD_NAME = java.util.regex.Pattern.compile("^(.*)_LOD(\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE);

        /**
         * Levels of detail made by hand and named the Unity and Unreal way: nodes beside each other called {@code rock_LOD0},
         * {@code rock_LOD1} and so on. Each part of a simpler level becomes a level of the same part of the first, which is
         * renamed {@code rock}; the others leave the tree.
         */
        void namedLevels(Node3D root) {
            Map<Node3D, Map<String, java.util.TreeMap<Integer, Node3D>>> groups = new java.util.LinkedHashMap<>();
            root.traverse(node -> {
                if (node.getParent() == null || node.getName() == null) return;
                java.util.regex.Matcher m = LOD_NAME.matcher(node.getName());
                if (!m.matches()) return;
                groups.computeIfAbsent(node.getParent(), p -> new java.util.LinkedHashMap<>())
                        .computeIfAbsent(m.group(1), b -> new java.util.TreeMap<>())
                        .putIfAbsent(Integer.parseInt(m.group(2)), node);
            });
            for (Map<String, java.util.TreeMap<Integer, Node3D>> byBase : groups.values()) {
                for (Map.Entry<String, java.util.TreeMap<Integer, Node3D>> group : byBase.entrySet()) {
                    java.util.TreeMap<Integer, Node3D> levels = group.getValue();
                    Node3D full = levels.get(0);
                    if (full == null || levels.size() < 2) continue;
                    List<Node3D> base = parts(full);
                    for (Map.Entry<Integer, Node3D> level : levels.tailMap(1).entrySet()) {
                        Node3D node = level.getValue();
                        List<Node3D> parts = parts(node);
                        if (parts.size() != base.size()) {
                            log.warn("{}: {} has {} parts and {} has {}; it is not used as a level of detail.", name, node.getName(), parts.size(), full.getName(), base.size());
                            continue;
                        }
                        if (!samePlace(node, full)) {
                            log.warn("{}: {} is not placed where {} is; it is not used as a level of detail.", name, node.getName(), full.getName());
                            continue;
                        }
                        for (int j = 0; j < base.size(); j++) addLevel(base.get(j), parts.get(j), level.getKey(), -1f);
                        node.getParent().remove(node);
                    }
                    String old = full.getName();
                    for (Node3D part : base) {
                        if (part != full && part.getName().startsWith(old)) part.setName(group.getKey() + part.getName().substring(old.length()));
                    }
                    full.setName(group.getKey());
                }
            }
        }

        /**
         * Levels of detail from glTF's {@code MSFT_lod}: the simpler levels' meshes join the chain of the node's own, and their
         * nodes, if Assimp put them in the tree, leave it.
         */
        void fileLevels(Node3D root) {
            for (GltfLods.Lod lod : fileLods) {
                Node3D full = nodesByName.get(lod.node());
                if (full == null) continue;
                List<Node3D> base = parts(full);
                float[] coverage = lod.coverage();
                for (int k = 0; k < lod.levels().size(); k++) {
                    // The file was read with every level's node in its scene ({@link GltfLods#withLevelsInScene}), named.
                    Node3D node = nodesByName.get(lod.levels().get(k));
                    List<Node3D> parts = node == null || node == full ? List.of() : parts(node);
                    if (parts.size() != base.size()) {
                        log.warn("{}: level {} of {} has {} parts where the node has {}; it and those after it are not used.", name, k + 1, full.getName(), parts.size(), base.size());
                        break;
                    }
                    for (int j = 0; j < base.size(); j++) {
                        float error = k < coverage.length && coverage[k] > 0f ? errorForCoverage(base.get(j).getMesh(), coverage[k]) : -1f;
                        addLevel(base.get(j), parts.get(j), k + 1, error);
                    }
                    if (node.getParent() != null && isUnder(node, root)) node.getParent().remove(node);
                }
                // One value more than there are levels: below it nothing is drawn.
                if (coverage.length == lod.levels().size() + 1 && coverage[coverage.length - 1] > 0f) {
                    for (Node3D part : base) part.setCullScreenSize((float) Math.sqrt(coverage[coverage.length - 1]));
                }
            }
        }

        /**
         * The error at which a level is drawn from the screen coverage a file gives (a share of the screen's area), taking a
         * screen 1080 pixels high and an error of a pixel: the level starts once the mesh is that share of the screen's height.
         */
        static float errorForCoverage(Mesh mesh, float coverage) {
            float height = (float) Math.sqrt(Math.min(1f, coverage));
            return 2f * mesh.bounds().radius() / (height * 1080f);
        }

        private static boolean isUnder(Node3D node, Node3D root) {
            for (Node3D n = node; n != null; n = n.getParent()) if (n == root) return true;
            return false;
        }

        /** The nodes that draw a level's meshes: the node and the parts Assimp split from it, or else its children that draw. */
        private static List<Node3D> parts(Node3D node) {
            List<Node3D> parts = new ArrayList<>();
            if (node.getMesh() != null) parts.add(node);
            for (Node3D child : node.getChildren()) {
                if (child.getMesh() != null && child.getName().startsWith(node.getName() + "#")) parts.add(child);
            }
            if (parts.isEmpty()) {
                for (Node3D child : node.getChildren()) if (child.getMesh() != null) parts.add(child);
            }
            return parts;
        }

        private static boolean samePlace(Node3D a, Node3D b) {
            float[] x = a.localMatrix().toArray(), y = b.localMatrix().toArray();
            for (int i = 0; i < 16; i++) if (Math.abs(x[i] - y[i]) > 1e-4f * (1f + Math.abs(y[i]))) return false;
            return true;
        }

        /**
         * Makes {@code level}'s mesh level {@code number} of {@code full}'s, measuring its error when {@code error} is below 0.
         * A skinned level has its joints renumbered to the full mesh's bones, which Assimp lists in each mesh's own order.
         */
        void addLevel(Node3D full, Node3D level, int number, float error) {
            Mesh base = full.getMesh(), mesh = level.getMesh();
            if (base == null || mesh == null || base == mesh || base.isLines() != mesh.isLines()) return;
            if (base.isSkinned() && (!mesh.isSkinned() || !renumberJoints(full, level, mesh))) {
                log.warn("{}: {} has no bones that match {}'s; it is not used as a level of detail.", name, level.getName(), full.getName());
                return;
            }
            base.setLodLevel(number, mesh, error >= 0f ? error : LodGenerator.measureError(base, mesh));
            levelMeshes.add(mesh);
        }

        private boolean renumberJoints(Node3D full, Node3D level, Mesh mesh) {
            Integer fullIndex = meshIndexOf.get(full), levelIndex = meshIndexOf.get(level);
            if (fullIndex == null || levelIndex == null) return false;
            List<String> fullBones = boneNames(fullIndex), levelBones = boneNames(levelIndex);
            int[] to = new int[levelBones.size()];
            for (int b = 0; b < to.length; b++) {
                to[b] = fullBones.indexOf(levelBones.get(b));
                if (to[b] < 0) return false;
            }
            int[] joints = mesh.skinJoints().clone();
            for (int i = 0; i < joints.length; i++) joints[i] = joints[i] < to.length ? to[joints[i]] : 0;
            mesh.setSkinning(joints, mesh.skinWeights());
            return true;
        }

        private List<String> boneNames(int meshIndex) {
            AIMesh source = AIMesh.create(scene.mMeshes().get(meshIndex));
            List<String> names = new ArrayList<>();
            PointerBuffer bones = source.mBones();
            for (int b = 0; b < source.mNumBones(); b++) names.add(AIBone.create(bones.get(b)).mName().dataString());
            return names;
        }

        /**
         * A skin per skinned mesh: its joints are the nodes named like the mesh's bones.
         */
        void attachSkins(Node3D root) {
            Map<Integer, Skin> skins = new HashMap<>();
            for (Object[] entry : meshNodes) {
                Node3D node = (Node3D) entry[0];
                int meshIndex = (Integer) entry[1];
                if (meshByIndex[meshIndex] == null || !meshByIndex[meshIndex].isSkinned()) continue;
                Skin skin = skins.computeIfAbsent(meshIndex, i -> {
                    AIMesh source = AIMesh.create(scene.mMeshes().get(i));
                    PointerBuffer bones = source.mBones();
                    List<Node3D> joints = new ArrayList<>();
                    List<Mat4> inverseBind = new ArrayList<>();
                    for (int b = 0; b < source.mNumBones(); b++) {
                        AIBone bone = AIBone.create(bones.get(b));
                        String boneName = bone.mName().dataString();
                        Node3D joint = nodesByName.get(boneName);
                        if (joint == null) {
                            log.warn("{} has a bone named {} but no node with that name; it will not move.", name, boneName);
                            joint = root;
                        }
                        joints.add(joint);
                        inverseBind.add(matrix(bone.mOffsetMatrix()));
                    }
                    return new Skin(joints, inverseBind);
                });
                node.setSkin(skin);
            }
        }

        List<AnimationClip> convertAnimations() {
            List<AnimationClip> clips = new ArrayList<>();
            PointerBuffer animations = scene.mAnimations();
            for (int a = 0; a < scene.mNumAnimations(); a++) {
                AIAnimation source = AIAnimation.create(animations.get(a));
                double ticks = source.mTicksPerSecond() > 0 ? source.mTicksPerSecond() : 25.0;
                List<AnimationClip.Channel> channels = new ArrayList<>();
                PointerBuffer nodeAnims = source.mChannels();
                for (int c = 0; c < source.mNumChannels(); c++) {
                    AINodeAnim anim = AINodeAnim.create(nodeAnims.get(c));
                    String target = anim.mNodeName().dataString();
                    Node3D node = nodesByName.get(target);
                    if (node == null) continue;
                    int positions = anim.mNumPositionKeys();
                    if (positions > 0) {
                        float[] times = new float[positions], values = new float[positions * 3];
                        AIVectorKey.Buffer keys = anim.mPositionKeys();
                        for (int k = 0; k < positions; k++) {
                            times[k] = (float) (keys.get(k).mTime() / ticks);
                            AIVector3D v = keys.get(k).mValue();
                            values[k * 3] = v.x();
                            values[k * 3 + 1] = v.y();
                            values[k * 3 + 2] = v.z();
                        }
                        channels.add(new AnimationClip.Channel(node, AnimationClip.Path.TRANSLATION, times, values, AnimationClip.Interpolation.LINEAR));
                    }
                    int rotations = anim.mNumRotationKeys();
                    if (rotations > 0) {
                        float[] times = new float[rotations], values = new float[rotations * 4];
                        AIQuatKey.Buffer keys = anim.mRotationKeys();
                        for (int k = 0; k < rotations; k++) {
                            times[k] = (float) (keys.get(k).mTime() / ticks);
                            AIQuaternion q = keys.get(k).mValue();
                            values[k * 4] = q.x();
                            values[k * 4 + 1] = q.y();
                            values[k * 4 + 2] = q.z();
                            values[k * 4 + 3] = q.w();
                        }
                        channels.add(new AnimationClip.Channel(node, AnimationClip.Path.ROTATION, times, values, AnimationClip.Interpolation.LINEAR));
                    }
                    int scalings = anim.mNumScalingKeys();
                    if (scalings > 0) {
                        float[] times = new float[scalings], values = new float[scalings * 3];
                        AIVectorKey.Buffer keys = anim.mScalingKeys();
                        for (int k = 0; k < scalings; k++) {
                            times[k] = (float) (keys.get(k).mTime() / ticks);
                            AIVector3D v = keys.get(k).mValue();
                            values[k * 3] = v.x();
                            values[k * 3 + 1] = v.y();
                            values[k * 3 + 2] = v.z();
                        }
                        channels.add(new AnimationClip.Channel(node, AnimationClip.Path.SCALE, times, values, AnimationClip.Interpolation.LINEAR));
                    }
                }
                PointerBuffer morphAnims = source.mMorphMeshChannels();
                for (int c = 0; c < source.mNumMorphMeshChannels(); c++) {
                    AIMeshMorphAnim anim = AIMeshMorphAnim.create(morphAnims.get(c));
                    Node3D node = nodesByName.get(anim.mName().dataString());
                    if (node == null || anim.mNumKeys() == 0) continue;
                    int keys = anim.mNumKeys();
                    AIMeshMorphKey.Buffer morphKeys = anim.mKeys();
                    int targets = 0;
                    for (int k = 0; k < keys; k++) {
                        AIMeshMorphKey key = morphKeys.get(k);
                        for (int j = 0; j < key.mNumValuesAndWeights(); j++) targets = Math.max(targets, key.mValues().get(j) + 1);
                    }
                    if (targets == 0) continue;
                    float[] times = new float[keys], values = new float[keys * targets];
                    for (int k = 0; k < keys; k++) {
                        AIMeshMorphKey key = morphKeys.get(k);
                        times[k] = (float) (key.mTime() / ticks);
                        for (int j = 0; j < key.mNumValuesAndWeights(); j++) values[k * targets + key.mValues().get(j)] = (float) key.mWeights().get(j);
                    }
                    channels.add(new AnimationClip.Channel(node, AnimationClip.Path.WEIGHTS, times, values, AnimationClip.Interpolation.LINEAR));
                }
                if (a < exactChannels.size()) applyExact(channels, exactChannels.get(a));
                String clipName = source.mName().dataString();
                clips.add(new AnimationClip(clipName.isEmpty() ? "animation" + a : clipName, (float) (source.mDuration() / ticks), channels));
            }
            return clips;
        }

        /**
         * Puts the glTF file's own step and cubic spline channels in place of what Assimp made for the same node and
         * property (or beside it, if it made none).
         */
        void applyExact(List<AnimationClip.Channel> channels, List<GltfAnimations.Channel> exact) {
            for (GltfAnimations.Channel e : exact) {
                Node3D node = nodesByName.get(e.node());
                if (node == null) continue;
                AnimationClip.Channel made = e.interpolation() == AnimationClip.Interpolation.CUBICSPLINE
                        ? new AnimationClip.Channel(node, e.path(), e.times(), e.values(), e.in(), e.out())
                        : new AnimationClip.Channel(node, e.path(), e.times(), e.values(), e.interpolation());
                boolean replaced = false;
                for (int i = 0; i < channels.size(); i++) {
                    AnimationClip.Channel old = channels.get(i);
                    if (old.target() == node && old.path() == e.path()) {
                        channels.set(i, made);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) channels.add(made);
            }
        }

        /**
         * Assimp's matrices are row-major with the translation in the last column; Mat4 is column-major.
         */
        static Mat4 matrix(AIMatrix4x4 t) {
            return new Mat4(new float[]{
                    t.a1(), t.b1(), t.c1(), t.d1(),
                    t.a2(), t.b2(), t.c2(), t.d2(),
                    t.a3(), t.b3(), t.c3(), t.d3(),
                    t.a4(), t.b4(), t.c4(), t.d4()});
        }

        Node3D convertNode(AINode source) {
            Node3D node = new Node3D(source.mName().dataString());
            node.setLocalMatrix(matrix(source.mTransformation()));
            nodesByName.putIfAbsent(node.getName(), node);

            int[] meshIndices = new int[source.mNumMeshes()];
            if (meshIndices.length > 0) source.mMeshes().get(meshIndices);
            boolean first = true;
            for (int i = 0; i < meshIndices.length; i++) {
                int meshIndex = meshIndices[i];
                Mesh mesh = meshByIndex[meshIndex];
                if (mesh == null) continue;
                AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(meshIndex));
                Material material = materialByIndex.length > aiMesh.mMaterialIndex() ? materialByIndex[aiMesh.mMaterialIndex()] : null;
                if (first) {
                    node.setMesh(mesh).setMaterial(material);
                    meshNodes.add(new Object[]{node, meshIndex});
                    meshIndexOf.put(node, meshIndex);
                    first = false;
                } else {
                    Node3D extra = new Node3D(node.getName() + "#" + i, mesh, material);
                    node.add(extra);
                    meshNodes.add(new Object[]{extra, meshIndex});
                    meshIndexOf.put(extra, meshIndex);
                }
            }
            // The parts of one mesh (a glTF mesh with a primitive per material) blend together, and an animation of the
            // weights targets this node, so every part follows this node's weights.
            float[] defaults = null;
            for (int meshIndex : meshIndices) {
                Mesh m = meshByIndex[meshIndex];
                if (m != null && m.isMorphed() && defaults == null) defaults = m.morphDefaults();
            }
            if (defaults != null) {
                node.setMorphWeights(defaults);
                for (Node3D part : node.getChildren()) {
                    if (part.getMesh() != null && part.getName().startsWith(node.getName() + "#")) part.shareMorphWeights(node);
                }
            }

            PointerBuffer children = source.mChildren();
            for (int i = 0; i < source.mNumChildren(); i++) node.add(convertNode(AINode.create(children.get(i))));
            return node;
        }

        Mesh convertMesh(AIMesh source) {
            if ((source.mPrimitiveTypes() & aiPrimitiveType_TRIANGLE) == 0) return null; // lines and points
            int vertexCount = source.mNumVertices();
            AIVector3D.Buffer vertices = source.mVertices();
            AIVector3D.Buffer normals = source.mNormals();
            AIVector3D.Buffer uvSource = source.mTextureCoords(0);
            AIVector3D.Buffer uvSource2 = source.mTextureCoords(1);

            float[] positions = new float[vertexCount * 3];
            float[] normalArray = normals != null ? new float[vertexCount * 3] : null;
            float[] uvs = uvSource != null ? new float[vertexCount * 2] : null;
            float[] uvs2 = uvSource2 != null ? new float[vertexCount * 2] : null;
            for (int i = 0; i < vertexCount; i++) {
                AIVector3D v = vertices.get(i);
                positions[i * 3] = v.x();
                positions[i * 3 + 1] = v.y();
                positions[i * 3 + 2] = v.z();
                if (normalArray != null) {
                    AIVector3D n = normals.get(i);
                    normalArray[i * 3] = n.x();
                    normalArray[i * 3 + 1] = n.y();
                    normalArray[i * 3 + 2] = n.z();
                }
                if (uvs != null) {
                    AIVector3D uv = uvSource.get(i);
                    uvs[i * 2] = uv.x();
                    uvs[i * 2 + 1] = uv.y();
                }
                if (uvs2 != null) {
                    AIVector3D uv = uvSource2.get(i);
                    uvs2[i * 2] = uv.x();
                    uvs2[i * 2 + 1] = uv.y();
                }
            }

            AIFace.Buffer faces = source.mFaces();
            int[] indices = new int[source.mNumFaces() * 3];
            int count = 0;
            for (int f = 0; f < source.mNumFaces(); f++) {
                AIFace face = faces.get(f);
                if (face.mNumIndices() != 3) continue; // Triangulate leaves only triangles in triangle meshes
                java.nio.IntBuffer ids = face.mIndices();
                indices[count++] = ids.get(0);
                indices[count++] = ids.get(1);
                indices[count++] = ids.get(2);
            }
            if (count == 0 || vertexCount == 0) return null;
            if (count < indices.length) indices = java.util.Arrays.copyOf(indices, count);
            Mesh mesh = new Mesh(positions, normalArray, uvs, indices);
            if (uvs2 != null) mesh.setUvs2(uvs2);
            if (source.mNumBones() > 0) applyBones(mesh, source, vertexCount);
            if (source.mNumAnimMeshes() > 0) applyMorphTargets(mesh, source, positions, normalArray);
            return mesh;
        }

        /**
         * Assimp gives each blend shape as a whole copy of the mesh, so the offsets are the difference from the base.
         */
        void applyMorphTargets(Mesh mesh, AIMesh source, float[] positions, float[] normals) {
            int vertexCount = positions.length / 3;
            List<Mesh.MorphTarget> targets = new ArrayList<>();
            float[] defaults = new float[source.mNumAnimMeshes()];
            PointerBuffer shapes = source.mAnimMeshes();
            for (int k = 0; k < source.mNumAnimMeshes(); k++) {
                AIAnimMesh shape = AIAnimMesh.create(shapes.get(k));
                if (shape.mNumVertices() != vertexCount || shape.mVertices() == null) {
                    log.warn("{} has a morph target with {} vertices on a mesh of {}; morph targets are skipped for this mesh.", name, shape.mNumVertices(), vertexCount);
                    return;
                }
                float[] offsets = new float[vertexCount * 3];
                AIVector3D.Buffer p = shape.mVertices();
                for (int i = 0; i < vertexCount; i++) {
                    AIVector3D v = p.get(i);
                    offsets[i * 3] = v.x() - positions[i * 3];
                    offsets[i * 3 + 1] = v.y() - positions[i * 3 + 1];
                    offsets[i * 3 + 2] = v.z() - positions[i * 3 + 2];
                }
                float[] normalOffsets = null;
                if (shape.mNormals() != null && normals != null) {
                    normalOffsets = new float[vertexCount * 3];
                    AIVector3D.Buffer n = shape.mNormals();
                    for (int i = 0; i < vertexCount; i++) {
                        AIVector3D v = n.get(i);
                        normalOffsets[i * 3] = v.x() - normals[i * 3];
                        normalOffsets[i * 3 + 1] = v.y() - normals[i * 3 + 1];
                        normalOffsets[i * 3 + 2] = v.z() - normals[i * 3 + 2];
                    }
                }
                targets.add(new Mesh.MorphTarget(shape.mName().dataString(), offsets, normalOffsets));
                defaults[k] = shape.mWeight();
            }
            mesh.setMorphTargets(targets, defaults);
        }

        /**
         * Up to four bones per vertex; when more influence a vertex the weakest are dropped.
         */
        void applyBones(Mesh mesh, AIMesh source, int vertexCount) {
            if (source.mNumBones() > Skin.MAX_JOINTS) {
                log.warn("{} has a mesh with {} bones; the 3D layer's limit is {}, so it is drawn without skinning.", name, source.mNumBones(), Skin.MAX_JOINTS);
                return;
            }
            int[] joints = new int[vertexCount * 4];
            float[] weights = new float[vertexCount * 4];
            PointerBuffer bones = source.mBones();
            for (int b = 0; b < source.mNumBones(); b++) {
                AIBone bone = AIBone.create(bones.get(b));
                AIVertexWeight.Buffer list = bone.mWeights();
                for (int w = 0; w < bone.mNumWeights(); w++) {
                    int vertex = list.get(w).mVertexId();
                    float weight = list.get(w).mWeight();
                    int slot = -1, weakest = 0;
                    for (int k = 0; k < 4; k++) {
                        if (weights[vertex * 4 + k] == 0f) {
                            slot = k;
                            break;
                        }
                        if (weights[vertex * 4 + k] < weights[vertex * 4 + weakest]) weakest = k;
                    }
                    if (slot < 0) {
                        if (weight <= weights[vertex * 4 + weakest]) continue;
                        slot = weakest;
                    }
                    joints[vertex * 4 + slot] = b;
                    weights[vertex * 4 + slot] = weight;
                }
            }
            for (int v = 0; v < vertexCount; v++) {
                float sum = weights[v * 4] + weights[v * 4 + 1] + weights[v * 4 + 2] + weights[v * 4 + 3];
                if (sum > 0f) {
                    for (int k = 0; k < 4; k++) weights[v * 4 + k] /= sum;
                } else {
                    weights[v * 4] = 1f; // unweighted vertices stay with the first bone
                }
            }
            mesh.setSkinning(joints, weights);
        }

        Material convertMaterial(AIMaterial source) {
            Material material = new Material();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                AIColor4D color = AIColor4D.malloc(stack);
                float r = 1f, g = 1f, b = 1f, a = 1f;
                if (aiGetMaterialColor(source, AI_MATKEY_BASE_COLOR, 0, 0, color) == aiReturn_SUCCESS
                        || aiGetMaterialColor(source, AI_MATKEY_COLOR_DIFFUSE, 0, 0, color) == aiReturn_SUCCESS) {
                    r = color.r();
                    g = color.g();
                    b = color.b();
                    a = color.a();
                }
                if (linearColors) { // glTF colour factors are linear light; the Material takes screen colours
                    r = toScreen(r);
                    g = toScreen(g);
                    b = toScreen(b);
                }

                float[] opacity = {1f};
                int[] max = {1};
                if (aiGetMaterialFloatArray(source, AI_MATKEY_OPACITY, 0, 0, opacity, max) == aiReturn_SUCCESS) a = Math.min(a, opacity[0]);
                material.setColor(r, g, b, a);
                if (a < 0.999f) material.setBlend(true);

                int[] twoSided = {0};
                max[0] = 1;
                if (aiGetMaterialIntegerArray(source, AI_MATKEY_TWOSIDED, 0, 0, twoSided, max) == aiReturn_SUCCESS && twoSided[0] != 0) {
                    material.setDoubleSided(true);
                }

                if (linearColors) {
                    material.setSpecular(0.25f);
                } else {
                    // OBJ and friends: Ks is the highlight's colour and Ns its tightness.
                    if (aiGetMaterialColor(source, AI_MATKEY_COLOR_SPECULAR, 0, 0, color) == aiReturn_SUCCESS) {
                        material.setSpecular((color.r() + color.g() + color.b()) / 3f);
                    }
                    float[] shininess = {32f};
                    max[0] = 1;
                    if (aiGetMaterialFloatArray(source, AI_MATKEY_SHININESS, 0, 0, shininess, max) == aiReturn_SUCCESS) {
                        material.setShininess(Math.max(1f, Math.min(256f, shininess[0])));
                    }
                }

                AIString mode = AIString.malloc(stack);
                if (aiGetMaterialString(source, AI_MATKEY_GLTF_ALPHAMODE, 0, 0, mode) == aiReturn_SUCCESS) {
                    String alphaMode = mode.dataString();
                    if (alphaMode.equals("BLEND")) {
                        material.setBlend(true);
                    } else if (alphaMode.equals("MASK")) {
                        float[] cutoff = {0.5f};
                        max[0] = 1;
                        aiGetMaterialFloatArray(source, AI_MATKEY_GLTF_ALPHACUTOFF, 0, 0, cutoff, max);
                        material.setAlphaCutoff(cutoff[0]);
                    }
                }

                AIString path = AIString.malloc(stack);
                for (int type : new int[]{aiTextureType_BASE_COLOR, aiTextureType_DIFFUSE}) {
                    if (aiGetMaterialTexture(source, type, 0, path, (int[]) null, null, null, null, null, null) == aiReturn_SUCCESS) {
                        TextureData texture = loadTexture(path.dataString());
                        if (texture != null) {
                            material.setTexture(texture);
                            break;
                        }
                    }
                }

                if (linearColors) { // glTF: metallic-roughness with its maps; an absent factor is 1, as the spec says
                    float[] metallic = {1f}, roughness = {1f};
                    max[0] = 1;
                    aiGetMaterialFloatArray(source, AI_MATKEY_METALLIC_FACTOR, 0, 0, metallic, max);
                    max[0] = 1;
                    aiGetMaterialFloatArray(source, AI_MATKEY_ROUGHNESS_FACTOR, 0, 0, roughness, max);
                    material.setMetallic(metallic[0]).setRoughness(roughness[0]);
                    TextureData normal = firstTexture(source, path, aiTextureType_NORMALS, aiTextureType_NORMAL_CAMERA);
                    if (normal != null) material.setNormalTexture(normal);
                    TextureData metalRough = firstTexture(source, path, aiTextureType_UNKNOWN, aiTextureType_METALNESS, aiTextureType_DIFFUSE_ROUGHNESS);
                    if (metalRough != null) material.setMetallicRoughnessTexture(metalRough);
                    TextureData occlusion = firstTexture(source, path, aiTextureType_LIGHTMAP, aiTextureType_AMBIENT_OCCLUSION);
                    if (occlusion != null) material.setOcclusionTexture(occlusion);
                    TextureData emissive = firstTexture(source, path, aiTextureType_EMISSIVE);
                    float[] strength = {1f};
                    max[0] = 1;
                    aiGetMaterialFloatArray(source, AI_MATKEY_EMISSIVE_INTENSITY, 0, 0, strength, max);
                    if (aiGetMaterialColor(source, AI_MATKEY_COLOR_EMISSIVE, 0, 0, color) == aiReturn_SUCCESS) {
                        material.setEmissive(color.r() * strength[0], color.g() * strength[0], color.b() * strength[0]);
                    }
                    if (emissive != null) material.setEmissiveTexture(emissive);
                }
            }
            return material;
        }

        /**
         * Gives a material the texture transform and texture coordinate sets its glTF file says.
         */
        void place(Material material, GltfTextureInfo.Material info) {
            float[] t = info.transform();
            if (t != null) material.setUvTransform(t[0], t[1], t[2], t[3], t[4]);
            if (info.mixedTransforms()) {
                log.warn("{} gives the textures of one material different transforms; every texture of it gets the first.", name);
            }
            Material.Slot[] slots = Material.Slot.values();
            // A glTF material has no height map, so it names a set for the slots before it only.
            for (int slot = 0; slot < slots.length && slot < info.texCoords().length; slot++) material.setTexCoordSet(slots[slot], info.texCoords()[slot]);
        }

        /**
         * The first texture found among the types, or null.
         */
        TextureData firstTexture(AIMaterial source, AIString path, int... types) {
            for (int type : types) {
                if (aiGetMaterialTexture(source, type, 0, path, (int[]) null, null, null, null, null, null) == aiReturn_SUCCESS) {
                    TextureData texture = loadTexture(path.dataString());
                    if (texture != null) return texture;
                }
            }
            return null;
        }

        TextureData loadTexture(String reference) {
            if (textures.containsKey(reference)) return textures.get(reference);
            TextureData texture = null;
            try {
                AITexture embedded = aiGetEmbeddedTexture(scene, reference);
                if (embedded != null) {
                    texture = decodeEmbedded(embedded, reference);
                } else if (directory != null) {
                    Path file = directory.resolve(URLDecoder.decode(reference.replace('\\', '/').replace("+", "%2B"), StandardCharsets.UTF_8));
                    if (Files.exists(file)) texture = TextureData.load(file);
                    else log.warn("{} uses the texture {}, which isn't beside it; drawing without it.", name, reference);
                } else {
                    log.warn("{} uses the texture {}, which isn't inside it; drawing without it.", name, reference);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Could not load the texture {} of {}: {}", reference, name, e.toString());
            }
            textures.put(reference, texture);
            return texture;
        }

        TextureData decodeEmbedded(AITexture embedded, String reference) throws IOException {
            if (embedded.mHeight() == 0) { // a whole PNG or JPEG file, mWidth bytes long
                ByteBuffer file = embedded.pcDataCompressed();
                byte[] bytes = new byte[file.remaining()];
                file.duplicate().get(bytes);
                return TextureData.decode(bytes, reference);
            }
            int width = embedded.mWidth(), height = embedded.mHeight();
            AITexel.Buffer texels = embedded.pcData();
            byte[] rgba = new byte[width * height * 4];
            for (int i = 0; i < width * height; i++) {
                AITexel t = texels.get(i);
                rgba[i * 4] = t.r();
                rgba[i * 4 + 1] = t.g();
                rgba[i * 4 + 2] = t.b();
                rgba[i * 4 + 3] = t.a();
            }
            return new TextureData(width, height, rgba);
        }

        static float toScreen(float linear) {
            return (float) Math.pow(Math.max(0f, linear), 1.0 / 2.2);
        }
    }
}
