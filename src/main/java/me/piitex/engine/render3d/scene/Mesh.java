package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Vec3;

import java.util.List;

/**
 * Triangle geometry held in ordinary memory: positions, normals and texture coordinates with 32-bit indices. A mesh
 * knows nothing about the GPU. The scene uploads it the first time it is drawn and keeps it there while it is in use,
 * so one mesh can be shared by any number of nodes, viewports and windows, and can be built on any thread.
 * <p>
 * Triangles that are counter-clockwise seen from outside are front faces. Texture coordinates have their origin at
 * the top-left of the image, like the image files do.
 */
public final class Mesh {
    /**
     * Floats per vertex in {@link #vertices()}: position (3), normal (3), uv (2).
     */
    public static final int FLOATS_PER_VERTEX = 8;

    private final float[] vertices;
    private final int[] indices;
    private volatile Bounds bounds;
    private final Bounds baseBounds;
    private int[] skinJoints;
    private float[] skinWeights;
    private float[] uvs2;
    private final boolean lines;
    private List<MorphTarget> morphTargets = List.of();
    private float[] morphDefaults = new float[0];
    private volatile Bvh bvh;

    /**
     * @param positions x, y, z per vertex
     * @param normals   x, y, z per vertex, or null to compute smooth normals from the triangles
     * @param uvs       u, v per vertex, or null for all zeros
     * @param indices   three per triangle
     */
    public Mesh(float[] positions, float[] normals, float[] uvs, int[] indices) {
        this(positions, normals, uvs, indices, false);
    }

    /**
     * Line segments instead of triangles, one pixel wide: an outline, a path, a grid. Each pair of indices is a segment.
     * A node with a line mesh is drawn unlit in its material's colour (give it {@code setUnlit(true)}), casts no shadow
     * and can't be picked.
     *
     * @param positions x, y, z per vertex
     * @param segments  two indices per line segment
     */
    public static Mesh lines(float[] positions, int[] segments) {
        return new Mesh(positions, null, null, segments, true);
    }

