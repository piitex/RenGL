package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * Rotations as three angles in degrees, which is how a scene file and an editor give them: a turn about X (pitch,
 * nodding), about Y (yaw, turning left and right) and about Z (roll, tilting), applied in that order from the last to
 * the first, as {@link Quat#fromEuler} does. {@code Vec3(x = pitch, y = yaw, z = roll)}.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class Euler {
    private Euler() {
    }

    /** The rotation of {@code degrees} (pitch, yaw, roll). */
    public static Quat toQuat(Vec3 degrees) {
        return Quat.fromEuler((float) Math.toRadians(degrees.x()), (float) Math.toRadians(degrees.y()), (float) Math.toRadians(degrees.z()));
    }

    /**
     * Angles that give {@code q}, with pitch within -90 to 90 and yaw and roll within -180 to 180. Turning past straight
     * up or down loses the difference between yaw and roll (gimbal lock), and then all of it is put in yaw.
     */
    public static Vec3 of(Quat q) {
        Quat n = q.normalized();
        float x = n.x(), y = n.y(), z = n.z(), w = n.w();
        // The entries of the rotation matrix that tell the angles apart.
        float r02 = 2 * (x * z + w * y), r12 = 2 * (y * z - w * x), r22 = 1 - 2 * (x * x + y * y);
        float r10 = 2 * (x * y + w * z), r11 = 1 - 2 * (x * x + z * z);
        float r00 = 1 - 2 * (y * y + z * z), r20 = 2 * (x * z - w * y);
        double pitch = Math.asin(Math.max(-1f, Math.min(1f, -r12)));
        double yaw, roll;
        if (Math.abs(r12) < 0.99999f) {
            yaw = Math.atan2(r02, r22);
            roll = Math.atan2(r10, r11);
        } else {
            yaw = Math.atan2(-r20, r00);
            roll = 0;
        }
        return new Vec3((float) Math.toDegrees(pitch), (float) Math.toDegrees(yaw), (float) Math.toDegrees(roll));
    }

    /** Whether two rotations are the same, to what a float can tell: a quaternion and its negative are. */
    static boolean same(Quat a, Quat b) {
        float dot = a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w();
        return Math.abs(dot) > 0.999999f;
    }
}
