package me.piitex.engine.render3d.scene;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.util.meshoptimizer.MeshOptimizer.*;

/**
 * Makes the levels of detail of a mesh with meshoptimizer: each level is the full mesh simplified to a share of its
 * triangles, keeping its shape, shading and texture as well as it can, with the error meshoptimizer measured, then reordered
 * for the GPU and given only the vertices it uses. Every part of the mesh comes along: skinning, the second texture
 * coordinates and the blend shapes. Runs on any thread; touches nothing but the meshes.
 */
final class LodGenerator {
    private static final int STRIDE = Mesh.FLOATS_PER_VERTEX * 4;
    /** How much more than a level's target may be left before the rough way is tried. */
    private static final float STUCK = 1.25f;
    /** A level that keeps more than this share of the one before is not worth a level of its own. */
    private static final float LEAST_GAIN = 0.85f;
    /** Points measured each way by {@link #measureError}. */
    private static final int ERROR_SAMPLES = 4096;

    private LodGenerator() {
    }

    /**
     * The chain of {@code base} and the levels made from it.
     */
    static LodChain generate(Mesh base, LodSettings settings) {
        if (base.isLines()) throw new IllegalArgumentException("A mesh of lines has no levels of detail");
        int[] source = base.indices();
        int triangles = source.length / 3;
        LodChain chain = LodChain.of(base);
        if (triangles < settings.minTriangles() * 2) return chain;
        float[] v = base.vertices();
        int vertexCount = base.vertexCount();
        // Three floats more than the vertices, so the attributes (which start three floats in) span whole vertices too.
        FloatBuffer vertices = MemoryUtil.memAllocFloat(v.length + 3);
        IntBuffer indices = MemoryUtil.memAllocInt(source.length);
        IntBuffer out = MemoryUtil.memAllocInt(source.length);
        IntBuffer rough = settings.sloppy() ? MemoryUtil.memAllocInt(source.length) : null;
        FloatBuffer error = MemoryUtil.memAllocFloat(1);
        FloatBuffer weights = MemoryUtil.memAllocFloat(5);
        try {
            vertices.put(v).put(0f).put(0f).put(0f).flip();
            FloatBuffer attributes = vertices.slice(3, v.length);
            vertices.limit(v.length);
            indices.put(source).flip();
            float n = settings.normalWeight(), uv = settings.uvWeight();
            weights.put(n).put(n).put(n).put(uv).put(uv).flip();
            // meshoptimizer gives its errors as a share of the mesh's size; this turns them into the mesh's units.
            float scale = meshopt_simplifyScale(vertices, vertexCount, STRIDE);
            int options = settings.lockBorders() ? meshopt_SimplifyLockBorder : 0;
            long previousCount = source.length;
            float previousError = 0f;
            for (float ratio : settings.ratios()) {
                long targetTriangles = (long) (triangles * (double) ratio);
                if (targetTriangles < settings.minTriangles()) break;
                long target = targetTriangles * 3;
                // Always from the full mesh, so the error is against it and not against the level before.
                out.clear();
                long count = meshopt_simplifyWithAttributes(out, indices, vertices, vertexCount, STRIDE, attributes, STRIDE, weights,
                        null, target, settings.maxError(), options, error);
                float e = error.get(0);
                IntBuffer result = out;
                if (count > target * STUCK && rough != null) {
                    rough.clear();
                    long roughCount = meshopt_simplifySloppy(rough, indices, vertices, vertexCount, STRIDE, null, target, settings.maxError(), error);
                    if (roughCount >= 3 && roughCount < count) {
                        count = roughCount;
                        e = error.get(0);
                        result = rough;
                    }
                }
                if (count < 3 || count > previousCount * LEAST_GAIN || e > settings.maxError()) break;
                int[] levelIndices = new int[(int) count];
                result.get(0, levelIndices);
                float absolute = Math.max(e * scale, previousError);
                chain = chain.with(new LodChain.Level(compact(base, levelIndices, settings.optimize()), absolute, false));
                previousCount = count;
                previousError = absolute;
            }
        } finally {
            MemoryUtil.memFree(vertices);
            MemoryUtil.memFree(indices);
            MemoryUtil.memFree(out);
            if (rough != null) MemoryUtil.memFree(rough);
            MemoryUtil.memFree(error);
            MemoryUtil.memFree(weights);
        }
        return chain;
    }