    private Mesh(float[] positions, float[] normals, float[] uvs, int[] indices, boolean lines) {
        this.lines = lines;
        if (positions.length == 0 || positions.length % 3 != 0) throw new IllegalArgumentException("positions must hold x, y, z per vertex");
        int count = positions.length / 3;
        if (normals != null && normals.length != positions.length) throw new IllegalArgumentException("normals must match positions");
        if (uvs != null && uvs.length != count * 2) throw new IllegalArgumentException("uvs must hold u, v per vertex");
        int per = lines ? 2 : 3;
        if (indices.length == 0 || indices.length % per != 0) throw new IllegalArgumentException("indices must hold " + (lines ? "two per line segment" : "three per triangle"));
        for (int index : indices) {
            if (index < 0 || index >= count) throw new IllegalArgumentException("index " + index + " is outside the " + count + " vertices");
        }

        float[] n = normals != null ? normals : lines ? new float[positions.length] : smoothNormals(positions, indices);
        vertices = new float[count * FLOATS_PER_VERTEX];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int o = i * FLOATS_PER_VERTEX;
            vertices[o] = positions[i * 3];
            vertices[o + 1] = positions[i * 3 + 1];
            vertices[o + 2] = positions[i * 3 + 2];
            vertices[o + 3] = n[i * 3];
            vertices[o + 4] = n[i * 3 + 1];
            vertices[o + 5] = n[i * 3 + 2];
            if (uvs != null) {
                vertices[o + 6] = uvs[i * 2];
                vertices[o + 7] = uvs[i * 2 + 1];
            }
            minX = Math.min(minX, vertices[o]);
            minY = Math.min(minY, vertices[o + 1]);
            minZ = Math.min(minZ, vertices[o + 2]);
            maxX = Math.max(maxX, vertices[o]);
            maxY = Math.max(maxY, vertices[o + 1]);
            maxZ = Math.max(maxZ, vertices[o + 2]);
        }
        this.indices = indices.clone();
        this.baseBounds = new Bounds(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ));
        this.bounds = baseBounds;
    }

    /**
     * Interleaved vertices, {@link #FLOATS_PER_VERTEX} floats each. The array is the mesh's own: do not change it.
     */
    public float[] vertices() {
        return vertices;
    }

    /**
     * Attaches skinning to the mesh: up to four joints per vertex, and how much of each one's movement the vertex takes.
     * A node with this mesh also needs a {@link Skin} ({@link Node3D#setSkin}), which says what the joint numbers are.
     * Model files with bones set this up themselves.
     *
     * @param joints  four joint numbers per vertex (index into the skin's joints); use 0 with weight 0 for unused slots
     * @param weights four weights per vertex, which should add up to 1
     */
    public Mesh setSkinning(int[] joints, float[] weights) {
        int count = vertexCount();
        if (joints.length != count * 4 || weights.length != count * 4) {
            throw new IllegalArgumentException("Skinning needs four joints and four weights per vertex (" + count + " vertices)");
        }
        this.skinJoints = joints.clone();
        this.skinWeights = weights.clone();
        return this;
    }

    public boolean isSkinned() {
        return skinJoints != null;
    }

    /**
     * Whether the mesh is {@linkplain #lines line segments} rather than triangles.
     */
    public boolean isLines() {
        return lines;
    }

    /**
     * A second set of texture coordinates, for a texture laid out apart from the colour texture (a baked light map or
     * occlusion map). Give a {@link Material} the textures that read it with {@link Material#setTexCoordSet}.
     *
     * @param uvs u, v per vertex, or null to take it away
     */
    public Mesh setUvs2(float[] uvs) {
        if (uvs != null && uvs.length != vertexCount() * 2) throw new IllegalArgumentException("uvs must hold u, v per vertex (" + vertexCount() + " vertices)");
        this.uvs2 = uvs == null ? null : uvs.clone();
        return this;
    }

    public boolean hasUvs2() {
        return uvs2 != null;
    }

    /**
     * The second set of texture coordinates, u and v per vertex, or null. Do not change the array.
     */
    public float[] uvs2() {
        return uvs2;
    }

    /**
     * A blend shape: how far every vertex moves (and, optionally, how its normal changes) when the shape is fully on.
     * A node blends any mix of a mesh's shapes with {@link Node3D#setMorphWeights}.
     *
     * @param name      what the file calls it, such as {@code "smile"}, or empty
     * @param positions x, y, z offset per vertex, in the mesh's space
     * @param normals   x, y, z change of the normal per vertex, or null to leave the normals alone
     */
    public record MorphTarget(String name, float[] positions, float[] normals) {
        public MorphTarget {
            positions = positions.clone();
            normals = normals == null ? null : normals.clone();
            name = name == null ? "" : name;
        }
    }

    /**
     * Gives the mesh blend shapes ("morph targets"), which a node mixes with {@link Node3D#setMorphWeights}: each weight
     * adds that many times its shape's offsets to the vertices. Faces, cloth and anything else a skeleton can't do well.
     * Model files with morph targets set this up themselves, and {@link AnimationClip.Path#WEIGHTS} animates the
     * weights. The vertices are blended on the CPU when the weights change, so keep the vertex count of a morphed mesh
     * moderate (tens of thousands is fine). Morphing runs before skinning.
     * <p>
     * The mesh's {@link #bounds()} grows to hold the shapes at weight 1 so culling and picking don't cut a morphed mesh
     * off.
     *
     * @param targets        one entry per shape, each as long as the vertex count times three
     * @param defaultWeights the weights a node starts with, or null for all zero
     */
    public Mesh setMorphTargets(List<MorphTarget> targets, float[] defaultWeights) {
        int count = vertexCount();
        for (MorphTarget t : targets) {
            if (t.positions().length != count * 3 || (t.normals() != null && t.normals().length != count * 3)) {
                throw new IllegalArgumentException("A morph target needs x, y, z per vertex (" + count + " vertices)");
            }
        }
        this.morphTargets = List.copyOf(targets);
        this.morphDefaults = new float[targets.size()];
        if (defaultWeights != null) System.arraycopy(defaultWeights, 0, morphDefaults, 0, Math.min(defaultWeights.length, morphDefaults.length));

        // Grow the box by the most any shape can add on each axis, so it holds the mesh at any weights up to 1.
        float[] grow = new float[3];
        for (MorphTarget t : targets) {
            float[] most = new float[3];
            for (int i = 0; i < count; i++) {
                for (int a = 0; a < 3; a++) most[a] = Math.max(most[a], Math.abs(t.positions()[i * 3 + a]));
            }
            for (int a = 0; a < 3; a++) grow[a] += most[a];
        }
        Vec3 g = new Vec3(grow[0], grow[1], grow[2]);
        bounds = new Bounds(baseBounds.min().sub(g), baseBounds.max().add(g));
        return this;
    }

    public boolean isMorphed() {
        return !morphTargets.isEmpty();
    }

    public List<MorphTarget> morphTargets() {
        return morphTargets;
    }

    /**
     * The weights a node starts with, one per target. The array is the mesh's own.
     */
    float[] morphDefaults() {
        return morphDefaults;
    }

    /**
     * A new array of interleaved vertices ({@link #FLOATS_PER_VERTEX} floats each) with the blend shapes mixed in at the
     * given weights; weights past the end count as 0. What the scene draws and picks for a morphed node.
     */
    public float[] morph(float[] weights) {
        float[] out = vertices.clone();
        boolean normalsChanged = false;
        for (int t = 0; t < morphTargets.size() && t < weights.length; t++) {
            float w = weights[t];
            if (w == 0f) continue;
            MorphTarget target = morphTargets.get(t);
            float[] p = target.positions(), n = target.normals();
            int count = vertexCount();
            for (int i = 0; i < count; i++) {
                int o = i * FLOATS_PER_VERTEX;
                out[o] += w * p[i * 3];
                out[o + 1] += w * p[i * 3 + 1];
                out[o + 2] += w * p[i * 3 + 2];
                if (n != null) {
                    out[o + 3] += w * n[i * 3];
                    out[o + 4] += w * n[i * 3 + 1];
                    out[o + 5] += w * n[i * 3 + 2];
                }
            }
            normalsChanged |= n != null;
        }
        if (normalsChanged) {
            for (int i = 0; i < vertexCount(); i++) {
                int o = i * FLOATS_PER_VERTEX;
                float len = (float) Math.sqrt(out[o + 3] * out[o + 3] + out[o + 4] * out[o + 4] + out[o + 5] * out[o + 5]);
                if (len > 1e-8f) {
                    out[o + 3] /= len;
                    out[o + 4] /= len;
                    out[o + 5] /= len;
                }
            }
        }
        return out;
    }

    /**
     * Four joint numbers per vertex, or null when the mesh is not skinned. The array is the mesh's own.
     */
    public int[] skinJoints() {
        return skinJoints;
    }

    /**
     * Four weights per vertex, or null when the mesh is not skinned. The array is the mesh's own.
     */
    public float[] skinWeights() {
        return skinWeights;
    }

    /**
     * The triangle search structure used for picking, built on first use.
     */
    Bvh bvh() {
        Bvh built = bvh;
        if (built == null) {
            synchronized (this) {
                built = bvh;
                if (built == null) bvh = built = new Bvh(vertices, indices, FLOATS_PER_VERTEX);
            }
        }
        return built;
    }

    /**
     * The triangle indices. The array is the mesh's own: do not change it.
     */
    public int[] indices() {
        return indices;
    }

    public int vertexCount() {
        return vertices.length / FLOATS_PER_VERTEX;
    }

    public int indexCount() {
        return indices.length;
    }

    /**
     * The box around the mesh in its own space, including everything its {@link #setMorphTargets morph targets} can
     * move it to at weight 1.
     */
    public Bounds bounds() {
        return bounds;
    }

    private static float[] smoothNormals(float[] p, int[] indices) {
        float[] n = new float[p.length];
        for (int t = 0; t < indices.length; t += 3) {
            int a = indices[t] * 3, b = indices[t + 1] * 3, c = indices[t + 2] * 3;
            Vec3 pa = new Vec3(p[a], p[a + 1], p[a + 2]);
            Vec3 pb = new Vec3(p[b], p[b + 1], p[b + 2]);
            Vec3 pc = new Vec3(p[c], p[c + 1], p[c + 2]);
            // Not normalized, so larger triangles weigh more.
            Vec3 face = pb.sub(pa).cross(pc.sub(pa));
            for (int v : new int[]{a, b, c}) {
                n[v] += face.x();
                n[v + 1] += face.y();
                n[v + 2] += face.z();
            }
        }
        for (int v = 0; v < n.length; v += 3) {
            Vec3 normal = new Vec3(n[v], n[v + 1], n[v + 2]).normalized();
            if (normal.equals(Vec3.ZERO)) normal = Vec3.UP;
            n[v] = normal.x();
            n[v + 1] = normal.y();
            n[v + 2] = normal.z();
        }
        return n;
    }

    /**
     * A cube centred on the origin with flat faces, each with the whole texture.
     */
    public static Mesh cube(float size) {
        float h = size * 0.5f;
        // Per face: normal, then the corners counter-clockwise seen from outside, starting bottom-left in the image.
        float[][] faces = {
                {0, 0, 1, -h, -h, h, h, -h, h, h, h, h, -h, h, h},
                {0, 0, -1, h, -h, -h, -h, -h, -h, -h, h, -h, h, h, -h},
                {1, 0, 0, h, -h, h, h, -h, -h, h, h, -h, h, h, h},
                {-1, 0, 0, -h, -h, -h, -h, -h, h, -h, h, h, -h, h, -h},
                {0, 1, 0, -h, h, h, h, h, h, h, h, -h, -h, h, -h},
                {0, -1, 0, -h, -h, -h, h, -h, -h, h, -h, h, -h, -h, h},
        };
        float[] uv = {0, 1, 1, 1, 1, 0, 0, 0};
        float[] positions = new float[24 * 3], normals = new float[24 * 3], uvs = new float[24 * 2];
        int[] indices = new int[36];
        for (int f = 0; f < 6; f++) {
            for (int v = 0; v < 4; v++) {
                int i = f * 4 + v;
                System.arraycopy(faces[f], 3 + v * 3, positions, i * 3, 3);
                System.arraycopy(faces[f], 0, normals, i * 3, 3);
                uvs[i * 2] = uv[v * 2];
                uvs[i * 2 + 1] = uv[v * 2 + 1];
            }
            int base = f * 4, o = f * 6;
            indices[o] = base;
            indices[o + 1] = base + 1;
            indices[o + 2] = base + 2;
            indices[o + 3] = base;
            indices[o + 4] = base + 2;
            indices[o + 5] = base + 3;
        }
        return new Mesh(positions, normals, uvs, indices);
    }

    /**
     * A sphere centred on the origin; the texture wraps around it like a world map.
     *
     * @param segments slices around the equator (at least 3)
     * @param rings    slices from pole to pole (at least 2)
     */
    public static Mesh sphere(float radius, int segments, int rings) {
        segments = Math.max(3, segments);
        rings = Math.max(2, rings);
        int columns = segments + 1;
        float[] positions = new float[(rings + 1) * columns * 3], normals = new float[positions.length];
        float[] uvs = new float[(rings + 1) * columns * 2];
        for (int r = 0; r <= rings; r++) {
            double phi = Math.PI * r / rings;
            for (int s = 0; s <= segments; s++) {
                double theta = 2 * Math.PI * s / segments;
                float nx = (float) (Math.sin(phi) * Math.cos(theta));
                float ny = (float) Math.cos(phi);
                float nz = (float) (Math.sin(phi) * Math.sin(theta));
                int i = r * columns + s;
                positions[i * 3] = nx * radius;
                positions[i * 3 + 1] = ny * radius;
                positions[i * 3 + 2] = nz * radius;
                normals[i * 3] = nx;
                normals[i * 3 + 1] = ny;
                normals[i * 3 + 2] = nz;
                uvs[i * 2] = (float) s / segments;
                uvs[i * 2 + 1] = (float) r / rings;
            }
        }
        int[] indices = new int[rings * segments * 6];
        int o = 0;
        for (int r = 0; r < rings; r++) {
            for (int s = 0; s < segments; s++) {
                int a = r * columns + s, b = a + columns;
                indices[o++] = a;
                indices[o++] = a + 1;
                indices[o++] = b;
                indices[o++] = a + 1;
                indices[o++] = b + 1;
                indices[o++] = b;
            }
        }
        return new Mesh(positions, normals, uvs, indices);
    }

    /**
     * A flat rectangle on the XZ plane facing +Y, centred on the origin. The texture's top edge is at -Z.
     */
    public static Mesh plane(float width, float depth) {
        float w = width * 0.5f, d = depth * 0.5f;
        return new Mesh(
                new float[]{-w, 0, d, w, 0, d, w, 0, -d, -w, 0, -d},
                new float[]{0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0},
                new float[]{0, 1, 1, 1, 1, 0, 0, 0},
                new int[]{0, 1, 2, 0, 2, 3});
    }
}
