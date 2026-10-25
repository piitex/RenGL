package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The cell grid for many lights: every light that reaches a point is listed in that point's cell. No GPU. */
class LightClustersTest {
    private static final float ASPECT = 16f / 9f;

    private final Camera camera = new Camera().lookAt(new Vec3(0, 3, 12), new Vec3(1, 1, 0)).setFovYDegrees(60).setClip(0.1f, 100f);
    private final Mat4 viewProj = camera.projection(ASPECT).mul(camera.view());
    private final Vec3 forward = camera.getTarget().sub(camera.getPosition()).normalized();

    private LightClusters build(List<Light> lights) {
        return LightClusters.build(lights, viewProj.toArray(), camera.getPosition(), forward, camera.getNear(), camera.getFar());
    }

    /** The cell the shader puts a world point in, or -1 when the point is outside the view. */
    private int cellOf(Vec3 p, LightClusters grid) {
        float[] m = viewProj.toArray();
        double w = m[3] * p.x() + m[7] * p.y() + m[11] * p.z() + m[15];
        if (w <= 0) return -1;
        double u = (m[0] * p.x() + m[4] * p.y() + m[8] * p.z() + m[12]) / w * 0.5 + 0.5;
        double v = (m[1] * p.x() + m[5] * p.y() + m[9] * p.z() + m[13]) / w * 0.5 + 0.5;
        if (u < 0 || u >= 1 || v < 0 || v >= 1) return -1;
        double z = p.sub(camera.getPosition()).dot(forward);
        if (z < camera.getNear() || z > camera.getFar()) return -1;
        return LightClusters.cell((int) (u * LightClusters.TILES_X), (int) (v * LightClusters.TILES_Y), grid.slice(z));
    }