    /**
     * The mesh with its triangles and vertices reordered for the GPU: the same picture, drawn faster.
     */
    static Mesh optimize(Mesh mesh) {
        if (mesh.isLines()) return mesh;
        return compact(mesh, mesh.indices(), true);
    }

    /**
     * A mesh of the triangles {@code indices} picks from {@code base}'s vertices, holding only the vertices they use, in the
     * order they are first used, with every part of {@code base} (skinning, second texture coordinates, blend shapes).
     */
    static Mesh compact(Mesh base, int[] indices, boolean optimize) {
        int vertexCount = base.vertexCount();
        float[] v = base.vertices();
        IntBuffer ib = MemoryUtil.memAllocInt(indices.length);
        IntBuffer tmp = optimize ? MemoryUtil.memAllocInt(indices.length) : null;
        FloatBuffer positions = optimize ? MemoryUtil.memAllocFloat(v.length) : null;
        IntBuffer remap = MemoryUtil.memAllocInt(vertexCount);
        int[] map = new int[vertexCount];
        int unique;
        try {
            ib.put(indices).flip();
            if (optimize) {
                positions.put(v).flip();
                // Triangles that share vertices next to each other, then those that face out first so fewer pixels are drawn twice.
                meshopt_optimizeVertexCache(tmp, ib, vertexCount);
                meshopt_optimizeOverdraw(ib, tmp, positions, vertexCount, STRIDE, 1.05f);
            }
            unique = (int) meshopt_optimizeVertexFetchRemap(remap, ib);
            remap.get(0, map);
            ib.get(0, indices = new int[indices.length]);
        } finally {
            MemoryUtil.memFree(ib);
            if (tmp != null) MemoryUtil.memFree(tmp);
            if (positions != null) MemoryUtil.memFree(positions);
            MemoryUtil.memFree(remap);
        }
        for (int i = 0; i < indices.length; i++) indices[i] = map[indices[i]];

        float[] p = new float[unique * 3], nrm = new float[unique * 3], uv = new float[unique * 2];
        int[] joints = base.isSkinned() ? new int[unique * 4] : null;
        float[] jointWeights = base.isSkinned() ? new float[unique * 4] : null;
        float[] uv2 = base.hasUvs2() ? new float[unique * 2] : null;
        List<Mesh.MorphTarget> shapes = base.morphTargets();
        float[][] shapeP = new float[shapes.size()][], shapeN = new float[shapes.size()][];
        for (int t = 0; t < shapes.size(); t++) {
            shapeP[t] = new float[unique * 3];
            shapeN[t] = shapes.get(t).normals() == null ? null : new float[unique * 3];
        }
        for (int old = 0; old < vertexCount; old++) {
            int to = map[old];
            if (to < 0) continue; // not used by any triangle left
            int o = old * Mesh.FLOATS_PER_VERTEX;
            System.arraycopy(v, o, p, to * 3, 3);
            System.arraycopy(v, o + 3, nrm, to * 3, 3);
            System.arraycopy(v, o + 6, uv, to * 2, 2);
            if (joints != null) {
                System.arraycopy(base.skinJoints(), old * 4, joints, to * 4, 4);
                System.arraycopy(base.skinWeights(), old * 4, jointWeights, to * 4, 4);
            }
            if (uv2 != null) System.arraycopy(base.uvs2(), old * 2, uv2, to * 2, 2);
            for (int t = 0; t < shapes.size(); t++) {
                System.arraycopy(shapes.get(t).positions(), old * 3, shapeP[t], to * 3, 3);
                if (shapeN[t] != null) System.arraycopy(shapes.get(t).normals(), old * 3, shapeN[t], to * 3, 3);
            }
        }
        Mesh level = new Mesh(p, nrm, uv, indices);
        if (joints != null) level.setSkinning(joints, jointWeights);
        if (uv2 != null) level.setUvs2(uv2);
        if (!shapes.isEmpty()) {
            List<Mesh.MorphTarget> moved = new ArrayList<>();
            for (int t = 0; t < shapes.size(); t++) moved.add(new Mesh.MorphTarget(shapes.get(t).name(), shapeP[t], shapeN[t]));
            level.setMorphTargets(moved, base.morphDefaults());
        }
        return level;
    }

