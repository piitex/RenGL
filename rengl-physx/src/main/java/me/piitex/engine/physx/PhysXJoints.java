package me.piitex.engine.physx;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.physics.BallJoint;
import me.piitex.engine.render3d.physics.DistanceJoint;
import me.piitex.engine.render3d.physics.FixedJoint;
import me.piitex.engine.render3d.physics.HingeJoint;
import me.piitex.engine.render3d.physics.Joint;
import me.piitex.engine.render3d.physics.PhysicsBridge;
import me.piitex.engine.render3d.physics.RigidBody;
import me.piitex.engine.render3d.physics.SliderJoint;
import physx.PxTopLevelFunctions;
import physx.common.PxTransform;
import physx.common.PxVec3;
import physx.extensions.PxD6AxisEnum;
import physx.extensions.PxD6DriveEnum;
import physx.extensions.PxD6Joint;
import physx.extensions.PxD6JointDrive;
import physx.extensions.PxD6MotionEnum;
import physx.extensions.PxDistanceJoint;
import physx.extensions.PxDistanceJointFlagEnum;
import physx.extensions.PxJoint;
import physx.extensions.PxJointAngularLimitPair;
import physx.extensions.PxJointLimitCone;
import physx.extensions.PxJointLinearLimitPair;
import physx.extensions.PxRevoluteJoint;
import physx.extensions.PxRevoluteJointFlagEnum;
import physx.extensions.PxSpring;
import physx.physics.PxConstraintFlagEnum;
import physx.physics.PxPhysics;
import physx.physics.PxRigidActor;

import java.util.Arrays;

/**
 * The PhysX joint behind each {@link Joint}: a revolute joint for a hinge, a distance joint for a distance joint, a
 * fixed joint for a weld, and PhysX's six-axis joint for the slider (so it can have a motor) and the ball (so it can
 * limit swing and twist apart). Each is built with its frames where the joint holds its bodies now and its first axis
 * along the joint's axis, so angles and travel start at zero as they do in the built-in solver.
 */
final class PhysXJoints {
    private PhysXJoints() {
    }

    /** Strong enough that a motor reaches its speed in a step; the force limit is what holds it back. */
    private static final float MOTOR_DAMPING = 1e7f;
    private static final float NEARLY_PI = (float) Math.PI - 0.001f;

    static final class Link {
        private final PhysXBackend backend;
        final Joint joint;
        private PxJoint px;
        private float[] applied;
        private boolean collides;

        Link(PhysXBackend backend, Joint joint) {
            this.backend = backend;
            this.joint = joint;
            create();
        }

        boolean touches(RigidBody body) {
            return joint.getBodyA() == body || joint.getBodyB() == body;
        }

        void release() {
            if (px == null) return;
            px.release();
            px = null;
            applied = null;
        }

        /**
         * Makes the PhysX joint if it isn't there (its bodies may have just got new actors) and sends it the limits,
         * motor and strength if they changed.
         */
        void push() {
            if (px == null) create();
            if (px == null) return;
            float[] now = settings();
            if (Arrays.equals(now, applied)) return;
            apply();
            applied = now;
        }

        /**
         * Reads what the step did to the joint.
         *
         * @return true if it broke
         */
        boolean pull(PxVec3 force, PxVec3 torque) {
            if (px == null) return false;
            if (px.getConstraintFlags().isSet(PxConstraintFlagEnum.eBROKEN)) return true;
            // PhysX gives what the joint did to its first actor; the joint reports what it did to its second.
            px.getConstraint().getForce(force, torque);
            PhysicsBridge.reaction(joint, PhysXBackend.vec(force).negate(), PhysXBackend.vec(torque).negate());
            return false;
        }

        private void create() {
            PhysicsBridge.JointFrame frame = PhysicsBridge.frame(joint);
            if (frame == null) return;
            PxRigidActor first = frame.first() == null ? null : backend.actorOf(frame.first());
            PxRigidActor second = backend.actorOf(frame.second());
            if (second == null || (frame.first() != null && first == null)) return;
            // PhysX wants a joint to have a body it can move.
            if (!backend.canMove(frame.second()) && (frame.first() == null || !backend.canMove(frame.first()))) return;
            Vec3 axis = switch (joint) {
                case HingeJoint hinge -> hinge.getAxis();
                case SliderJoint slider -> slider.getAxis();
                case BallJoint ball -> ball.getAxis();
                case DistanceJoint _, FixedJoint _ -> null;
            };
            Quat basis = axis == null ? Quat.IDENTITY : PhysXBackend.between(Vec3.X, axis.normalized());
            PxTransform onFirst = local(backend.scratchA(), frame.first(), frame.anchorFirst(), basis);
            PxTransform onSecond = local(backend.scratchB(), frame.second(), frame.anchorSecond(), basis);
            PxPhysics physics = PhysX.physics();
            px = switch (joint) {
                case HingeJoint _ -> PxTopLevelFunctions.RevoluteJointCreate(physics, first, onFirst, second, onSecond);
                case DistanceJoint _ -> PxTopLevelFunctions.DistanceJointCreate(physics, first, onFirst, second, onSecond);
                case FixedJoint _ -> PxTopLevelFunctions.FixedJointCreate(physics, first, onFirst, second, onSecond);
                case SliderJoint _, BallJoint _ -> PxTopLevelFunctions.D6JointCreate(physics, first, onFirst, second, onSecond);
            };
            if (px == null || px.getAddress() == 0L) {
                px = null;
                return;
            }
            if (px instanceof PxDistanceJoint distance) distance.setTolerance(0.005f);
            applied = null;
            collides = false;
        }

