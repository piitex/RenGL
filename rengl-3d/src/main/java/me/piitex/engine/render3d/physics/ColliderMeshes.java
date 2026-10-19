package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.TextureData;

import java.util.Arrays;

/**
 * Meshes of collision shapes for {@link ColliderView}, drawn as faint see-through surfaces; the outlines are
 * {@linkplain ColliderLines real lines}. The texture coordinates still run to whole numbers along each face, a grid
 * the {@linkplain Pattern pattern} of a shape picks (squares for boxes, balls and planes; triangles for hulls and meshes),
 * though the texture is plain now.
 * <p>
 * Each mesh is grown a few millimetres out from the shape, so it doesn't flicker against a visible mesh of the same size.
 */
final class ColliderMeshes {
    /** How far the meshes sit outside the shapes, in metres. */
    static final float SKIN = 0.004f;
    /** About how far apart the lines across a face are, in metres. */
    private static final float CELL = 1f, TRIANGLE_CELL = 2f;
    private static final int PIXELS = 128;
    /** How clear the fill is. */
    private static final float FILL = 0.12f;
    private static final int SPHERE_SEGMENTS = 16, SPHERE_RINGS = 8;
    private static final float PLANE_SIZE = 100f, PLANE_CELL = 2f;

    enum Pattern {
        SQUARES, TRIANGLES;

        private TextureData texture;

        /** White lines on a faint white fill, to be tinted by the material's colour. */
        synchronized TextureData texture() {
            if (texture == null) texture = draw(this == TRIANGLES);
            return texture;
        }
    }

    record Built(Mesh mesh, Pattern pattern) {
    }

    private float[] positions = new float[3 * 512], normals = new float[3 * 512], uvs = new float[2 * 512];
    private int vertices, capacity = 512;

    private ColliderMeshes() {
    }

    /**
     * A faint white fill, to be tinted by the material's colour. The outlines are real lines, drawn by
     * {@link ColliderLines}, so the texture has none of its own.
     */
    private static TextureData draw(boolean diagonals) {
        byte[] rgba = new byte[PIXELS * PIXELS * 4];
        for (int i = 0; i < PIXELS * PIXELS; i++) {
            rgba[i * 4] = rgba[i * 4 + 1] = rgba[i * 4 + 2] = (byte) 255;
            rgba[i * 4 + 3] = (byte) Math.round(FILL * 255f);
        }
        return new TextureData(PIXELS, PIXELS, rgba);
    }

    /**
     * The mesh of {@code shape}, in the shape's own space, in metres, and the texture pattern it is drawn with.
     */
    static Built of(Shape shape) {
        ColliderMeshes b = new ColliderMeshes();
        Pattern pattern = switch (shape) {
            case BoxShape box -> {
                b.box(box.halfExtents().add(new Vec3(SKIN, SKIN, SKIN)));
                yield Pattern.SQUARES;
            }
            case SphereShape sphere -> {
                b.capsule(sphere.radius(), 0f);
                yield Pattern.SQUARES;
            }
            case CapsuleShape capsule -> {
                b.capsule(capsule.radius(), capsule.halfHeight());
                yield Pattern.SQUARES;
            }
            case ConvexHullShape hull -> {
                b.hull(hull.polytope());
                yield Pattern.TRIANGLES;
            }
            case MeshShape mesh -> {
                b.mesh(mesh);
                yield Pattern.TRIANGLES;
            }
            case PlaneShape plane -> {
                b.plane(plane.normal());
                yield Pattern.SQUARES;
            }
        };
        int[] indices = new int[b.vertices];
        for (int i = 0; i < b.vertices; i++) indices[i] = i;
        Mesh mesh = new Mesh(Arrays.copyOf(b.positions, b.vertices * 3), Arrays.copyOf(b.normals, b.vertices * 3),
                Arrays.copyOf(b.uvs, b.vertices * 2), indices);
        return new Built(mesh, pattern);
    }

