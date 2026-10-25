package me.piitex.engine.tilemap;

import me.piitex.engine.graphics.Animation;
import me.piitex.engine.graphics.BlendMode;
import me.piitex.engine.graphics.Texture;
import me.piitex.engine.graphics.TextureRegion;
import me.piitex.engine.io.Json;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.text.TextAlign;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.ColorAlphaType;
import org.jetbrains.skia.ColorType;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.ImageInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads a Tiled map and everything it uses. Both formats come here in one shape: JSON as parsed, XML through
 * {@link TmxConverter}. One reader loads one map, sharing each tile set, template and image the map names more
 * than once.
 */
final class TiledReader {
    private static final int FLIPPED_HORIZONTALLY = 0x80000000;
    private static final int FLIPPED_VERTICALLY = 0x40000000;
    private static final int FLIPPED_DIAGONALLY = 0x20000000;
    private static final int GID_MASK = 0x0FFFFFFF; // bit 28 turns hexagonal tiles by 120 degrees, which is not read

    private final Map<Path, TileSet> tileSets = new HashMap<>();
    private final Map<Path, Template> templates = new HashMap<>();
    private final Map<String, Texture> textures = new HashMap<>();
    private TileMap map;
    private final TiledProject project;

    TiledReader() {
        this(null);
    }

    /**
     * @param project the project the map belongs to, whose class defaults fill in what the map leaves out, or null
     */
    TiledReader(TiledProject project) {
        this.project = project;
    }

    /**
     * A problem already worded with the file it is in, so it isn't wrapped again on its way out.
     */
    private static final class FileProblem extends IllegalArgumentException {
        FileProblem(Path file, RuntimeException cause) {
            super(file + ": " + cause.getMessage(), cause);
        }
    }

    private record Template(Map<String, Object> object, Path file, Gids gids) {
    }

    /**
     * Global tile numbers to tiles: each tile set takes the numbers from its first gid up.
     */
    private record Gids(int[] firsts, TileSet[] sets) {
        Tile tile(int gid, String where) {
            int found = -1;
            for (int i = 0; i < firsts.length; i++) if (firsts[i] <= gid && (found < 0 || firsts[i] > firsts[found])) found = i;
            Tile tile = found < 0 ? null : sets[found].findTile(gid - firsts[found]);
            if (tile == null) throw new IllegalArgumentException(where + " uses tile " + gid + ", which is in none of the map's tile sets");
            return tile;
        }
    }

    TileMap readMap(Path path) {
        Map<String, Object> root = readTree(path);
        try {
            return buildMap(root, path);
        } catch (FileProblem e) {
            throw e;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new FileProblem(path, e);
        }
    }

