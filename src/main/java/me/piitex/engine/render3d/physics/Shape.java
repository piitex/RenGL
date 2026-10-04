package me.piitex.engine.render3d.physics;

import me.piitex.engine.render3d.math.Bounds;
import me.piitex.engine.render3d.math.Vec3;

/**
 * The solid form of a {@link RigidBody}, in the body's own space and in metres, centred on its origin. A body can carry
 * several, each moved and turned by a {@link BodyShape}. Shapes are given in the node's local units: a body made for a
 * node scaled by 2 has shapes twice as big, so a shape that fits the mesh keeps fitting it.
 */
public sealed interface Shape permits SphereShape, BoxShape, CapsuleShape, PlaneShape {

    /**
     * The volume in cubic metres, which shares out a body's mass between its shapes.
     */
    float volume();

    /**
     * The moments of inertia around the shape's own X, Y and Z axes through its centre, for a mass of 1 kg spread
     * evenly through it. They scale with the mass.
     */
    Vec3 unitInertia();

    /**
     * The box around the shape in its own space.
     */
    Bounds bounds();

    /**
     * The shape with each axis stretched by {@code scale}. Round shapes stay round, so they take the largest stretch
     * across their round directions.
     */
    Shape scaled(Vec3 scale);
}
