package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Contacts between a {@link MeshShape} and another shape, found through the mesh's tree. Each triangle the shape
 * touches is tested on its own; then the triangles facing the same way are merged into one contact, so a box on a floor
 * of many triangles gets four points, as on one big face, instead of a few from every triangle under it. The work
 * happens in the mesh's own space, so its triangles are never moved.
 */
final class MeshCollision {
    /**
     * Triangle contacts whose normals are closer than this (about 2 degrees) merge into one.
     */
    private static final float SAME_NORMAL = 0.9995f;

    private static final class Group {
        final Vec3 normal;
        int triangle;
        final Manifold manifold = new Manifold();

        Group(Vec3 normal, int triangle) {
            this.normal = normal;
            this.triangle = triangle;
        }
    }

    private MeshCollision() {
    }

    /**
     * Receives one contact: the triangles facing one way, named by the lowest numbered of them.
     */
    interface TriangleContact {
        /**
         * @return false to stop at this contact
         */
        boolean accept(int triangle, Manifold manifold);
    }

    /**
     * Finds the triangles of {@code mesh} touching {@code other}. The manifold's normal points from the first shape to
     * the second, so from the mesh when {@code meshFirst}.
     */
    static void collide(MeshShape mesh, Pose meshPose, Shape other, Pose otherPose, float margin, boolean meshFirst, TriangleContact visit) {
        Pose local = new Pose(meshPose.toLocal(otherPose.position()), meshPose.rotation().conjugate().mul(otherPose.rotation()));
        Polytope solid;
        Vec3 p0 = local.position(), p1 = p0;
        float radius = 0f;
        switch (other) {
            case SphereShape s -> {
                solid = null;
                radius = s.radius();
            }
            case CapsuleShape c -> {
                solid = null;
                radius = c.radius();
                Vec3 up = local.axis(1).mul(c.halfHeight());
                p0 = p0.sub(up);
                p1 = p1.add(up);
            }
            case BoxShape b -> solid = Polytope.box(b.halfExtents()).placed(local);
            case ConvexHullShape h -> solid = h.polytope().placed(local);
            case PlaneShape _, MeshShape _ -> {
                return; // neither moves, so they never meet
            }
        }
        Bounds box = RigidBody.worldBounds(other, local, margin);
        Manifold manifold = new Manifold();
        Vec3 c0 = p0, c1 = p1;
        float r = radius;
        List<Group> groups = new ArrayList<>();
        mesh.tree().overlapping(box.min().x(), box.min().y(), box.min().z(), box.max().x(), box.max().y(), box.max().z(), t -> {
            Vec3 normal = mesh.triangleNormal(t);
            Polytope triangle = Polytope.triangle(mesh.corner(t, 0), mesh.corner(t, 1), mesh.corner(t, 2), normal);
            PolytopeCollision.MeshTriangle info = new PolytopeCollision.MeshTriangle(normal, triangle.vertices[0], mesh.realEdges(t));
            manifold.reset();
            if (solid == null) PolytopeCollision.rounded(c0, c1, r, triangle, margin, manifold, info);
            else PolytopeCollision.polytopes(solid, triangle, margin, manifold, info);
            manifold.reduce();
            if (manifold.count == 0) return;
            Group group = null;
            for (int g = 0; g < groups.size() && group == null; g++) if (groups.get(g).normal.dot(manifold.normal) > SAME_NORMAL) group = groups.get(g);
            if (group == null) {
                group = new Group(manifold.normal, t);
                groups.add(group);
            }
            group.triangle = Math.min(group.triangle, t);
            for (int i = 0; i < manifold.count; i++) {
                if (group.manifold.full()) {
                    group.manifold.normal = group.normal;
                    group.manifold.reduce();
                }
                group.manifold.add(manifold.points[i], manifold.depths[i]);
            }
        });
        for (Group group : groups) {
            Manifold m = group.manifold;
            m.normal = group.normal;
            m.reduce();
            m.normal = meshPose.rotate(meshFirst ? m.normal.negate() : m.normal);
            for (int i = 0; i < m.count; i++) m.points[i] = meshPose.toWorld(m.points[i]);
            if (!visit.accept(group.triangle, m)) return;
        }
    }
}
