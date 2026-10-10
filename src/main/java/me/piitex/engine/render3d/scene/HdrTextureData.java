package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Vec3;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A picture of light, held in ordinary memory as floats: red, green, blue and alpha per pixel in <b>linear</b> light, top
 * row first, with no upper limit. A {@link TextureData} stops at white; this holds the sun, which is thousands of times
 * brighter than a blue sky, so it is what a sky that lights the scene properly is made of ({@link Skybox#equirectHdr}).
 * Radiance {@code .hdr} files load, the format most HDRI panoramas come in.
 * <p>
 * Like the other pictures it is uploaded when first drawn, so it can be loaded on any thread and shared freely.
 */
public final class HdrTextureData {
    private final int width;
    private final int height;
    private final float[] rgba;

    /**
     * @param rgba {@code width * height * 4} linear floats, top row first; copied
     */
    public HdrTextureData(int width, int height, float[] rgba) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("A texture needs at least one pixel");
        if (rgba.length != width * height * 4) throw new IllegalArgumentException("Expected " + width * height * 4 + " floats for " + width + "x" + height + ", got " + rgba.length);
        this.width = width;
        this.height = height;
        this.rgba = rgba.clone();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * The pixels themselves, not a copy: read only.
     */
    float[] pixels() {
        return rgba;
    }

    /**
     * Fills a new direct buffer with the pixels, for the device to upload. The caller frees it with
     * {@link MemoryUtil#memFree}.
     */
    FloatBuffer toBuffer() {
        FloatBuffer buffer = MemoryUtil.memAllocFloat(rgba.length);
        buffer.put(rgba).flip();
        return buffer;
    }

    /**
     * The world direction, as seen from the middle of the sky, of the brightest part of the picture read as an equirectangular
     * panorama ({@link Skybox#equirectHdr}): where the sun is, in a daytime sky. Use it to point a directional light the
     * same way ({@code Light.directional(sunDirection().negate(), ...)}, since a light's direction is the way it travels).
     * <p>
     * It looks at the brightness of small blocks rather than single pixels, so one hot pixel doesn't move it. A picture with
     * no sun gives the direction of whatever is brightest in it.
     */
    public Vec3 sunDirection() {
        int bw = Math.max(1, width / 128), bh = Math.max(1, height / 64);
        double best = -1;
        double bestX = 0, bestY = 0;
        for (int y0 = 0; y0 < height; y0 += bh) {
            for (int x0 = 0; x0 < width; x0 += bw) {
                double sum = 0;
                int count = 0;
                for (int y = y0; y < Math.min(height, y0 + bh); y++) {
                    for (int x = x0; x < Math.min(width, x0 + bw); x++) {
                        int i = (y * width + x) * 4;
                        sum += rgba[i] * 0.2126 + rgba[i + 1] * 0.7152 + rgba[i + 2] * 0.0722;
                        count++;
                    }
                }
                if (sum / count > best) {
                    best = sum / count;
                    bestX = (x0 + Math.min(width, x0 + bw)) * 0.5;
                    bestY = (y0 + Math.min(height, y0 + bh)) * 0.5;
                }
            }
        }
        // u = 0.5 looks along -Z, u = 0.75 along +X, the top row straight up.
        double phi = (bestX / width - 0.5) * 2 * Math.PI, elevation = (0.5 - bestY / height) * Math.PI;
        return new Vec3((float) (Math.sin(phi) * Math.cos(elevation)), (float) Math.sin(elevation), (float) (-Math.cos(phi) * Math.cos(elevation)));
    }

    public static HdrTextureData load(Path file) throws IOException {
        return decode(Files.readAllBytes(file), file.toString());
    }

    /**
     * Decodes a Radiance (.hdr) file held in memory.
     *
     * @param name what to call it in an error
     */
    public static HdrTextureData decode(byte[] encoded, String name) throws IOException {
        ByteBuffer data = MemoryUtil.memAlloc(encoded.length);
        data.put(encoded).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), channels = stack.mallocInt(1);
            FloatBuffer pixels = STBImage.stbi_loadf_from_memory(data, w, h, channels, 4);
            if (pixels == null) throw new IOException("Could not decode the image " + name + ": " + STBImage.stbi_failure_reason());
            try {
                float[] rgba = new float[w.get(0) * h.get(0) * 4];
                pixels.get(0, rgba);
                return new HdrTextureData(w.get(0), h.get(0), rgba);
            } finally {
                STBImage.stbi_image_free(pixels);
            }
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /**
     * One colour of linear light, which may be above 1.
     */
    public static HdrTextureData solid(float r, float g, float b) {
        return new HdrTextureData(1, 1, new float[]{r, g, b, 1f});
    }
}