    // ---- Shapes -----------------------------------------------------------------------------------------------------

    private void box(Vec3 h) {
        float x = h.x(), y = h.y(), z = h.z();
        Vec3[] c = new Vec3[8];
        for (int i = 0; i < 8; i++) c[i] = new Vec3((i & 1) == 0 ? -x : x, (i & 2) == 0 ? -y : y, (i & 4) == 0 ? -z : z);
        quad(c[1], c[3], c[7], c[5], Vec3.X, CELL);
        quad(c[4], c[6], c[2], c[0], new Vec3(-1, 0, 0), CELL);
        quad(c[2], c[6], c[7], c[3], Vec3.UP, CELL);
        quad(c[0], c[1], c[5], c[4], new Vec3(0, -1, 0), CELL);
        quad(c[4], c[5], c[7], c[6], Vec3.Z, CELL);
        quad(c[0], c[2], c[3], c[1], new Vec3(0, 0, -1), CELL);
    }

    /**
     * A capsule around the Y axis, as rings of latitude and lines of longitude; a sphere is a capsule of no height.
     */
    private void capsule(float radius, float halfHeight) {
        int half = SPHERE_RINGS / 2;
        // Flat faces cut inside a round surface, the more the fewer there are; these are pushed out until their middles
        // are on it, so they hide nothing of a finer mesh drawn on the same sphere.
        float r = (radius + SKIN) / (float) (Math.cos(Math.PI / SPHERE_SEGMENTS) * Math.cos(Math.PI / 4 / half));
        // The rings from the top pole down: the top half of a sphere raised by halfHeight, then the bottom half lowered.
        int count = 2 * half + 2;
        float[] ringY = new float[count], ringR = new float[count];
        for (int i = 0; i <= half; i++) {
            double phi = Math.PI / 2 * i / half;
            ringY[i] = (float) Math.cos(phi) * r + halfHeight;
            ringR[i] = (float) Math.sin(phi) * r;
            ringY[count - 1 - i] = -ringY[i];
            ringR[count - 1 - i] = ringR[i];
        }
        Vec3[][] rings = new Vec3[count][SPHERE_SEGMENTS];
        for (int i = 0; i < count; i++) {
            for (int s = 0; s < SPHERE_SEGMENTS; s++) {
                double theta = 2 * Math.PI * s / SPHERE_SEGMENTS;
                rings[i][s] = new Vec3((float) Math.cos(theta) * ringR[i], ringY[i], (float) -Math.sin(theta) * ringR[i]);
            }
        }
        for (int i = 0; i + 1 < count; i++) {
            if (halfHeight == 0f && i == half) continue; // a sphere's two equators are one
            for (int s = 0; s < SPHERE_SEGMENTS; s++) {
                int t = (s + 1) % SPHERE_SEGMENTS;
                Vec3 a = rings[i][s], b = rings[i + 1][s], c = rings[i + 1][t], d = rings[i][t];
                Vec3 n = a.add(b).add(c).add(d).mul(0.25f);
                n = new Vec3(n.x(), n.y() - Math.copySign(Math.min(Math.abs(n.y()), halfHeight), n.y()), n.z()).normalized();
                // A pole's ring is a point. Its triangles get the latitude line and one line of longitude, with the
                // next triangle drawing the other.
                if (i == 0) {
                    emit(a, b, c, n, 0f, 0f, 0f, 1f, 1f, 1f);
                } else if (i + 1 == count - 1) {
                    emit(a, b, d, n, 0f, 0f, 0f, 1f, 1f, 0f);
                } else {
                    float along = i == half ? Math.max(1f, Math.round(2f * halfHeight / CELL)) : 1f; // the straight side
                    emit(a, b, c, n, 0f, 0f, 0f, along, 1f, along);
                    emit(a, c, d, n, 0f, 0f, 1f, along, 1f, 0f);
                }
            }
        }
    }

