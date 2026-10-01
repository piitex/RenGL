package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Vec3;

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
    private final Bounds bounds;

    /**
     * @param positions x, y, z per vertex
     * @param normals   x, y, z per vertex, or null to compute smooth normals from the triangles
     * @param uvs       u, v per vertex, or null for all zeros
     * @param indices   three per triangle
     */
    public Mesh(float[] positions, float[] normals, float[] uvs, int[] indices) {
        if (positions.length == 0 || positions.length % 3 != 0) throw new IllegalArgumentException("positions must hold x, y, z per vertex");
        int count = positions.length / 3;
        if (normals != null && normals.length != positions.length) throw new IllegalArgumentException("normals must match positions");
        if (uvs != null && uvs.length != count * 2) throw new IllegalArgumentException("uvs must hold u, v per vertex");
        if (indices.length == 0 || indices.length % 3 != 0) throw new IllegalArgumentException("indices must hold three per triangle");
        for (int index : indices) {
            if (index < 0 || index >= count) throw new IllegalArgumentException("index " + index + " is outside the " + count + " vertices");
        }

        float[] n = normals != null ? normals : smoothNormals(positions, indices);
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
        this.bounds = new Bounds(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ));
    }

    /**
     * Interleaved vertices, {@link #FLOATS_PER_VERTEX} floats each. The array is the mesh's own: do not change it.
     */
    public float[] vertices() {
        return vertices;
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
     * The box around the mesh in its own space.
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