    private static List<Light> randomLights(int count, long seed, float spread, float maxRange) {
        Random random = new Random(seed);
        List<Light> lights = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Vec3 at = new Vec3((random.nextFloat() - 0.5f) * spread, random.nextFloat() * 6f, (random.nextFloat() - 0.5f) * spread);
            lights.add(Light.point(at, Color.WHITE, 1f, 0.5f + random.nextFloat() * maxRange));
        }
        return lights;
    }

    @Test
    void everyLightThatReachesAPointIsListedInItsCell() {
        List<Light> lights = randomLights(300, 7, 60f, 6f);
        LightClusters grid = build(lights);
        assertEquals(0, grid.dropped);
        Random random = new Random(11);
        int checked = 0, reaching = 0;
        for (int s = 0; s < 40000; s++) {
            Vec3 p = new Vec3((random.nextFloat() - 0.5f) * 60f, random.nextFloat() * 6f, (random.nextFloat() - 0.5f) * 60f);
            int cell = cellOf(p, grid);
            if (cell < 0) continue;
            checked++;
            java.util.Set<Integer> listed = new java.util.HashSet<>();
            for (int k = 0; k < grid.count(cell); k++) listed.add(grid.entry(cell, k));
            for (int i = 0; i < lights.size(); i++) {
                if (lights.get(i).getVector().sub(p).length() < lights.get(i).getRange() * 0.999f) {
                    reaching++;
                    assertTrue(listed.contains(i), "light " + i + " reaches " + p + " but is missing from cell " + cell);
                }
            }
        }
        assertTrue(checked > 5000, "the sample should land in view often enough to mean something: " + checked);
        assertTrue(reaching > 200, "some lights should reach the samples: " + reaching);
    }

    @Test
    void aCellListsOnlyAFewLightsWhenManyAreSpreadOut() {
        List<Light> lights = randomLights(1500, 3, 120f, 4f);
        LightClusters grid = build(lights);
        int busiest = 0;
        long entries = 0;
        for (int c = 0; c < LightClusters.CELLS; c++) {
            busiest = Math.max(busiest, grid.count(c));
            entries += grid.count(c);
        }
        assertTrue(busiest < 300, "no cell should hold most of the lights: " + busiest);
        assertTrue(entries < 1500L * 40, "the lists should be far shorter than lights times cells: " + entries);
    }

    @Test
    void aLightBehindTheCameraOrBeyondTheFarPlaneIsInNoCell() {
        List<Light> lights = List.of(
                Light.point(new Vec3(0, 3, 40), Color.WHITE, 1f, 5f),    // behind the camera, which looks toward -z
                Light.point(new Vec3(0, 1, -400), Color.WHITE, 1f, 5f)); // far beyond 100
        assertEquals(0, build(lights).entries.length);
    }

    @Test
    void aLightAroundTheCameraIsInEveryTileOfTheNearSlices() {
        LightClusters grid = build(List.of(Light.point(camera.getPosition(), Color.WHITE, 1f, 4f)));
        for (int ty = 0; ty < LightClusters.TILES_Y; ty++) {
            for (int tx = 0; tx < LightClusters.TILES_X; tx++) {
                assertEquals(1, grid.count(LightClusters.cell(tx, ty, 0)), "tile " + tx + "," + ty);
            }
        }
        assertEquals(0, grid.count(LightClusters.cell(0, 0, LightClusters.SLICES - 1)));
    }

    @Test
    void aCellStopsAtItsLimitAndCountsWhatItDropped() {
        List<Light> lights = new ArrayList<>();
        for (int i = 0; i < 400; i++) lights.add(Light.point(new Vec3(1, 1, 0), Color.WHITE, 1f, 3f));
        LightClusters grid = build(lights);
        int most = 0;
        for (int c = 0; c < LightClusters.CELLS; c++) most = Math.max(most, grid.count(c));
        assertEquals(LightClusters.MAX_PER_CELL, most);
        assertTrue(grid.dropped > 0);
    }

    @Test
    void sliceBoundsGrowAndStayInRange() {
        LightClusters grid = build(List.of());
        assertEquals(0, grid.slice(0.05));
        assertEquals(0, grid.slice(camera.getNear()));
        assertEquals(LightClusters.SLICES - 1, grid.slice(camera.getFar()));
        assertEquals(LightClusters.SLICES - 1, grid.slice(1e6));
        for (double z = 0.2; z < 100; z *= 1.3) assertTrue(grid.slice(z * 1.3) >= grid.slice(z));
    }

    @Test
    void lightRowsCarryEveryFloatBackIntact() {
        Light spot = Light.spot(new Vec3(1.5f, 2.25f, -3.125f), new Vec3(0, -1, 0), new Color(1f, 0.5f, 0.25f, 1f), 3f, 7.5f, 20f, 40f);
        Light point = Light.point(new Vec3(-4, 5, 6), Color.WHITE, 2f, 9f);
        ByteBuffer out = ByteBuffer.allocate(4 * LightClusters.LIGHT_TEXELS * 4).order(ByteOrder.LITTLE_ENDIAN);
        LightClusters.writeLights(out, List.of(spot, point), 4, light -> light == spot ? 5 : 0);
        out.order(ByteOrder.LITTLE_ENDIAN);
        int row = LightClusters.LIGHT_TEXELS * 4;
        assertEquals(1.5f, out.getFloat(0));
        assertEquals(2.25f, out.getFloat(4));
        assertEquals(-3.125f, out.getFloat(8));
        assertEquals(7.5f, out.getFloat(12));
        assertEquals(255, out.get(16) & 0xFF, "the strongest colour part is a full byte");
        assertEquals(1 + 2 * 5, out.get(19) & 0xFF, "a spot light with falloff 5");
        assertEquals(3f, out.getFloat(20), 1e-5, "the red part of white-ish orange is the peak, times the intensity");
        assertEquals(-1f, out.getFloat(28), 1e-6, "the direction's y");
        assertEquals(Math.cos(Math.toRadians(40)), out.getFloat(36), 1e-6);
        assertEquals(Math.cos(Math.toRadians(20)), out.getFloat(40), 1e-6);
        assertEquals(-4f, out.getFloat(row));
        assertEquals(0, out.get(row + 19) & 0xFF, "a point light with falloff 0");
        assertEquals(2f, out.getFloat(row + 20), 1e-5);
        assertEquals(0f, out.getFloat(2 * row + 12), "rows past the lights are empty");
    }

    @Test
    void headersAndIndicesDecodeTheWayTheShaderReadsThem() {
        List<Light> lights = randomLights(60, 5, 30f, 5f);
        LightClusters grid = build(lights);
        ByteBuffer headers = ByteBuffer.allocate(LightClusters.HEADER_WIDTH * LightClusters.HEADER_ROWS * 4);
        grid.writeHeaders(headers);
        int rows = grid.indexRows();
        ByteBuffer indices = ByteBuffer.allocate(rows * LightClusters.INDEX_WIDTH * 4);
        grid.writeIndices(indices, rows);
        for (int c = 0; c < LightClusters.CELLS; c++) {
            int at = c * 4;
            int offset = (headers.get(at) & 0xFF) | (headers.get(at + 1) & 0xFF) << 8 | (headers.get(at + 2) & 0xFF) << 16;
            int count = headers.get(at + 3) & 0xFF;
            assertEquals(grid.count(c), count);
            for (int k = 0; k < count; k++) {
                int e = offset + k;
                int texel = e >> 1;
                int b = texel * 4 + ((e & 1) == 0 ? 0 : 2);
                int index = (indices.get(b) & 0xFF) | (indices.get(b + 1) & 0xFF) << 8;
                assertEquals(grid.entry(c, k), index);
            }
        }
    }
}
