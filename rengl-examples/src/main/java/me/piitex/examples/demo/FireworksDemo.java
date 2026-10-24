package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.game.GameTime;
import me.piitex.engine.graphics.ParticleEffect;
import me.piitex.engine.graphics.ParticleSystem;
import me.piitex.engine.graphics.ShaderEffect;
import me.piitex.engine.graphics.Texture;
import me.piitex.engine.graphics.TextureRegion;
import me.piitex.engine.ui.animation.Easing;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import org.jetbrains.skia.Paint;
import org.jetbrains.skia.Surface;

import java.io.File;
import java.util.Locale;
import java.util.Random;

import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

/**
 * Fireworks over a city at dusk, in a plain window with no game engine on top.
 * <ul>
 *   <li>Every burst is a few hundred particles from one {@link ParticleSystem}. Particles are pooled, batched into a
 *       single draw and stretched along their velocity; the HUD shows how many are alive.</li>
 *   <li>The sky, with its stars and moon, is one {@link ShaderEffect} written in SkSL. The skyline is drawn with
 *       quads. Both are drawn in the container's {@code onRender} hook, behind the particles.</li>
 *   <li>Click anywhere to launch a firework there.</li>
 * </ul>
 */
public class FireworksDemo {
    private static final String SKY = """
            uniform float2 size;
            float hash(float2 p) {
                float3 p3 = fract(float3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }
            half4 main(float2 coord) {
                float2 uv = coord / size;
                float3 top = float3(0.01, 0.01, 0.07);
                float3 mid = float3(0.10, 0.04, 0.25);
                float3 hor = float3(0.85, 0.25, 0.35);
                float3 col = mix(top, mid, smoothstep(0.0, 0.6, uv.y));
                col = mix(col, hor, pow(smoothstep(0.55, 1.0, uv.y), 2.2) * 0.8);
                float2 g = floor(coord / 3.0);
                col += step(0.9965, hash(g)) * (0.4 + 0.6 * hash(g + 7.0)) * (1.0 - uv.y);
                float2 m = (uv - float2(0.82, 0.2)) * float2(size.x / size.y, 1.0);
                col = mix(col, float3(1.0, 0.96, 0.85), smoothstep(0.052, 0.048, length(m)));
                col += float3(1.0, 0.9, 0.7) * 0.10 * smoothstep(0.2, 0.0, length(m));
                col += (hash(coord) - 0.5) / 256.0; // dither, so the gradient doesn't band
                return half4(half3(col), 1.0);
            }
            """;

    private static final Color[][] PALETTES = {
            {new Color(1f, 0.85f, 0.4f, 1f), new Color(1f, 0.2f, 0.1f, 1f)},
            {new Color(0.5f, 0.95f, 1f, 1f), new Color(0.2f, 0.3f, 1f, 1f)},
            {new Color(1f, 0.5f, 0.9f, 1f), new Color(0.6f, 0.1f, 0.9f, 1f)},
            {new Color(0.7f, 1f, 0.5f, 1f), new Color(0.1f, 0.6f, 0.4f, 1f)},
    };
    private static final Color BUILDING = new Color(0.03f, 0.02f, 0.08f, 1f);
    private static final Color WINDOW_LIT = new Color(1f, 0.82f, 0.4f, 0.75f);

    private static final ParticleSystem particles = new ParticleSystem(20000);
    private static final Random random = new Random();
    private static ParticleEffect[] sparks, cores;
    private static float launchTimer;

