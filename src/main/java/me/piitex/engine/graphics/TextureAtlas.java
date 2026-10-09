package me.piitex.engine.graphics;

import me.piitex.engine.io.Json;
import me.piitex.engine.utils.flags.GeneratedClass;
import me.piitex.engine.utils.flags.GeneratedFragments;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Named {@link TextureRegion}s, and optionally named {@link Animation}s, usually all cut from one packed image.
 * <pre>{@code
 * TextureAtlas atlas = TextureAtlas.load(Path.of("assets/characters.json"));
 * Sprite coin = new Sprite(atlas.get("coin"));
 * Animation walk = atlas.getAnimation("walk");                                   // a tag in an Aseprite export
 * Animation spin = Animation.of(atlas.getRegions("coin_"), 0.08f, Animation.PlayMode.LOOP); // coin_0, coin_1, ...
 * }</pre>
 * <b>Reading one.</b> {@link #load(Path)} reads the JSON sprite sheet format that most sprite tools can export,
 * TexturePacker's "JSON (Hash)" and "JSON (Array)", which Aseprite, Free Texture Packer and others also write:
 * <ul>
 *   <li>Each entry of {@code frames} becomes a region under its name, trimmed if the tool trimmed it.</li>
 *   <li>The image is {@code meta.image}, next to the JSON file.</li>
 *   <li>Aseprite's tags ({@code meta.frameTags}) become animations under the tag's name, with each frame's
 *       {@code duration}, and forward, reverse and ping-pong directions. A tag set to repeat once plays
 *       {@link Animation.PlayMode#ONCE}; any other loops.</li>
 *   <li>Frames the packer turned sideways ({@code "rotated": true}, 90 degrees clockwise) are read as rotated regions
 *       and draw upright.</li>
 * </ul>
 * <b>Building one.</b> Any other format, or a grid sheet, is a few lines: create an empty atlas and {@link #add} regions,
 * from {@link TextureRegion#split} or from a loader of your own.
 * <p>
 * Loading and building can happen on any thread, such as in a {@link me.piitex.engine.scheduler.Loader} step. Once
 * built, an atlas can be read from any thread as long as nothing is added to it any more.
 */
@GeneratedFragments(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public final class TextureAtlas {
    private final Map<String, TextureRegion> regions = new LinkedHashMap<>();
    private final Map<String, TextureRegion> withoutExtension = new LinkedHashMap<>();
    private final Map<String, Animation> animations = new LinkedHashMap<>();

    /**
     * Adds {@code region} under {@code name}, replacing any region of that name.
     */
    public TextureAtlas add(String name, TextureRegion region) {
        if (name == null || region == null) throw new IllegalArgumentException("name and region must not be null");
        regions.put(name, region);
        String base = stripExtension(name);
        if (!base.equals(name)) withoutExtension.putIfAbsent(base, region);
        return this;
    }

    /**
     * Adds {@code animation} under {@code name}, replacing any animation of that name.
     */
    public TextureAtlas addAnimation(String name, Animation animation) {
        if (name == null || animation == null) throw new IllegalArgumentException("name and animation must not be null");
        animations.put(name, animation);
        return this;
    }

    /**
     * The region named {@code name}. The file extension that packers keep in names may be left out: "coin" finds
     * "coin.png". Throws if there is none, naming the missing region.
     */
    public TextureRegion get(String name) {
        TextureRegion region = find(name);
        if (region == null) throw new IllegalArgumentException("No region named '" + name + "' in the atlas");
        return region;
    }

    /**
     * As {@link #get}, but null if there is no such region.
     */
    public TextureRegion find(String name) {
        TextureRegion region = regions.get(name);
        return region != null ? region : withoutExtension.get(name);
    }

    /**
     * The regions whose names start with {@code prefix}, ordered by name with numbers compared by value, so
     * "walk_2" comes before "walk_10". For the frames of an animation packed as separate pictures.
     */
    public List<TextureRegion> getRegions(String prefix) {
        List<String> names = new ArrayList<>();
        for (String name : regions.keySet()) {
            if (name.startsWith(prefix)) names.add(name);
        }
        names.sort(TextureAtlas::compareNatural);
        List<TextureRegion> found = new ArrayList<>(names.size());
        for (String name : names) found.add(regions.get(name));
        return found;
    }

    /**
     * The animation named {@code name}. Throws if there is none, naming the missing animation.
     */
    public Animation getAnimation(String name) {
        Animation animation = animations.get(name);
        if (animation == null) throw new IllegalArgumentException("No animation named '" + name + "' in the atlas");
        return animation;
    }

    /**
     * As {@link #getAnimation}, but null if there is no such animation.
     */
    public Animation findAnimation(String name) {
        return animations.get(name);
    }

    /**
     * Every region name, in the order they were added.
     */
    public Set<String> getRegionNames() {
        return Collections.unmodifiableSet(regions.keySet());
    }

    /**
     * Every animation name, in the order they were added.
     */
    public Set<String> getAnimationNames() {
        return Collections.unmodifiableSet(animations.keySet());
    }

    public static TextureAtlas load(String jsonPath) {
        return load(Path.of(jsonPath));
    }

    public static TextureAtlas load(File jsonFile) {
        return load(jsonFile.toPath());
    }

    /**
     * Reads a JSON sprite sheet and the image it names, which is looked for next to the JSON file. Works with paths
     * inside a zip file system too, such as a mounted asset pack.
     */
    public static TextureAtlas load(Path jsonPath) {
        String json;
        try {
            json = Files.readString(jsonPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read atlas: " + jsonPath, e);
        }
        Map<String, Object> root = root(json, jsonPath.toString());
        Object image = map(root.get("meta"), "meta").get("image");
        if (!(image instanceof String imageName)) throw new IllegalArgumentException(jsonPath + ": no meta.image naming the atlas image");
        Path imagePath = jsonPath.resolveSibling(imageName);
        return read(root, Texture.load(imagePath), jsonPath.toString());
    }

    /**
     * Reads a JSON sprite sheet whose image is already loaded, ignoring the image it names.
     */
    public static TextureAtlas parse(String json, Texture texture) {
        return read(root(json, "atlas"), texture, "atlas");
    }

    private static Map<String, Object> root(String json, String source) {
        Object root;
        try {
            root = Json.parse(json);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(source + ": " + e.getMessage(), e);
        }
        return map(root, source);
    }

    private static TextureAtlas read(Map<String, Object> root, Texture texture, String source) {
        TextureAtlas atlas = new TextureAtlas();
        Object frames = root.get("frames");
        List<String> order = new ArrayList<>();
        List<Float> durations = new ArrayList<>();
        try {
            if (frames instanceof Map<?, ?> hash) {
                for (Map.Entry<?, ?> entry : hash.entrySet()) {
                    readFrame(atlas, texture, String.valueOf(entry.getKey()), map(entry.getValue(), "frame " + entry.getKey()), order, durations);
                }
            } else if (frames instanceof List<?> array) {
                for (Object item : array) {
                    Map<String, Object> frame = map(item, "frame");
                    readFrame(atlas, texture, string(frame.get("filename"), "filename"), frame, order, durations);
                }
            } else {
                throw new IllegalArgumentException("no \"frames\" object or array");
            }
            Object meta = root.get("meta");
            if (meta instanceof Map<?, ?> metaMap && metaMap.get("frameTags") instanceof List<?> tags) {
                for (Object tag : tags) readTag(atlas, map(tag, "frame tag"), order, durations);
            }
        } catch (IllegalArgumentException | ClassCastException e) {
            throw new IllegalArgumentException(source + ": " + e.getMessage(), e);
        }
        return atlas;
    }

    private static void readFrame(TextureAtlas atlas, Texture texture, String name, Map<String, Object> entry,
                                  List<String> order, List<Float> durations) {
        boolean rotated = Boolean.TRUE.equals(entry.get("rotated"));
        Map<String, Object> frame = map(entry.get("frame"), "frame '" + name + "'");
        int x = integer(frame, "x"), y = integer(frame, "y"), w = integer(frame, "w"), h = integer(frame, "h");
        int offsetX = 0, offsetY = 0, frameWidth = w, frameHeight = h;
        if (Boolean.TRUE.equals(entry.get("trimmed"))) {
            Map<String, Object> placed = map(entry.get("spriteSourceSize"), "spriteSourceSize of '" + name + "'");
            Map<String, Object> full = map(entry.get("sourceSize"), "sourceSize of '" + name + "'");
            offsetX = integer(placed, "x");
            offsetY = integer(placed, "y");
            frameWidth = integer(full, "w");
            frameHeight = integer(full, "h");
        }
        atlas.add(name, new TextureRegion(texture, x, y, w, h, offsetX, offsetY, frameWidth, frameHeight, rotated));
        order.add(name);
        Object duration = entry.get("duration"); // milliseconds, in Aseprite exports
        durations.add(duration instanceof Double ms && ms > 0 ? (float) (ms / 1000.0) : 0.1f);
    }

    private static void readTag(TextureAtlas atlas, Map<String, Object> tag, List<String> order, List<Float> durations) {
        String name = string(tag.get("name"), "tag name");
        int from = integer(tag, "from"), to = integer(tag, "to");
        if (from < 0 || to >= order.size() || from > to) {
            throw new IllegalArgumentException("tag '" + name + "' covers frames " + from + " to " + to + " of " + order.size());
        }
        List<Animation.Frame> frames = new ArrayList<>();
        for (int i = from; i <= to; i++) frames.add(new Animation.Frame(atlas.regions.get(order.get(i)), durations.get(i)));
        String direction = tag.get("direction") instanceof String d ? d.toLowerCase(Locale.ROOT) : "forward";
        Object repeat = tag.get("repeat"); // a string in Aseprite's exports; absent or 0 means forever
        boolean once = repeat != null && String.valueOf(repeat).replaceAll("\\.0$", "").equals("1");
        Animation.PlayMode mode = once ? Animation.PlayMode.ONCE
                : direction.startsWith("pingpong") ? Animation.PlayMode.PING_PONG : Animation.PlayMode.LOOP;
        Animation animation = new Animation(frames, mode);
        if (direction.equals("reverse") || direction.equals("pingpong_reverse")) animation = animation.reversed();
        atlas.addAnimation(name, animation);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String what) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException(what + " is missing or not an object");
    }

    private static String string(Object value, String what) {
        if (value instanceof String s) return s;
        throw new IllegalArgumentException(what + " is missing or not a string");
    }

    private static int integer(Map<String, Object> map, String key) {
        if (map.get(key) instanceof Double d && d == Math.rint(d)) return d.intValue();
        throw new IllegalArgumentException("\"" + key + "\" is missing or not a whole number");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return dot > slash + 1 ? name.substring(0, dot) : name;
    }

    /**
     * Compares names with runs of digits compared by value: "walk_2" before "walk_10".
     */
    static int compareNatural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int endA = i, endB = j;
                while (endA < a.length() && Character.isDigit(a.charAt(endA))) endA++;
                while (endB < b.length() && Character.isDigit(b.charAt(endB))) endB++;
                String digitsA = a.substring(i, endA).replaceFirst("^0+(?=.)", "");
                String digitsB = b.substring(j, endB).replaceFirst("^0+(?=.)", "");
                int byValue = digitsA.length() != digitsB.length()
                        ? Integer.compare(digitsA.length(), digitsB.length()) : digitsA.compareTo(digitsB);
                if (byValue != 0) return byValue;
                i = endA;
                j = endB;
            } else {
                if (ca != cb) return Character.compare(ca, cb);
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
