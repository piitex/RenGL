package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;

import java.util.List;

/**
 * Turns the glowing surfaces of a scene ({@link Material#setGlow}) into point lights for one frame. A node with such a
 * material makes one light at the middle of its mesh, an instanced node one per instance. The colour is the material's
 * emissive colour; the scene's lights are in sRGB and scaled by their intensity, so the colour is split into a unit part
 * and a strength that give the emissive light back exactly.
 */
final class GlowLights {
    private GlowLights() {
    }

    /**
     * Adds the lights of the visible nodes under {@code node} to {@code out}, up to {@code limit} in all.
     *
     * @param parentWorld the world matrix of {@code node}'s parent
     * @return how many lights were added
     */
    static int collect(Node3D node, Mat4 parentWorld, List<Light> out, int limit) {
        if (!node.isVisible() || out.size() >= limit) return 0;
        Mat4 world = parentWorld.mul(node.localMatrix());
        int added = 0;
        Mesh mesh = node.getMesh();
        Material material = node.getMaterial();
        if (mesh != null && material != null && material.getGlowRange() > 0f) {
            float peak = Math.max(material.getEmissiveRed(), Math.max(material.getEmissiveGreen(), material.getEmissiveBlue()));
            float strength = peak * material.getGlowStrength();
            if (strength > 0f) {
                Color color = new Color(unit(material.getEmissiveRed() / peak), unit(material.getEmissiveGreen() / peak),
                        unit(material.getEmissiveBlue() / peak), 1f);
                Vec3 centre = mesh.bounds().center();
                Instances instances = node.getInstances();
                if (instances == null) {
                    added += add(out, limit, world.transformPoint(centre), color, strength, material.getGlowRange());
                } else {
                    float[] data = instances.matrixData();
                    for (int i = 0; i < instances.size() && out.size() < limit; i++) {
                        int o = i * 16;
                        Vec3 inside = new Vec3(data[o] * centre.x() + data[o + 4] * centre.y() + data[o + 8] * centre.z() + data[o + 12],
                                data[o + 1] * centre.x() + data[o + 5] * centre.y() + data[o + 9] * centre.z() + data[o + 13],
                                data[o + 2] * centre.x() + data[o + 6] * centre.y() + data[o + 10] * centre.z() + data[o + 14]);
                        added += add(out, limit, world.transformPoint(inside), color, strength, material.getGlowRange());
                    }
                }
            }
        }
        for (Node3D child : node.getChildren()) added += collect(child, world, out, limit);
        return added;
    }

    private static int add(List<Light> out, int limit, Vec3 at, Color color, float strength, float range) {
        if (out.size() >= limit) return 0;
        out.add(Light.point(at, color, strength, range));
        return 1;
    }

    /** The sRGB value whose linear light is {@code linear}. */
    private static float unit(float linear) {
        return (float) Math.pow(Math.max(0f, Math.min(1f, linear)), 1.0 / 2.2);
    }
}