    private void hull(Polytope hull) {
        Vec3 middle = Vec3.ZERO;
        for (Vec3 v : hull.vertices) middle = middle.add(v);
        middle = middle.mul(1f / hull.vertices.length);
        Vec3[] grown = new Vec3[hull.vertices.length];
        for (int i = 0; i < grown.length; i++) {
            Vec3 out = hull.vertices[i].sub(middle);
            float length = out.length();
            grown[i] = length > 1e-6f ? hull.vertices[i].add(out.mul(SKIN / length)) : hull.vertices[i];
        }
        for (int f = 0; f < hull.faces.length; f++) {
            int[] loop = hull.faces[f];
            for (int i = 1; i + 1 < loop.length; i++) triangle(grown[loop[0]], grown[loop[i]], grown[loop[i + 1]], hull.normals[f]);
        }
    }

    private void mesh(MeshShape mesh) {
        for (int t = 0; t < mesh.triangleCount(); t++) {
            Vec3[] corners = mesh.triangle(t);
            Vec3 n = mesh.triangleNormal(t), lift = n.mul(SKIN);
            triangle(corners[0].add(lift), corners[1].add(lift), corners[2].add(lift), n);
        }
    }

    /**
     * An endless plane, as a square of grid around the shape's origin.
     */
    private void plane(Vec3 normal) {
        Vec3 n = normal.normalized();
        Vec3[] side = Joint.perpendiculars(n);
        Vec3 u = side[0].mul(PLANE_SIZE / 2f), v = side[1].mul(PLANE_SIZE / 2f), lift = n.mul(SKIN);
        quad(lift.sub(u).sub(v), lift.add(u).sub(v), lift.add(u).add(v), lift.sub(u).add(v), n, PLANE_CELL);
    }

    // ---- Faces ------------------------------------------------------------------------------------------------------

    /**
     * Flat four-sided face a, b, c, d, with lines along its edges and about every {@code cell} metres across it.
     */
    private void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, Vec3 n, float cell) {
        float across = Math.max(1f, Math.round(a.distance(b) / cell)), up = Math.max(1f, Math.round(a.distance(d) / cell));
        emit(a, b, c, n, 0f, 0f, across, 0f, across, up);
        emit(a, c, d, n, 0f, 0f, across, up, 0f, up);
    }

    /**
     * Triangle a, b, c with lines along its edges, and across it if it is big: its corners take texture coordinates
     * (0, 0), (k, 0) and (0, k), so its edges lie on lines of the triangle pattern.
     */
    private void triangle(Vec3 a, Vec3 b, Vec3 c, Vec3 n) {
        float longest = Math.max(a.distance(b), Math.max(b.distance(c), c.distance(a)));
        float k = Math.max(1f, Math.round(longest / TRIANGLE_CELL));
        emit(a, b, c, n, 0f, 0f, k, 0f, 0f, k);
    }

    private void emit(Vec3 a, Vec3 b, Vec3 c, Vec3 n, float ua, float va, float ub, float vb, float uc, float vc) {
        if (vertices + 3 > capacity) {
            capacity *= 2;
            positions = Arrays.copyOf(positions, capacity * 3);
            normals = Arrays.copyOf(normals, capacity * 3);
            uvs = Arrays.copyOf(uvs, capacity * 2);
        }
        put(a, n, ua, va);
        put(b, n, ub, vb);
        put(c, n, uc, vc);
    }

    private void put(Vec3 p, Vec3 n, float u, float v) {
        int i = vertices * 3;
        positions[i] = p.x();
        positions[i + 1] = p.y();
        positions[i + 2] = p.z();
        normals[i] = n.x();
        normals[i + 1] = n.y();
        normals[i + 2] = n.z();
        uvs[vertices * 2] = u;
        uvs[vertices * 2 + 1] = v;
        vertices++;
    }
}
