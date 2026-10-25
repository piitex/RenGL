package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Vec3;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Sorts a frame's local lights (point and spot) into a grid of cells in front of the camera, so a pixel only shades the lights
 * whose reach includes its cell instead of every light in the scene. The grid is {@link #TILES_X} by {@link #TILES_Y} tiles of
 * the picture and {@link #SLICES} slices of depth, the slices growing geometrically from the near plane to the far one.
 * <p>
 * The result goes to the GPU as three small RGBA8 textures read with {@code texelFetch}, because both backends can update
 * those every frame and neither needs a storage buffer. Floats travel as their four bytes, so nothing loses precision:
 * <ul>
 * <li><b>lights</b>, {@link #LIGHT_TEXELS} texels a row, one row a light (see {@link #writeLights});</li>
 * <li><b>headers</b>, one texel a cell: the offset of the cell's first entry (three bytes) and how many it has (one byte);</li>
 * <li><b>indices</b>, two lights' row numbers (16 bits each) to a texel.</li>
 * </ul>
 * Everything here is plain arithmetic and is checked without a GPU.
 */
final class LightClusters {
    static final int TILES_X = 16, TILES_Y = 9, SLICES = 24;
    static final int CELLS = TILES_X * TILES_Y * SLICES;
    /** The most local lights one frame shades through the grid. */
    static final int MAX_LIGHTS = 2048;
    /** The most lights one cell holds; a cell in the middle of a crowd of lights shades the first of them. */
    static final int MAX_PER_CELL = 255;
    /** The most entries over all the cells. */
    static final int MAX_ENTRIES = 1 << 19;
    static final int LIGHT_TEXELS = 12;
    static final int HEADER_WIDTH = 128;
    static final int HEADER_ROWS = (CELLS + HEADER_WIDTH - 1) / HEADER_WIDTH;
    static final int INDEX_WIDTH = 1024;

    final float near;
    /** {@code SLICES / ln(far / near)}: a distance along the view axis becomes {@code ln(z / near) * scale}. */
    final float scale;
    final int[] offsets = new int[CELLS + 1];
    int[] entries = new int[0];
    /** Lights that did not fit in a cell or in {@link #MAX_ENTRIES}. */
    int dropped;

    private LightClusters(float near, float far) {
        this.near = near;
        this.scale = (float) (SLICES / Math.log(far / near));
    }

    int count(int cell) {
        return offsets[cell + 1] - offsets[cell];
    }

    int entry(int cell, int k) {
        return entries[offsets[cell] + k];
    }

    static int cell(int tx, int ty, int slice) {
        return (slice * TILES_Y + ty) * TILES_X + tx;
    }

    /** The slice a distance along the view axis falls in. */
    int slice(double z) {
        return (int) Math.max(0, Math.min(SLICES - 1, Math.floor(Math.log(Math.max(z, near) / near) * scale)));
    }

    /**
     * @param lights   point and spot lights, at most {@link #MAX_LIGHTS}; a cell lists them by their place in this list
     * @param viewProj the camera's view-projection matrix, column-major, the one the shader projects with
     * @param forward  the camera's viewing direction, unit length
     */
    static LightClusters build(List<Light> lights, float[] viewProj, Vec3 eye, Vec3 forward, float near, float far) {
        LightClusters grid = new LightClusters(near, far);
        double[][] tilePlanes = tilePlanes(viewProj);
        int n = Math.min(lights.size(), MAX_LIGHTS);
        // Two passes over the same loops: the first counts what each cell gets, the second writes it.
        int[] fill = new int[CELLS], used = {0};
        for (int pass = 0; pass < 2; pass++) {
            used[0] = 0;
            if (pass == 1) {
                int total = 0;
                for (int c = 0; c < CELLS; c++) {
                    grid.offsets[c] = total;
                    total += fill[c];
                    fill[c] = 0;
                }
                grid.offsets[CELLS] = total;
                grid.entries = new int[total];
            }
            for (int i = 0; i < n; i++) place(grid, lights.get(i), i, viewProj, tilePlanes, eye, forward, near, far, fill, used, pass == 1);
        }
        return grid;
    }

    private static void place(LightClusters grid, Light light, int index, float[] m, double[][] tilePlanes, Vec3 eye, Vec3 fwd,
                              float near, float far, int[] fill, int[] used, boolean write) {
        Vec3 p = light.getVector();
        double r = light.getRange();
        double z = (p.x() - eye.x()) * fwd.x() + (p.y() - eye.y()) * fwd.y() + (p.z() - eye.z()) * fwd.z();
        if (z + r < near || z - r > far) return;
        int s0 = grid.slice(Math.max(z - r, near) * 0.998), s1 = grid.slice(Math.min(z + r, far) * 1.002);

        // The tiles the sphere can touch: the extent of its bounding box in the view's axes, which has no corner behind the
        // camera unless the sphere reaches the near plane, and then it is every tile.
        int x0 = 0, x1 = TILES_X - 1, y0 = 0, y1 = TILES_Y - 1;
        if (z - r > near) {
            Vec3 right = perpendicular(fwd), up = fwd.cross(right);
            double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
            for (int c = 0; c < 8; c++) {
                double sx = (c & 1) == 0 ? -r : r, sy = (c & 2) == 0 ? -r : r, sz = (c & 4) == 0 ? -r : r;
                double wx = p.x() + sx * right.x() + sy * up.x() + sz * fwd.x();
                double wy = p.y() + sx * right.y() + sy * up.y() + sz * fwd.y();
                double wz = p.z() + sx * right.z() + sy * up.z() + sz * fwd.z();
                double w = m[3] * wx + m[7] * wy + m[11] * wz + m[15];
                if (w <= 1e-6) {
                    minU = minV = -1;
                    maxU = maxV = 2;
                    break;
                }
                double u = (m[0] * wx + m[4] * wy + m[8] * wz + m[12]) / w * 0.5 + 0.5;
                double v = (m[1] * wx + m[5] * wy + m[9] * wz + m[13]) / w * 0.5 + 0.5;
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
            }
            double pad = 0.002;
            x0 = clampTile((int) Math.floor((minU - pad) * TILES_X), TILES_X);
            x1 = clampTile((int) Math.floor((maxU + pad) * TILES_X), TILES_X);
            y0 = clampTile((int) Math.floor((minV - pad) * TILES_Y), TILES_Y);
            y1 = clampTile((int) Math.floor((maxV + pad) * TILES_Y), TILES_Y);
            if (maxU < -pad || minU > 1 + pad || maxV < -pad || minV > 1 + pad) return;
        }
        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                if (!touchesTile(tilePlanes, tx, ty, p, r)) continue;
                for (int s = s0; s <= s1; s++) {
                    int cell = cell(tx, ty, s);
                    if (fill[cell] >= MAX_PER_CELL || used[0] >= MAX_ENTRIES) {
                        if (write) grid.dropped++;
                        continue;
                    }
                    used[0]++;
                    if (write) {
                        int at = grid.offsets[cell] + fill[cell];
                        grid.entries[at] = index;
                    }
                    fill[cell]++;
                }
            }
        }
    }

    private static int clampTile(int tile, int tiles) {
        return Math.max(0, Math.min(tiles - 1, tile));
    }

    private static Vec3 perpendicular(Vec3 f) {
        Vec3 axis = Math.abs(f.y()) < 0.9 ? Vec3.UP : Vec3.X;
        return f.cross(axis).normalized();
    }

    /**
     * The planes through the eye that bound each tile column and row, as {@code {a, b, c, d}} with a unit normal pointing into
     * the tile: first the {@code TILES_X + 1} vertical edges (the inside is to the right of an edge), then the {@code TILES_Y + 1}
     * horizontal ones (above it).
     */
    private static double[][] tilePlanes(float[] m) {
        double[][] planes = new double[TILES_X + 1 + TILES_Y + 1][];
        for (int i = 0; i <= TILES_X; i++) planes[i] = edge(m, 0, -1.0 + 2.0 * i / TILES_X);
        for (int i = 0; i <= TILES_Y; i++) planes[TILES_X + 1 + i] = edge(m, 1, -1.0 + 2.0 * i / TILES_Y);
        return planes;
    }

    /** The plane where clip {@code x} (or {@code y}) over {@code w} equals {@code at}, positive on the side of larger values. */
    private static double[] edge(float[] m, int row, double at) {
        double a = m[row] - at * m[3], b = m[4 + row] - at * m[7], c = m[8 + row] - at * m[11], d = m[12 + row] - at * m[15];
        double len = Math.sqrt(a * a + b * b + c * c);
        if (len < 1e-12) len = 1;
        return new double[]{a / len, b / len, c / len, d / len};
    }

    private static boolean touchesTile(double[][] planes, int tx, int ty, Vec3 p, double r) {
        return distance(planes[tx], p) >= -r && distance(planes[tx + 1], p) <= r
                && distance(planes[TILES_X + 1 + ty], p) >= -r && distance(planes[TILES_X + 1 + ty + 1], p) <= r;
    }

    private static double distance(double[] plane, Vec3 p) {
        return plane[0] * p.x() + plane[1] * p.y() + plane[2] * p.z() + plane[3];
    }

    // ---- texture data ---------------------------------------------------------------------------------------------

    /**
     * Fills {@code out} (cleared first) with {@code rows} rows of {@link #LIGHT_TEXELS} texels, one row a light:
     * <pre>
     *  0 1 2   position
     *  3       range
     *  4       colour divided by its largest part, one byte each, and in the fourth byte 1 for a spot light plus twice the falloff id
     *  5       the largest part of the colour, which is the intensity
     *  6 7 8   a spot light's direction
     *  9       the cosine of its outer angle
     * 10       the cosine of its inner angle
     * </pre>
     * Floats are written as four little-endian bytes, which the shader puts back together.
     *
     * @param falloffs the falloff id for a light, as the shader's {@code falloff} function takes it
     */
    static void writeLights(ByteBuffer out, List<Light> lights, int rows, java.util.function.ToIntFunction<Light> falloffs) {
        out.clear();
        out.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < rows; i++) {
            if (i >= lights.size() || i >= MAX_LIGHTS) {
                for (int t = 0; t < LIGHT_TEXELS; t++) out.putInt(0);
                continue;
            }
            Light light = lights.get(i);
            Vec3 p = light.getVector();
            float k = light.getIntensity();
            float r = linear(light.getColor().getR()) * k, g = linear(light.getColor().getG()) * k, b = linear(light.getColor().getB()) * k;
            float peak = Math.max(r, Math.max(g, b));
            boolean spot = light.getType() == Light.Type.SPOT;
            out.putFloat(p.x()).putFloat(p.y()).putFloat(p.z()).putFloat(light.getRange());
            if (peak <= 0f) {
                out.putInt(0).putFloat(0f);
            } else {
                out.put(unit(r / peak)).put(unit(g / peak)).put(unit(b / peak)).put((byte) ((spot ? 1 : 0) + 2 * falloffs.applyAsInt(light)));
                out.putFloat(peak);
            }
            Vec3 d = spot ? light.getDirection() : Vec3.ZERO;
            out.putFloat(d.x()).putFloat(d.y()).putFloat(d.z());
            out.putFloat(spot ? (float) Math.cos(Math.toRadians(light.getOuterAngle())) : -1f);
            out.putFloat(spot ? (float) Math.cos(Math.toRadians(light.getInnerAngle())) : 1f);
            out.putInt(0);
        }
        out.flip();
    }

    private static byte unit(float v) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    private static float linear(float srgb) {
        return (float) Math.pow(Math.max(0f, srgb), 2.2);
    }

    /** Fills {@code out} with {@link #HEADER_ROWS} rows of {@link #HEADER_WIDTH} header texels. */
    void writeHeaders(ByteBuffer out) {
        out.clear();
        out.order(ByteOrder.LITTLE_ENDIAN);
        for (int c = 0; c < HEADER_ROWS * HEADER_WIDTH; c++) {
            if (c < CELLS) {
                int offset = offsets[c];
                out.put((byte) offset).put((byte) (offset >> 8)).put((byte) (offset >> 16)).put((byte) count(c));
            } else {
                out.putInt(0);
            }
        }
        out.flip();
    }

    /** The rows of {@link #INDEX_WIDTH} texels the entries need, two to a texel. */
    int indexRows() {
        return Math.max(1, (entries.length + 2 * INDEX_WIDTH - 1) / (2 * INDEX_WIDTH));
    }

    /** Fills {@code out} with {@code rows} rows of index texels. */
    void writeIndices(ByteBuffer out, int rows) {
        out.clear();
        out.order(ByteOrder.LITTLE_ENDIAN);
        int total = rows * INDEX_WIDTH * 2;
        for (int e = 0; e < total; e++) out.putShort((short) (e < entries.length ? entries[e] : 0));
        out.flip();
    }
}