    static void main() {
        // To reduce console spam add these jvm flags
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Window window = new Window(new WindowOptions("Fireworks Demo").setDimensions(1400, 800));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        // Each palette has its own recipes, one for the colored sparks and one for the small white core.
        TextureRegion glow = new TextureRegion(glowTexture());
        sparks = new ParticleEffect[PALETTES.length];
        cores = new ParticleEffect[PALETTES.length];
        for (int i = 0; i < PALETTES.length; i++) {
            sparks[i] = new ParticleEffect(glow).speed(120f, 520f).lifetime(1.6f, 2.8f).size(13f, 24f).endScale(0.15f)
                    .drag(0.72f).alignToVelocity(1f / 900f).gravity(0f, 90f)
                    .color(PALETTES[i][0], PALETTES[i][1]).fade(Easing.EASE_IN);
            cores[i] = new ParticleEffect(glow).speed(40f, 160f).lifetime(1.6f, 2.8f).size(8f, 14f).endScale(0.15f)
                    .drag(0.72f).gravity(0f, 90f).fade(Easing.EASE_IN);
        }

        ShaderEffect sky = ShaderEffect.compile(SKY);
        WindowOptions options = window.getWindowOptions();

        Container root = new Container(options.getWidth(), options.getHeight());
        root.getStyling().setBackgroundColor(Color.TRANSPARENT);
        root.getStyling().setHoverColor(Color.TRANSPARENT);
        root.getStyling().setBorderThickness(0f);
        root.onUpdate(e -> {
            root.setWidth(options.getWidth());
            root.setHeight(options.getHeight());
            float dt = Math.min(GameTime.getRealDelta(), 0.05f);
            launchTimer -= dt;
            if (launchTimer <= 0) {
                launchTimer = 0.22f + random.nextFloat() * 0.4f;
                int width = options.getWidth(), height = options.getHeight();
                burst(random.nextInt(PALETTES.length), 150 + random.nextFloat() * (width - 300),
                        height * 0.14f + random.nextFloat() * height * 0.41f);
            }
            particles.update(dt);
        });
        root.onMouseClick(e -> {
            if (e.getAction() == GLFW_PRESS) burst(random.nextInt(PALETTES.length), (float) e.getMouseX(), (float) e.getMouseY());
        });
        root.onRender(e -> {
            var r = e.getRenderer();
            float width = options.getWidth(), height = options.getHeight();
            sky.set("size", width, height);
            r.drawShader(sky, 0, 0, width, height, 0f);

            // The skyline comes from a fixed seed, so it is the same city every frame.
            Random city = new Random(3);
            float x = 0;
            while (x < width) {
                float bw = 40 + city.nextInt(70), bh = 90 + city.nextInt(190);
                r.drawQuad(x, height - bh, bw, bh, BUILDING, 0f);
                for (float wy = height - bh + 12; wy < height - 10; wy += 20) {
                    for (float wx = x + 8; wx < x + bw - 10; wx += 16) {
                        if (city.nextFloat() < 0.35f) r.drawQuad(wx, wy, 7, 10, WINDOW_LIT, 1f);
                    }
                }
                x += bw + 2;
            }
            particles.draw(r);
        });
        window.addContainer(root);

        Container hud = new Container(40, 36, 330, 96);
        hud.getStyling().setBackgroundColor(new Color(0f, 0f, 0f, 0.35f));
        hud.getStyling().setHoverColor(new Color(0f, 0f, 0f, 0.35f));
        hud.getStyling().setBorderColor(new Color(1f, 1f, 1f, 0.25f));
        hud.getStyling().setBorderThickness(1.2f);
        hud.getStyling().setCornerRadius(16f);
        TextOverlay title = new TextOverlay("Particles", 28f, Color.WHITE);
        title.setTextColor(Color.WHITE);
        title.setPosition(20, 14);
        hud.addElement(title);
        TextOverlay stats = new TextOverlay("0 live  ·  pooled and batched", 17f, Color.WHITE);
        stats.setTextColor(new Color(1f, 1f, 1f, 0.7f));
        stats.setPosition(21, 56);
        hud.onUpdate(e -> stats.setText(String.format(Locale.ROOT, "%,d live  ·  pooled and batched", particles.getCount())));
        hud.addElement(stats);
        window.addContainer(hud);

        new Engine().start(window);
    }

    private static void burst(int palette, float x, float y) {
        particles.burst(sparks[palette], x, y, 420);
        particles.burst(cores[palette], x, y, 80, Color.WHITE);
    }

    /** A soft round glow: concentric translucent circles with a brighter core. */
    private static Texture glowTexture() {
        Surface surface = Surface.Companion.makeRasterN32Premul(64, 64);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        for (int radius = 32; radius >= 1; radius--) {
            int alpha = (int) (255 * (radius < 8 ? 0.5f : 0.06f));
            paint.setColor((alpha << 24) | 0xFFFFFF);
            surface.getCanvas().drawCircle(32, 32, radius, paint);
        }
        return new Texture(surface.makeImageSnapshot());
    }
}