    private Map<String, Object> readTree(Path path) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
        try {
            int start = 0;
            while (start < text.length() && (Character.isWhitespace(text.charAt(start)) || text.charAt(start) == '\uFEFF')) start++;
            if (start < text.length() && text.charAt(start) == '<') return TmxConverter.convert(text.substring(start));
            if (Json.parse(text.substring(start)) instanceof Map<?, ?> json) return cast(json);
            throw new IllegalArgumentException("not a Tiled file: expected a JSON object or XML");
        } catch (IllegalArgumentException e) {
            throw new FileProblem(path, e);
        }
    }

    // ---- Map

    private TileMap buildMap(Map<String, Object> root, Path file) {
        String kind = str(root, "type", "map");
        if (!kind.equals("map")) throw new IllegalArgumentException("this is a " + kind + " file, not a map");
        String orientation = str(root, "orientation", "orthogonal");
        int mapWidth = intOr(root, "width", 0), mapHeight = intOr(root, "height", 0), tileWidth = integer(root, "tilewidth"), tileHeight = integer(root, "tileheight");
        TileMap.StaggerAxis axis = str(root, "staggeraxis", "y").equals("x") ? TileMap.StaggerAxis.X : TileMap.StaggerAxis.Y;
        boolean odd = !str(root, "staggerindex", "odd").equals("even");
        if (!orientation.equals("orthogonal") && bool(root, "infinite", false)) {
            throw new IllegalArgumentException("an infinite map must be orthogonal, and this one is " + orientation);
        }
        map = switch (orientation) {
            case "orthogonal" -> new TileMap(mapWidth, mapHeight, tileWidth, tileHeight);
            case "isometric" -> TileMap.isometric(mapWidth, mapHeight, tileWidth, tileHeight);
            case "staggered" -> TileMap.staggered(mapWidth, mapHeight, tileWidth, tileHeight, axis, odd);
            case "hexagonal" -> TileMap.hexagonal(mapWidth, mapHeight, tileWidth, tileHeight, intOr(root, "hexsidelength", 0), axis, odd);
            default -> throw new IllegalArgumentException("unknown map orientation '" + orientation + "'");
        };
        map.setSource(file);
        map.setInfinite(bool(root, "infinite", false));
        map.setType(str(root, "class", ""));
        map.setBackgroundColor(color(root, "backgroundcolor"));
        map.setParallaxOrigin((float) num(root, "parallaxoriginx", 0), (float) num(root, "parallaxoriginy", 0));
        String order = str(root, "renderorder", "right-down");
        map.setRenderOrder(!order.startsWith("left"), !order.endsWith("up"));
        map.setProperties(new Properties(properties(root, file), map));

        List<Object> sets = list(root, "tilesets");
        int[] firsts = new int[sets.size()];
        TileSet[] loaded = new TileSet[sets.size()];
        for (int i = 0; i < sets.size(); i++) {
            Map<String, Object> entry = cast(sets.get(i));
            firsts[i] = integer(entry, "firstgid");
            String source = str(entry, "source", null);
            loaded[i] = source != null ? tileSetFile(file.resolveSibling(source).normalize()) : tileSet(entry, file);
            map.addTileSet(loaded[i]);
        }
        Gids gids = new Gids(firsts, loaded);
        for (Object layer : list(root, "layers")) map.addLayer(layer(cast(layer), file, gids));

        if (map.isInfinite()) {
            var bounds = map.getBounds();
            map.setSize(Math.round(bounds.width() / map.getTileWidth()), Math.round(bounds.height() / map.getTileHeight()));
        }
        return map;
    }

    // ---- Layers

    private MapLayer layer(Map<String, Object> entry, Path file, Gids gids) {
        String name = str(entry, "name", "");
        String kind = str(entry, "type", "");
        MapLayer layer = switch (kind) {
            case "tilelayer" -> tileLayer(entry, name, gids);
            case "objectgroup" -> objectLayer(entry, name, file, gids);
            case "imagelayer" -> imageLayer(entry, name, file);
            case "group" -> {
                GroupLayer group = new GroupLayer(name);
                for (Object child : list(entry, "layers")) group.add(layer(cast(child), file, gids));
                yield group;
            }
            default -> throw new IllegalArgumentException("layer '" + name + "' is of an unknown type: '" + kind + "'");
        };
        layer.setId(intOr(entry, "id", 0));
        layer.setType(str(entry, "class", ""));
        layer.setVisible(bool(entry, "visible", true));
        layer.setOpacity((float) num(entry, "opacity", 1));
        layer.setTint(color(entry, "tintcolor"));
        layer.setBlendMode(BlendMode.fromName(str(entry, "mode", "normal")));
        layer.setOffset((float) num(entry, "offsetx", 0), (float) num(entry, "offsety", 0));
        layer.setParallax((float) num(entry, "parallaxx", 1), (float) num(entry, "parallaxy", 1));
        layer.setProperties(new Properties(properties(entry, file), map));
        return layer;
    }

    private TileLayer tileLayer(Map<String, Object> entry, String name, Gids gids) {
        String where = "Layer '" + name + "'";
        String encoding = str(entry, "encoding", "csv"), compression = str(entry, "compression", "");
        List<Object> chunks = list(entry, "chunks");
        if (!chunks.isEmpty()) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            for (Object c : chunks) {
                Map<String, Object> chunk = cast(c);
                int x = integer(chunk, "x"), y = integer(chunk, "y");
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + integer(chunk, "width"));
                maxY = Math.max(maxY, y + integer(chunk, "height"));
            }
            TileLayer layer = new TileLayer(name, minX, minY, maxX - minX, maxY - minY);
            for (Object c : chunks) {
                Map<String, Object> chunk = cast(c);
                int x = integer(chunk, "x"), y = integer(chunk, "y"), w = integer(chunk, "width"), h = integer(chunk, "height");
                int[] data = decode(chunk.get("data"), encoding, compression, w * h, where + ", chunk at " + x + ", " + y);
                for (int i = 0; i < data.length; i++) place(layer, x + i % w, y + i / w, data[i], gids, where);
            }
            return layer;
        }
        int width = intOr(entry, "width", map.getWidth()), height = intOr(entry, "height", map.getHeight());
        TileLayer layer = new TileLayer(name, width, height);
        if (entry.get("data") != null) {
            int[] data = decode(entry.get("data"), encoding, compression, width * height, where);
            for (int i = 0; i < data.length; i++) place(layer, i % width, i / width, data[i], gids, where);
        }
        return layer;
    }

    private static void place(TileLayer layer, int x, int y, int gid, Gids gids, String where) {
        if (gid == 0) return;
        int flags = ((gid & FLIPPED_HORIZONTALLY) != 0 ? TileLayer.FLIP_X : 0)
                | ((gid & FLIPPED_VERTICALLY) != 0 ? TileLayer.FLIP_Y : 0)
                | ((gid & FLIPPED_DIAGONALLY) != 0 ? TileLayer.FLIP_DIAGONAL : 0);
        layer.setTile(x, y, gids.tile(gid & GID_MASK, where), flags);
    }

    /**
     * A layer's gids from any of the ways Tiled saves them: a JSON array, XML {@code <tile>} elements, CSV text, or
     * base64 text, compressed or not.
     */
    private static int[] decode(Object data, String encoding, String compression, int count, String where) {
        int[] gids;
        if (data instanceof List<?> list) {
            gids = new int[list.size()];
            for (int i = 0; i < gids.length; i++) gids[i] = (int) unsigned(list.get(i), where);
        } else if (data instanceof String text && encoding.equals("base64")) {
            byte[] bytes = TileCompression.decompress(Base64.getMimeDecoder().decode(text.trim()), compression, where);
            if (bytes.length % 4 != 0) throw new IllegalArgumentException(where + ": tile data is " + bytes.length + " bytes, not whole numbers");
            gids = new int[bytes.length / 4];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(gids);
        } else if (data instanceof String text && encoding.equals("csv")) {
            List<Integer> values = new ArrayList<>();
            for (String value : text.split("[,\\s]+")) if (!value.isEmpty()) values.add((int) unsigned(value, where));
            gids = values.stream().mapToInt(Integer::intValue).toArray();
        } else {
            throw new IllegalArgumentException(where + ": tile data in an unknown encoding: '" + encoding + "'");
        }
        if (gids.length != count) throw new IllegalArgumentException(where + " has " + gids.length + " tiles, not the " + count + " its size needs");
        return gids;
    }

    private static long unsigned(Object value, String where) {
        try {
            long gid = value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value).trim());
            if (gid < 0 || gid > 0xFFFFFFFFL) throw new NumberFormatException();
            return gid;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(where + ": bad tile number '" + value + "'");
        }
    }

    private ObjectLayer objectLayer(Map<String, Object> entry, String name, Path file, Gids gids) {
        List<MapObject> objects = new ArrayList<>();
        for (Object object : list(entry, "objects")) objects.add(object(cast(object), file, gids, "layer '" + name + "'"));
        boolean topDown = !str(entry, "draworder", "topdown").equals("index");
        return new ObjectLayer(name, objects, color(entry, "color"), topDown);
    }

    private ImageLayer imageLayer(Map<String, Object> entry, String name, Path file) {
        Texture texture = imageOf(entry, file, str(entry, "transparentcolor", null));
        ImageLayer layer = new ImageLayer(name, texture);
        layer.setRepeat(bool(entry, "repeatx", false), bool(entry, "repeaty", false));
        return layer;
    }

    // ---- Objects

    /**
     * An object, with its template's settings under its own. Tile objects take their tile from the map's tile sets,
     * or from the template's tile set if the template sets the tile; {@code gids} is null for the shapes of a
     * tile's collision, which can't show tiles.
     */
    private MapObject object(Map<String, Object> entry, Path file, Gids gids, String where) {
        Map<String, Object> merged = entry;
        Map<String, Object> props = new LinkedHashMap<>();
        Tile tile = null;
        int gid = 0;
        String templateName = str(entry, "template", null);
        if (templateName != null) {
            Template template = template(file.resolveSibling(templateName).normalize());
            merged = new LinkedHashMap<>(template.object());
            merged.putAll(entry);
            props.putAll(properties(template.object(), template.file()));
            if (!entry.containsKey("gid") && template.object().containsKey("gid") && template.gids() != null) {
                gid = (int) unsigned(template.object().get("gid"), where);
                tile = template.gids().tile(gid & GID_MASK, "Template " + template.file());
            }
        }
        props.putAll(properties(entry, file));
        if (tile == null && merged.containsKey("gid") && gids != null) {
            gid = (int) unsigned(merged.get("gid"), where);
            tile = gids.tile(gid & GID_MASK, "Object " + intOr(merged, "id", 0) + " in " + where);
        }

        MapObject.Kind kind;
        float[] points = null;
        ObjectText text = null;
        if (tile != null) {
            kind = MapObject.Kind.TILE;
        } else if (bool(merged, "ellipse", false)) {
            kind = MapObject.Kind.ELLIPSE;
        } else if (bool(merged, "capsule", false)) {
            kind = MapObject.Kind.CAPSULE;
        } else if (bool(merged, "point", false)) {
            kind = MapObject.Kind.POINT;
        } else if (merged.get("polygon") instanceof List<?> polygon) {
            kind = MapObject.Kind.POLYGON;
            points = points(polygon);
        } else if (merged.get("polyline") instanceof List<?> polyline) {
            kind = MapObject.Kind.POLYLINE;
            points = points(polyline);
        } else if (merged.get("text") instanceof Map<?, ?> t) {
            kind = MapObject.Kind.TEXT;
            text = objectText(cast(t));
        } else {
            kind = MapObject.Kind.RECTANGLE;
        }
        String type = str(merged, "class", "");
        if (type.isEmpty()) type = str(merged, "type", "");
        float x = (float) num(merged, "x", 0), y = (float) num(merged, "y", 0);
        float width = (float) num(merged, "width", 0), height = (float) num(merged, "height", 0), rotation = (float) num(merged, "rotation", 0);
        if (map.getOrientation() == TileMap.Orientation.ISOMETRIC) {
            // Tiled saves the objects of an isometric map on a square grid of tileHeight pixels, which it then draws as diamonds.
            // They are read where they are drawn, so a rectangle becomes the parallelogram it is, and an ellipse its outline.
            float[] at = map.isometricToScreen(x, y);
            float[] shape = null;
            if (kind == MapObject.Kind.POLYGON || kind == MapObject.Kind.POLYLINE) {
                shape = new float[points.length];
                for (int i = 0; i < points.length; i += 2) {
                    float[] p = map.isometricToScreen(x + points[i], y + points[i + 1]);
                    shape[i] = p[0] - at[0];
                    shape[i + 1] = p[1] - at[1];
                }
            } else if ((kind == MapObject.Kind.RECTANGLE || kind == MapObject.Kind.CAPSULE) && width > 0f && height > 0f) {
                shape = isometricOutline(map, at, x, y, new float[]{0, 0, width, 0, width, height, 0, height});
                kind = MapObject.Kind.POLYGON;
            } else if (kind == MapObject.Kind.ELLIPSE && width > 0f && height > 0f) {
                float[] ring = new float[32];
                for (int i = 0; i < 16; i++) {
                    double angle = Math.PI * 2 * i / 16;
                    ring[i * 2] = width / 2f + width / 2f * (float) Math.cos(angle);
                    ring[i * 2 + 1] = height / 2f + height / 2f * (float) Math.sin(angle);
                }
                shape = isometricOutline(map, at, x, y, ring);
                kind = MapObject.Kind.POLYGON;
            }
            if (shape != null) {
                points = shape;
                width = 0f;
                height = 0f;
                rotation = 0f;
            }
            x = at[0];
            y = at[1];
        }
        return new MapObject(intOr(merged, "id", 0), str(merged, "name", ""), type, kind,
                x, y, width, height, rotation,
                points, tile, (gid & FLIPPED_HORIZONTALLY) != 0, (gid & FLIPPED_VERTICALLY) != 0, text,
                bool(merged, "visible", true), (float) num(merged, "opacity", 1), new Properties(props, map));
    }

    /**
     * A shape given as points measured from an isometric object's corner, as points measured from where that corner is drawn.
     */
    private static float[] isometricOutline(TileMap map, float[] at, float x, float y, float[] local) {
        float[] out = new float[local.length];
        for (int i = 0; i < local.length; i += 2) {
            float[] p = map.isometricToScreen(x + local[i], y + local[i + 1]);
            out[i] = p[0] - at[0];
            out[i + 1] = p[1] - at[1];
        }
        return out;
    }

    private static ObjectText objectText(Map<String, Object> t) {
        Color color = color(t, "color");
        TextAlign horizontal = switch (str(t, "halign", "left")) {
            case "center" -> TextAlign.CENTER;
            case "right" -> TextAlign.RIGHT;
            default -> TextAlign.LEFT;
        };
        float vertical = switch (str(t, "valign", "top")) {
            case "center" -> 0.5f;
            case "bottom" -> 1f;
            default -> 0f;
        };
        return new ObjectText(str(t, "text", ""), str(t, "fontfamily", "sans-serif"), (float) num(t, "pixelsize", 16),
                bool(t, "wrap", false), color, bool(t, "bold", false), bool(t, "italic", false),
                bool(t, "underline", false), bool(t, "strikeout", false), horizontal, vertical);
    }

    private static float[] points(List<?> list) {
        float[] points = new float[list.size() * 2];
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> point = cast(list.get(i));
            points[i * 2] = (float) num(point, "x", 0);
            points[i * 2 + 1] = (float) num(point, "y", 0);
        }
        return points;
    }

    private Template template(Path path) {
        Template cached = templates.get(path);
        if (cached != null) return cached;
        Map<String, Object> root = readTree(path);
        try {
            if (!str(root, "type", "template").equals("template")) throw new IllegalArgumentException("not a template");
            Gids gids = null;
            if (root.get("tileset") instanceof Map<?, ?> set) {
                Map<String, Object> entry = cast(set);
                String source = str(entry, "source", null);
                if (source == null) throw new IllegalArgumentException("the template's tile set names no file");
                gids = new Gids(new int[]{integer(entry, "firstgid")}, new TileSet[]{tileSetFile(path.resolveSibling(source).normalize())});
            }
            if (!(root.get("object") instanceof Map<?, ?> object)) throw new IllegalArgumentException("the template has no object");
            Template template = new Template(cast(object), path, gids);
            templates.put(path, template);
            return template;
        } catch (FileProblem e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new FileProblem(path, e);
        }
    }

    // ---- Tile sets

    private TileSet tileSetFile(Path path) {
        TileSet cached = tileSets.get(path);
        if (cached != null) return cached;
        Map<String, Object> root = readTree(path);
        try {
            if (!str(root, "type", "tileset").equals("tileset")) throw new IllegalArgumentException("not a tile set");
            TileSet set = tileSet(root, path);
            tileSets.put(path, set);
            return set;
        } catch (FileProblem e) {
            throw e;
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new FileProblem(path, e);
        }
    }

    /**
     * A tile set, whose files are looked for next to {@code file}: the map for a tile set inside it, or the tile
     * set's own file.
     */
    private TileSet tileSet(Map<String, Object> entry, Path file) {
        String name = str(entry, "name", "");
        String where = "Tile set '" + name + "'";
        int tileWidth = integer(entry, "tilewidth"), tileHeight = integer(entry, "tileheight");
        int margin = intOr(entry, "margin", 0), spacing = intOr(entry, "spacing", 0);
        Texture texture = imageOf(entry, file, str(entry, "transparentcolor", null));
        TileSet set;
        if (texture != null) {
            // The image as it is now decides the grid, as in Tiled, even if it has changed size since the map was saved.
            int columns = Math.max(0, (texture.getWidth() - 2 * margin + spacing) / (tileWidth + spacing));
            int rows = Math.max(0, (texture.getHeight() - 2 * margin + spacing) / (tileHeight + spacing));
            set = new TileSet(name, tileWidth, tileHeight, texture, columns);
            int count = columns * rows;
            int declared = intOr(entry, "tilecount", 0);
            if (declared > 0) count = Math.min(count, declared);
            for (int i = 0; i < count; i++) {
                int x = margin + (i % columns) * (tileWidth + spacing), y = margin + (i / columns) * (tileHeight + spacing);
                set.put(new Tile(set, i, new TextureRegion(texture, x, y, tileWidth, tileHeight)));
            }
        } else {
            set = new TileSet(name, tileWidth, tileHeight, null, 0);
        }
        if (entry.get("tileoffset") instanceof Map<?, ?> offset) {
            set.setTileOffset(intOr(cast(offset), "x", 0), intOr(cast(offset), "y", 0));
        }
        set.setType(str(entry, "class", ""));
        set.setProperties(new Properties(properties(entry, file), map));
        float[] pivot = pivot(str(entry, "objectalignment", "unspecified"), where, map != null && map.getOrientation() == TileMap.Orientation.ISOMETRIC);
        set.setObjectPivot(pivot[0], pivot[1]);
        set.setRenderSize(str(entry, "tilerendersize", "tile").equals("grid"), str(entry, "fillmode", "stretch").equals("preserve-aspect-fit"));

        Map<Tile, List<Object>> animations = new LinkedHashMap<>();
        for (Object t : list(entry, "tiles")) {
            Map<String, Object> definition = cast(t);
            int id = integer(definition, "id");
            Tile tile;
            if (set.getTexture() == null) {
                Texture tileTexture = imageOf(definition, file, null);
                if (tileTexture == null) throw new IllegalArgumentException(where + ": tile " + id + " has no image");
                int x = intOr(definition, "x", 0), y = intOr(definition, "y", 0);
                int w = intOr(definition, "width", tileTexture.getWidth() - x), h = intOr(definition, "height", tileTexture.getHeight() - y);
                tile = new Tile(set, id, new TextureRegion(tileTexture, x, y, w, h));
                set.put(tile);
            } else {
                tile = set.findTile(id);
                if (tile == null) continue; // a tile the image no longer has room for
            }
            String type = str(definition, "class", "");
            tile.setType(type.isEmpty() ? str(definition, "type", "") : type);
            tile.setProperties(new Properties(properties(definition, file), map));
            if (definition.get("objectgroup") instanceof Map<?, ?> group) {
                List<MapObject> shapes = new ArrayList<>();
                for (Object object : list(cast(group), "objects")) shapes.add(object(cast(object), file, null, "tile " + id + " of " + where));
                tile.setCollisionObjects(shapes);
            }
            List<Object> frames = list(definition, "animation");
            if (!frames.isEmpty()) animations.put(tile, frames);
        }
        for (Map.Entry<Tile, List<Object>> animation : animations.entrySet()) {
            List<Animation.Frame> frames = new ArrayList<>();
            for (Object f : animation.getValue()) {
                Map<String, Object> frame = cast(f);
                int id = integer(frame, "tileid");
                Tile shown = set.findTile(id);
                if (shown == null) throw new IllegalArgumentException(where + ": tile " + animation.getKey().getId() + "'s animation shows tile " + id + ", which doesn't exist");
                frames.add(new Animation.Frame(shown.getRegion(), (float) Math.max(1.0, num(frame, "duration", 100)) / 1000f));
            }
            animation.getKey().setAnimation(new Animation(frames, Animation.PlayMode.LOOP));
        }
        return set;
    }

    private static float[] pivot(String alignment, String where, boolean isometric) {
        return switch (alignment) {
            case "unspecified" -> isometric ? new float[]{0.5f, 1f} : new float[]{0f, 1f}; // bottom centre by default on an isometric map
            case "bottomleft" -> new float[]{0f, 1f};
            case "topleft" -> new float[]{0f, 0f};
            case "top" -> new float[]{0.5f, 0f};
            case "topright" -> new float[]{1f, 0f};
            case "left" -> new float[]{0f, 0.5f};
            case "center" -> new float[]{0.5f, 0.5f};
            case "right" -> new float[]{1f, 0.5f};
            case "bottom" -> new float[]{0.5f, 1f};
            case "bottomright" -> new float[]{1f, 1f};
            default -> throw new IllegalArgumentException(where + ": unknown object alignment '" + alignment + "'");
        };
    }

    /**
     * The picture a tile set, tile or image layer names: a file next to {@code file}, or the bytes embedded in it.
     * Null if it names none.
     */
    private Texture imageOf(Map<String, Object> entry, Path file, String transparent) {
        String image = str(entry, "image", "");
        if (!image.isEmpty()) return texture(file.resolveSibling(image).normalize(), transparent);
        if (!(entry.get("imagedata") instanceof byte[] bytes)) return null;
        Image decoded;
        try {
            decoded = Image.Companion.makeFromEncoded(bytes);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("an embedded image can't be read as a picture: " + e.getMessage(), e);
        }
        Texture texture = new Texture(decoded);
        Color cut = parseColor(transparent);
        return cut == null ? texture : withoutColor(texture, cut.toARGB() & 0xFFFFFF);
    }

    private Texture texture(Path path, String transparent) {
        String key = path + "|" + (transparent == null ? "" : transparent);
        Texture cached = textures.get(key);
        if (cached != null) return cached;
        Texture texture = Texture.load(path);
        Color cut = parseColor(transparent);
        if (cut != null) texture = withoutColor(texture, cut.toARGB() & 0xFFFFFF);
        textures.put(key, texture);
        return texture;
    }

    /**
     * A copy of the texture with every pixel of the color {@code rgb} made transparent: the old way of cutting
     * out tiles, still used by many tile sets drawn on a magenta background.
     */
    private static Texture withoutColor(Texture texture, int rgb) {
        Image image = texture.getImage();
        int w = image.getWidth(), h = image.getHeight();
        ImageInfo info = new ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
        byte[] pixels;
        try (Bitmap bitmap = Bitmap.Companion.makeFromImage(image)) {
            pixels = bitmap.readPixels(info, w * 4, 0, 0);
        }
        if (pixels == null) throw new IllegalArgumentException("Could not read the pixels of an image to cut out its transparent color");
        byte r = (byte) (rgb >> 16), g = (byte) (rgb >> 8), b = (byte) rgb;
        for (int i = 0; i < pixels.length; i += 4) {
            if (pixels[i] == r && pixels[i + 1] == g && pixels[i + 2] == b) {
                pixels[i] = pixels[i + 1] = pixels[i + 2] = pixels[i + 3] = 0;
            }
        }
        return new Texture(Image.Companion.makeRaster(info, pixels, w * 4));
    }

    // ---- Properties

    /**
     * The properties of a map, layer, tile set, tile or object: the defaults of its class if the project defines it, with
     * what the file sets on top.
     */
    private Map<String, Object> properties(Map<String, Object> entry, Path file) {
        String className = str(entry, "class", "");
        Map<String, Object> values = new LinkedHashMap<>();
        if (project != null && project.hasClass(className)) values.putAll(classDefaults(className, file, 0));
        values.putAll(propertyList(list(entry, "properties"), file, values));
        return values;
    }

    private Map<String, Object> propertyList(List<Object> list, Path file) {
        return propertyList(list, file, Map.of());
    }

    /**
     * The properties in {@code list}, typed. {@code below} is what they are set on top of: for a class-typed one,
     * the value already there supplies the members the file leaves out.
     */
    private Map<String, Object> propertyList(List<Object> list, Path file, Map<String, Object> below) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Object p : list) {
            Map<String, Object> property = cast(p);
            String name = str(property, "name", "");
            String type = str(property, "type", "string");
            Object value = type.equals("class")
                    ? classProperty(str(property, "propertytype", ""), property.get("value"), below.get(name) instanceof Properties p2 ? p2 : null, file, 0)
                    : propertyValue(name, type, property.get("value"), file);
            if (value != null) values.put(name, value);
        }
        return values;
    }

    private Object propertyValue(String name, String type, Object raw, Path file) {
        String where = "property '" + name + "'";
        return switch (type) {
            case "int" -> raw == null ? 0 : (int) whole(raw, where);
            case "float" -> raw == null ? 0f : (float) number(raw, where);
            case "bool" -> raw != null && (raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw)));
            case "color" -> parseColor(raw == null ? null : String.valueOf(raw));
            case "file" -> raw == null || String.valueOf(raw).isEmpty() ? null : file.resolveSibling(String.valueOf(raw)).normalize();
            case "object" -> new Properties.ObjectId(raw == null ? 0 : (int) whole(raw, where));
            case "class" -> classProperty("", raw, null, file, 0);
            default -> raw == null ? "" : raw instanceof Double d ? formatNumber(d) : String.valueOf(raw);
        };
    }

    /**
     * A property whose type is a custom class. Built from the class's defaults in the project (or from {@code below}, if
     * an outer class changed them), then the members the file sets.
     *
     * @param className the class's name, or "" when unknown
     * @param raw       the members the file sets: a typed list in XML, an untyped object in JSON, or null
     */
    private Properties classProperty(String className, Object raw, Properties below, Path file, int depth) {
        if (depth > 16) throw new IllegalArgumentException("class '" + className + "' contains itself");
        Map<String, Object> values = new LinkedHashMap<>();
        if (below != null) values.putAll(below.raw());
        else if (project != null && project.hasClass(className)) values.putAll(classDefaults(className, file, depth));
        if (raw instanceof List<?> members) {
            values.putAll(propertyList(cast(members), file, values));
        } else if (raw instanceof Map<?, ?> members) {
            Map<String, Object> given = cast(members);
            if (project != null && project.hasClass(className)) {
                for (Map.Entry<String, Object> member : given.entrySet()) {
                    TiledProject.Member definition = project.member(className, member.getKey());
                    if (definition == null) {
                        values.putAll(untyped(Map.of(member.getKey(), member.getValue())));
                    } else if (definition.type().equals("class")) {
                        values.put(member.getKey(), classProperty(definition.propertyType(), member.getValue(),
                                values.get(member.getKey()) instanceof Properties p ? p : null, file, depth + 1));
                    } else {
                        Object value = propertyValue(member.getKey(), definition.type(), member.getValue(), file);
                        if (value != null) values.put(member.getKey(), value);
                    }
                }
            } else {
                values.putAll(untyped(given));
            }
        }
        return values.isEmpty() ? Properties.EMPTY : new Properties(values, map);
    }

    /**
     * Every member of a class at its default, as the project defines them.
     */
    private Map<String, Object> classDefaults(String className, Path file, int depth) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (TiledProject.Member member : project.members(className)) {
            Object value = member.type().equals("class")
                    ? classProperty(member.propertyType(), member.value(), null, file, depth + 1)
                    : propertyValue(member.name(), member.type(), member.value(), file);
            if (value != null) values.put(member.name(), value);
        }
        return values;
    }

    /**
     * The members of a class saved in JSON, which carry no types: numbers become whole or not by their value.
     */
    private Map<String, Object> untyped(Map<String, Object> members) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> member : members.entrySet()) {
            Object value = member.getValue();
            if (value instanceof Double d) value = d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE ? (Object) d.intValue() : (Object) d.floatValue();
            if (value instanceof Map<?, ?> nested) value = new Properties(untyped(cast(nested)), map);
            if (value != null) values.put(member.getKey(), value);
        }
        return values;
    }

    static Color parseColor(String text) {
        if (text == null) return null;
        String hex = text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6 && hex.length() != 8) return null;
        long value;
        try {
            value = Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            return null;
        }
        float a = hex.length() == 8 ? ((value >> 24) & 0xFF) / 255f : 1f;
        return new Color(((value >> 16) & 0xFF) / 255f, ((value >> 8) & 0xFF) / 255f, (value & 0xFF) / 255f, a);
    }

    private static Color color(Map<String, Object> entry, String key) {
        String text = str(entry, key, null);
        if (text == null || text.isEmpty()) return null;
        Color color = parseColor(text);
        if (color == null) throw new IllegalArgumentException("\"" + key + "\" is not a color: '" + text + "'");
        return color;
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) return (T) value;
        throw new IllegalArgumentException("expected an object or list, got " + value);
    }

    private static List<Object> list(Map<String, Object> entry, String key) {
        return entry.get(key) instanceof List<?> list ? cast(list) : List.of();
    }

    private static String str(Map<String, Object> entry, String key, String defaultValue) {
        Object value = entry.get(key);
        if (value == null) return defaultValue;
        return value instanceof Double d ? formatNumber(d) : String.valueOf(value);
    }

    private static String formatNumber(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }

    private static double num(Map<String, Object> entry, String key, double defaultValue) {
        Object value = entry.get(key);
        if (value == null || (value instanceof String s && s.isBlank())) return defaultValue;
        return number(value, "\"" + key + "\"");
    }

    private static double number(Object value, String what) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " is not a number: '" + value + "'");
        }
    }

    private static long whole(Object value, String what) {
        double number = number(value, what);
        if (number != Math.rint(number)) throw new IllegalArgumentException(what + " is not a whole number: '" + value + "'");
        return (long) number;
    }

    private static int integer(Map<String, Object> entry, String key) {
        if (!entry.containsKey(key)) throw new IllegalArgumentException("\"" + key + "\" is missing");
        return (int) whole(entry.get(key), "\"" + key + "\"");
    }

    private static int intOr(Map<String, Object> entry, String key, int defaultValue) {
        Object value = entry.get(key);
        if (value == null || (value instanceof String s && s.isBlank())) return defaultValue;
        return (int) whole(value, "\"" + key + "\"");
    }

    private static boolean bool(Map<String, Object> entry, String key, boolean defaultValue) {
        Object value = entry.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        String text = String.valueOf(value).trim();
        if (text.equals("1") || text.equalsIgnoreCase("true") || text.equals("1.0")) return true;
        if (text.equals("0") || text.equalsIgnoreCase("false") || text.equals("0.0")) return false;
        throw new IllegalArgumentException("\"" + key + "\" is not true or false: '" + value + "'");
    }
}
