package me.piitex.engine.render3d.scene;

import me.piitex.engine.io.Json;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The animation channels of a glTF file that Assimp can't carry whole: those with {@code STEP} (which it turns into
 * plain blends) and {@code CUBICSPLINE} interpolation (which it turns into blends of the values alone, dropping the
 * tangents). They are read straight from the file's own JSON and binary data, and replace the channels Assimp made for
 * the same node and property. Animations come in the file's order, which is Assimp's order.
 */
final class GltfAnimations {
    /**
     * One channel: the name of the node it moves, what it moves, how it blends, and its keys. For a cubic spline,
     * {@code in} and {@code out} are the tangents, and {@code values} just the values at the keys.
     */
    record Channel(String node, AnimationClip.Path path, AnimationClip.Interpolation interpolation, float[] times,
                   float[] values, float[] in, float[] out) {
    }

    private GltfAnimations() {
    }

    /**
     * The special channels of each animation of a glTF or GLB file given as its bytes, one list per animation (empty
     * when it has none); an empty result if the file isn't one or can't be read.
     *
     * @param directory where files the glTF points at are looked up, or null for none
     */
    static List<List<Channel>> read(byte[] file, Path directory) {
        try {
            byte[] glbBinary = null;
            String json;
            if (file.length >= 20 && file[0] == 'g' && file[1] == 'l' && file[2] == 'T' && file[3] == 'F') {
                ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
                int length = b.getInt(12);
                json = new String(file, 20, length, StandardCharsets.UTF_8);
                int next = 20 + ((length + 3) & ~3);
                if (next + 8 <= file.length && b.getInt(next + 4) == 0x004E4942) { // "BIN"
                    glbBinary = java.util.Arrays.copyOfRange(file, next + 8, Math.min(file.length, next + 8 + b.getInt(next)));
                }
            } else {
                json = new String(file, StandardCharsets.UTF_8);
            }
            if (!(Json.parse(json) instanceof Map<?, ?> root) || !(root.get("animations") instanceof List<?> animations)) return List.of();
            Data data = new Data(root, glbBinary, directory);
            List<List<Channel>> out = new ArrayList<>();
            for (Object animation : animations) out.add(animation instanceof Map<?, ?> a ? channels(a, root, data) : List.of());
            return out;
        } catch (RuntimeException | IOException e) {
            return List.of();
        }
    }

    private static List<Channel> channels(Map<?, ?> animation, Map<?, ?> root, Data data) throws IOException {
        List<Channel> out = new ArrayList<>();
        if (!(animation.get("channels") instanceof List<?> channels) || !(animation.get("samplers") instanceof List<?> samplers)) return out;
        List<?> nodes = root.get("nodes") instanceof List<?> n ? n : List.of();
        for (Object c : channels) {
            if (!(c instanceof Map<?, ?> channel) || !(channel.get("target") instanceof Map<?, ?> target)) continue;
            if (!(channel.get("sampler") instanceof Number s) || s.intValue() >= samplers.size() || !(samplers.get(s.intValue()) instanceof Map<?, ?> sampler)) continue;
            String mode = sampler.get("interpolation") instanceof String m ? m : "LINEAR";
            AnimationClip.Interpolation interpolation = switch (mode) {
                case "STEP" -> AnimationClip.Interpolation.STEP;
                case "CUBICSPLINE" -> AnimationClip.Interpolation.CUBICSPLINE;
                default -> null;
            };
            if (interpolation == null) continue; // plain blends are what Assimp gives already
            AnimationClip.Path path = switch (target.get("path") instanceof String p ? p : "") {
                case "translation" -> AnimationClip.Path.TRANSLATION;
                case "rotation" -> AnimationClip.Path.ROTATION;
                case "scale" -> AnimationClip.Path.SCALE;
                case "weights" -> AnimationClip.Path.WEIGHTS;
                default -> null;
            };
            if (path == null || !(target.get("node") instanceof Number nodeIndex) || nodeIndex.intValue() >= nodes.size()) continue;
            if (!(nodes.get(nodeIndex.intValue()) instanceof Map<?, ?> node) || !(node.get("name") instanceof String name) || name.isEmpty()) continue;
            if (!(sampler.get("input") instanceof Number input) || !(sampler.get("output") instanceof Number output)) continue;
            float[] times = data.floats(input.intValue()), values = data.floats(output.intValue());
            if (times == null || values == null || times.length == 0) continue;
            if (interpolation == AnimationClip.Interpolation.STEP) {
                if (!fits(path, times.length, values.length)) continue;
                out.add(new Channel(name, path, interpolation, times, values, null, null));
                continue;
            }
            // Cubic: three values per key, the arriving slope, the value and the leaving slope, one after another.
            if (values.length % (times.length * 3) != 0) continue;
            int per = values.length / (times.length * 3);
            if (path != AnimationClip.Path.WEIGHTS && per != path.width) continue;
            float[] v = new float[times.length * per], in = new float[v.length], outTangents = new float[v.length];
            for (int k = 0; k < times.length; k++) {
                System.arraycopy(values, (k * 3) * per, in, k * per, per);
                System.arraycopy(values, (k * 3 + 1) * per, v, k * per, per);
                System.arraycopy(values, (k * 3 + 2) * per, outTangents, k * per, per);
            }
            out.add(new Channel(name, path, interpolation, times, v, in, outTangents));
        }
        return out;
    }

