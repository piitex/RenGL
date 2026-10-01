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
 * texture, transparency, two-sided surfaces. Textures come from beside the file, or from inside it for .glb and
 * embedded images. What doesn't: skeletons, animations, normal, metallic-roughness and other maps, lights and cameras
 * from the file. The model's own units and up axis are kept (glTF is Y-up, which is what the scene uses).
 * <p>
 * Loading reads files and decodes images but never touches the GPU, so it can run on a background thread. The mesh and
 * textures are uploaded the first time the scene draws them.
 */
public final class Model {
    private static final Logger log = LogManager.getLogger("Engine");

    private final Node3D root;
    private final List<Mesh> meshes;
    private final List<Material> materials;

    private Model(Node3D root, List<Mesh> meshes, List<Material> materials) {
        this.root = root;
        this.meshes = meshes;
        this.materials = materials;
    }

    public Node3D root() {
        return root;
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
        if (!Files.isRegularFile(file)) throw new IOException("No model file at " + file);
        // From the path, not from memory, so Assimp's own file access finds the .mtl, .bin and texture files beside it.
        AIScene scene = aiImportFile(file.toAbsolutePath().toString(), IMPORT_FLAGS);
        return convert(scene, file.getFileName().toString(), file.toAbsolutePath().getParent());
    }

    /**
     * Loads a model from memory. This works for self-contained files, such as .glb and .gltf with embedded data, and
     * not for ones that point at other files. Handy for a model bundled in the jar.
     *
     * @param name the file name, whose ending tells Assimp the format, such as {@code "ship.glb"}
     */
    public static Model load(String name, byte[] data) throws IOException {
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        ByteBuffer buffer = MemoryUtil.memAlloc(data.length);
        AIScene scene;
        try {
            buffer.put(data).flip();
            scene = aiImportFileFromMemory(buffer, IMPORT_FLAGS, ext);
        } finally {
            MemoryUtil.memFree(buffer);
        }
        return convert(scene, name, null);
    }

    private static final int IMPORT_FLAGS = aiProcess_Triangulate | aiProcess_JoinIdenticalVertices | aiProcess_GenSmoothNormals
            | aiProcess_GenUVCoords | aiProcess_SortByPType | aiProcess_ImproveCacheLocality
            // Assimp hands out OpenGL-style texture coordinates for every format; undo that for top-first images.
            | aiProcess_FlipUVs;

    private static Model convert(AIScene scene, String name, Path directory) throws IOException {
        if (scene == null || scene.mRootNode() == null) {
            throw new IOException("Could not load the model " + name + ": " + aiGetErrorString());
        }
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            return new Converter(scene, name, directory, lower.endsWith(".gltf") || lower.endsWith(".glb")).convert();
        } finally {
            aiReleaseImport(scene);
        }
    }

    private static final class Converter {
        final AIScene scene;
        final String name;
        final Path directory;
        final boolean linearColors;
        final Map<String, TextureData> textures = new HashMap<>();
        final List<Mesh> meshes = new ArrayList<>();
        final List<Material> materials = new ArrayList<>();
        Mesh[] meshByIndex;
        Material[] materialByIndex;

        Converter(AIScene scene, String name, Path directory, boolean linearColors) {
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
                materials.add(materialByIndex[i]);
            }

            int meshCount = scene.mNumMeshes();
            meshByIndex = new Mesh[meshCount];
            PointerBuffer meshPointers = scene.mMeshes();
            for (int i = 0; i < meshCount; i++) {
                meshByIndex[i] = convertMesh(AIMesh.create(meshPointers.get(i)));
                if (meshByIndex[i] != null) meshes.add(meshByIndex[i]);
            }
            if (scene.mNumAnimations() > 0) log.info("{} has {} animations; the 3D layer doesn't play them yet.", name, scene.mNumAnimations());

            return new Model(convertNode(scene.mRootNode()), meshes, materials);
        }

        Node3D convertNode(AINode source) {
            Node3D node = new Node3D(source.mName().dataString());
            AIMatrix4x4 t = source.mTransformation();
            // Assimp's matrices are row-major with the translation in the last column; Mat4 is column-major.
            node.setLocalMatrix(new Mat4(new float[]{
                    t.a1(), t.b1(), t.c1(), t.d1(),
                    t.a2(), t.b2(), t.c2(), t.d2(),
                    t.a3(), t.b3(), t.c3(), t.d3(),
                    t.a4(), t.b4(), t.c4(), t.d4()}));

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
                    first = false;
                } else {
                    node.add(new Node3D(node.getName() + "#" + i, mesh, material));
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

            float[] positions = new float[vertexCount * 3];
            float[] normalArray = normals != null ? new float[vertexCount * 3] : null;
            float[] uvs = uvSource != null ? new float[vertexCount * 2] : null;
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
            return new Mesh(positions, normalArray, uvs, indices);
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
            }
            return material;
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
