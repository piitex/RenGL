package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Solves the rows of the joints exactly (the pivot of a ball joint or hinge, a hinge's axis, a weld, a slider's rails and
 * its lock on turning, and the stops and motors), all joints of a tree at once, instead of a pass at a time.
 * <p>
 * A pass-at-a-time solver passes a correction one link along a chain per pass, and a heavy end load takes more passes
 * than there are to hold the chain together. Here the rows of all the joints of a connected tree are one linear system,
 * {@code (J M^-1 J^T) x = -J v}, whose matrix has a block for each pair of joints that share a body. Joints are taken
 * out leaf first, as a block LDL factorisation, and because the bodies form a tree the joints left to update at each
 * step are all at one body, which they already share: no new coupling appears, so factoring and solving take time in
 * proportion to the number of joints, however long the chain or heavy the load.
 * <p>
 * A stop or motor is a row whose impulse depends on its speed in steps (a stop can only push, a motor only so hard, and
 * a joint with both has one row for the two), so which of them are pushing is not known beforehand. The tree starts
 * each from where it was last step. After each solve a row that pushed past what its step can give is moved to the
 * stretch beside it, pushing with a fixed impulse and left out of the system, and one held out whose speed has reached
 * a step is put back, and the system is solved again. That is a few extra factorisations when a stop is first touched
 * or let go, and none while the set stays as it is.
 * <p>
 * Only the joints that give rows ({@link Joint#directRows}) are taken; a joint that would close a loop of bodies, or a
 * rope or spring, stays with the pass-at-a-time solver.
 */
final class JointTree {
    /** The most a body is turned by one position pass, in radians, as a joint's own fix is held to. */
    private static final double MAX_TURN = Joint.MAX_ANGULAR_CORRECTION;
    /** A pivot below this fraction of the block's largest is a row the others already hold, and is left out. */
    private static final double DEPENDENT = 1e-9;
    /** How many times one velocity solve may change which stops and motors are pushing. */
    private static final int MAX_PASSES = 4;
    /** A held row lets go when its speed is this far past what it should be, so a row at rest doesn't flicker. */
    private static final double LET_GO = 1e-6;

    private static final class Entry {
        final Joint joint;
        final int m;
        int off, parent, nodeA = -1, nodeB = -1, localA, localB;
        final Joint.Row[] rows;
        final double[] diag = new double[Joint.MAX_DIRECT_ROWS * Joint.MAX_DIRECT_ROWS], inverse = new double[diag.length];
        /** The rows' effect on each body's velocity, [0] for body A and [1] for B: linear and turning, 3 per row. */
        final double[][] lin = {new double[Joint.MAX_DIRECT_ROWS * 3], new double[Joint.MAX_DIRECT_ROWS * 3]},
                ang = {new double[Joint.MAX_DIRECT_ROWS * 3], new double[Joint.MAX_DIRECT_ROWS * 3]};
        /** The same, with the rows of stops that aren't pushing left in: what a stop held at a bound still does. */
        final double[][] lin0 = {new double[Joint.MAX_DIRECT_ROWS * 3], new double[Joint.MAX_DIRECT_ROWS * 3]},
                ang0 = {new double[Joint.MAX_DIRECT_ROWS * 3], new double[Joint.MAX_DIRECT_ROWS * 3]};
        /** The rows from here on are stops and motors. */
        final int firstBounded;
        /**
         * For a stop or motor row, where on its staircase ({@link Joint.Row#steps}) it is: {@code 2k + 1} when it holds
         * the speed of step {@code k} (and is solved with the rest), and {@code 2k} when its speed is between steps
         * {@code k - 1} and {@code k} and it pushes with a fixed impulse (and is held out of the system).
         */
        final int[] mode = new int[Joint.MAX_DIRECT_ROWS];

        boolean pushing(int r) {
            return r < firstBounded || (mode[r] & 1) == 1;
        }

        /** Whether the row was kept when the system was last factored, or left out because the others already hold it. */
        final boolean[] kept = new boolean[Joint.MAX_DIRECT_ROWS];

        Entry(Joint joint) {
            this.joint = joint;
            this.m = joint.directRows();
            this.firstBounded = m - joint.directBoundedRows();
            this.rows = joint.directBuffer();
        }
    }

    /** The joints at one body, in the order they are taken out, and the matrix blocks between them. */
    private static final class Node {
        final RigidBody body;
        final List<Entry> entries = new ArrayList<>(2);
        double[][] block; // [p * d + q] is m_p x m_q, for p != q
        final double[] mass = new double[3], inertia = new double[9]; // what the system is weighted by

        Node(RigidBody body) {
            this.body = body;
        }
    }

    private final List<Entry> order = new ArrayList<>();
    private final List<Node> nodes = new ArrayList<>();
    private final Map<RigidBody, Integer> nodeOf = new IdentityHashMap<>();
    private double[] rhs = new double[0], solution = new double[0], scratch = new double[0];
    private final float[] error = new float[Joint.MAX_DIRECT_ROWS];
    private int total;
    private boolean anyBounded, stale;

    /** A stop or motor row held at a bound this solve, with the impulse that takes it there from where it was. */
    private record Held(Entry entry, int row, double delta) {
    }

    private final List<Held> held = new ArrayList<>();

    /**
     * Picks the joints it can solve and sets their {@link Joint#direct}, and clears it on the others.
     */
    void build(List<Joint> joints) {
        order.clear();
        nodes.clear();
        nodeOf.clear();
        total = 0;
        List<Joint> taken = new ArrayList<>();
        List<Integer> union = new ArrayList<>();
        for (Joint joint : joints) {
            joint.direct = false;
            if (joint.directRows() == 0) continue;
            boolean da = joint.a.isAwakeDynamic(), db = joint.b.isAwakeDynamic();
            if (!da && !db) continue;
            if (da && db) {
                int x = find(union, node(joint.a, union)), y = find(union, node(joint.b, union));
                if (x == y) continue; // closes a loop of bodies
                union.set(x, y);
            } else {
                node(da ? joint.a : joint.b, union);
            }
            taken.add(joint);
        }
        if (taken.isEmpty()) return;
        List<List<Joint>> at = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) at.add(new ArrayList<>(2));
        for (Joint joint : taken) {
            if (joint.a.isAwakeDynamic()) at.get(nodeOf.get(joint.a)).add(joint);
            if (joint.b.isAwakeDynamic()) at.get(nodeOf.get(joint.b)).add(joint);
        }
        sort(at);
        for (Entry entry : order) {
            Joint joint = entry.joint;
            joint.direct = true;
            entry.off = total;
            total += entry.m;
            Node a = joint.a.isAwakeDynamic() ? nodes.get(nodeOf.get(joint.a)) : null;
            Node b = joint.b.isAwakeDynamic() ? nodes.get(nodeOf.get(joint.b)) : null;
            if (a != null) {
                entry.nodeA = nodeOf.get(joint.a);
                entry.localA = a.entries.size();
                a.entries.add(entry);
            }
            if (b != null) {
                entry.nodeB = nodeOf.get(joint.b);
                entry.localB = b.entries.size();
                b.entries.add(entry);
            }
        }
        if (rhs.length < total) {
            rhs = new double[total];
            solution = new double[total];
            scratch = new double[total];
        }
        for (Node node : nodes) {
            int d = node.entries.size();
            node.block = new double[d * d][];
            for (int p = 0; p < d; p++) {
                for (int q = 0; q < d; q++) {
                    if (p != q) node.block[p * d + q] = new double[node.entries.get(p).m * node.entries.get(q).m];
                }
            }
        }
    }

    private int node(RigidBody body, List<Integer> union) {
        Integer index = nodeOf.get(body);
        if (index != null) return index;
        index = nodes.size();
        nodes.add(new Node(body));
        nodeOf.put(body, index);
        union.add(index);
        return index;
    }

    private static int find(List<Integer> union, int x) {
        while (union.get(x) != x) {
            union.set(x, union.get(union.get(x)));
            x = union.get(x);
        }
        return x;
    }

    /**
     * Puts the joints in the order they are taken out: depth first from each tree's first body, a joint after
     * everything beyond it.
     */
    private void sort(List<List<Joint>> at) {
        int count = nodes.size();
        boolean[] seen = new boolean[count];
        int[] stackNode = new int[count], stackIndex = new int[count];
        Joint[] stackVia = new Joint[count];
        for (int root = 0; root < count; root++) {
            if (seen[root]) continue;
            stackNode[0] = root;
            stackIndex[0] = 0;
            stackVia[0] = null;
            seen[root] = true;
            int sp = 1;
            while (sp > 0) {
                int top = sp - 1, x = stackNode[top];
                List<Joint> list = at.get(x);
                if (stackIndex[top] < list.size()) {
                    Joint joint = list.get(stackIndex[top]++);
                    if (joint == stackVia[top]) continue;
                    RigidBody other = nodes.get(x).body == joint.a ? joint.b : joint.a;
                    Integer child = other.isAwakeDynamic() ? nodeOf.get(other) : null;
                    if (child == null) {
                        add(joint, x);
                    } else {
                        seen[child] = true;
                        stackNode[sp] = child;
                        stackIndex[sp] = 0;
                        stackVia[sp] = joint;
                        sp++;
                    }
                } else {
                    sp--;
                    if (stackVia[top] != null) add(stackVia[top], stackNode[top - 1]);
                }
            }
        }
    }

    private void add(Joint joint, int parent) {
        Entry entry = new Entry(joint);
        entry.parent = parent;
        order.add(entry);
    }

    boolean isEmpty() {
        return order.isEmpty();
    }

    /**
     * Takes the rows from the joints as they are set up for the step, starts the stops and motors off as they were
     * last step, and factors the system.
     */
    void prepare() {
        if (order.isEmpty()) return;
        anyBounded = false;
        held.clear();
        for (Entry entry : order) {
            entry.joint.directVelocityRows(entry.rows);
            for (int r = entry.firstBounded; r < entry.m; r++) {
                anyBounded = true;
                entry.mode[r] = start(entry.rows[r], entry.joint.directAccumulated(r));
            }
        }
        factor(false);
        stale = false;
    }

    /**
     * Where on its staircase a row starts: held at the impulse it was at, if that is one a stretch between steps pushes
     * with, else on the step that has room for it (or the nearest, for an impulse that a weakened motor can't give).
     */
    private static int start(Joint.Row row, double acc) {
        int steps = row.steps;
        for (int p = 0; p <= 2 * steps; p += 2) {
            double at = between(row, p);
            if (Double.isFinite(at) && Math.abs(acc - at) <= tolerance(at)) return p;
        }
        int best = 1;
        double nearest = Double.POSITIVE_INFINITY;
        for (int k = 0; k < steps; k++) {
            double away = acc < row.low[k] ? row.low[k] - acc : acc > row.high[k] ? acc - row.high[k] : 0.0;
            if (away < nearest) {
                nearest = away;
                best = 2 * k + 1;
            }
        }
        return best;
    }

    /** The impulse a row pushes with while its speed is in stretch {@code p} (even) of its staircase. */
    private static double between(Joint.Row row, int p) {
        return p == 0 ? row.high[0] : row.low[p / 2 - 1];
    }

    private static double tolerance(double bound) {
        return 1e-6 * Math.max(1.0, Math.abs(bound));
    }

    /**
     * Stops the joints' rows from moving apart, with what each body is doing now.
     * <p>
     * The stops and motors that are holding a speed are solved with the rest. A row that comes out past the impulse it
     * can push with is moved off its step instead, and the system is solved again without it and with its push counted
     * in as known, before anything is applied to the bodies: the push on its own would throw the light bodies around
     * until the rest caught up.
     */
    void solveVelocity() {
        if (order.isEmpty()) return;
        for (int pass = 0; ; pass++) {
            do {
                if (stale) {
                    factor(false);
                    stale = false;
                }
                speeds();
                solve();
            } while (anyBounded && clamp());
            for (Held h : held) solution[h.entry.off + h.row] += h.delta;
            held.clear();
            for (Entry entry : order) {
                for (int r = 0; r < entry.m; r++) entry.rows[r].apply(entry.joint.a, entry.joint.b, (float) solution[entry.off + r]);
                entry.joint.directImpulses(solution, entry.off);
            }
            if (!anyBounded || pass == MAX_PASSES || !recheck()) return;
            stale = true;
        }
    }

    /**
     * Sets the right-hand side to minus each row's speed (from the speed it should hold, for a stop or motor), less what
     * the impulses already held between steps would do to it.
     */
    private void speeds() {
        for (Entry entry : order) {
            for (int r = 0; r < entry.m; r++) {
                Joint.Row row = entry.rows[r];
                double speed = row.velocity(entry.joint.a, entry.joint.b);
                rhs[entry.off + r] = r < entry.firstBounded ? -speed
                        : (entry.mode[r] & 1) == 1 ? row.speed[entry.mode[r] / 2] - speed : 0.0;
            }
        }
        for (Held h : held) {
            Entry p = h.entry;
            for (int side = 0; side < 2; side++) {
                int n = side == 0 ? p.nodeA : p.nodeB;
                if (n < 0) continue;
                Node node = nodes.get(n);
                for (Entry e : node.entries) {
                    int se = e.nodeB == n ? 1 : 0;
                    for (int s = 0; s < e.m; s++) {
                        if (!e.pushing(s)) continue;
                        double v = 0;
                        for (int i = 0; i < 3; i++) {
                            v += p.lin0[side][h.row * 3 + i] * e.lin0[se][s * 3 + i] * node.mass[i];
                            double iw = node.inertia[i * 3] * e.ang0[se][s * 3] + node.inertia[i * 3 + 1] * e.ang0[se][s * 3 + 1]
                                    + node.inertia[i * 3 + 2] * e.ang0[se][s * 3 + 2];
                            v += p.ang0[side][h.row * 3 + i] * iw;
                        }
                        rhs[e.off + s] -= v * h.delta;
                    }
                }
            }
        }
    }

    /**
     * Moves each stop or motor row whose impulse came out past what its step can give off the step, to the stretch
     * beside it that pushes with the impulse at that edge.
     *
     * @return true if any was, so the rest has to be solved again
     */
    private boolean clamp() {
        boolean any = false;
        for (Entry entry : order) {
            for (int r = entry.firstBounded; r < entry.m; r++) {
                if (!entry.pushing(r)) continue;
                Joint.Row row = entry.rows[r];
                int k = entry.mode[r] / 2;
                double acc = entry.joint.directAccumulated(r), next = acc + solution[entry.off + r];
                if (next > row.high[k]) hold(entry, r, 2 * k, row.high[k] - acc);
                else if (next < row.low[k]) hold(entry, r, 2 * k + 2, row.low[k] - acc);
                else continue;
                any = true;
            }
        }
        if (any) stale = true;
        return any;
    }

    private void hold(Entry entry, int r, int mode, double delta) {
        entry.mode[r] = mode;
        held.add(new Held(entry, r, delta));
    }

    /**
     * After the rows have been applied: puts a row held between steps back on the step its speed has reached.
     *
     * @return true if any row moved, so there is more to solve
     */
    private boolean recheck() {
        boolean changed = false;
        for (Entry entry : order) {
            for (int r = entry.firstBounded; r < entry.m; r++) {
                int p = entry.mode[r];
                if ((p & 1) == 1) continue;
                Joint.Row row = entry.rows[r];
                double speed = row.velocity(entry.joint.a, entry.joint.b);
                if (p > 0 && speed < row.speed[p / 2 - 1] - LET_GO) entry.mode[r] = p - 1;
                else if (p < 2 * row.steps && speed > row.speed[p / 2] + LET_GO) entry.mode[r] = p + 1;
                else continue;
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Moves the bodies so the joints' rows close, with where each body is now.
     *
     * @return true if every joint was already as close as it needs to be
     */
    boolean solvePosition() {
        if (order.isEmpty()) return true;
        boolean done = true;
        for (Entry entry : order) {
            done &= entry.joint.directPositionRows(entry.rows, error);
            for (int r = 0; r < entry.m; r++) rhs[entry.off + r] = -error[r];
        }
        factor(true);
        solve();
        // How far each body goes: what the rows push it with, over its (shared-out) weight.
        double[] move = new double[nodes.size() * 6];
        double worst = 0;
        for (int n = 0; n < nodes.size(); n++) {
            Node node = nodes.get(n);
            double fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            for (Entry e : node.entries) {
                int side = e.nodeB == n ? 1 : 0;
                for (int r = 0; r < e.m; r++) {
                    double l = solution[e.off + r];
                    fx += e.lin[side][r * 3] * l;
                    fy += e.lin[side][r * 3 + 1] * l;
                    fz += e.lin[side][r * 3 + 2] * l;
                    tx += e.ang[side][r * 3] * l;
                    ty += e.ang[side][r * 3 + 1] * l;
                    tz += e.ang[side][r * 3 + 2] * l;
                }
            }
            double[] w = node.inertia;
            move[n * 6] = node.mass[0] * fx;
            move[n * 6 + 1] = node.mass[1] * fy;
            move[n * 6 + 2] = node.mass[2] * fz;
            move[n * 6 + 3] = w[0] * tx + w[1] * ty + w[2] * tz;
            move[n * 6 + 4] = w[3] * tx + w[4] * ty + w[5] * tz;
            move[n * 6 + 5] = w[6] * tx + w[7] * ty + w[8] * tz;
            worst = Math.max(worst, Math.sqrt(move[n * 6 + 3] * move[n * 6 + 3] + move[n * 6 + 4] * move[n * 6 + 4] + move[n * 6 + 5] * move[n * 6 + 5]));
        }
        double scale = worst > MAX_TURN ? MAX_TURN / worst : 1.0;
        for (int n = 0; n < nodes.size(); n++) {
            nodes.get(n).body.shift((float) (move[n * 6] * scale), (float) (move[n * 6 + 1] * scale), (float) (move[n * 6 + 2] * scale),
                    (float) (move[n * 6 + 3] * scale), (float) (move[n * 6 + 4] * scale), (float) (move[n * 6 + 5] * scale));
        }
        return done;
    }

    // ---- The system -----------------------------------------------------------------------------------------------

    private void factor(boolean uniform) {
        assemble(uniform);
        for (Entry p : order) {
            invert(p.diag, p.m, p.inverse, p.kept);
            Node node = nodes.get(p.parent);
            int d = node.entries.size(), lp = p.nodeA == p.parent ? p.localA : p.localB;
            for (int k = lp + 1; k < d; k++) {
                Entry ek = node.entries.get(k);
                double[] akp = node.block[k * d + lp];
                double[] t = new double[ek.m * p.m]; // A_kp * D_p^-1
                multiply(akp, ek.m, p.m, p.inverse, p.m, t);
                for (int l = lp + 1; l < d; l++) {
                    Entry el = node.entries.get(l);
                    double[] apl = node.block[lp * d + l];
                    if (k == l) subtractProduct(ek.diag, t, ek.m, p.m, apl, ek.m);
                    else subtractProduct(node.block[k * d + l], t, ek.m, p.m, apl, el.m);
                }
            }
        }
    }

    /**
     * Fills in the matrix {@code J M^-1 J^T}: a block for each pair of joints at a body, and for each joint its own.
     */
    private void assemble(boolean uniform) {
        for (Entry e : order) {
            Arrays.fill(e.diag, 0, e.m * e.m, 0.0);
            for (int r = 0; r < e.m; r++) {
                Joint.Row row = e.rows[r];
                e.lin[1][r * 3] = row.nx;
                e.lin[1][r * 3 + 1] = row.ny;
                e.lin[1][r * 3 + 2] = row.nz;
                e.ang[1][r * 3] = row.sbx;
                e.ang[1][r * 3 + 1] = row.sby;
                e.ang[1][r * 3 + 2] = row.sbz;
                e.lin[0][r * 3] = -row.nx;
                e.lin[0][r * 3 + 1] = -row.ny;
                e.lin[0][r * 3 + 2] = -row.nz;
                e.ang[0][r * 3] = -row.sax;
                e.ang[0][r * 3 + 1] = -row.say;
                e.ang[0][r * 3 + 2] = -row.saz;
                for (int side = 0; side < 2; side++) {
                    for (int i = 0; i < 3; i++) {
                        e.lin0[side][r * 3 + i] = e.lin[side][r * 3 + i];
                        e.ang0[side][r * 3 + i] = e.ang[side][r * 3 + i];
                    }
                }
                // A stop that isn't pushing is out of the system for the speeds; for positions the joint leaves the
                // row empty itself.
                if (!uniform && !e.pushing(r)) {
                    for (int side = 0; side < 2; side++) {
                        for (int i = 0; i < 3; i++) e.lin[side][r * 3 + i] = e.ang[side][r * 3 + i] = 0.0;
                    }
                }
            }
        }
        for (int n = 0; n < nodes.size(); n++) {
            Node node = nodes.get(n);
            Vec3 im = node.body.inverseMassAxes();
            Mat3 ii = node.body.inverseInertiaWorld();
            double[] mass = node.mass, inertia = node.inertia;
            mass[0] = im.x();
            mass[1] = im.y();
            mass[2] = im.z();
            inertia[0] = ii.xx();
            inertia[1] = ii.xy();
            inertia[2] = ii.xz();
            inertia[3] = ii.yx();
            inertia[4] = ii.yy();
            inertia[5] = ii.yz();
            inertia[6] = ii.zx();
            inertia[7] = ii.zy();
            inertia[8] = ii.zz();
            if (uniform) {
                // Every body weighs the same, with the same shape, so a correction is shared out instead of all of it
                // going to the lightest parts, which would have to turn a long way.
                double m = node.body.getInverseMass() > 0f ? 1.0 / node.body.getInverseMass() : 0.0;
                for (int i = 0; i < 3; i++) mass[i] = mass[i] > 0 ? 1.0 : 0.0;
                for (int i = 0; i < 9; i++) inertia[i] *= m;
            }
            int d = node.entries.size();
            for (int p = 0; p < d; p++) {
                Entry ep = node.entries.get(p);
                int sp = ep.nodeB == n ? 1 : 0;
                for (int q = p; q < d; q++) {
                    Entry eq = node.entries.get(q);
                    int sq = eq.nodeB == n ? 1 : 0;
                    for (int r = 0; r < ep.m; r++) {
                        for (int s = p == q ? r : 0; s < eq.m; s++) {
                            double v = 0;
                            for (int i = 0; i < 3; i++) {
                                v += ep.lin[sp][r * 3 + i] * eq.lin[sq][s * 3 + i] * mass[i];
                                double iw = inertia[i * 3] * eq.ang[sq][s * 3] + inertia[i * 3 + 1] * eq.ang[sq][s * 3 + 1]
                                        + inertia[i * 3 + 2] * eq.ang[sq][s * 3 + 2];
                                v += ep.ang[sp][r * 3 + i] * iw;
                            }
                            if (p == q) {
                                ep.diag[r * ep.m + s] += v;
                                if (s != r) ep.diag[s * ep.m + r] += v;
                            } else {
                                node.block[p * d + q][r * eq.m + s] = v;
                                node.block[q * d + p][s * ep.m + r] = v;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Solves for {@link #rhs} into {@link #solution}.
     */
    private void solve() {
        System.arraycopy(rhs, 0, scratch, 0, total);
        double[] z = solution;
        for (Entry p : order) {
            multiplyVector(p.inverse, p.m, scratch, p.off, z, p.off);
            Node node = nodes.get(p.parent);
            int d = node.entries.size(), lp = p.nodeA == p.parent ? p.localA : p.localB;
            for (int k = lp + 1; k < d; k++) {
                Entry ek = node.entries.get(k);
                double[] akp = node.block[k * d + lp];
                for (int r = 0; r < ek.m; r++) {
                    double v = 0;
                    for (int c = 0; c < p.m; c++) v += akp[r * p.m + c] * z[p.off + c];
                    scratch[ek.off + r] -= v;
                }
            }
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            Entry p = order.get(i);
            Node node = nodes.get(p.parent);
            int d = node.entries.size(), lp = p.nodeA == p.parent ? p.localA : p.localB;
            for (int r = 0; r < p.m; r++) {
                double v = 0;
                for (int k = lp + 1; k < d; k++) {
                    Entry ek = node.entries.get(k);
                    double[] apk = node.block[lp * d + k];
                    for (int c = 0; c < ek.m; c++) v += apk[r * ek.m + c] * solution[ek.off + c];
                }
                scratch[p.off + r] = v;
            }
            // x_p = z_p - D^-1 * (sum A_pk x_k)
            for (int r = 0; r < p.m; r++) {
                double v = 0;
                for (int c = 0; c < p.m; c++) v += p.inverse[r * p.m + c] * scratch[p.off + c];
                solution[p.off + r] = z[p.off + r] - v;
            }
        }
    }

    // ---- Small dense algebra --------------------------------------------------------------------------------------

    private static void multiply(double[] a, int ar, int ac, double[] b, int bc, double[] out) {
        for (int i = 0; i < ar; i++) {
            for (int j = 0; j < bc; j++) {
                double v = 0;
                for (int k = 0; k < ac; k++) v += a[i * ac + k] * b[k * bc + j];
                out[i * bc + j] = v;
            }
        }
    }

    /** {@code target -= t * b}, with t of {@code rows x inner} and b of {@code inner x cols}. */
    private static void subtractProduct(double[] target, double[] t, int rows, int inner, double[] b, int cols) {
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                double v = 0;
                for (int k = 0; k < inner; k++) v += t[i * inner + k] * b[k * cols + j];
                target[i * cols + j] -= v;
            }
        }
    }

    private static void multiplyVector(double[] m, int n, double[] x, int xAt, double[] out, int outAt) {
        for (int i = 0; i < n; i++) {
            double v = 0;
            for (int j = 0; j < n; j++) v += m[i * n + j] * x[xAt + j];
            out[outAt + i] = v;
        }
    }

    /**
     * Inverts a symmetric positive semi-definite matrix of up to 6x6, leaving out any row that the others already hold
     * (as a hinge held to a body that cannot turn would have).
     */
    private static void invert(double[] a, int n, double[] out, boolean[] kept) {
        double[] l = new double[n * n];
        Arrays.fill(kept, false);
        double largest = 0;
        for (int i = 0; i < n; i++) largest = Math.max(largest, a[i * n + i]);
        for (int j = 0; j < n; j++) {
            double s = a[j * n + j];
            for (int k = 0; k < j; k++) s -= l[j * n + k] * l[j * n + k];
            if (s <= DEPENDENT * largest) continue;
            kept[j] = true;
            double root = Math.sqrt(s);
            l[j * n + j] = root;
            for (int i = j + 1; i < n; i++) {
                double v = a[i * n + j];
                for (int k = 0; k < j; k++) v -= l[i * n + k] * l[j * n + k];
                l[i * n + j] = v / root;
            }
        }
        double[] column = new double[n];
        for (int c = 0; c < n; c++) {
            Arrays.fill(column, 0.0);
            if (kept[c]) {
                column[c] = 1.0;
                for (int i = 0; i < n; i++) {
                    if (!kept[i]) {
                        column[i] = 0;
                        continue;
                    }
                    double v = column[i];
                    for (int k = 0; k < i; k++) v -= l[i * n + k] * column[k];
                    column[i] = v / l[i * n + i];
                }
                for (int i = n - 1; i >= 0; i--) {
                    if (!kept[i]) continue;
                    double v = column[i];
                    for (int k = i + 1; k < n; k++) v -= l[k * n + i] * column[k];
                    column[i] = v / l[i * n + i];
                }
            }
            for (int i = 0; i < n; i++) out[i * n + c] = column[i];
        }
    }
}
