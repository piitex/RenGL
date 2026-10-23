package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The smallest convex solid around a set of points, as if shrink-wrapped: the shape for a dynamic body that isn't a
 * box, ball or capsule, such as a rock, a crate with bevelled corners or a car body. Build it from the points, or from a
 * mesh with {@link #of(Mesh)}; dents and holes are filled in. Points inside are dropped, so the hull keeps only its
 * corners.
 * <p>
 * Unlike the other shapes a hull isn't centred on the body's origin: it stays where its points are, so a hull made from
 * a mesh lines up with that mesh. Its mass is spread through its volume, and {@link #centerOfMass()} says where its
 * middle is. Every corner costs a little in each contact test, so a hull of a few dozen corners is quicker than one of
 * hundreds; a low-detail version of the model makes a better hull than the model itself.
 */
public final class ConvexHullShape implements Shape {
    private final Polytope hull;
    private final float volume;
    private final Vec3 center;
    private final Mat3 unitInertia; // around the centre of mass, for 1 kg
    private final Bounds bounds;
    private Vec3 lastScale;
    private ConvexHullShape lastScaled;

    private ConvexHullShape(Polytope hull) {
        this.hull = hull;
        // Split the hull into tetrahedra from one inside point; each adds its volume, middle and spread.
        Vec3 ref = Vec3.ZERO;
        for (Vec3 v : hull.vertices) ref = ref.add(v);
        ref = ref.mul(1f / hull.vertices.length);
        double vol = 0, mx = 0, my = 0, mz = 0;
        double cxx = 0, cyy = 0, czz = 0, cxy = 0, cxz = 0, cyz = 0;
        for (int[] loop : hull.faces) {
            Vec3 a = hull.vertices[loop[0]].sub(ref);
            for (int i = 1; i + 1 < loop.length; i++) {
                Vec3 b = hull.vertices[loop[i]].sub(ref), c = hull.vertices[loop[i + 1]].sub(ref);
                double det = a.dot(b.cross(c)); // six times the tetrahedron's volume
                vol += det / 6.0;
                mx += det / 24.0 * (a.x() + b.x() + c.x());
                my += det / 24.0 * (a.y() + b.y() + c.y());
                mz += det / 24.0 * (a.z() + b.z() + c.z());
                // The second moments of a tetrahedron with a corner at the origin (Blow and Binstock).
                double k = det / 120.0;
                cxx += k * (a.x() * a.x() + b.x() * b.x() + c.x() * c.x() + sum(a.x(), b.x(), c.x()) * sum(a.x(), b.x(), c.x()));
                cyy += k * (a.y() * a.y() + b.y() * b.y() + c.y() * c.y() + sum(a.y(), b.y(), c.y()) * sum(a.y(), b.y(), c.y()));
                czz += k * (a.z() * a.z() + b.z() * b.z() + c.z() * c.z() + sum(a.z(), b.z(), c.z()) * sum(a.z(), b.z(), c.z()));
                cxy += k * (a.x() * a.y() + b.x() * b.y() + c.x() * c.y() + sum(a.x(), b.x(), c.x()) * sum(a.y(), b.y(), c.y()));
                cxz += k * (a.x() * a.z() + b.x() * b.z() + c.x() * c.z() + sum(a.x(), b.x(), c.x()) * sum(a.z(), b.z(), c.z()));
                cyz += k * (a.y() * a.z() + b.y() * b.z() + c.y() * c.z() + sum(a.y(), b.y(), c.y()) * sum(a.z(), b.z(), c.z()));
            }
        }
        if (!(vol > 1e-12)) throw new IllegalArgumentException("The hull has no volume");
        double gx = mx / vol, gy = my / vol, gz = mz / vol;
        // Move the second moments to the centre of mass, then turn them into an inertia tensor for 1 kg.
        cxx = (cxx - vol * gx * gx) / vol;
        cyy = (cyy - vol * gy * gy) / vol;
        czz = (czz - vol * gz * gz) / vol;
        cxy = (cxy - vol * gx * gy) / vol;
        cxz = (cxz - vol * gx * gz) / vol;
        cyz = (cyz - vol * gy * gz) / vol;
        double trace = cxx + cyy + czz;
        unitInertia = new Mat3(
                (float) (trace - cxx), (float) -cxy, (float) -cxz,
                (float) -cxy, (float) (trace - cyy), (float) -cyz,
                (float) -cxz, (float) -cyz, (float) (trace - czz));
        volume = (float) vol;
        center = ref.add(new Vec3((float) gx, (float) gy, (float) gz));
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (Vec3 v : hull.vertices) {
            minX = Math.min(minX, v.x());
            minY = Math.min(minY, v.y());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxY = Math.max(maxY, v.y());
            maxZ = Math.max(maxZ, v.z());
        }
        bounds = new Bounds(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ));
    }

    private static double sum(double a, double b, double c) {
        return a + b + c;
    }

    /**
     * The hull around {@code points}. They must not all lie in one plane.
     *
     * @throws IllegalArgumentException when the points are flat, a line or fewer than four
     */
    public static ConvexHullShape of(Vec3... points) {
        return new ConvexHullShape(QuickHull.build(points));
    }

    public static ConvexHullShape of(List<Vec3> points) {
        return of(points.toArray(Vec3[]::new));
    }

    /**
     * The hull around {@code xyz}, three floats per point.
     */
    public static ConvexHullShape of(float[] xyz) {
        if (xyz.length % 3 != 0) throw new IllegalArgumentException("The points must be x, y, z each");
        Vec3[] points = new Vec3[xyz.length / 3];
        for (int i = 0; i < points.length; i++) points[i] = new Vec3(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]);
        return of(points);
    }

    /**
     * The hull around a mesh's corners, in the mesh's own space. Give it to a body on a node showing that mesh and the
     * two line up, the node's scale included.
     */
    public static ConvexHullShape of(Mesh mesh) {
        float[] v = mesh.vertices();
        Vec3[] points = new Vec3[mesh.vertexCount()];
        for (int i = 0; i < points.length; i++) {
            int o = i * Mesh.FLOATS_PER_VERTEX;
            points[i] = new Vec3(v[o], v[o + 1], v[o + 2]);
        }
        return of(points);
    }

    /**
     * The hull around the meshes of {@code node} and everything under it, in {@code node}'s own space: one hull for a
     * model loaded as several parts. The node's own position, rotation and scale aren't applied, since the body on that
     * node applies them.
     */
    public static ConvexHullShape of(Node3D node) {
        List<Vec3> points = new ArrayList<>();
        MeshShape.collect(node, null, (positions, _) -> {
            for (int i = 0; i < positions.length; i += 3) points.add(new Vec3(positions[i], positions[i + 1], positions[i + 2]));
        });
        if (points.isEmpty()) throw new IllegalArgumentException(node + " has no meshes to make a hull from");
        return of(points);
    }

    /**
     * The hull's corners.
     */
    public List<Vec3> vertices() {
        return List.of(hull.vertices);
    }

    public int faceCount() {
        return hull.faces.length;
    }

    /**
     * A flat-shaded mesh of the hull's faces: to see a hull, or to draw an object made from points, such as a rock from
     * a random cloud, exactly as it collides.
     */
    public Mesh toMesh() {
        int corners = 0, triangles = 0;
        for (int[] loop : hull.faces) {
            corners += loop.length;
            triangles += loop.length - 2;
        }
        float[] positions = new float[corners * 3], normals = new float[corners * 3];
        int[] indices = new int[triangles * 3];
        int v = 0, k = 0;
        for (int f = 0; f < hull.faces.length; f++) {
            int[] loop = hull.faces[f];
            Vec3 n = hull.normals[f];
            for (int i = 0; i < loop.length; i++) {
                Vec3 p = hull.vertices[loop[i]];
                positions[(v + i) * 3] = p.x();
                positions[(v + i) * 3 + 1] = p.y();
                positions[(v + i) * 3 + 2] = p.z();
                normals[(v + i) * 3] = n.x();
                normals[(v + i) * 3 + 1] = n.y();
                normals[(v + i) * 3 + 2] = n.z();
            }
            for (int i = 1; i + 1 < loop.length; i++) {
                indices[k++] = v;
                indices[k++] = v + i;
                indices[k++] = v + i + 1;
            }
            v += loop.length;
        }
        return new Mesh(positions, normals, null, indices);
    }

    @Override
    public float volume() {
        return volume;
    }

    /**
     * The moments of inertia around X, Y and Z axes through {@link #centerOfMass()}, for 1 kg. A lopsided hull also has
     * products of inertia, which the body takes into account.
     */
    @Override
    public Vec3 unitInertia() {
        return unitInertia.diagonal();
    }

    @Override
    public Vec3 centerOfMass() {
        return center;
    }

    @Override
    public Bounds bounds() {
        return bounds;
    }

    /**
     * The hull with each corner stretched by {@code scale}. A hull stretches exactly, whatever the scale.
     */
    @Override
    public ConvexHullShape scaled(Vec3 scale) {
        if (scale.equals(Vec3.ONE)) return this;
        if (scale.equals(lastScale)) return lastScaled;
        Vec3[] points = new Vec3[hull.vertices.length];
        for (int i = 0; i < points.length; i++) points[i] = hull.vertices[i].mul(scale);
        ConvexHullShape scaled = of(points);
        lastScale = scale;
        lastScaled = scaled;
        return scaled;
    }

    Polytope polytope() {
        return hull;
    }

    Mat3 unitInertiaTensor() {
        return unitInertia;
    }

    @Override
    public String toString() {
        return "ConvexHullShape[" + hull.vertices.length + " corners, " + hull.faces.length + " faces, volume " + volume + "]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ConvexHullShape other && (other == this || Arrays.equals(hull.vertices, other.hull.vertices));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hull.vertices);
    }
}
