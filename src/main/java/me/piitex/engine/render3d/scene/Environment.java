package me.piitex.engine.render3d.scene;

/**
 * Works out, on the CPU, how a {@link Skybox} lights a scene: nine spherical-harmonic coefficients per colour channel for
 * the light a surface collects from every direction (the diffuse part), and how many mip levels the sky has for its
 * blurred reflections (the specular part, which the shader reads from the sky's own mipmaps).
 * <p>
 * Done once per skybox, on the render thread, the first time a scene lights from it.
 */
final class Environment {
    private Environment() {
    }

    private static final float[] SRGB_TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) SRGB_TO_LINEAR[i] = (float) Math.pow(i / 255.0, 2.2);
    }

    /**
     * The cell grid the sky is reduced to before the harmonics are summed: fine enough for the lowest-frequency
     * lighting, which is all nine coefficients can hold.
     */
    private static final int CUBE_CELLS = 32;
    private static final int EQUIRECT_WIDTH = 64, EQUIRECT_HEIGHT = 32;

    /**
     * 27 floats: the nine coefficients as r, g, b, each already scaled by the cosine lobe and by 1/pi, so that a surface's
     * diffuse light is the polynomial of its normal in the shader times its albedo. Brightness is not included.
     */
    static float[] irradiance(Skybox sky) {
        double[] sh = new double[27];
        switch (sky.kind) {
            case GRADIENT -> gradient(sky, sh);
            case CUBE -> cube(sky, sh);
            case EQUIRECT -> equirect(sky, sh);
        }
        // Radiance coefficients to irradiance over pi: the lobe's 1, 2/3 and 1/4 for bands 0, 1 and 2, and each basis
        // function's normalisation constant, which the shader leaves out of its polynomials.
        double[] k = {0.282095, 0.488603, 0.488603, 0.488603, 1.092548, 1.092548, 0.315392, 1.092548, 0.546274};
        double[] band = {1, 2.0 / 3, 2.0 / 3, 2.0 / 3, 0.25, 0.25, 0.25, 0.25, 0.25};
        float[] out = new float[27];
        for (int i = 0; i < 9; i++) {
            for (int c = 0; c < 3; c++) out[i * 3 + c] = (float) (sh[i * 3 + c] * k[i] * band[i]);
        }
        return out;
    }

    /**
     * How many mip levels above the base the sky has, for the shader to scale roughness into.
     */
    static float maxLod(Skybox sky) {
        int size = switch (sky.kind) {
            case GRADIENT -> 1;
            case CUBE -> sky.faces[0].width();
            case EQUIRECT -> Math.max(sky.panorama.width(), sky.panorama.height());
        };
        return (float) Math.max(0, Math.floor(Math.log(size) / Math.log(2)));
    }

    // ---- the sources -----------------------------------------------------------------------------------------

    private static void gradient(Skybox sky, double[] sh) {
        int w = 32, h = 16;
        for (int y = 0; y < h; y++) {
            double theta = (y + 0.5) / h * Math.PI;
            double dy = Math.cos(theta);
            double[] c = gradientAt(sky, dy);
            double weight = Math.sin(theta) * (Math.PI / h) * (2 * Math.PI / w);
            for (int x = 0; x < w; x++) {
                double phi = (x + 0.5) / w * 2 * Math.PI;
                accumulate(sh, Math.sin(theta) * Math.sin(phi), dy, -Math.sin(theta) * Math.cos(phi), c[0], c[1], c[2], weight);
            }
        }
    }

    /**
     * What the gradient sky shader draws looking along a direction with the given y, as linear colour.
     */
    private static double[] gradientAt(Skybox sky, double y) {
        var from = sky.horizon;
        var to = y >= 0 ? sky.top : sky.bottom;
        double t = Math.pow(Math.abs(y), 0.6);
        return new double[]{
                Math.pow(from.getR() + (to.getR() - from.getR()) * t, 2.2),
                Math.pow(from.getG() + (to.getG() - from.getG()) * t, 2.2),
                Math.pow(from.getB() + (to.getB() - from.getB()) * t, 2.2)};
    }

    private static void equirect(Skybox sky, double[] sh) {
        TextureData img = sky.panorama;
        byte[] px = img.pixels();
        int w = EQUIRECT_WIDTH, h = EQUIRECT_HEIGHT;
        for (int y = 0; y < h; y++) {
            double theta = (y + 0.5) / h * Math.PI;
            double weight = Math.sin(theta) * (Math.PI / h) * (2 * Math.PI / w);
            for (int x = 0; x < w; x++) {
                double phi = (x + 0.5) / w * 2 * Math.PI - Math.PI;
                double[] c = average(px, img.width(), img.height(), x * img.width() / w, (x + 1) * img.width() / w,
                        y * img.height() / h, (y + 1) * img.height() / h);
                // The panorama's u is atan(x, -z) / 2pi + 0.5, so u = 0.5 looks along -z.
                accumulate(sh, Math.sin(theta) * Math.sin(phi), Math.cos(theta), -Math.sin(theta) * Math.cos(phi), c[0], c[1], c[2], weight);
            }
        }
    }

    private static void cube(Skybox sky, double[] sh) {
        int n = CUBE_CELLS;
        for (int face = 0; face < 6; face++) {
            TextureData img = sky.faces[face];
            byte[] px = img.pixels();
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    double[] c = average(px, img.width(), img.height(), x * img.width() / n, (x + 1) * img.width() / n,
                            y * img.height() / n, (y + 1) * img.height() / n);
                    double sc = 2 * (x + 0.5) / n - 1, tc = 2 * (y + 0.5) / n - 1;
                    double dx, dy, dz;
                    // The faces as OpenGL and Vulkan lay them out: +X, -X, +Y, -Y, +Z, -Z.
                    switch (face) {
                        case 0 -> { dx = 1; dy = -tc; dz = -sc; }
                        case 1 -> { dx = -1; dy = -tc; dz = sc; }
                        case 2 -> { dx = sc; dy = 1; dz = tc; }
                        case 3 -> { dx = sc; dy = -1; dz = -tc; }
                        case 4 -> { dx = sc; dy = -tc; dz = 1; }
                        default -> { dx = -sc; dy = -tc; dz = -1; }
                    }
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    // A cell's solid angle: its area on the cube over the cube of the distance, area 4 / n^2 at 1.
                    double weight = 4.0 / (n * n) / (len * len * len);
                    accumulate(sh, dx / len, dy / len, dz / len, c[0], c[1], c[2], weight);
                }
            }
        }
    }

    /**
     * The mean linear colour of a block of pixels.
     */
    private static double[] average(byte[] px, int width, int height, int x0, int x1, int y0, int y1) {
        x1 = Math.max(x1, x0 + 1);
        y1 = Math.max(y1, y0 + 1);
        double r = 0, g = 0, b = 0;
        int count = 0;
        for (int y = y0; y < Math.min(y1, height); y++) {
            for (int x = x0; x < Math.min(x1, width); x++) {
                int o = (y * width + x) * 4;
                r += SRGB_TO_LINEAR[px[o] & 0xFF];
                g += SRGB_TO_LINEAR[px[o + 1] & 0xFF];
                b += SRGB_TO_LINEAR[px[o + 2] & 0xFF];
                count++;
            }
        }
        count = Math.max(count, 1);
        return new double[]{r / count, g / count, b / count};
    }

    /**
     * Adds a colour seen from direction (x, y, z) to the nine radiance coefficients, with the basis the shader's
     * polynomials use (order: 1, y, z, x, xy, yz, 3z^2 - 1, xz, x^2 - y^2).
     */
    private static void accumulate(double[] sh, double x, double y, double z, double r, double g, double b, double weight) {
        double[] basis = {1, y, z, x, x * y, y * z, 3 * z * z - 1, x * z, x * x - y * y};
        // The same constants the caller multiplies back in, so that the coefficients are those of the true basis.
        double[] k = {0.282095, 0.488603, 0.488603, 0.488603, 1.092548, 1.092548, 0.315392, 1.092548, 0.546274};
        for (int i = 0; i < 9; i++) {
            double f = basis[i] * k[i] * weight;
            sh[i * 3] += r * f;
            sh[i * 3 + 1] += g * f;
            sh[i * 3 + 2] += b * f;
        }
    }
}
