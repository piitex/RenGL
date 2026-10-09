package me.piitex.engine.render3d.scene;

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