    private static boolean fits(AnimationClip.Path path, int keys, int values) {
        return path == AnimationClip.Path.WEIGHTS ? values % keys == 0 : values == keys * path.width;
    }

    /**
     * The binary data of a glTF file, read on demand.
     */
    private static final class Data {
        private final Map<?, ?> root;
        private final byte[] glb;
        private final Path directory;
        private final Map<Integer, byte[]> buffers = new java.util.HashMap<>();

        Data(Map<?, ?> root, byte[] glb, Path directory) {
            this.root = root;
            this.glb = glb;
            this.directory = directory;
        }

        private byte[] buffer(int index) throws IOException {
            byte[] cached = buffers.get(index);
            if (cached != null) return cached;
            if (!(root.get("buffers") instanceof List<?> list) || index >= list.size() || !(list.get(index) instanceof Map<?, ?> b)) return null;
            byte[] bytes;
            if (!(b.get("uri") instanceof String uri)) {
                bytes = glb;
            } else if (uri.startsWith("data:")) {
                bytes = Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1));
            } else if (directory != null) {
                bytes = Files.readAllBytes(directory.resolve(URLDecoder.decode(uri.replace("+", "%2B"), StandardCharsets.UTF_8)));
            } else {
                bytes = null;
            }
            if (bytes != null) buffers.put(index, bytes);
            return bytes;
        }

        /**
         * The numbers of a float accessor, or null for any other kind (normalized integers, sparse data).
         */
        float[] floats(int accessorIndex) throws IOException {
            if (!(root.get("accessors") instanceof List<?> accessors) || accessorIndex >= accessors.size()
                    || !(accessors.get(accessorIndex) instanceof Map<?, ?> accessor)) return null;
            if (!(accessor.get("componentType") instanceof Number type) || type.intValue() != 5126 || accessor.get("sparse") != null) return null;
            if (!(accessor.get("bufferView") instanceof Number viewIndex) || !(root.get("bufferViews") instanceof List<?> views)
                    || viewIndex.intValue() >= views.size() || !(views.get(viewIndex.intValue()) instanceof Map<?, ?> view)) return null;
            int count = accessor.get("count") instanceof Number n ? n.intValue() : 0;
            int width = switch (accessor.get("type") instanceof String t ? t : "") {
                case "SCALAR" -> 1;
                case "VEC2" -> 2;
                case "VEC3" -> 3;
                case "VEC4" -> 4;
                default -> 0;
            };
            if (count == 0 || width == 0 || !(view.get("buffer") instanceof Number bufferIndex)) return null;
            byte[] bytes = buffer(bufferIndex.intValue());
            if (bytes == null) return null;
            int start = (view.get("byteOffset") instanceof Number o ? o.intValue() : 0) + (accessor.get("byteOffset") instanceof Number o ? o.intValue() : 0);
            int stride = view.get("byteStride") instanceof Number s && s.intValue() > 0 ? s.intValue() : width * 4;
            if ((long) start + (long) (count - 1) * stride + width * 4L > bytes.length) return null;
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            float[] out = new float[count * width];
            for (int i = 0; i < count; i++) for (int c = 0; c < width; c++) out[i * width + c] = b.getFloat(start + i * stride + c * 4);
            return out;
        }
    }
}
