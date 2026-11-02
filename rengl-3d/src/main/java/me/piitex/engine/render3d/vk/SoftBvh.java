package me.piitex.engine.render3d.vk;

import java.util.Arrays;

/**
 * A bounding volume hierarchy over boxes, built on the CPU, for tracing rays in shader code on a GPU that has no ray queries.
 * It is laid out for the shader: a node is {@link #NODE_WORDS} words in a flat array, and a node's left child is the node after
 * it, so only the right child needs saying.
 * <pre>
 * word 0-2  the box's smallest corner (floats)
 * word 3    an inner node: the index of its right child; a leaf: the place of its first box in {@link #order}
 * word 4-6  the box's largest corner (floats)
 * word 7    a leaf: how many boxes it holds; an inner node: {@link #INNER} plus the axis it was split along
 * </pre>
 * It is cut by the surface area heuristic over {@value #BINS} bins, and by the median once it is {@value #SAH_DEPTH} deep, which
 * keeps it no deeper than the shader's stack ({@link #MAX_DEPTH}).
 */
final class SoftBvh {
    static final int NODE_WORDS = 8;
    /** Set in word 7 of an inner node. */
    static final int INNER = 0x10000;
    /** The deepest a tree can go, which is as many as the shader's stack holds. */
    static final int MAX_DEPTH = 40;
    private static final int BINS = 16;
    private static final int SAH_DEPTH = 12;
    /** The most boxes a leaf holds. */
    private static final int LEAF_LIMIT = 8;

    /** The nodes, root first. */
    final int[] nodes;
    final int nodeCount;
    /** The boxes in the order the leaves hold them: leaf {@code first}..{@code first + count} are {@code order[first..]}. */
    final int[] order;
    /** The longest path from the root to a leaf, counted in nodes. */
    final int depth;

    private SoftBvh(int[] nodes, int nodeCount, int[] order, int depth) {
        this.nodes = nodes;
        this.nodeCount = nodeCount;
        this.order = order;
        this.depth = depth;
    }

    float minX() {
        return Float.intBitsToFloat(nodes[0]);
    }

    float minY() {
        return Float.intBitsToFloat(nodes[1]);
    }

    float minZ() {
        return Float.intBitsToFloat(nodes[2]);
    }

    float maxX() {
        return Float.intBitsToFloat(nodes[4]);
    }

    float maxY() {
        return Float.intBitsToFloat(nodes[5]);
    }

    float maxZ() {
        return Float.intBitsToFloat(nodes[6]);
    }

    /**
     * @param boxes   six floats for each box: its smallest corner, then its largest
     * @param count   how many boxes
     * @param maxLeaf the most boxes the builder puts in a leaf when it can help it (1 to {@value #LEAF_LIMIT})
     */
    static SoftBvh build(float[] boxes, int count, int maxLeaf) {
        if (count < 1) throw new IllegalArgumentException("A hierarchy needs a box");
        if (boxes.length < count * 6) throw new IllegalArgumentException("Six floats for each of " + count + " boxes");
        Builder builder = new Builder(boxes, count, Math.clamp(maxLeaf, 1, LEAF_LIMIT));
        builder.node(0, count, 1);
        return new SoftBvh(Arrays.copyOf(builder.words, builder.next * NODE_WORDS), builder.next, builder.order, builder.deepest);
    }

    private static final class Builder {
        final float[] boxes;
        final float[] centres;
        final int[] order;
        final int[] words;
        final int maxLeaf;
        int next;
        int deepest;

        Builder(float[] boxes, int count, int maxLeaf) {
            this.boxes = boxes;
            this.maxLeaf = maxLeaf;
            centres = new float[count * 3];
            order = new int[count];
            for (int i = 0; i < count; i++) {
                order[i] = i;
                for (int a = 0; a < 3; a++) centres[i * 3 + a] = 0.5f * (boxes[i * 6 + a] + boxes[i * 6 + 3 + a]);
            }
            words = new int[(2 * count - 1) * NODE_WORDS];
        }

