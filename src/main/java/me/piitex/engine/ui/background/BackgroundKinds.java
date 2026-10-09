package me.piitex.engine.ui.background;

import me.piitex.engine.property.PropertyCodec;
import me.piitex.engine.property.PropertyTypes;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.PaintCodec;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The backgrounds a layout or a theme can hold, and the settings of each, so an effect is data a person (or an editor)
 * changes: {@code {"type": "constellation", "particleCount": 120, "linkDistance": 90}}. The kinds are
 * <ul>
 *   <li>{@code dotgrid}, a still grid of dots ({@link DotGridBackground});</li>
 *   <li>{@code animateddots}, dots that pulse and change color in waves ({@link AnimatedDotBackground});</li>
 *   <li>{@code constellation}, drifting dots joined by lines ({@link ConstellationBackground});</li>
 *   <li>{@code electric}, glowing nodes that throw lightning ({@link ElectricNodeBackground});</li>
 *   <li>{@code image}, a still picture ({@link ImageBackground}).</li>
 * </ul>
 * A background of another class, such as one an app made, has no kind, so it can't be written and a file leaves it out.
 * <p>
 * An effect's settings are written as a map in this shape, and {@link #write} and {@link #read} turn one into the other.
 * A map is what a tool keeps to remember an effect: {@link #apply} puts it onto a background that is showing, changing only
 * what differs, so editing a color doesn't start a particle field over.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public final class BackgroundKinds {
    private static final Map<String, Kind> byId = new LinkedHashMap<>();

    /** What a text setting is, so a tool can offer the right control for it. */
    public enum Hint {
        NONE,
        /** A path to an image file in the project. */
        IMAGE
    }

    /**
     * One setting of a kind: its name in the file, what a person calls it, its type and how to read and change it. The
     * types are {@link Boolean}, {@link Integer}, {@link Float}, {@link String}, an enum and {@link Color}.
     */
    public static final class Setting {
        private final String name, label;
        private final Class<?> type;
        private final Function<Background, Object> get;
        private final BiConsumer<Background, Object> set;
        private double min = Double.NEGATIVE_INFINITY, max = Double.POSITIVE_INFINITY;
        private String tooltip = "";
        private Hint hint = Hint.NONE;

        private Setting(String name, String label, Class<?> type, Function<Background, Object> get, BiConsumer<Background, Object> set) {
            this.name = name;
            this.label = label;
            this.type = type;
            this.get = get;
            this.set = set;
        }

        private Setting range(double min, double max) {
            this.min = min;
            this.max = max;
            return this;
        }

        private Setting tip(String tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        /** Gives the setting a tooltip if it has none. */
        private void defaultTip(String tooltip) {
            if (this.tooltip.isEmpty()) this.tooltip = tooltip;
        }

        private Setting hint(Hint hint) {
            this.hint = hint;
            return this;
        }

        public String getName() {
            return name;
        }

        public String getLabel() {
            return label;
        }

        public Class<?> getType() {
            return type;
        }

        public double getMin() {
            return min;
        }

        public double getMax() {
            return max;
        }

        public String getTooltip() {
            return tooltip;
        }

        public Hint getHint() {
            return hint;
        }

        public boolean isNumber() {
            return Number.class.isAssignableFrom(type);
        }

        /** The value now, boxed. */
        public Object get(Background background) {
            return get.apply(background);
        }

        /** Changes it. Numbers are converted to the setting's number type and kept between its range. */
        public void set(Background background, Object value) {
            if (value instanceof Number n && isNumber()) {
                double clamped = Math.clamp(n.doubleValue(), min, max);
                value = type == Integer.class ? (Object) (int) Math.round(clamped) : (Object) (float) clamped;
            }
            set.accept(background, value);
        }

        private Object write(Background background) {
            Object value = get.apply(background);
            return value == null ? null : type == Paint.class ? PaintCodec.JSON.write((Paint) value) : codec().write(value);
        }

        private Object read(Object json) {
            try {
                if (type != Paint.class) return codec().read(json);
                return PaintCodec.JSON.read(json);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("\"" + name + "\": " + e.getMessage(), e);
            }
        }

        @SuppressWarnings("unchecked")
        private PropertyCodec<Object> codec() {
            return (PropertyCodec<Object>) PropertyTypes.codecFor(type);
        }
    }

    /** A kind of background: its name in files, how to make one and its settings. */
    public static final class Kind {
        private final String id, label;
        private final Class<? extends Background> type;
        private final Supplier<? extends Background> maker;
        private final List<Setting> settings;

        private Kind(String id, String label, Class<? extends Background> type, Supplier<? extends Background> maker, List<Setting> settings) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.maker = maker;
            this.settings = List.copyOf(settings);
        }

        /** The name saved in files: lowercase letters. */
        public String getId() {
            return id;
        }

        /** The name shown to people. */
        public String getLabel() {
            return label;
        }

        /** What this kind of background is, in a short line. Empty for a kind with no description. */
        public String getDescription() {
            return BackgroundTips.kind(id);
        }

        public Class<? extends Background> getType() {
            return type;
        }

        public List<Setting> getSettings() {
            return settings;
        }

        public Setting getSetting(String name) {
            for (Setting setting : settings) if (setting.name.equals(name)) return setting;
            return null;
        }

        /** A new one, with every setting as it starts. */
        public Background create() {
            return maker.get();
        }

        @Override
        public String toString() {
            return "BackgroundKind[" + id + "]";
        }
    }

    static {
        register(new Kind("dotgrid", "Dot Grid", DotGridBackground.class, DotGridBackground::new, withOpacity(DotGridBackground.class,
                setting(DotGridBackground.class, "spacing", "Spacing", Float.class, DotGridBackground::getSpacing, DotGridBackground::setSpacing).range(4, 1000),
                setting(DotGridBackground.class, "dotRadius", "Dot Size", Float.class, DotGridBackground::getDotRadius, DotGridBackground::setDotRadius).range(0, 100),
                setting(DotGridBackground.class, "dotColor", "Dot Color", Paint.class, DotGridBackground::getDotColor, DotGridBackground::setDotColor))));

        register(new Kind("animateddots", "Animated Dots", AnimatedDotBackground.class, AnimatedDotBackground::new, withOpacity(AnimatedDotBackground.class,
                setting(AnimatedDotBackground.class, "spacing", "Spacing", Float.class, AnimatedDotBackground::getSpacing, AnimatedDotBackground::setSpacing).range(4, 1000),
                setting(AnimatedDotBackground.class, "minRadius", "Smallest Dot", Float.class, AnimatedDotBackground::getMinRadius, AnimatedDotBackground::setMinRadius).range(0, 100),
                setting(AnimatedDotBackground.class, "maxRadius", "Biggest Dot", Float.class, AnimatedDotBackground::getMaxRadius, AnimatedDotBackground::setMaxRadius).range(0, 100)
                        .tip("Keep it at or below half the spacing so the dots don't overlap"),
                setting(AnimatedDotBackground.class, "speed", "Speed", Float.class, AnimatedDotBackground::getSpeed, AnimatedDotBackground::setSpeed).range(-20, 20)
                        .tip("Negative runs the waves backwards"),
                setting(AnimatedDotBackground.class, "intensity", "Intensity", Float.class, AnimatedDotBackground::getIntensity, AnimatedDotBackground::setIntensity).range(0, 2),
                setting(AnimatedDotBackground.class, "hueSpan", "Color Range", Float.class, AnimatedDotBackground::getHueSpan, AnimatedDotBackground::setHueSpan).range(0, 1)
                        .tip("How much of the color wheel it goes through: 1 is the whole rainbow"),
                setting(AnimatedDotBackground.class, "waveLength", "Wave Length", Float.class, AnimatedDotBackground::getWaveLength, AnimatedDotBackground::setWaveLength).range(10, 5000),
                setting(AnimatedDotBackground.class, "saturation", "Saturation", Float.class, AnimatedDotBackground::getSaturation, AnimatedDotBackground::setSaturation).range(0, 1),
                setting(AnimatedDotBackground.class, "brightness", "Brightness", Float.class, AnimatedDotBackground::getBrightness, AnimatedDotBackground::setBrightness).range(0, 1),
                setting(AnimatedDotBackground.class, "alpha", "Dot Opacity", Float.class, AnimatedDotBackground::getAlpha, AnimatedDotBackground::setAlpha).range(0, 1))));

        register(new Kind("constellation", "Constellation", ConstellationBackground.class, ConstellationBackground::new, withOpacity(ConstellationBackground.class,
                setting(ConstellationBackground.class, "particleCount", "Dots", Integer.class, ConstellationBackground::getParticleCount, ConstellationBackground::setParticleCount).range(0, 1000)
                        .tip("It checks every pair, so a few hundred is plenty"),
                setting(ConstellationBackground.class, "minSpeed", "Slowest", Float.class, ConstellationBackground::getMinSpeed, (c, v) -> c.setSpeedRange(v, c.getMaxSpeed())).range(0, 1000),
                setting(ConstellationBackground.class, "maxSpeed", "Fastest", Float.class, ConstellationBackground::getMaxSpeed, (c, v) -> c.setSpeedRange(c.getMinSpeed(), v)).range(0, 1000),
                setting(ConstellationBackground.class, "particleRadius", "Dot Size", Float.class, ConstellationBackground::getParticleRadius, ConstellationBackground::setParticleRadius).range(0, 50),
                setting(ConstellationBackground.class, "linkDistance", "Link Distance", Float.class, ConstellationBackground::getLinkDistance, ConstellationBackground::setLinkDistance).range(0, 2000)
                        .tip("Dots closer than this are joined by a line"),
                setting(ConstellationBackground.class, "lineThickness", "Line Thickness", Float.class, ConstellationBackground::getLineThickness, ConstellationBackground::setLineThickness).range(0, 20),
                setting(ConstellationBackground.class, "particleColor", "Dot Color", Paint.class, ConstellationBackground::getParticleColor, ConstellationBackground::setParticleColor),
                setting(ConstellationBackground.class, "lineColor", "Line Color", Paint.class, ConstellationBackground::getLineColor, ConstellationBackground::setLineColor),
                setting(ConstellationBackground.class, "mouse", "Pushed By Mouse", Boolean.class, ConstellationBackground::isMouseInteractionEnabled, ConstellationBackground::setMouseInteractionEnabled),
                setting(ConstellationBackground.class, "repelRadius", "Mouse Reach", Float.class, ConstellationBackground::getRepelRadius, ConstellationBackground::setRepelRadius).range(0, 2000),
                setting(ConstellationBackground.class, "repelStrength", "Mouse Push", Float.class, ConstellationBackground::getRepelStrength, ConstellationBackground::setRepelStrength).range(0, 5000))));

        register(new Kind("electric", "Electric Arcs", ElectricNodeBackground.class, ElectricNodeBackground::new, withOpacity(ElectricNodeBackground.class,
                setting(ElectricNodeBackground.class, "nodeCount", "Nodes", Integer.class, ElectricNodeBackground::getNodeCount, ElectricNodeBackground::setNodeCount).range(0, 50),
                setting(ElectricNodeBackground.class, "nodeRadius", "Node Size", Float.class, ElectricNodeBackground::getNodeRadius, ElectricNodeBackground::setNodeRadius).range(0, 100),
                setting(ElectricNodeBackground.class, "minSpeed", "Slowest", Float.class, ElectricNodeBackground::getMinSpeed, (b, v) -> b.setSpeedRange(v, b.getMaxSpeed())).range(0, 2000),
                setting(ElectricNodeBackground.class, "maxSpeed", "Fastest", Float.class, ElectricNodeBackground::getMaxSpeed, (b, v) -> b.setSpeedRange(b.getMinSpeed(), v)).range(0, 2000),
                setting(ElectricNodeBackground.class, "arcRate", "Arcs a Second", Float.class, ElectricNodeBackground::getArcRate, ElectricNodeBackground::setArcRate).range(0, 100),
                setting(ElectricNodeBackground.class, "minArcLength", "Shortest Arc", Float.class, ElectricNodeBackground::getMinArcLength, (b, v) -> b.setArcLengthRange(v, b.getMaxArcLength())).range(0, 5000),
                setting(ElectricNodeBackground.class, "maxArcLength", "Longest Arc", Float.class, ElectricNodeBackground::getMaxArcLength, (b, v) -> b.setArcLengthRange(b.getMinArcLength(), v)).range(0, 5000),
                setting(ElectricNodeBackground.class, "arcLifetime", "Arc Lifetime", Float.class, ElectricNodeBackground::getArcLifetime, ElectricNodeBackground::setArcLifetime).range(0.02, 10),
                setting(ElectricNodeBackground.class, "jaggedness", "Jaggedness", Float.class, ElectricNodeBackground::getJaggedness, ElectricNodeBackground::setJaggedness).range(0, 3),
                setting(ElectricNodeBackground.class, "branchChance", "Forking", Float.class, ElectricNodeBackground::getBranchChance, ElectricNodeBackground::setBranchChance).range(0, 1),
                setting(ElectricNodeBackground.class, "boltThickness", "Bolt Thickness", Float.class, ElectricNodeBackground::getBoltThickness, ElectricNodeBackground::setBoltThickness).range(0, 50),
                setting(ElectricNodeBackground.class, "nodeLinkDistance", "Node Link Distance", Float.class, ElectricNodeBackground::getNodeLinkDistance, ElectricNodeBackground::setNodeLinkDistance).range(0, 5000)
                        .tip("Nodes closer than this arc between each other; 0 for never"),
                setting(ElectricNodeBackground.class, "glowColor", "Glow", Paint.class, ElectricNodeBackground::getGlowColor, ElectricNodeBackground::setGlowColor),
                setting(ElectricNodeBackground.class, "coreColor", "Core", Paint.class, ElectricNodeBackground::getCoreColor, ElectricNodeBackground::setCoreColor),
                setting(ElectricNodeBackground.class, "sparks", "Sparks", Boolean.class, ElectricNodeBackground::isSparksEnabled, ElectricNodeBackground::setSparksEnabled),
                setting(ElectricNodeBackground.class, "mouse", "Reaches For Mouse", Boolean.class, ElectricNodeBackground::isMouseInteractionEnabled, ElectricNodeBackground::setMouseInteractionEnabled),
                setting(ElectricNodeBackground.class, "strikeRadius", "Mouse Reach", Float.class, ElectricNodeBackground::getStrikeRadius, ElectricNodeBackground::setStrikeRadius).range(0, 5000))));

        register(new Kind("image", "Image", ImageBackground.class, ImageBackground::new, withOpacity(ImageBackground.class,
                setting(ImageBackground.class, "image", "Image", String.class, ImageBackground::getPath, ImageBackground::setPath).hint(Hint.IMAGE),
                setting(ImageBackground.class, "fit", "Fit", ImageBackground.Fit.class, ImageBackground::getFit, ImageBackground::setFit)
                        .tip("Cover fills it and cuts off what doesn't fit; Contain shows all of it; Stretch squeezes it to fit; Tile repeats it"))));
    }

    private BackgroundKinds() {
    }

    private static <B extends AbstractBackground> List<Setting> withOpacity(Class<B> owner, Setting... own) {
        List<Setting> all = new ArrayList<>(List.of(own));
        all.add(setting(owner, "opacity", "Opacity", Float.class, AbstractBackground::getOpacity, AbstractBackground::setOpacity).range(0, 1));
        return all;
    }

    private static <B extends Background, T> Setting setting(Class<B> owner, String name, String label, Class<T> type,
                                                             Function<B, T> get, BiConsumer<B, T> set) {
        return new Setting(name, label, type, b -> get.apply(owner.cast(b)), (b, v) -> set.accept(owner.cast(b), type.cast(v)));
    }

    // --- The kinds ------------------------------------------------------------------------------------------------

    /** Every kind, in the order a tool lists them. */
    public static List<Kind> all() {
        synchronized (byId) {
            return List.copyOf(byId.values());
        }
    }

    /** The kind saved as {@code id}, or null. */
    public static Kind get(String id) {
        synchronized (byId) {
            return byId.get(id);
        }
    }

    /** The kind of {@code background}, or null for one of another class. */
    public static Kind of(Background background) {
        if (background == null) return null;
        synchronized (byId) {
            for (Kind kind : byId.values()) if (kind.type == background.getClass()) return kind;
        }
        return null;
    }

    /** Adds a kind of your own, or replaces one with the same id, so files can hold it. */
    private static void register(Kind kind) {
        for (Setting setting : kind.settings) setting.defaultTip(BackgroundTips.setting(kind.id, setting.name));
        synchronized (byId) {
            byId.put(kind.id, kind);
        }
    }

    // --- Reading and writing --------------------------------------------------------------------------------------

    /**
     * The background as settings: its {@code "type"} and every setting it has. Null for one of another class, which
     * a file can't hold.
     */
    public static Map<String, Object> write(Background background) {
        Kind kind = of(background);
        if (kind == null) return null;
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("type", kind.id);
        for (Setting setting : kind.settings) {
            Object value = setting.write(background);
            if (value != null) json.put(setting.name, value);
        }
        return json;
    }

    /**
     * A new background from settings, with whatever they leave out as it starts. A picture is looked for in {@code base}.
     *
     * @throws IllegalArgumentException if it isn't an object with the {@code "type"} of a kind, or a setting has the
     *                                  wrong sort of value, naming which
     */
    public static Background read(Object json, Path base) {
        Kind kind = kindOf(json);
        Background background = kind.create();
        fill(kind, background, (Map<?, ?>) json);
        resolve(background, base);
        return background;
    }

    /**
     * Gives {@code existing} the settings, or makes a new background if it isn't of the settings' kind, or is null.
     * Only the settings that differ are changed, so one that is already showing keeps its state: editing a color doesn't
     * start a field of dots over. Returns the background to use.
     *
     * @throws IllegalArgumentException as {@link #read}
     */
    public static Background apply(Background existing, Object json, Path base) {
        Kind kind = kindOf(json);
        if (existing == null || of(existing) != kind) return read(json, base);
        Map<?, ?> settings = (Map<?, ?>) json;
        for (Setting setting : kind.settings) {
            if (!settings.containsKey(setting.name)) continue;
            Object value = setting.read(settings.get(setting.name));
            Object now = setting.get(existing);
            if (!java.util.Objects.equals(now instanceof Color c ? PaintCodec.JSON.write(c) : now,
                    value instanceof Color c ? PaintCodec.JSON.write(c) : value)) {
                setting.set(existing, value);
            }
        }
        if (existing instanceof ImageBackground image && image.getImage() == null && !image.isMissing() && !image.getPath().isEmpty()) {
            image.resolveFrom(base);
        }
        return existing;
    }

    /**
     * Reads the picture of an {@link ImageBackground} from {@code base}, if {@code background} is one. A background loaded
     * from a file does this itself; it is for one whose path was set afterwards.
     */
    public static void resolve(Background background, Path base) {
        if (background instanceof ImageBackground image) image.resolveFrom(base);
    }

    private static Kind kindOf(Object json) {
        if (!(json instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("expected an object with a \"type\", found " + PropertyCodec.describe(json));
        }
        Object type = map.get("type");
        Kind kind = type instanceof String id ? get(id) : null;
        if (kind == null) {
            List<String> ids = all().stream().map(k -> k.id).toList();
            throw new IllegalArgumentException("\"type\" is " + PropertyCodec.describe(type) + ", which isn't one of " + String.join(", ", ids));
        }
        return kind;
    }

    private static void fill(Kind kind, Background background, Map<?, ?> json) {
        for (Setting setting : kind.settings) {
            if (json.containsKey(setting.name)) setting.set(background, setting.read(json.get(setting.name)));
        }
    }
}
