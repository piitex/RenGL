package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.RayGeometry;
import me.piitex.engine.render3d.RayInstance;
import me.piitex.engine.render3d.math.Mat4;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A scene's meshes as the structures rays are traced through: one {@link RayGeometry} for every mesh that casts shadows,
 * built the first time it is seen and freed some time after the scene stops using it, and the placement of each node (and
 * of each of its instances) in the world, collected afresh every frame. Render thread only.
 * <p>
 * Only what a ray can hit as a solid is in: nodes that cast shadows, opaque, drawn as triangles. A skinned or blend-shaped
 * node (its shape is not the mesh's), a see-through one and a mesh with an alpha cutoff (a ray cannot tell its holes yet) cast
 * no ray traced shadow; they still receive them.
 */
final class RayWorld {
    private static final Logger log = LogManager.getLogger("Engine");

    private static final class Entry {
        final RayGeometry geometry;
        long lastUsed;

        Entry(RayGeometry geometry) {
            this.geometry = geometry;
        }
    }

    private final GpuDevice device;
    private final Map<Mesh, Entry> geometries = new IdentityHashMap<>();
    private final java.util.Set<Mesh> broken = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private long frame;

    RayWorld(GpuDevice device) {
        this.device = device;
    }

    /**
     * Adds the placement of every node of the tree that rays can hit.
     */
    void collect(Node3D root, List<RayInstance> into) {
        frame++;
        collect(root, Mat4.identity(), into);
    }

    private void collect(Node3D node, Mat4 parentWorld, List<RayInstance> into) {
        if (!node.isVisible()) return;
        Mat4 world = parentWorld.mul(node.localMatrix());
        Mesh mesh = node.getMesh();
        if (mesh != null && solid(node, mesh)) {
            RayGeometry geometry = geometry(mesh);
            if (geometry != null) {
                Instances instances = node.getInstances();
                float[] w = world.toArray();
                if (instances == null) {
                    into.add(new RayInstance(geometry, w));
                } else {
                    float[] data = instances.matrixData();
                    for (int i = 0; i < instances.size(); i++) {
                        float[] model = new float[16];
                        multiply(w, data, i * 16, model);
                        into.add(new RayInstance(geometry, model));
                    }
                }
            }
        }
        for (Node3D child : node.getChildren()) collect(child, world, into);
    }

    private static boolean solid(Node3D node, Mesh mesh) {
        if (!node.isCastShadow() || mesh.isLines() || node.getSkin() != null || node.activeMorph() != null) return false;
        Material material = node.getMaterial();
        return material == null || (!material.isBlend() && material.getAlphaCutoff() <= 0f);
    }

    private RayGeometry geometry(Mesh mesh) {
        Entry entry = geometries.get(mesh);
        if (entry == null) {
            if (broken.contains(mesh) || mesh.indices().length < 3) return null;
            try {
                entry = new Entry(device.createRayGeometry(mesh.vertices(), mesh.vertexCount(), Mesh.FLOATS_PER_VERTEX, mesh.indices()));
            } catch (RuntimeException e) {
                broken.add(mesh);
                log.error("A mesh could not be built for ray tracing; it casts no ray traced shadow.", e);
                return null;
            }
            geometries.put(mesh, entry);
        }
        entry.lastUsed = frame;
        return entry.geometry;
    }

    /**
     * Frees the geometry of meshes that have not been collected for {@code keep} frames.
     */
    void sweep(long keep) {
        for (Iterator<Entry> it = geometries.values().iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (frame - entry.lastUsed > keep) {
                entry.geometry.close();
                it.remove();
            }
        }
        broken.clear();
    }

    void close() {
        for (Entry entry : geometries.values()) entry.geometry.close();
        geometries.clear();
    }

    /** out = a * b[offset..offset+16), all column-major. */
    private static void multiply(float[] a, float[] b, int offset, float[] out) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                out[c * 4 + r] = a[r] * b[offset + c * 4] + a[4 + r] * b[offset + c * 4 + 1]
                        + a[8 + r] * b[offset + c * 4 + 2] + a[12 + r] * b[offset + c * 4 + 3];
            }
        }
    }
}
