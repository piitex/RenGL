package me.piitex.engine.render3d.scene;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pictures of light with no upper limit, and what lights a scene from one. No GPU needed.
 */
class HdrSkyTest {
    private static HdrTextureData uniform(float value, int width, int height) {
        float[] rgba = new float[width * height * 4];
        for (int i = 0; i < width * height; i++) {
            rgba[i * 4] = rgba[i * 4 + 1] = rgba[i * 4 + 2] = value;
            rgba[i * 4 + 3] = 1f;
        }
        return new HdrTextureData(width, height, rgba);
    }

    @Test
    void aRadianceFileLoadsAsLinearFloats() throws Exception {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 2 +X 4\n".getBytes(StandardCharsets.US_ASCII));
        // mantissa / 256 * 2^(exponent - 128): (128, 130) is 2, (128, 127) is 0.25, (192, 129) is 1.5, (128, 120) is about 0.002
        int[][] pixels = {{128, 128, 128, 130}, {128, 128, 128, 127}, {192, 192, 192, 129}, {128, 128, 128, 120},
                {128, 64, 32, 130}, {0, 0, 0, 0}, {128, 128, 128, 140}, {255, 255, 255, 128}};
        for (int[] p : pixels) for (int v : p) file.write(v);
        HdrTextureData hdr = HdrTextureData.decode(file.toByteArray(), "test.hdr");
        assertEquals(4, hdr.width());
        assertEquals(2, hdr.height());
        float[] px = hdr.pixels();
        assertEquals(2f, px[0], 1e-3f);
        assertEquals(0.25f, px[4], 1e-3f);
        assertEquals(1.5f, px[8], 1e-3f);
        assertEquals(1f, px[3], 0f, "alpha is 1");
        assertEquals(2f, px[16], 1e-3f, "red of the first pixel of the second row");
        assertEquals(1f, px[17], 1e-3f);
        assertEquals(0.5f, px[18], 1e-3f);
        assertEquals(0f, px[20], 0f, "a zero pixel is black");
        assertTrue(px[24] > 2000f, "values far above white are kept: " + px[24]);
    }

    @Test
    void aRunLengthEncodedRadianceFileLoads() throws Exception {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 2 +X 8\n".getBytes(StandardCharsets.US_ASCII));
        for (int row = 0; row < 2; row++) {
            file.write(new byte[]{2, 2, 0, 8});
            // Red: a run of 8 at 128. Green: 4 literals then a run of 4. Blue: a run of 8 at 64. Exponent: 130 on the first row, 129 on the second.
            file.write(new byte[]{(byte) (128 + 8), (byte) 128});
            file.write(new byte[]{4, 0, 64, (byte) 128, (byte) 192, (byte) (128 + 4), 32});
            file.write(new byte[]{(byte) (128 + 8), 64});
            file.write(new byte[]{(byte) (128 + 8), (byte) (130 - row)});
        }
        HdrTextureData hdr = HdrTextureData.decode(file.toByteArray(), "rle.hdr");
        assertEquals(8, hdr.width());
        assertEquals(2, hdr.height());
        float[] px = hdr.pixels();
        assertEquals(2f, px[0], 1e-3f);
        assertEquals(0f, px[1], 0f);
        assertEquals(1f, px[2], 1e-3f);
        assertEquals(1f, px[4 + 1], 1e-3f, "second green literal");
        assertEquals(0.5f, px[7 * 4 + 1], 1e-3f, "green run");
        assertEquals(1f, px[8 * 4], 1e-3f, "the second row has half the exponent");
        assertEquals(1f, px[15 * 4 + 3], 0f);
    }

    @Test
    void notAPictureIsAnError() {
        assertThrows(java.io.IOException.class, () -> HdrTextureData.decode(new byte[]{1, 2, 3}, "nothing"));
        assertThrows(IllegalArgumentException.class, () -> new HdrTextureData(2, 2, new float[3]));
    }

    @Test
    void aSkyOfOneBrightnessLightsEverySurfaceByThatMuch() {
        float[] sh = Environment.irradiance(Skybox.equirectHdr(uniform(0.5f, 16, 8)));
        // The constant term is the sky's brightness (light over pi, which a white surface returns as itself); the rest vanish.
        for (int c = 0; c < 3; c++) assertEquals(0.5f, sh[c], 0.01f, "constant term " + c);
        for (int i = 3; i < 27; i++) assertEquals(0f, sh[i], 0.01f, "term " + i);
    }

    @Test
    void aSunLightsOneSideMoreAndIsLimitedSoItCannotDrownTheSky() {
        int w = 64, h = 32;
        float[] rgba = new float[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            rgba[i * 4] = rgba[i * 4 + 1] = rgba[i * 4 + 2] = 0.2f;
            rgba[i * 4 + 3] = 1f;
        }
        // One pixel of sun near the top, in the direction the panorama's u = 0.75 turns to (+x), at 100000 times the sky.
        int sun = (5 * w + 48) * 4;
        rgba[sun] = rgba[sun + 1] = rgba[sun + 2] = 20000f;
        float[] sh = Environment.irradiance(Skybox.equirectHdr(new HdrTextureData(w, h, rgba)));
        for (float v : sh) assertTrue(Float.isFinite(v));
        // The term in x is the third group of three after the constant, y and z terms: the sun pulls it positive.
        assertTrue(sh[9] > 0.01f, "light from +x: " + sh[9]);
        assertTrue(sh[0] < 5f, "a single pixel of sun does not turn the whole sky into daylight: " + sh[0]);
    }

    @Test
    void levelsOfDetailFollowTheSize() {
        assertEquals(6f, Environment.maxLod(Skybox.equirectHdr(uniform(1f, 64, 32))), 0f);
        assertEquals(10f, Environment.maxLod(Skybox.equirectHdr(uniform(1f, 1024, 512))), 0f);
    }

    @Test
    void theSkyIsAKindOfSkyAndIsPreloaded() {
        Skybox sky = Skybox.equirectHdr(uniform(1f, 4, 2));
        Scene3D scene = new Scene3D();
        scene.setSkybox(sky);
        assertSame(sky, scene.getSkybox());
        assertNotNull(scene.preload(sky), "an HDR sky has a picture to upload, unlike a gradient");
    }

    @Test
    void theSunIsWhereTheBrightestPartIs() {
        int w = 64, h = 32;
        float[] rgba = new float[w * h * 4];
        java.util.Arrays.fill(rgba, 0.2f);
        // A sun a quarter of the way across, at the horizon: u = 0.75 looks along +X.
        int sun = (16 * w + 48) * 4;
        rgba[sun] = rgba[sun + 1] = rgba[sun + 2] = 500f;
        var d = new HdrTextureData(w, h, rgba).sunDirection();
        assertEquals(1f, d.x(), 0.1f);
        assertEquals(0f, d.y(), 0.1f);
        assertEquals(0f, d.z(), 0.1f);
        // And in the middle of the picture, high up, it looks along -Z and up.
        java.util.Arrays.fill(rgba, 0.2f);
        int high = (4 * w + 32) * 4;
        rgba[high] = rgba[high + 1] = rgba[high + 2] = 500f;
        d = new HdrTextureData(w, h, rgba).sunDirection();
        assertTrue(d.y() > 0.8f && d.z() < -0.3f, d.toString());
    }
}