        /**
         * The joint's frame as one of its bodies sees it, or in the world for a body that is the world.
         */
        private PxTransform local(PxTransform target, RigidBody body, Vec3 anchor, Quat basis) {
            if (body == null) return backend.pose(target, anchor, basis);
            Quat back = body.getRotation().conjugate();
            return backend.pose(target, back.rotate(anchor.sub(body.getPosition())), back.mul(basis).normalized());
        }

        /**
         * Everything {@link #apply} reads, to tell when it has to run again.
         */
        private float[] settings() {
            float[] common = {joint.getBreakForce(), joint.getBreakTorque(), joint.isCollideConnected() ? 1f : 0f};
            float[] own = switch (joint) {
                case HingeJoint j -> new float[]{j.hasLimits() ? 1f : 0f, j.getLowerLimit(), j.getUpperLimit(), j.hasMotor() ? 1f : 0f, j.getMotorSpeed(), j.getMaxMotorTorque()};
                case SliderJoint j -> new float[]{j.hasLimits() ? 1f : 0f, j.getLowerLimit(), j.getUpperLimit(), j.hasMotor() ? 1f : 0f, j.getMotorSpeed(), j.getMaxMotorForce()};
                case BallJoint j -> new float[]{j.hasSwingLimit() ? 1f : 0f, j.getSwingLimit(), j.hasTwistLimit() ? 1f : 0f, j.getLowerTwistLimit(), j.getUpperTwistLimit()};
                case DistanceJoint j -> new float[]{j.getLength(), j.getMinLength(), j.getMaxLength(), j.getSpringFrequency(), j.getSpringDamping(), sharedMass()};
                case FixedJoint _ -> new float[0];
            };
            float[] all = Arrays.copyOf(common, common.length + own.length);
            System.arraycopy(own, 0, all, common.length, own.length);
            return all;
        }

        private void apply() {
            px.setBreakForce(finite(joint.getBreakForce()), finite(joint.getBreakTorque()));
            px.setConstraintFlag(PxConstraintFlagEnum.eCOLLISION_ENABLED, joint.isCollideConnected());
            if (collides != joint.isCollideConnected()) {
                // PhysX decided about this pair when it first saw it; it has to be asked again.
                collides = joint.isCollideConnected();
                backend.refilter(joint.getBodyA());
            }
            switch (joint) {
                case HingeJoint hinge -> apply(hinge, (PxRevoluteJoint) px);
                case SliderJoint slider -> apply(slider, (PxD6Joint) px);
                case BallJoint ball -> apply(ball, (PxD6Joint) px);
                case DistanceJoint distance -> apply(distance, (PxDistanceJoint) px);
                case FixedJoint _ -> {
                }
            }
        }

        private void apply(HingeJoint hinge, PxRevoluteJoint revolute) {
            if (hinge.hasLimits()) {
                PxJointAngularLimitPair limit = new PxJointAngularLimitPair(hinge.getLowerLimit(), hinge.getUpperLimit());
                revolute.setLimit(limit);
                limit.destroy();
            }
            revolute.setRevoluteJointFlag(PxRevoluteJointFlagEnum.eLIMIT_ENABLED, hinge.hasLimits());
            if (hinge.hasMotor()) {
                revolute.setDriveVelocity(hinge.getMotorSpeed(), true);
                revolute.setDriveForceLimit(finite(hinge.getMaxMotorTorque()));
            }
            revolute.setRevoluteJointFlag(PxRevoluteJointFlagEnum.eDRIVE_ENABLED, hinge.hasMotor());
        }