        /** Builds the node for {@code order[start..end)} and returns its index. */
        int node(int start, int end, int depth) {
            int me = next++;
            deepest = Math.max(deepest, depth);
            float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            float[] centre = bounds.clone();
            for (int i = start; i < end; i++) {
                int b = order[i];
                for (int a = 0; a < 3; a++) {
                    bounds[a] = Math.min(bounds[a], boxes[b * 6 + a]);
                    bounds[3 + a] = Math.max(bounds[3 + a], boxes[b * 6 + 3 + a]);
                    centre[a] = Math.min(centre[a], centres[b * 3 + a]);
                    centre[3 + a] = Math.max(centre[3 + a], centres[b * 3 + a]);
                }
            }
            int at = me * NODE_WORDS;
            for (int a = 0; a < 3; a++) {
                words[at + a] = Float.floatToRawIntBits(bounds[a]);
                words[at + 4 + a] = Float.floatToRawIntBits(bounds[3 + a]);
            }
            int count = end - start;
            if (count <= maxLeaf) {
                words[at + 3] = start;
                words[at + 7] = count;
                return me;
            }
            int axis = 0;
            for (int a = 1; a < 3; a++) if (centre[3 + a] - centre[a] > centre[3 + axis] - centre[axis]) axis = a;
            int mid = depth < SAH_DEPTH ? splitByCost(start, end, axis, bounds, centre) : -1;
            if (mid <= start || mid >= end) {
                // Median: every box on one side of the middle one along the axis. Boxes with the same centre fall either way.
                mid = (start + end) >>> 1;
                select(start, end, mid, axis);
            }
            words[at + 7] = INNER | axis;
            node(start, mid, depth + 1);
            words[at + 3] = node(mid, end, depth + 1);
            return me;
        }

        /**
         * Cuts the range along the axis where the surface area heuristic says it is cheapest and returns where the second half
         * starts, or -1 where no cut helps (every centre is the same).
         */
        private int splitByCost(int start, int end, int axis, float[] bounds, float[] centre) {
            float low = centre[axis], extent = centre[3 + axis] - low;
            if (!(extent > 0f)) return -1;
            float[] binBox = new float[BINS * 6];
            int[] binCount = new int[BINS];
            for (int k = 0; k < BINS; k++) {
                for (int a = 0; a < 3; a++) {
                    binBox[k * 6 + a] = Float.POSITIVE_INFINITY;
                    binBox[k * 6 + 3 + a] = Float.NEGATIVE_INFINITY;
                }
            }
            float scale = BINS / extent;
            for (int i = start; i < end; i++) {
                int b = order[i];
                int bin = Math.min(BINS - 1, (int) ((centres[b * 3 + axis] - low) * scale));
                binCount[bin]++;
                for (int a = 0; a < 3; a++) {
                    binBox[bin * 6 + a] = Math.min(binBox[bin * 6 + a], boxes[b * 6 + a]);
                    binBox[bin * 6 + 3 + a] = Math.max(binBox[bin * 6 + 3 + a], boxes[b * 6 + 3 + a]);
                }
            }
            // The cost of cutting after each bin: boxes times area, for the part below and the part above.
            float[] below = new float[BINS - 1];
            float[] run = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            int seen = 0;
            for (int k = 0; k < BINS - 1; k++) {
                grow(run, binBox, k);
                seen += binCount[k];
                below[k] = seen * area(run);
            }
            run = new float[]{Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            seen = 0;
            float best = Float.POSITIVE_INFINITY;
            int cut = -1;
            for (int k = BINS - 1; k > 0; k--) {
                grow(run, binBox, k);
                seen += binCount[k];
                float cost = below[k - 1] + seen * area(run);
                if (cost < best) {
                    best = cost;
                    cut = k;
                }
            }
            if (cut < 0) return -1;
            // Everything in a bin below the cut goes first.
            int i = start, j = end - 1;
            while (i <= j) {
                int bin = Math.min(BINS - 1, (int) ((centres[order[i] * 3 + axis] - low) * scale));
                if (bin < cut) {
                    i++;
                } else {
                    int t = order[i];
                    order[i] = order[j];
                    order[j] = t;
                    j--;
                }
            }
            return i;
        }

        private static void grow(float[] into, float[] binBox, int k) {
            if (binBox[k * 6] > binBox[k * 6 + 3]) return; // an empty bin
            for (int a = 0; a < 3; a++) {
                into[a] = Math.min(into[a], binBox[k * 6 + a]);
                into[3 + a] = Math.max(into[3 + a], binBox[k * 6 + 3 + a]);
            }
        }

        private static float area(float[] box) {
            if (box[0] > box[3]) return 0f;
            float x = box[3] - box[0], y = box[4] - box[1], z = box[5] - box[2];
            return 2f * (x * y + y * z + z * x);
        }

        /** Quickselect: puts the box that belongs at {@code nth} there, smaller centres before it and larger after. */
        private void select(int start, int end, int nth, int axis) {
            int lo = start, hi = end - 1;
            while (lo < hi) {
                float pivot = centres[order[(lo + hi) >>> 1] * 3 + axis];
                int i = lo, j = hi;
                while (i <= j) {
                    while (centres[order[i] * 3 + axis] < pivot) i++;
                    while (centres[order[j] * 3 + axis] > pivot) j--;
                    if (i <= j) {
                        int t = order[i];
                        order[i] = order[j];
                        order[j] = t;
                        i++;
                        j--;
                    }
                }
                if (nth <= j) hi = j;
                else if (nth >= i) lo = i;
                else return;
            }
        }
    }
}
