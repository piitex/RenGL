package me.piitex.engine.render3d.scene;

import me.piitex.engine.io.Json;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The hand-made levels of detail a glTF file gives with the {@code MSFT_lod} extension, which Assimp does not read: for each
 * node that has them, the nodes of its simpler levels and, from {@code MSFT_screencoverage}, how much of the screen each
 * level is for. The level nodes are usually outside the scene, where Assimp doesn't look, so the file is read with them
 * added ({@link #withLevelsInScene}) and the model takes them out again.
 */
final class GltfLods {
    /**
     * One node's levels.
     *
     * @param node      the name of the node that holds the full mesh
     * @param levels    the names of the nodes of its simpler levels, from the most detailed
     * @param coverage  the share of the screen's area below which the next level is drawn, one per level and one more below
     *                  which nothing is; empty when the file gives none
     */
    record Lod(String node, List<String> levels, float[] coverage) {
    }

    private GltfLods() {
    }

    /** The levels of every node of a glTF or GLB file that has some; empty if it has none or can't be read. */
    static List<Lod> read(byte[] file) {
        try {
            String json = jsonOf(file);
            if (json == null || !json.contains("MSFT_lod")) return List.of();
            if (!(Json.parse(json) instanceof Map<?, ?> top) || !(top.get("nodes") instanceof List<?> nodes)) return List.of();
            List<Lod> out = new ArrayList<>();
            for (Object value : nodes) {
                if (!(value instanceof Map<?, ?> node) || !(node.get("name") instanceof String name)) continue;
                if (!(node.get("extensions") instanceof Map<?, ?> ext) || !(ext.get("MSFT_lod") instanceof Map<?, ?> lod)
                        || !(lod.get("ids") instanceof List<?> ids)) continue;
                List<String> levels = new ArrayList<>();
                for (Object id : ids) {
                    int index = id instanceof Number n ? n.intValue() : -1;
                    if (index < 0 || index >= nodes.size() || !(nodes.get(index) instanceof Map<?, ?> levelNode)
                            || !(levelNode.get("name") instanceof String levelName)) break;
                    levels.add(levelName);
                }
                float[] coverage = new float[0];
                if (node.get("extras") instanceof Map<?, ?> extras && extras.get("MSFT_screencoverage") instanceof List<?> c) {
                    coverage = new float[c.size()];
                    for (int i = 0; i < c.size(); i++) coverage[i] = c.get(i) instanceof Number n ? n.floatValue() : 0f;
                }
                if (!levels.isEmpty()) out.add(new Lod(name, levels, coverage));
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The file with the nodes of every {@code MSFT_lod} level added to the top of its scene, each with a name, or null when
     * there is nothing to add. Assimp only reads the meshes of nodes in the scene, and the levels usually are not.
     */
    @SuppressWarnings("unchecked")
    static byte[] withLevelsInScene(byte[] file) {
        try {
            String json = jsonOf(file);
            if (json == null || !json.contains("MSFT_lod")) return null;
            if (!(Json.parse(json) instanceof Map<?, ?> parsed) || !(parsed.get("nodes") instanceof List<?> nodes)) return null;
            Map<String, Object> top = (Map<String, Object>) parsed;
            if (!(top.get("scenes") instanceof List<?> scenes) || scenes.isEmpty()) return null;
            int sceneIndex = top.get("scene") instanceof Number n ? n.intValue() : 0;
            if (sceneIndex < 0 || sceneIndex >= scenes.size() || !(scenes.get(sceneIndex) instanceof Map<?, ?> s)) return null;
            Map<String, Object> scene = (Map<String, Object>) s;
            List<Object> roots = scene.get("nodes") instanceof List<?> r ? (List<Object>) r : new ArrayList<>();
            scene.put("nodes", roots);
            // Every node the scene already reaches.
            boolean[] reached = new boolean[nodes.size()];
            List<Integer> stack = new ArrayList<>();
            for (Object root : roots) if (root instanceof Number n) stack.add(n.intValue());
            while (!stack.isEmpty()) {
                int i = stack.remove(stack.size() - 1);
                if (i < 0 || i >= nodes.size() || reached[i]) continue;
                reached[i] = true;
                if (nodes.get(i) instanceof Map<?, ?> node && node.get("children") instanceof List<?> children) {
                    for (Object c : children) if (c instanceof Number n) stack.add(n.intValue());
                }
            }
            boolean changed = false;
            for (Object value : nodes) {
                if (!(value instanceof Map<?, ?> node) || !(node.get("extensions") instanceof Map<?, ?> ext)
                        || !(ext.get("MSFT_lod") instanceof Map<?, ?> lod) || !(lod.get("ids") instanceof List<?> ids)) continue;
                for (Object id : ids) {
                    int index = id instanceof Number n ? n.intValue() : -1;
                    if (index < 0 || index >= nodes.size() || !(nodes.get(index) instanceof Map<?, ?> l)) continue;
                    Map<String, Object> level = (Map<String, Object>) l;
                    if (!(level.get("name") instanceof String)) {
                        level.put("name", "MSFT_lod_" + index);
                        changed = true;
                    }
                    if (!reached[index]) {
                        roots.add((double) index);
                        reached[index] = true;
                        changed = true;
                    }
                }
            }
            if (!changed) return null;
            byte[] text = write(top, new StringBuilder()).toString().getBytes(StandardCharsets.UTF_8);
            if (!(file.length >= 20 && file[0] == 'g' && file[1] == 'l' && file[2] == 'T' && file[3] == 'F')) return text;
            // A .glb: the new JSON chunk (padded with spaces to four bytes), then the rest of the file as it was.
            ByteBuffer in = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
            int oldChunk = in.getInt(12);
            int padded = (text.length + 3) & ~3;
            int rest = file.length - 20 - oldChunk;
            ByteBuffer out = ByteBuffer.allocate(20 + padded + rest).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(0x46546C67).putInt(in.getInt(4)).putInt(20 + padded + rest).putInt(padded).putInt(0x4E4F534A);
            out.put(text);
            for (int i = text.length; i < padded; i++) out.put((byte) ' ');
            out.put(file, 20 + oldChunk, rest);
            return out.array();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static StringBuilder write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                write(String.valueOf(e.getKey()), out).append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                write(list.get(i), out);
            }
            out.append(']');
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            // Counts, offsets and indices have to stay whole numbers.
            if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append((long) d);
            else out.append(Double.isFinite(d) ? Double.toString(d) : "0");
        } else if (value instanceof Boolean b) {
            out.append(b);
        } else {
            String s = value.toString();
            out.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            out.append('"');
        }
        return out;
    }

    private static String jsonOf(byte[] file) {
        if (file.length >= 20 && file[0] == 'g' && file[1] == 'l' && file[2] == 'T' && file[3] == 'F') {
            ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
            int length = b.getInt(12), type = b.getInt(16);
            if (type != 0x4E4F534A || 20 + length > file.length) return null; // "JSON"
            return new String(file, 20, length, StandardCharsets.UTF_8);
        }
        int start = 0;
        while (start < file.length && file[start] <= ' ') start++;
        return start < file.length && file[start] == '{' ? new String(file, StandardCharsets.UTF_8) : null;
    }
}
