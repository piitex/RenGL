package me.piitex.engine.render3d.scene;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * A picture for a {@link Material}, held in ordinary memory as RGBA8 with the top row first. Like {@link Mesh} it is
 * uploaded to the GPU when first drawn, so it can be loaded on any thread and shared freely.
 * <p>
 * The colours are sRGB, as in any image file. PNG, JPEG, BMP, TGA and GIF load.
 * <p>
 * The size is fixed but the pixels are not: {@link #update(byte[])} and {@link #edit} change them, and every scene
 * that draws the texture uploads the new picture before its next frame. That is what an animated screen is: one
 * {@code TextureData} on a material's {@link Material#setEmissiveTexture emissive} (or colour) texture, rewritten as
 * often as it should change.
 */
public final class TextureData {
    private final int width;
    private final int height;
    private final byte[] rgba;
    private long version;

    /**
     * @param rgba {@code width * height * 4} bytes, top row first; copied
     */
    public TextureData(int width, int height, byte[] rgba) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("A texture needs at least one pixel");
        if (rgba.length != width * height * 4) throw new IllegalArgumentException("Expected " + width * height * 4 + " bytes for " + width + "x" + height + ", got " + rgba.length);
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
    byte[] pixels() {
        return rgba;
    }

    /**
     * Replaces every pixel. The scenes drawing this texture show the new picture from their next frame.
     *
     * @param rgba {@code width * height * 4} bytes, top row first; copied
     */
    public synchronized void update(byte[] rgba) {
        if (rgba.length != this.rgba.length) throw new IllegalArgumentException("Expected " + this.rgba.length + " bytes for " + width + "x" + height + ", got " + rgba.length);
        System.arraycopy(rgba, 0, this.rgba, 0, rgba.length);
        version++;
    }

    /**
     * Changes the pixels in place, for drawing into the texture without making an array per frame. The editor gets
     * the live {@code width * height * 4} byte array (RGBA8, top row first) and must not keep it past the call.
     */
    public synchronized void edit(Consumer<byte[]> editor) {
        editor.accept(rgba);
        version++;
    }

    /**
     * Counts the changes made with {@link #update} and {@link #edit}, 0 for a texture never changed. A scene uploads
     * the picture again when this is not the number it last saw.
     */
    public synchronized long version() {
        return version;
    }

    /**
     * Fills a new direct buffer with the pixels, for the device to upload. The caller frees it with
     * {@link MemoryUtil#memFree}.
     */
    ByteBuffer toBuffer() {
        return toBuffer(null);
    }

    /**
     * As {@link #toBuffer()}, also writing the {@link #version} the buffer holds into {@code versionOut[0]}, read under
     * the same lock as the copy so the two always agree.
     */
    synchronized ByteBuffer toBuffer(long[] versionOut) {
        if (versionOut != null) versionOut[0] = version;
        ByteBuffer buffer = MemoryUtil.memAlloc(rgba.length);
        buffer.put(rgba).flip();
        return buffer;
    }

    public static TextureData load(Path file) throws IOException {
        return decode(Files.readAllBytes(file), file.toString());
    }

    /**
     * Decodes an image file held in memory.
     *
     * @param name what to call it in an error
     */
    public static TextureData decode(byte[] encoded, String name) throws IOException {
        ByteBuffer data = MemoryUtil.memAlloc(encoded.length);
        data.put(encoded).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), channels = stack.mallocInt(1);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(data, w, h, channels, 4);
            if (pixels == null) throw new IOException("Could not decode the image " + name + ": " + STBImage.stbi_failure_reason());
            try {
                byte[] rgba = new byte[w.get(0) * h.get(0) * 4];
                pixels.get(0, rgba); // absolute: the free below uses the buffer's address plus its position
                return new TextureData(w.get(0), h.get(0), rgba);
            } finally {
                STBImage.stbi_image_free(pixels);
            }
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /**
     * A single colour, 0 to 1 per channel.
     */
    public static TextureData solid(float r, float g, float b, float a) {
        return new TextureData(1, 1, new byte[]{toByte(r), toByte(g), toByte(b), toByte(a)});
    }

    /**
     * A checkerboard of {@code cells} by {@code cells} squares, handy for seeing how a mesh is mapped. Colours are
     * {@code {r, g, b}}, 0 to 1.
     */
    public static TextureData checker(int size, int cells, float[] colorA, float[] colorB) {
        byte[] rgba = new byte[size * size * 4];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float[] c = ((x * cells / size) + (y * cells / size)) % 2 == 0 ? colorA : colorB;
                int o = (y * size + x) * 4;
                rgba[o] = toByte(c[0]);
                rgba[o + 1] = toByte(c[1]);
                rgba[o + 2] = toByte(c[2]);
                rgba[o + 3] = (byte) 255;
            }
        }
        return new TextureData(size, size, rgba);
    }

    private static byte toByte(float v) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }
}
