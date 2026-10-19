package me.piitex.engine.render3d.physics;

/**
 * How a body's surface behaves in a contact. {@code friction} resists sliding: 0 is ice-smooth, 1 grips like rubber on
 * concrete, and a box rests on a slope while the friction is above the slope's tangent. {@code restitution} is how
 * much bounce comes back: 0 stops dead, 1 bounces back as high as it fell.
 * <p>
 * When two bodies touch, their friction is combined as the square root of the product (so ice on anything stays
 * slippery) and the bouncier restitution of the two is used.
 */
public record PhysicsMaterial(float friction, float restitution) {
    public static final PhysicsMaterial DEFAULT = new PhysicsMaterial(0.5f, 0f);
    public static final PhysicsMaterial ICE = new PhysicsMaterial(0.02f, 0.05f);
    public static final PhysicsMaterial WOOD = new PhysicsMaterial(0.5f, 0.2f);
    public static final PhysicsMaterial METAL = new PhysicsMaterial(0.35f, 0.1f);
    public static final PhysicsMaterial RUBBER = new PhysicsMaterial(0.9f, 0.7f);

    public PhysicsMaterial {
        if (!(friction >= 0f)) throw new IllegalArgumentException("Friction can't be below 0, not " + friction);
        if (!(restitution >= 0f && restitution <= 1f)) throw new IllegalArgumentException("Restitution must be from 0 to 1, not " + restitution);
    }

    public PhysicsMaterial withFriction(float friction) {
        return new PhysicsMaterial(friction, restitution);
    }

    public PhysicsMaterial withRestitution(float restitution) {
        return new PhysicsMaterial(friction, restitution);
    }

    static float combineFriction(PhysicsMaterial a, PhysicsMaterial b) {
        return (float) Math.sqrt(a.friction * b.friction);
    }

    static float combineRestitution(PhysicsMaterial a, PhysicsMaterial b) {
        return Math.max(a.restitution, b.restitution);
    }
}