        private void apply(SliderJoint slider, PxD6Joint d6) {
            if (slider.hasLimits()) {
                PxSpring hard = new PxSpring(0f, 0f);
                PxJointLinearLimitPair limit = new PxJointLinearLimitPair(slider.getLowerLimit(), slider.getUpperLimit(), hard);
                d6.setLinearLimit(PxD6AxisEnum.eX, limit);
                limit.destroy();
                hard.destroy();
            }
            d6.setMotion(PxD6AxisEnum.eX, slider.hasLimits() ? PxD6MotionEnum.eLIMITED : PxD6MotionEnum.eFREE);
            PxD6JointDrive drive = slider.hasMotor() ? new PxD6JointDrive(0f, MOTOR_DAMPING, finite(slider.getMaxMotorForce()), false) : new PxD6JointDrive(0f, 0f, 0f, false);
            d6.setDrive(PxD6DriveEnum.eX, drive);
            drive.destroy();
            PxVec3 along = new PxVec3(slider.hasMotor() ? slider.getMotorSpeed() : 0f, 0f, 0f), none = new PxVec3(0f, 0f, 0f);
            d6.setDriveVelocity(along, none);
            along.destroy();
            none.destroy();
        }

        private void apply(BallJoint ball, PxD6Joint d6) {
            if (!ball.hasTwistLimit()) {
                d6.setMotion(PxD6AxisEnum.eTWIST, PxD6MotionEnum.eFREE);
            } else if (ball.getUpperTwistLimit() - ball.getLowerTwistLimit() < 0.002f) {
                d6.setMotion(PxD6AxisEnum.eTWIST, PxD6MotionEnum.eLOCKED);
            } else {
                PxJointAngularLimitPair limit = new PxJointAngularLimitPair(Math.max(ball.getLowerTwistLimit(), -NEARLY_PI), Math.min(ball.getUpperTwistLimit(), NEARLY_PI));
                d6.setTwistLimit(limit);
                limit.destroy();
                d6.setMotion(PxD6AxisEnum.eTWIST, PxD6MotionEnum.eLIMITED);
            }
            PxD6MotionEnum swing = PxD6MotionEnum.eFREE;
            if (ball.hasSwingLimit()) {
                if (ball.getSwingLimit() < 0.002f) {
                    swing = PxD6MotionEnum.eLOCKED;
                } else {
                    float angle = Math.min(ball.getSwingLimit(), NEARLY_PI);
                    PxJointLimitCone cone = new PxJointLimitCone(angle, angle);
                    d6.setSwingLimit(cone);
                    cone.destroy();
                    swing = PxD6MotionEnum.eLIMITED;
                }
            }
            d6.setMotion(PxD6AxisEnum.eSWING1, swing);
            d6.setMotion(PxD6AxisEnum.eSWING2, swing);
        }

        private void apply(DistanceJoint joint, PxDistanceJoint distance) {
            if (joint.isSpring()) {
                // PhysX's spring pulls back into the range from outside it, so the range is the one length.
                double omega = 2.0 * Math.PI * joint.getSpringFrequency();
                float mass = sharedMass();
                distance.setMinDistance(joint.getLength());
                distance.setMaxDistance(joint.getLength());
                distance.setStiffness((float) (mass * omega * omega));
                distance.setDamping((float) (2.0 * mass * joint.getSpringDamping() * omega));
                distance.setDistanceJointFlag(PxDistanceJointFlagEnum.eMIN_DISTANCE_ENABLED, true);
                distance.setDistanceJointFlag(PxDistanceJointFlagEnum.eMAX_DISTANCE_ENABLED, true);
                distance.setDistanceJointFlag(PxDistanceJointFlagEnum.eSPRING_ENABLED, true);
                return;
            }
            float min = joint.getMinLength(), max = joint.getMaxLength();
            boolean bounded = max < Float.MAX_VALUE;
            distance.setMinDistance(Math.min(min, bounded ? max : min));
            if (bounded) distance.setMaxDistance(max);
            distance.setDistanceJointFlag(PxDistanceJointFlagEnum.eMIN_DISTANCE_ENABLED, min > 0f);
            distance.setDistanceJointFlag(PxDistanceJointFlagEnum.eMAX_DISTANCE_ENABLED, bounded);
            distance.setDistanceJointFlag(PxDistanceJointFlagEnum.eSPRING_ENABLED, false);
        }

        /**
         * The mass a spring between the two bodies has to move: what makes it bounce at the frequency asked for
         * whatever they weigh.
         */
        private float sharedMass() {
            float inverse = inverseMass(joint.getBodyA()) + inverseMass(joint.getBodyB());
            return inverse > 0f ? 1f / inverse : 1f;
        }

        private static float inverseMass(RigidBody body) {
            return body != null && body.isDynamic() ? body.getInverseMass() : 0f;
        }

        private static float finite(float value) {
            return value < Float.MAX_VALUE ? value : Float.MAX_VALUE;
        }
    }
}
