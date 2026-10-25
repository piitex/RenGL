package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chooses which of a scene's lights the shader gets in a frame, when there are more than it has room for
 * ({@link Scene3D#MAX_LIGHTS}).
 */
final class LightBudget {
    private LightBudget() {
    }

    /**
     * The lights to shade this frame, at most {@link Scene3D#MAX_LIGHTS}. A scene within the limit keeps all its lights in
     * their order. Beyond it, lights that add nothing are left out first (switched off, or a point or spot light whose reach
     * is wholly outside the camera's view, since nothing it lights can be seen), then directional lights go first and the
     * rest follow by how near their reach is to the camera, until the room is used up.
     *
     * @param cameraPlanes the camera's view volume, as for {@link ShadowPlanner#intersects}
     * @param truncated    {@code truncated[0]} is set when lights that mattered were left out
     */
    static List<Light> choose(List<Light> all, Vec3 eye, float[] cameraPlanes, boolean[] truncated) {
        if (all.size() <= Scene3D.MAX_LIGHTS) return all;
        List<Light> directional = new ArrayList<>();
        List<Light> local = new ArrayList<>();
        for (Light light : all) {
            if (light.getIntensity() <= 0f) continue;
            if (light.getType() == Light.Type.DIRECTIONAL) {
                directional.add(light);
                continue;
            }
            Vec3 p = light.getVector();
            if (ShadowPlanner.intersects(cameraPlanes, p.x(), p.y(), p.z(), light.getRange())) local.add(light);
        }
        // How far the camera is from the edge of the light's reach: a light the camera stands inside comes first.
        local.sort(Comparator.comparingDouble(l -> l.getVector().sub(eye).length() - l.getRange()));
        List<Light> chosen = new ArrayList<>(Math.min(Scene3D.MAX_LIGHTS, directional.size() + local.size()));
        for (Light light : directional) {
            if (chosen.size() == Scene3D.MAX_LIGHTS) break;
            chosen.add(light);
        }
        for (Light light : local) {
            if (chosen.size() == Scene3D.MAX_LIGHTS) break;
            chosen.add(light);
        }
        if (chosen.size() < directional.size() + local.size()) truncated[0] = true;
        return chosen;
    }

    /**
     * The lights every pixel tests ({@code direct}) and the ones sorted into the cluster grid ({@code local}).
     *
     * @param directCut true when lights that mattered were left out of {@code direct}
     * @param localCut  true when lights that mattered were left out of {@code local}
     */
    record Split(List<Light> direct, List<Light> local, boolean directCut, boolean localCut) {
    }

    /**
     * Splits a frame's lights for the given mode. Without clustering this is {@link #choose} and nothing is local. With it
     * the directional lights and the ones that cast shadows (which the shader looks up by place, with their shadow maps)
     * go through {@link #choose} as before, and every other point or spot light that is on and reaches into the camera's
     * view goes to the grid, the nearest {@link LightClusters#MAX_LIGHTS} of them.
     */
    static Split split(Scene3D.LightMode mode, List<Light> all, Vec3 eye, float[] cameraPlanes) {
        boolean clustered = mode == Scene3D.LightMode.CLUSTERED || (mode == Scene3D.LightMode.AUTO && all.size() > Scene3D.MAX_LIGHTS);
        boolean[] cut = {false};
        if (!clustered) {
            List<Light> direct = choose(all, eye, cameraPlanes, cut);
            return new Split(direct, List.of(), cut[0], false);
        }
        List<Light> everyPixel = new ArrayList<>();
        List<Light> local = new ArrayList<>();
        for (Light light : all) {
            if (light.getType() == Light.Type.DIRECTIONAL || light.isCastShadow()) {
                everyPixel.add(light);
            } else if (light.getIntensity() > 0f) {
                Vec3 p = light.getVector();
                if (ShadowPlanner.intersects(cameraPlanes, p.x(), p.y(), p.z(), light.getRange())) local.add(light);
            }
        }
        List<Light> direct = choose(everyPixel, eye, cameraPlanes, cut);
        boolean localCut = local.size() > LightClusters.MAX_LIGHTS;
        if (localCut) {
            local.sort(Comparator.comparingDouble(l -> l.getVector().sub(eye).length() - l.getRange()));
            local = new ArrayList<>(local.subList(0, LightClusters.MAX_LIGHTS));
        }
        return new Split(direct, local, cut[0], localCut);
    }
}
