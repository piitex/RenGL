package me.piitex.engine.render3d.scene;

import java.util.Arrays;

/**
 * How {@link Mesh#generateLods} makes a mesh's levels of detail. The defaults suit most props, rocks, trees and buildings:
 * four levels with a half, a quarter, an eighth and a sixteenth of the triangles.
 * <pre>
 * mesh.generateLods(new LodSettings().ratios(0.4f, 0.15f).minTriangles(200));
 * Model.load(Path.of("rock.glb"), new LodSettings());
 * </pre>
 */
public final class LodSettings {
    private float[] ratios = {0.5f, 0.25f, 0.12f, 0.06f};
    private int minTriangles = 64;
    private float maxError = 0.1f;
    private float normalWeight = 0.5f;
    private float uvWeight = 1f;
    private boolean lockBorders;
    private boolean sloppy = true;
    private boolean optimize = true;
    private boolean impostor;

    /**
     * The share of the full mesh's triangles each level aims for, from the most detailed level down: 0.5 is half. Each has
     * to be below the one before. A level that can't get much below the one before is where the chain stops.
     */
    public LodSettings ratios(float... ratios) {
        if (ratios.length == 0) throw new IllegalArgumentException("Give at least one ratio");
        float before = 1f;
        for (float r : ratios) {
            if (!(r > 0f) || r >= before) throw new IllegalArgumentException("Each ratio must be above 0 and below the one before it: " + Arrays.toString(ratios));
            before = r;
        }
        this.ratios = ratios.clone();
        return this;
    }

    public float[] ratios() {
        return ratios.clone();
    }

    /** No level is made with fewer triangles than this (64 by default); a mesh with fewer than twice this gets no levels. */
    public LodSettings minTriangles(int triangles) {
        this.minTriangles = Math.max(1, triangles);
        return this;
    }

    public int minTriangles() {
        return minTriangles;
    }

    /**
     * The most a level may be off from the full mesh, as a share of the mesh's size: 0.1, the default, is a tenth. Levels
     * that would be off by more are not made. A level is only drawn once its error is under a pixel or so on screen, so a
     * large one is only seen on a small object.
     */
    public LodSettings maxError(float share) {
        if (!(share > 0f)) throw new IllegalArgumentException("The error must be above 0");
        this.maxError = share;
        return this;
    }

    public float maxError() {
        return maxError;
    }

    /**
     * How much keeping the shading (normals) and the texture in place matters next to keeping the shape, when triangles are
     * taken away: 0 ignores them. 0.5 and 1 by default. Raise the texture weight for a mesh with a detailed texture.
     */
    public LodSettings attributeWeights(float normals, float uvs) {
        this.normalWeight = Math.max(0f, normals);
        this.uvWeight = Math.max(0f, uvs);
        return this;
    }

    public float normalWeight() {
        return normalWeight;
    }

    public float uvWeight() {
        return uvWeight;
    }

    /**
     * Keeps the open edges of the mesh where they are (false by default), for pieces that have to meet their neighbours
     * without a gap: tiles of ground, wall sections, a mesh cut into chunks.
     */
    public LodSettings lockBorders(boolean lock) {
        this.lockBorders = lock;
        return this;
    }

    public boolean lockBorders() {
        return lockBorders;
    }

    /**
     * When a mesh can't be simplified far enough by joining its vertices along its surface (it is made of many separate
     * pieces, such as the leaves of a tree), simplify it the rough way instead, which merges nearby vertices whatever they
     * are joined to; true by default. The rough way can smear the texture, which is seldom seen at the distance those
     * levels are drawn from.
     */
    public LodSettings sloppy(boolean allow) {
        this.sloppy = allow;
        return this;
    }

    public boolean sloppy() {
        return sloppy;
    }

    /**
     * Reorders each level's triangles and vertices so the GPU draws them faster (true by default): fewer vertices shaded
     * twice, fewer pixels drawn over, vertices read in order. A model loaded with levels has its full mesh reordered too.
     */
    public LodSettings optimize(boolean optimize) {
        this.optimize = optimize;
        return this;
    }

    public boolean optimize() {
        return optimize;
    }

    /**
     * Ends the levels with a picture card ({@link Mesh#setImpostor}), for meshes seen by the hundred far off: trees, rocks,
     * houses. False by default, as each card holds 8 MB of pictures once drawn.
     */
    public LodSettings impostor(boolean impostor) {
        this.impostor = impostor;
        return this;
    }

    public boolean impostor() {
        return impostor;
    }

    private boolean clusters;

    /**
     * Also splits every mesh into the clusters of virtual geometry ({@link Mesh#buildClusters}), used where
     * {@link Scene3D#setVirtualGeometry} is on and the GPU can. False by default. Skinned and morphing meshes are left out.
     */
    public LodSettings clusters(boolean clusters) {
        this.clusters = clusters;
        return this;
    }

    public boolean clusters() {
        return clusters;
    }
}
