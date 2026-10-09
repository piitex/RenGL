package me.piitex.engine.ui.background;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.HashMap;
import java.util.Map;

/**
 * What {@link BackgroundKinds} says about each kind and setting when asked for a tooltip: what a kind of background is,
 * and what a setting of it does. A setting that means the same on every kind, such as opacity, is explained once; one
 * that means something of its own is explained for its kind.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
final class BackgroundTips {
    private static final Map<String, String> KINDS = new HashMap<>();
    private static final Map<String, String> BY_NAME = new HashMap<>();
    private static final Map<String, String> BY_KIND = new HashMap<>();

    static {
        KINDS.put("dotgrid", "A still grid of dots, like dotted paper");
        KINDS.put("animateddots", "A grid of dots whose size and color ripple in waves");
        KINDS.put("constellation", "Dots that drift about and are joined by lines when they come near each other");
        KINDS.put("electric", "Glowing nodes that drift about and throw bolts of lightning");
        KINDS.put("image", "A picture, which is the one effect that stands still");

        BY_NAME.put("opacity", "How solid the effect is, from 0 (invisible) to 1 (solid)");
        BY_NAME.put("spacing", "The distance from one dot to the next, in points");
        BY_NAME.put("dotColor", "The color of the dots");
        BY_NAME.put("minSpeed", "The slowest a dot or node moves, in points a second");
        BY_NAME.put("maxSpeed", "The fastest a dot or node moves, in points a second");
        BY_NAME.put("mouse", "Reacts to the mouse when it comes near");

        kind("dotgrid", "dotRadius", "How big each dot is, in points");
        kind("animateddots", "minRadius", "How small a dot gets between the waves, in points");
        kind("animateddots", "intensity", "How strongly the waves change the dots' size and color, from 0 to 2");
        kind("animateddots", "waveLength", "The distance from one wave to the next, in points. Smaller looks busier");
        kind("animateddots", "saturation", "How strong the dots' colors are: 0 is grey, 1 is the strongest");
        kind("animateddots", "brightness", "How bright the dots are: 0 is black, 1 is the brightest");
        kind("animateddots", "alpha", "How solid the dots are, from 0 (invisible) to 1 (solid)");
        kind("constellation", "particleRadius", "How big the dots are, in points. Each one is a little different");
        kind("constellation", "lineThickness", "How thick the lines between dots are, in points");
        kind("constellation", "particleColor", "The color of the dots");
        kind("constellation", "lineColor", "The color of the lines between dots");
        kind("constellation", "mouse", "The dots are pushed away when the mouse comes near");
        kind("constellation", "repelRadius", "How close the mouse has to be before it pushes a dot, in points");
        kind("constellation", "repelStrength", "How hard the mouse pushes a dot when it is right on it");
        kind("electric", "nodeCount", "How many glowing nodes there are");
        kind("electric", "nodeRadius", "How big the bright middle of a node is, in points. Its glow reaches about four times as far");
        kind("electric", "arcRate", "How many bolts each node throws a second, on average");
        kind("electric", "minArcLength", "The shortest bolt, in points");
        kind("electric", "maxArcLength", "The longest bolt, in points. One that would reach the edge strikes it");
        kind("electric", "arcLifetime", "How many seconds a bolt stays visible, on average");
        kind("electric", "jaggedness", "How far the bolts zig-zag, from 0 (straight) to 3");
        kind("electric", "branchChance", "How likely a bolt is to fork, from 0 (never) to 1 (always)");
        kind("electric", "boltThickness", "How thick the bright middle of a bolt is, in points");
        kind("electric", "glowColor", "The color of the glow round the nodes and bolts");
        kind("electric", "coreColor", "The color of the bright middle of the nodes and bolts");
        kind("electric", "sparks", "Throw little sparks where a bolt lands");
        kind("electric", "mouse", "Nodes near the mouse throw bolts toward it");
        kind("electric", "strikeRadius", "How close the mouse has to be to a node for it to throw a bolt at it, in points");
        kind("image", "image", "The picture file, as a path in the project, such as assets/sky.png");
    }

    private BackgroundTips() {
    }

    private static void kind(String kind, String name, String tip) {
        BY_KIND.put(kind + "." + name, tip);
    }

    /** What the kind {@code id} is, in a short line. */
    static String kind(String id) {
        return KINDS.getOrDefault(id, "");
    }

    /** What setting {@code name} of kind {@code kindId} does: the tip for that kind, else the one for the name. */
    static String setting(String kindId, String name) {
        String own = BY_KIND.get(kindId + "." + name);
        return own != null ? own : BY_NAME.getOrDefault(name, "");
    }
}
