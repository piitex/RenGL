package me.piitex.engine.render3d.scene;

import me.piitex.engine.io.Json;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a glTF file says about how its materials' textures are placed on the surface, which Assimp does not hand on
 * faithfully: the {@code KHR_texture_transform} offset, rotation and scale, and which set of texture coordinates each
 * texture reads. Read straight from the file's JSON. Materials come in the file's order, which is Assimp's order.
 */
final class GltfTextureInfo {
    /**
     * One material's: the transform of the first of its textures that has one (a material has one transform for all
     * its textures), and the coordinate set of each texture.
     */
    record Material(float[] transform, int[] texCoords, boolean mixedTransforms) {
        static final Material NONE = new Material(null, new int[5], false);
    }

    private GltfTextureInfo() {
    }

    /**
     * The materials of a glTF or GLB file given as its bytes; an empty list if it isn't one or can't be read.
     */
    static List<Material> read(byte[] file) {
        try {
            String json = jsonOf(file);
            if (json == null) return List.of();
            Object root = Json.parse(json);
            if (!(root instanceof Map<?, ?> top) || !(top.get("materials") instanceof List<?> materials)) return List.of();
            List<Material> out = new ArrayList<>(materials.size());
            for (Object material : materials) out.add(material instanceof Map<?, ?> m ? material(m) : Material.NONE);
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
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

    private static final String[] SLOT_KEYS = {"baseColorTexture", "normalTexture", "metallicRoughnessTexture", "occlusionTexture", "emissiveTexture"};

    private static Material material(Map<?, ?> m) {
        Object pbrValue = m.get("pbrMetallicRoughness");
        Map<?, ?> pbr = pbrValue instanceof Map<?, ?> p ? p : Map.of();
        int[] sets = new int[SLOT_KEYS.length];
        float[] transform = null;
        boolean mixed = false;
        for (int slot = 0; slot < SLOT_KEYS.length; slot++) {
            Object texture = slot == 0 || slot == 2 ? pbr.get(SLOT_KEYS[slot]) : m.get(SLOT_KEYS[slot]);
            if (!(texture instanceof Map<?, ?> info)) continue;
            sets[slot] = number(info.get("texCoord"), 0) == 1 ? 1 : 0;
            if (info.get("extensions") instanceof Map<?, ?> extensions && extensions.get("KHR_texture_transform") instanceof Map<?, ?> t) {
                float[] own = {number(list(t.get("offset"), 0), 0), number(list(t.get("offset"), 1), 0),
                        number(list(t.get("scale"), 0), 1), number(list(t.get("scale"), 1), 1), number(t.get("rotation"), 0)};
                if (t.get("texCoord") != null) sets[slot] = number(t.get("texCoord"), 0) == 1 ? 1 : 0;
                if (transform == null) transform = own;
                else if (!java.util.Arrays.equals(transform, own)) mixed = true;
            }
        }
        return new Material(transform, sets, mixed);
    }

    private static Object list(Object value, int index) {
        return value instanceof List<?> l && index < l.size() ? l.get(index) : null;
    }

    private static float number(Object value, float fallback) {
        return value instanceof Number n ? n.floatValue() : fallback;
    }
}