    /**
     * How far apart two versions of a mesh are, in its units: the farthest any of (up to) {@value #ERROR_SAMPLES} vertices of
     * either one is from the other's surface. For a level made by hand, whose error no simplifier measured.
     */
    static float measureError(Mesh full, Mesh level) {
        return Math.max(farthest(full, new TriangleGrid(level)), farthest(level, new TriangleGrid(full)));
    }

    private static float farthest(Mesh from, TriangleGrid to) {
        float[] v = from.vertices();
        int count = from.vertexCount();
        int step = Math.max(1, count / ERROR_SAMPLES);
        float worst = 0f;
        for (int i = 0; i < count; i += step) {
            int o = i * Mesh.FLOATS_PER_VERTEX;
            worst = Math.max(worst, to.distance(v[o], v[o + 1], v[o + 2]));
        }
        return worst;
    }

    /**
     * The triangles of a mesh sorted into a grid of cubes, for the nearest point of its surface to a point.
     */
    private static final class TriangleGrid {
        final float[] v;
        final int[] idx;
        final float minX, minY, minZ, cell;
        final int nx, ny, nz;
        final int[][] cells;

        TriangleGrid(Mesh mesh) {
            v = mesh.vertices();
            idx = mesh.indices();
            int triangles = idx.length / 3;
            var b = mesh.bounds();
            minX = b.min().x();
            minY = b.min().y();
            minZ = b.min().z();
            float sx = Math.max(1e-6f, b.max().x() - minX), sy = Math.max(1e-6f, b.max().y() - minY), sz = Math.max(1e-6f, b.max().z() - minZ);
            // About two triangles a cell.
            double volume = (double) sx * sy * sz;
            float size = (float) Math.cbrt(volume * 2.0 / Math.max(1, triangles));
            size = Math.max(size, Math.max(sx, Math.max(sy, sz)) / 128f);
            cell = Math.max(size, 1e-6f);
            nx = Math.max(1, (int) Math.ceil(sx / cell));
            ny = Math.max(1, (int) Math.ceil(sy / cell));
            nz = Math.max(1, (int) Math.ceil(sz / cell));
            int[] counts = new int[nx * ny * nz];
            for (int pass = 0; pass < 2; pass++) {
                for (int t = 0; t < triangles; t++) {
                    int[] box = box(t);
                    for (int z = box[2]; z <= box[5]; z++)
                        for (int y = box[1]; y <= box[4]; y++)
                            for (int x = box[0]; x <= box[3]; x++) {
                                int c = (z * ny + y) * nx + x;
                                if (pass == 0) counts[c]++;
                                else cellsFill(c, t);
                            }
                }
                if (pass == 0) {
                    filled = new int[counts.length];
                    tmpCells = new int[counts.length][];
                    for (int c = 0; c < counts.length; c++) tmpCells[c] = new int[counts[c]];
                }
            }
            cells = tmpCells;
        }

        private int[] filled;
        private int[][] tmpCells;

        private void cellsFill(int c, int t) {
            tmpCells[c][filled[c]++] = t;
        }

        private int[] box(int t) {
            float lx = Float.MAX_VALUE, ly = Float.MAX_VALUE, lz = Float.MAX_VALUE, hx = -Float.MAX_VALUE, hy = -Float.MAX_VALUE, hz = -Float.MAX_VALUE;
            for (int k = 0; k < 3; k++) {
                int o = idx[t * 3 + k] * Mesh.FLOATS_PER_VERTEX;
                lx = Math.min(lx, v[o]);
                hx = Math.max(hx, v[o]);
                ly = Math.min(ly, v[o + 1]);
                hy = Math.max(hy, v[o + 1]);
                lz = Math.min(lz, v[o + 2]);
                hz = Math.max(hz, v[o + 2]);
            }
            return new int[]{cx(lx), cy(ly), cz(lz), cx(hx), cy(hy), cz(hz)};
        }

        private int cx(float x) {
            return Math.max(0, Math.min(nx - 1, (int) ((x - minX) / cell)));
        }

        private int cy(float y) {
            return Math.max(0, Math.min(ny - 1, (int) ((y - minY) / cell)));
        }

        private int cz(float z) {
            return Math.max(0, Math.min(nz - 1, (int) ((z - minZ) / cell)));
        }

