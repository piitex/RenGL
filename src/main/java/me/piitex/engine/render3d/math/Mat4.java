package me.piitex.engine.render3d.math;

import java.nio.ByteBuffer;

/**
 * An immutable 4x4 matrix, stored column-major like GLSL: element {@code (row, column)} is at {@code column * 4 + row}.
 * Points are transformed as column vectors, {@code m.mul(n)} applies {@code n} first.
 * <p>
 * Projections made here match the 3D layer's clip space on both backends: +Y up and depth from 0 at the near plane to
 * 1 at the far plane.
 */
public final class Mat4 {
    private final float[] m;

    /**
     * @param columnMajor 16 floats; the array is used as is, so do not change it afterwards
     */
    public Mat4(float[] columnMajor) {
        if (columnMajor.length != 16) throw new IllegalArgumentException("A Mat4 needs 16 values");
        this.m = columnMajor;
    }

    public static Mat4 identity() {
        return new Mat4(new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
    }

    public static Mat4 translation(Vec3 t) {
        Mat4 r = identity();
        r.m[12] = t.x();
        r.m[13] = t.y();
        r.m[14] = t.z();
        return r;
    }

    public static Mat4 scale(Vec3 s) {
        Mat4 r = identity();
        r.m[0] = s.x();
        r.m[5] = s.y();
        r.m[10] = s.z();
        return r;
    }

    /**
     * Scale, then rotate, then move.
     */
    public static Mat4 compose(Vec3 position, Quat rotation, Vec3 scale) {
        Mat4 r = rotation.toMatrix();
        for (int i = 0; i < 3; i++) {
            r.m[i] *= scale.x();
            r.m[4 + i] *= scale.y();
            r.m[8 + i] *= scale.z();
        }
        r.m[12] = position.x();
        r.m[13] = position.y();
        r.m[14] = position.z();
        return r;
    }

    /**
     * A perspective projection.
     *
     * @param fovY   the vertical field of view in radians
     * @param aspect width divided by height
     */
    public static Mat4 perspective(float fovY, float aspect, float near, float far) {
        float f = (float) (1.0 / Math.tan(fovY * 0.5));
        float[] r = new float[16];
        r[0] = f / aspect;
        r[5] = f;
        r[10] = far / (near - far);
        r[11] = -1;
        r[14] = near * far / (near - far);
        return new Mat4(r);
    }

    /**
     * A view matrix for a camera at {@code eye} looking at {@code target}.
     */
    public static Mat4 lookAt(Vec3 eye, Vec3 target, Vec3 up) {
        Vec3 f = target.sub(eye).normalized();
        Vec3 s = f.cross(up).normalized();
        Vec3 u = s.cross(f);
        float[] r = new float[16];
        r[0] = s.x();
        r[4] = s.y();
        r[8] = s.z();
        r[1] = u.x();
        r[5] = u.y();
        r[9] = u.z();
        r[2] = -f.x();
        r[6] = -f.y();
        r[10] = -f.z();
        r[12] = -s.dot(eye);
        r[13] = -u.dot(eye);
        r[14] = f.dot(eye);
        r[15] = 1;
        return new Mat4(r);
    }

    public float get(int row, int column) {
        return m[column * 4 + row];
    }

    /**
     * A copy of the 16 column-major values.
     */
    public float[] toArray() {
        return m.clone();
    }

    /**
     * Writes the 16 values at the buffer's position (advancing it), the layout a GLSL {@code mat4} reads.
     */
    public void put(ByteBuffer out) {
        for (float v : m) out.putFloat(v);
    }

    public Mat4 mul(Mat4 o) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) sum += m[k * 4 + row] * o.m[c * 4 + k];
                r[c * 4 + row] = sum;
            }
        }
        return new Mat4(r);
    }

    public Vec3 transformPoint(Vec3 p) {
        float w = m[3] * p.x() + m[7] * p.y() + m[11] * p.z() + m[15];
        if (Math.abs(w) < 1e-12f) w = 1;
        return new Vec3(
                (m[0] * p.x() + m[4] * p.y() + m[8] * p.z() + m[12]) / w,
                (m[1] * p.x() + m[5] * p.y() + m[9] * p.z() + m[13]) / w,
                (m[2] * p.x() + m[6] * p.y() + m[10] * p.z() + m[14]) / w);
    }

    /**
     * Transforms a direction: rotation and scale, no translation.
     */
    public Vec3 transformDirection(Vec3 d) {
        return new Vec3(
                m[0] * d.x() + m[4] * d.y() + m[8] * d.z(),
                m[1] * d.x() + m[5] * d.y() + m[9] * d.z(),
                m[2] * d.x() + m[6] * d.y() + m[10] * d.z());
    }

    /**
     * The inverse matrix, or null when this one can't be undone (a zero scale, for instance).
     */
    public Mat4 inverse() {
        float[] a = m;
        float[] r = new float[16];
        r[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        r[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        r[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        r[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        r[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        r[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        r[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        r[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        r[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        r[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        r[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        r[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        r[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        r[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        r[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        r[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
        float det = a[0] * r[0] + a[1] * r[4] + a[2] * r[8] + a[3] * r[12];
        if (Math.abs(det) < 1e-30f || Float.isNaN(det)) return null;
        float inv = 1f / det;
        for (int i = 0; i < 16; i++) r[i] *= inv;
        return new Mat4(r);
    }

    /**
     * The matrix that carries surface normals along with this transform: the inverse transpose of its upper-left
     * 3x3, in a 4x4 so a shader can read it as a {@code mat4}.
     */
    public Mat4 normalMatrix() {
        float a = m[0], b = m[4], c = m[8];
        float d = m[1], e = m[5], f = m[9];
        float g = m[2], h = m[6], i = m[10];
        float c00 = e * i - f * h, c01 = f * g - d * i, c02 = d * h - e * g;
        float c10 = c * h - b * i, c11 = a * i - c * g, c12 = b * g - a * h;
        float c20 = b * f - c * e, c21 = c * d - a * f, c22 = a * e - b * d;
        float det = a * c00 + b * c01 + c * c02;
        float inv = Math.abs(det) < 1e-20f ? 1f : 1f / det;
        // The cofactor matrix over the determinant is the inverse transpose, and it is already laid out row by row.
        float[] r = new float[16];
        r[0] = c00 * inv;
        r[4] = c01 * inv;
        r[8] = c02 * inv;
        r[1] = c10 * inv;
        r[5] = c11 * inv;
        r[9] = c12 * inv;
        r[2] = c20 * inv;
        r[6] = c21 * inv;
        r[10] = c22 * inv;
        r[15] = 1;
        return new Mat4(r);
    }

    /**
     * Splits a matrix made of a move, a rotation and a scale back into those parts. Shear is dropped; a mirroring
     * matrix comes back with a negative X scale.
     */
    public Trs decompose() {
        Vec3 position = new Vec3(m[12], m[13], m[14]);
        Vec3 cx = new Vec3(m[0], m[1], m[2]);
        Vec3 cy = new Vec3(m[4], m[5], m[6]);
        Vec3 cz = new Vec3(m[8], m[9], m[10]);
        float sx = cx.length(), sy = cy.length(), sz = cz.length();
        if (cx.cross(cy).dot(cz) < 0) sx = -sx;
        if (Math.abs(sx) < 1e-12f || sy < 1e-12f || sz < 1e-12f) return new Trs(position, Quat.IDENTITY, new Vec3(sx, sy, sz));

        float r00 = m[0] / sx, r10 = m[1] / sx, r20 = m[2] / sx;
        float r01 = m[4] / sy, r11 = m[5] / sy, r21 = m[6] / sy;
        float r02 = m[8] / sz, r12 = m[9] / sz, r22 = m[10] / sz;

        float trace = r00 + r11 + r22;
        float qx, qy, qz, qw;
        if (trace > 0) {
            float s = (float) Math.sqrt(trace + 1f) * 2f;
            qw = 0.25f * s;
            qx = (r21 - r12) / s;
            qy = (r02 - r20) / s;
            qz = (r10 - r01) / s;
        } else if (r00 > r11 && r00 > r22) {
            float s = (float) Math.sqrt(1f + r00 - r11 - r22) * 2f;
            qw = (r21 - r12) / s;
            qx = 0.25f * s;
            qy = (r01 + r10) / s;
            qz = (r02 + r20) / s;
        } else if (r11 > r22) {
            float s = (float) Math.sqrt(1f + r11 - r00 - r22) * 2f;
            qw = (r02 - r20) / s;
            qx = (r01 + r10) / s;
            qy = 0.25f * s;
            qz = (r12 + r21) / s;
        } else {
            float s = (float) Math.sqrt(1f + r22 - r00 - r11) * 2f;
            qw = (r10 - r01) / s;
            qx = (r02 + r20) / s;
            qy = (r12 + r21) / s;
            qz = 0.25f * s;
        }
        return new Trs(position, new Quat(qx, qy, qz, qw).normalized(), new Vec3(sx, sy, sz));
    }

    /**
     * The parts of a transform, from {@link #decompose()}.
     */
    public record Trs(Vec3 position, Quat rotation, Vec3 scale) {
    }
}
