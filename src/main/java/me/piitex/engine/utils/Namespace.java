package me.piitex.engine.utils;

/**
 * The single source of truth for every top-level ID namespace this engine uses.
 * Declaring them here replaces a bare {@code int} literal, such as {@code 0x01},
 * hardcoded separately wherever a {@link FormIDRegistry} is created. Nothing would stop
 * two such registries from picking the same byte, which would make two unrelated
 * objects, say an Element and a game-logic object, compare as {@code equals()} whenever
 * their category and local ID also lined up.
 * <p>
 * Add a constant here whenever a new top-level kind of ID-bearing object is introduced,
 * such as Elements, Overlays, or game-logic objects, rather than inventing a literal at
 * the call site. The static check below fails class loading immediately if two constants
 * collide, rather than letting the collision go unnoticed.
 */
public enum Namespace {
    ELEMENT(0x0),
    OVERLAY(0x01),
    GAME_LOGIC(0x02);

    private final int value;

    Namespace(int value) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException("Namespace value must fit in a single byte (0-255): " + value);
        }
        this.value = value;
    }

    /**
     * This namespace's packed byte value (0-255), as stored in the top 8 bits of a {@link FormID}.
     */
    public int value() {
        return value;
    }

    static {
        // Catch a copy-paste mistake (two constants given the same byte) at class-load
        // time, rather than letting it silently corrupt FormID uniqueness at runtime.
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Namespace ns : values()) {
            if (!seen.add(ns.value)) {
                throw new ExceptionInInitializerError(
                        "Duplicate Namespace value " + ns.value + "; every constant must be unique.");
            }
        }
    }
}