        /** The distance from the point to the nearest point of the surface. */
        float distance(float px, float py, float pz) {
            int x0 = cx(px), y0 = cy(py), z0 = cz(pz);
            double best = Double.MAX_VALUE;
            int most = Math.max(nx, Math.max(ny, nz));
            for (int r = 0; r <= most; r++) {
                for (int z = z0 - r; z <= z0 + r; z++) {
                    if (z < 0 || z >= nz) continue;
                    for (int y = y0 - r; y <= y0 + r; y++) {
                        if (y < 0 || y >= ny) continue;
                        for (int x = x0 - r; x <= x0 + r; x++) {
                            if (x < 0 || x >= nx) continue;
                            // Only the shell at distance r; the inside was searched before.
                            if (Math.abs(x - x0) != r && Math.abs(y - y0) != r && Math.abs(z - z0) != r) continue;
                            for (int t : cells[(z * ny + y) * nx + x]) {
                                double d = squaredDistance(t, px, py, pz);
                                if (d < best) best = d; // a triangle with no area gives NaN, which is never less
                            }
                        }
                    }
                }
                // Every cell not yet searched is at least r cells away.
                double reach = (double) r * cell;
                if (best <= reach * reach) break;
            }
            return best == Double.MAX_VALUE ? 0f : (float) Math.sqrt(best);
        }

        /** The squared distance from a point to the nearest point of triangle t (Ericson, Real-Time Collision Detection 5.1.5). */
        private double squaredDistance(int t, double px, double py, double pz) {
            int a = idx[t * 3] * Mesh.FLOATS_PER_VERTEX, b = idx[t * 3 + 1] * Mesh.FLOATS_PER_VERTEX, c = idx[t * 3 + 2] * Mesh.FLOATS_PER_VERTEX;
            double ax = v[a], ay = v[a + 1], az = v[a + 2];
            double abx = v[b] - ax, aby = v[b + 1] - ay, abz = v[b + 2] - az;
            double acx = v[c] - ax, acy = v[c + 1] - ay, acz = v[c + 2] - az;
            double apx = px - ax, apy = py - ay, apz = pz - az;
            double d1 = abx * apx + aby * apy + abz * apz, d2 = acx * apx + acy * apy + acz * apz;
            double qx, qy, qz;
            if (d1 <= 0 && d2 <= 0) {
                qx = ax; qy = ay; qz = az;
            } else {
                double bpx = px - v[b], bpy = py - v[b + 1], bpz = pz - v[b + 2];
                double d3 = abx * bpx + aby * bpy + abz * bpz, d4 = acx * bpx + acy * bpy + acz * bpz;
                double cpx = px - v[c], cpy = py - v[c + 1], cpz = pz - v[c + 2];
                double d5 = abx * cpx + aby * cpy + abz * cpz, d6 = acx * cpx + acy * cpy + acz * cpz;
                double vc = d1 * d4 - d3 * d2, vb = d5 * d2 - d1 * d6, va = d3 * d6 - d5 * d4;
                if (d3 >= 0 && d4 <= d3) {
                    qx = v[b]; qy = v[b + 1]; qz = v[b + 2];
                } else if (d6 >= 0 && d5 <= d6) {
                    qx = v[c]; qy = v[c + 1]; qz = v[c + 2];
                } else if (vc <= 0 && d1 >= 0 && d3 <= 0) {
                    double s = d1 / (d1 - d3);
                    qx = ax + s * abx; qy = ay + s * aby; qz = az + s * abz;
                } else if (vb <= 0 && d2 >= 0 && d6 <= 0) {
                    double s = d2 / (d2 - d6);
                    qx = ax + s * acx; qy = ay + s * acy; qz = az + s * acz;
                } else if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
                    double s = (d4 - d3) / ((d4 - d3) + (d5 - d6));
                    qx = v[b] + s * (v[c] - v[b]); qy = v[b + 1] + s * (v[c + 1] - v[b + 1]); qz = v[b + 2] + s * (v[c + 2] - v[b + 2]);
                } else {
                    double sum = va + vb + vc;
                    if (Math.abs(sum) < 1e-30) {
                        qx = ax; qy = ay; qz = az;
                    } else {
                        double sv = vb / sum, sw = vc / sum;
                        qx = ax + abx * sv + acx * sw; qy = ay + aby * sv + acy * sw; qz = az + abz * sv + acz * sw;
                    }
                }
            }
            double dx = px - qx, dy = py - qy, dz = pz - qz;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
