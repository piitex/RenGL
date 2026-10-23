package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Vec3;

/**
 * Where a ray met a node's mesh, from {@link Scene3D#pick}.
 *
 * @param node     the node whose mesh was hit
 * @param distance how far along the ray, in units of its direction's length (scene units for a ray from
 *                 {@link Camera#screenRay})
 * @param point    the hit position in scene space
 * @param normal   the surface's unit normal in scene space, on the side the ray came from. It is the flat normal of
 *                 the triangle, not the smoothed one that lighting uses
 * @param triangle the index of the triangle in {@link Mesh#indices()}, counting three indices per triangle
 * @param instance which of the node's {@link Instances} was hit, or -1 for a node that is not instanced
 */
public record Hit(Node3D node, float distance, Vec3 point, Vec3 normal, int triangle, int instance) {
    public Hit(Node3D node, float distance, Vec3 point, Vec3 normal, int triangle) {
        this(node, distance, point, normal, triangle, -1);
    }
}
