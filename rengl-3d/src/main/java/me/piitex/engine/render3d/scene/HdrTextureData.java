package me.piitex.engine.render3d.scene;

import me.piitex.engine.render3d.math.Vec3;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.FloatBuffer;
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
     * Decodes a Radiance (.hdr) file held in memory: flat or run-length encoded scanlines, top row first ({@code -Y h +X w}).
     * <p>
     * This is done in Java rather than with stb_image, whose {@code stbi_loadf} in LWJGL 3.4.3 writes each row of a flat
     * file to the wrong place.
     *
     * @param name what to call it in an error
     */
    public static HdrTextureData decode(byte[] encoded, String name) throws IOException {
        int[] pos = {0};
        String magic = line(encoded, pos);
        if (!magic.startsWith("#?RADIANCE") && !magic.startsWith("#?RGBE")) {
            throw new IOException("Could not decode the image " + name + ": not a Radiance file");
        }
        for (String header = line(encoded, pos); !header.isEmpty(); header = line(encoded, pos)) {
            if (header.startsWith("FORMAT=") && !header.equals("FORMAT=32-bit_rle_rgbe")) {
                throw new IOException("Could not decode the image " + name + ": unsupported " + header);
            }
        }
        String[] size = line(encoded, pos).trim().split("\\s+");
        if (size.length != 4 || !size[0].equals("-Y") || !size[2].equals("+X")) {
            throw new IOException("Could not decode the image " + name + ": unsupported orientation " + String.join(" ", size));
        }
        int width, height;
        try {
            height = Integer.parseInt(size[1]);
            width = Integer.parseInt(size[3]);
        } catch (NumberFormatException e) {
            throw new IOException("Could not decode the image " + name + ": bad size " + String.join(" ", size));
        }
        if (width < 1 || height < 1 || (long) width * height > Integer.MAX_VALUE / 4) {
            throw new IOException("Could not decode the image " + name + ": bad size " + width + "x" + height);
        }

        float[] rgba = new float[width * height * 4];
        byte[] row = new byte[width * 4];
        for (int y = 0; y < height; y++) {
            readScanline(encoded, pos, row, width, name);
            for (int x = 0; x < width; x++) {
                int e = row[x * 4 + 3] & 0xff, i = (y * width + x) * 4;
                if (e != 0) {
                    // mantissa / 256 * 2^(exponent - 128)
                    float scale = Math.scalb(1f, e - 136);
                    rgba[i] = (row[x * 4] & 0xff) * scale;
                    rgba[i + 1] = (row[x * 4 + 1] & 0xff) * scale;
                    rgba[i + 2] = (row[x * 4 + 2] & 0xff) * scale;
                }
                rgba[i + 3] = 1f;
            }
        }
        return new HdrTextureData(width, height, rgba);
    }

    /**
     * One header line, without its newline.
     */
    private static String line(byte[] data, int[] pos) throws IOException {
        int start = pos[0];
        while (pos[0] < data.length && data[pos[0]] != '\n') pos[0]++;
        if (pos[0] >= data.length) throw new IOException("Radiance header ends early");
        return new String(data, start, pos[0]++ - start, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * Reads one row of RGBE pixels into {@code row}. A row of 8 to 32767 pixels may be run-length encoded, one channel at a
     * time, and starts with {@code 2 2} and its width; anything else is flat.
     */
    private static void readScanline(byte[] data, int[] pos, byte[] row, int width, String name) throws IOException {
        int p = pos[0];
        if (p + 4 > data.length) throw new IOException("Could not decode the image " + name + ": the file ends early");
        boolean rle = width >= 8 && width < 32768 && data[p] == 2 && data[p + 1] == 2 && (data[p + 2] & 0x80) == 0;
        if (!rle) {
            if (p + width * 4 > data.length) throw new IOException("Could not decode the image " + name + ": the file ends early");
            System.arraycopy(data, p, row, 0, width * 4);
            pos[0] = p + width * 4;
            return;
        }
        if (((data[p + 2] & 0xff) << 8 | (data[p + 3] & 0xff)) != width) {
            throw new IOException("Could not decode the image " + name + ": a run-length row has the wrong width");
        }
        p += 4;
        for (int c = 0; c < 4; c++) {
            for (int x = 0; x < width; ) {
                if (p >= data.length) throw new IOException("Could not decode the image " + name + ": the file ends early");
                int count = data[p++] & 0xff;
                boolean run = count > 128;
                if (run) count -= 128;
                if (count == 0 || x + count > width) throw new IOException("Could not decode the image " + name + ": bad run-length data");
                if (run) {
                    if (p >= data.length) throw new IOException("Could not decode the image " + name + ": the file ends early");
                    byte value = data[p++];
                    for (int k = 0; k < count; k++) row[(x++) * 4 + c] = value;
                } else {
                    if (p + count > data.length) throw new IOException("Could not decode the image " + name + ": the file ends early");
                    for (int k = 0; k < count; k++) row[(x++) * 4 + c] = data[p++];
                }
            }
        }
        pos[0] = p;
    }

    /**
     * One colour of linear light, which may be above 1.
     */
    public static HdrTextureData solid(float r, float g, float b) {
        return new HdrTextureData(1, 1, new float[]{r, g, b, 1f});
    }
}
