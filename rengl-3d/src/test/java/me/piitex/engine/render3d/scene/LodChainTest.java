package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The levels of detail of a mesh: their order, what may be a level, and which one a node draws at a given size on screen.
 */
class LodChainTest {
    private static final Mesh FULL = Mesh.sphere(1f, 64, 32);
    private static final Mesh MEDIUM = Mesh.sphere(1f, 16, 8);
    private static final Mesh LOW = Mesh.sphere(1f, 6, 4);

    private static LodChain chain() {
        Mesh full = Mesh.sphere(1f, 64, 32);
        full.addLod(LOW, 0.1f).addLod(MEDIUM, 0.02f);
        return full.getLods();
    }

    @Test
    void levelsAreKeptInOrderOfTheirError() {
        LodChain chain = chain();
        assertEquals(3, chain.size());
        assertEquals(0f, chain.level(0).error());
        assertSame(MEDIUM, chain.level(1).mesh());
        assertSame(LOW, chain.level(2).mesh());
        assertSame(LOW, chain.coarsest());
        assertTrue(chain.level(1).handMade());
    }

    @Test
    void aMeshWithoutLevelsHasNoChain() {
        assertNull(Mesh.cube(1f).getLods());
        Mesh m = Mesh.cube(1f).addLod(Mesh.cube(1f), 0.1f);
        m.setLods(null);
        assertNull(m.getLods());
    }

    @Test
    void whatCantBeALevel() {
        Mesh m = Mesh.cube(1f);
        assertThrows(IllegalArgumentException.class, () -> m.addLod(m, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> m.addLod(Mesh.lines(new float[]{0, 0, 0, 1, 0, 0}, new int[]{0, 1}), 0.1f));
        assertThrows(IllegalArgumentException.class, () -> m.addLod(Mesh.cube(1f), -1f));
        assertThrows(IllegalArgumentException.class, () -> m.addLod(Mesh.cube(1f), Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> m.setLods(chain()));
    }

    @Test
    void theSimplestLevelWhoseErrorIsSmallEnoughOnScreen() {
        LodChain chain = chain();
        // 0.02 units at 10 pixels a unit is 0.2 pixels; 0.1 units is 1 pixel.
        assertEquals(2, chain.select(10f, 1f, -1, 2));
        assertEquals(1, chain.select(20f, 1f, -1, 2));
        assertEquals(1, chain.select(50f, 1f, -1, 2));
        assertEquals(0, chain.select(51f, 1f, -1, 2));
        // A looser limit takes simpler levels.
        assertEquals(2, chain.select(20f, 2f, -1, 2));
        // Nothing on screen at all, and the last level that may be used.
        assertEquals(2, chain.select(0f, 1f, -1, 2));
        assertEquals(1, chain.select(0f, 1f, -1, 1));
        assertEquals(0, chain.select(1f, 1f, -1, 0));
        assertEquals(0, chain.select(Float.NaN, 1f, -1, 2));
    }

    @Test
    void aNodeAtTheBorderKeepsItsLevel() {
        LodChain chain = chain();
        // The border between levels 1 and 2 is at 10 pixels a unit (level 2's error is then exactly 1 pixel).
        int level = chain.select(9f, 1f, -1, 2);
        assertEquals(2, level);
        int changes = 0;
        for (int i = 0; i < 200; i++) {
            float pixelsPerUnit = 10f + (float) Math.sin(i * 0.7) * 1.2f; // sways 12% either side of the border
            int next = chain.select(pixelsPerUnit, 1f, level, 2);
            if (next != level) changes++;
            level = next;
        }
        assertEquals(0, changes, "the level flicked " + changes + " times");
        // Well past the band it changes.
        assertEquals(1, chain.select(12f, 1f, 2, 2));
        assertEquals(2, chain.select(8f, 1f, 1, 2));
        // A previous level that may no longer be used is not kept.
        assertEquals(1, chain.select(10f, 1f, 2, 1));
    }

    @Test
    void levelsThatLackWhatTheNodeNeedsAreNotUsed() {
        Mesh skinned = Mesh.cube(1f);
        int n = skinned.vertexCount();
        skinned.setSkinning(new int[n * 4], filledWeights(n));
        Mesh skinnedLow = Mesh.cube(1f).setSkinning(new int[n * 4], filledWeights(n));
        skinned.addLod(skinnedLow, 0.05f).addLod(Mesh.cube(1f), 0.1f);
        assertEquals(1, SceneRenderer.lastUsable(skinned.getLods(), true, false));
        assertEquals(2, SceneRenderer.lastUsable(skinned.getLods(), false, false));

        Mesh morphed = Mesh.cube(1f);
        morphed.setMorphTargets(java.util.List.of(new Mesh.MorphTarget("up", new float[n * 3], null)), null);
        morphed.addLod(Mesh.cube(1f), 0.05f);
        assertEquals(0, SceneRenderer.lastUsable(morphed.getLods(), false, true));
        assertEquals(1, SceneRenderer.lastUsable(morphed.getLods(), false, false));
    }

    private static float[] filledWeights(int vertices) {
        float[] w = new float[vertices * 4];
        for (int i = 0; i < vertices; i++) w[i * 4] = 1f;
        return w;
    }

    @Test
    void nodeSettingsAreCopied() {
        Node3D node = new Node3D("rock", FULL, new Material()).setLodBias(1.5f).setForcedLod(2).setCullScreenSize(0.02f).setOccluder(true)
                .setVirtualGeometry(false);
        node.setPosition(new Vec3(1, 2, 3));
        Node3D copy = node.copy();
        assertFalse(copy.isVirtualGeometry());
        assertTrue(new Node3D("n").isVirtualGeometry(), "on by default");
        assertEquals(1.5f, copy.getLodBias());
        assertEquals(2, copy.getForcedLod());
        assertEquals(0.02f, copy.getCullScreenSize());
        assertTrue(copy.isOccluder());
        assertEquals(-1, new Node3D("n").setForcedLod(-7).getForcedLod());
        assertEquals(0f, new Node3D("n").setCullScreenSize(-1f).getCullScreenSize());
    }

    @Test
    void sceneSettings() {
        Scene3D scene = new Scene3D();
        assertTrue(scene.isLodEnabled());
        assertEquals(1f, scene.getLodPixelError());
        assertThrows(IllegalArgumentException.class, () -> scene.setLodPixelError(0f));
        Scene3D.Stats stats = new Scene3D.Stats(1, 0, 1, 1, 0, 0, 0);
        assertEquals(0, stats.recordsAtLevel(3));
        assertEquals(0L, stats.triangles());
        int[] perLevel = new int[Scene3D.Stats.LEVELS];
        perLevel[Scene3D.Stats.LEVELS - 1] = 5;
        Scene3D.Stats more = new Scene3D.Stats(1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 10L, perLevel);
        perLevel[Scene3D.Stats.LEVELS - 1] = 9;
        assertEquals(5, more.recordsAtLevel(40), "levels past the last count in it, and the record keeps its own copy");
    }
}
