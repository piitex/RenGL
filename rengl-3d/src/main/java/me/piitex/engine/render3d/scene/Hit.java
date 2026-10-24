package me.piitex.engine.render3d.scene;

import me.piitex.engine.math.Vec2;
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
 * @param uv       the mesh's texture coordinates at the hit, blended between the triangle's three corners: the spot
 *                 on a texture (top-left origin, 0 to 1 across the image) that was clicked. It is the raw coordinate
 *                 of the mesh, before the material's {@link Material#setUvTransform UV transform}. Null when the
 *                 triangle has no area. A mesh without texture coordinates gives (0, 0)
 */
public record Hit(Node3D node, float distance, Vec3 point, Vec3 normal, int triangle, int instance, Vec2 uv) {
    public Hit(Node3D node, float distance, Vec3 point, Vec3 normal, int triangle, int instance) {
        this(node, distance, point, normal, triangle, instance, null);
    }

    public Hit(Node3D node, float distance, Vec3 point, Vec3 normal, int triangle) {
        this(node, distance, point, normal, triangle, -1, null);
    }
}
