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
 * Only what a ray can hit as a solid is in: opaque nodes drawn as triangles. A skinned or blend-shaped node (its shape is not
 * the mesh's), a see-through one and a mesh with an alpha cutoff (a ray cannot tell its holes yet) are left out; they still
 * receive shadows and reflections. Each placement carries a ray mask: {@link #SHADOWS} when the node casts shadows, and
 * {@link #SEEN} for every node, so a shadow ray and a reflection ray each find what they should.
 */
final class RayWorld {
    private static final Logger log = LogManager.getLogger("Engine");

    /** The mask bit of a node that casts shadows, the one shadow rays look for. */
    static final int SHADOWS = 1;
    /** The mask bit of every node, the one reflection and occlusion rays look for. */
    static final int SEEN = 2;
    private static final Material DEFAULT_MATERIAL = new Material();

    /**
     * Gives a material's base colour texture and its glow texture a place in the array a reflection ray reads, for a frame: the
     * place plus one, or 0 for a material with none (or when the array is full).
     */
    interface Textures {
        int slot(Material material);

        int glowSlot(Material material);

        /** For a frame whose rays read no textures. */
        Textures NONE = new Textures() {
            @Override
            public int slot(Material material) {
                return 0;
            }

            @Override
            public int glowSlot(Material material) {
                return 0;
            }
        };
    }

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
     * Adds the placement of every node of the tree that rays can hit. With {@code everything} that is every solid node, for
     * reflections and occlusion; without, only the ones that cast shadows. {@code textures} places each one's material in the
     * data a ray reads where it hits it.
     */
    void collect(Node3D root, List<RayInstance> into, boolean everything, Textures textures) {
        frame++;
        collect(root, Mat4.identity(), into, everything, textures);
    }

    private void collect(Node3D node, Mat4 parentWorld, List<RayInstance> into, boolean everything, Textures textures) {
        if (!node.isVisible()) return;
        Mat4 world = parentWorld.mul(node.localMatrix());
        Mesh mesh = node.getMesh();
        if (mesh != null && solid(node, mesh, everything)) {
            RayGeometry geometry = geometry(mesh);
            if (geometry != null) {
                Instances instances = node.getInstances();
                Material material = node.getMaterial() == null ? DEFAULT_MATERIAL : node.getMaterial();
                int mask = SEEN | (node.isCastShadow() ? SHADOWS : 0);
                int slot = textures.slot(material), glowSlot = textures.glowSlot(material);
                float[] w = world.toArray();
                if (instances == null) {
                    into.add(new RayInstance(geometry, w, mask, record(material, slot, glowSlot, 1f, 1f, 1f, 1f)));
                } else {
                    float[] data = instances.matrixData();
                    float[] tints = instances.hasTints() ? instances.tintData() : null;
                    for (int i = 0; i < instances.size(); i++) {
                        float[] model = new float[16];
                        multiply(w, data, i * 16, model);
                        into.add(new RayInstance(geometry, model, mask, tints == null ? record(material, slot, glowSlot, 1f, 1f, 1f, 1f)
                                : record(material, slot, glowSlot, tints[i * 4], tints[i * 4 + 1], tints[i * 4 + 2], tints[i * 4 + 3])));
                    }
                }
            }
        }
        for (Node3D child : node.getChildren()) collect(child, world, into, everything, textures);
    }

    private static boolean solid(Node3D node, Mesh mesh, boolean everything) {
        if ((!everything && !node.isCastShadow()) || mesh.isLines() || node.getSkin() != null || node.activeMorph() != null) return false;
        Material material = node.getMaterial();
        return material == null || (!material.isBlend() && material.getAlphaCutoff() <= 0f);
    }

    /**
     * What a ray that hit this placement reads, the way the scene shader's instance records hold it: the tint, the material's
     * two parameters with its mode and the place of its texture, the emissive colour with the place of its glow texture, and the
     * texture transform.
     */
    private static float[] record(Material m, int slot, int glowSlot, float tr, float tg, float tb, float ta) {
        int mode = m.isUnlit() ? SceneShaders.MODE_UNLIT : m.isPbr() ? SceneShaders.MODE_PBR : SceneShaders.MODE_CLASSIC;
        float p0 = m.isPbr() ? m.getMetallic() : m.getSpecular();
        float p1 = m.isPbr() ? m.getRoughness() : Math.max(1f, m.getShininess());
        return new float[]{
                m.getRed() * tr, m.getGreen() * tg, m.getBlue() * tb, m.getAlpha() * ta,
                p0, p1, mode, slot,
                m.getEmissiveRed(), m.getEmissiveGreen(), m.getEmissiveBlue(), glowSlot,
                m.getUvOffsetU(), m.getUvOffsetV(), m.getUvScaleU(), m.getUvScaleV(),
                (float) Math.cos(m.getUvRotation()), (float) Math.sin(m.getUvRotation()), 0f, 0f};
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
