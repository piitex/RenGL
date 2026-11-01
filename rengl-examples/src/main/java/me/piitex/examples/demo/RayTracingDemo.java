package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.OrbitControls;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.Skybox;
import me.piitex.engine.render3d.upscale.UpscalerProvider;
import me.piitex.engine.render3d.upscale.Upscalers;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.upscale.dlss.DlssProvider;
import me.piitex.engine.upscale.fsr.FsrProvider;

import java.io.File;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Ray tracing and path tracing, with the upscalers that go with them: a white room with a red wall and a green one, open on the
 * side the camera looks in from, a polished floor, a mirror ball, a tall box, a spinning cube, a lamp in the ceiling and an
 * orange orb that circles inside with a light of its own. Each effect is a switch, so the picture can be compared with and without
 * it.
 * <ul>
 *   <li><b>Shadows.</b> The shadows of the lights are traced, not drawn from maps: exact at any distance, hard edged.</li>
 *   <li><b>Reflections.</b> The floor and the ball show the room, lit and in colour, in place of the sky alone.</li>
 *   <li><b>Ambient occlusion.</b> Corners and the feet of things darken by rays that find something close.</li>
 *   <li><b>Global illumination.</b> One bounce: the red wall tints the floor beside it and the orb lights its surroundings.</li>
 *   <li><b>Path tracing.</b> The light goes on bouncing, as many times as set, from the lamp, the orb and the sky. Turn the lamp off
 *       and the room is lit by the orb alone, by light that went round the walls. It is noisy, as one ray per pixel is, and it is
 *       cleaned up by a denoiser.</li>
 * </ul>
 * <b>Upscaling.</b> Rays cost a lot per pixel, so drawing fewer pixels and enlarging them is where the speed is. Any of the
 * upscalers of {@code UpscalingDemo} can be used (the vendors' libraries are found as it describes: {@code -Drengl.dlss.dir},
 * {@code -Drengl.fsr.dir}). With path tracing on and the upscaler on DLSS, <b>Ray Reconstruction</b> does the denoising and the
 * enlarging in one step from the noisy picture and the surfaces' colour, normals and roughness, in place of the engine's own filter;
 * the panel says which one is at work. The effects need a GPU with ray queries (NVIDIA RTX, AMD RDNA 2 and later, Intel Arc) and
 * Vulkan; elsewhere the room is drawn without them, and the panel says so. See {@code docs/3d/ray-tracing.md}.
 * <p>
 * The camera sweeps in front of the room; turn that off to orbit with the mouse (drag, scroll). The demo starts with path tracing and the
 * best upscaler there is; {@code -Drengl.demo.preset=0..4} starts it in another of the presets (in the order of the list),
 * {@code -Drengl.demo.upscaler=smooth|builtin|fsr|dlss} with another upscaler and {@code -Drengl.demo.reconstruction=false} without
 * Ray Reconstruction.
 */
public class RayTracingDemo {
    private static final int WIDTH = 1600, HEIGHT = 900;
    private static final int PANEL_WIDTH = 360;

    private static final String[] PRESETS = {"Rasterized (no rays)", "Ray traced shadows", "Shadows, reflections, occlusion",
            "Global illumination (one bounce)", "Path tracing"};
    private static final String[] UPSCALERS = {"Smooth enlargement", "Built-in temporal", "FSR", "DLSS"};
    private static final String[] QUALITY = {"Native (100%)", "Quality (67%)", "Balanced (58%)", "Performance (50%)", "Ultra performance (33%)"};
    private static final float[] QUALITY_SCALES = {1f, 0.67f, 0.58f, 0.5f, 0.33f};

    static void main() {
        Window window = new Window(new WindowOptions("Ray tracing and path tracing").setDimensions(WIDTH, HEIGHT)
                .setRenderer(RenderAPI.VULKAN).setVsync(false).setMaxFPS(0).setBackgroundFPS(0));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(WIDTH, HEIGHT);
        root.getStyling().setBackgroundColor(Color.BLACK);
        root.getStyling().setBorderThickness(0f);
        window.addContainer(root);

        Room room = new Room();

        Viewport3D view = new Viewport3D(WIDTH, HEIGHT);
        view.setContent(room.scene);
        view.setInputListener(room.orbit);
        root.addElement(view);
        room.view = view;

        Container panel = new Container(PANEL_WIDTH, 100);
        panel.setPosition(16, 16);
        panel.getStyling().setBackgroundColor(new Color(0.04f, 0.05f, 0.08f, 0.82f));
        panel.getStyling().setHoverColor(new Color(0.04f, 0.05f, 0.08f, 0.82f));
        panel.getStyling().setBorderThickness(0f);
        panel.getStyling().setCornerRadius(12f);
        root.addElement(panel);
        panel.setHeight(room.buildControls(panel) + 8);

        window.onResize((w, h) -> {
            root.setWidth(w);
            root.setHeight(h);
            view.setWidth(w);
            view.setHeight(h);
        });

        new Engine().start(window);
    }

    /** The scene and what the controls change in it. */
    private static final class Room {
        final Scene3D scene = new Scene3D();
        final OrbitControls orbit = new OrbitControls(scene.camera());
        Viewport3D view;

        // The things that move or change.
        final Node3D orb = new Node3D("orb", Mesh.sphere(0.32f, 24, 12), new Material(new Color(1f, 0.55f, 0.15f, 1f)).setEmissive(new Color(1f, 0.55f, 0.15f, 1f), 6f));
        final Light orbLight = Light.point(new Vec3(0, 2, 0), new Color(1f, 0.6f, 0.25f, 1f), 3f, 14f);
        final Node3D cube = new Node3D("spinner", Mesh.cube(1f), new Material(new Color(0.25f, 0.45f, 0.9f, 1f)).setRoughness(0.35f));
        final Material panelMaterial = new Material(Color.BLACK).setRoughness(0.9f).setEmissive(Color.WHITE, 0.5f);
        final Light lampLight = Light.point(new Vec3(0f, 5.4f, -0.5f), new Color(1f, 0.96f, 0.9f, 1f), 1.5f, 20f);

        // What the controls say.
        boolean shadows, reflections, occlusion, illumination, paths;
        int bounces = 3, rays = 1;
        UpscalerProvider provider;
        String providerName = UPSCALERS[0];
        float renderScale = 0.67f;
        boolean lampOn = true, sweeping = true, frozen, reconstruct = true;
        double clock;
        boolean ready;

        // The numbers.
        int framesSince;
        float timeSince;
        String rateLine = "", sizeLine = "", inUseLine = "", denoiserLine = "", gpuLine = "", dlssLine = "", fsrLine = "", rrLine = "";

        // The controls the scene changes.
        DropdownOverlay<String> presetBox, upscalerBox, qualityBox;
        CheckBoxOverlay shadowsBox, reflectionsBox, occlusionBox, illuminationBox, pathsBox, reconstructBox;
        SliderOverlay scaleSlider;
        TextOverlay scaleLabel;
        TextOverlay[] numbers = new TextOverlay[8];
        boolean setting;

        Room() {
            buildWorld();
            scene.setUpdateHandler(this::update);
            orbit.setTarget(new Vec3(0, 2.6f, 0)).setDistance(14f).setAngles(0f, 0.12f).setDistanceLimits(3f, 40f);
            applyEffects();
        }

        // ---- the scene ---------------------------------------------------------------------------------------------

        private void slab(String name, Color color, float x, float y, float z, float sx, float sy, float sz) {
            Node3D node = new Node3D(name, Mesh.cube(1f), new Material(color).setRoughness(0.9f));
            node.setPosition(x, y, z);
            node.setScale(new Vec3(sx, sy, sz));
            scene.add(node);
        }

        private void buildWorld() {
            scene.lights().clear();
            scene.setBackground(new Color(0.02f, 0.025f, 0.04f, 1f));
            // A dim sky: some light comes in at the open side, and what the rays miss is lit by it.
            scene.setSkybox(Skybox.gradient(new Color(0.16f, 0.26f, 0.5f, 1f), new Color(0.35f, 0.38f, 0.45f, 1f), new Color(0.06f, 0.06f, 0.07f, 1f)));
            scene.setAmbient(new Color(0.7f, 0.8f, 1f, 1f), 0.05f);
            scene.setBloom(0.35f);
            scene.setBloomThreshold(1.3f);
            scene.camera().setFovYDegrees(58).setClip(0.1f, 200f);

            // The room is 10 wide, 6 high and 10 deep, open on the +Z side.
            Color white = new Color(0.75f, 0.75f, 0.73f, 1f);
            Node3D floor = new Node3D("floor", Mesh.cube(1f), new Material(new Color(0.8f, 0.8f, 0.78f, 1f)).setRoughness(0.1f));
            floor.setPosition(0f, -0.1f, 0f);
            floor.setScale(new Vec3(10.4f, 0.2f, 10.4f));
            scene.add(floor);
            slab("ceiling", white, 0f, 6.1f, 0f, 10.4f, 0.2f, 10.4f);
            slab("back", white, 0f, 3f, -5.1f, 10.4f, 6.4f, 0.2f);
            slab("left", new Color(0.8f, 0.1f, 0.08f, 1f), -5.1f, 3f, 0f, 0.2f, 6.4f, 10.4f);
            slab("right", new Color(0.12f, 0.6f, 0.15f, 1f), 5.1f, 3f, 0f, 0.2f, 6.4f, 10.4f);

            Node3D ground = new Node3D("ground", Mesh.plane(80f, 80f), new Material(new Color(0.1f, 0.1f, 0.11f, 1f)).setRoughness(0.95f));
            ground.setPosition(0f, -0.21f, 0f);
            scene.add(ground);

            Node3D lamp = new Node3D("lamp", Mesh.cube(1f), panelMaterial);
            lamp.setPosition(0f, 5.95f, -0.5f);
            lamp.setScale(new Vec3(3f, 0.1f, 3f));
            lamp.setCastShadow(false);
            scene.add(lamp);
            scene.lights().add(lampLight);

            Node3D tall = new Node3D("tall box", Mesh.cube(1f), new Material(new Color(0.5f, 0.55f, 0.65f, 1f)).setRoughness(0.85f));
            tall.setPosition(-2.6f, 1.7f, -2.4f);
            tall.setScale(new Vec3(2f, 3.4f, 2f));
            tall.setRotation(Quat.fromAxisAngle(Vec3.UP, 0.35f));
            scene.add(tall);

            Node3D ball = new Node3D("mirror ball", Mesh.sphere(1.3f, 64, 32), new Material(new Color(0.95f, 0.95f, 0.97f, 1f)).setMetallic(1f).setRoughness(0.04f));
            ball.setPosition(2.3f, 1.3f, -1.2f);
            scene.add(ball);

            cube.setPosition(-0.4f, 0.6f, 1.6f);
            cube.setScale(new Vec3(1.2f, 1.2f, 1.2f));
            scene.add(cube);

            orb.setCastShadow(false);
            scene.add(orb);
            scene.lights().add(orbLight);
        }

        // ---- every frame -------------------------------------------------------------------------------------------

        private void update(Scene3D s, float delta) {
            GpuDevice device = view == null ? null : view.getDevice();
            if (device == null) return;
            if (!ready) start(device);

            if (!frozen) clock += delta;
            double t = clock;
            double a = t * 0.7;
            Vec3 at = new Vec3((float) (3.4 * Math.cos(a)), (float) (2.4 + 0.9 * Math.sin(a * 1.7)), (float) (-0.5 + 3.4 * Math.sin(a)));
            orb.setPosition(at);
            orbLight.setVector(at);
            cube.setRotation(Quat.fromAxisAngle(new Vec3(0.3f, 1f, 0.2f).normalized(), (float) (t * 1.2)));
            if (sweeping) {
                scene.camera().lookAt(new Vec3((float) (5.5 * Math.sin(t * 0.17)), (float) (3.3 + 0.5 * Math.sin(t * 0.11)), (float) (13 + 1.5 * Math.cos(t * 0.13))),
                        new Vec3(-1.6f, 2.5f, 0f));
            }
            count(delta);
        }

        // ---- the choices -------------------------------------------------------------------------------------------

        /** The device is there: see what it can do, and begin with the best upscaler of it. */
        private void start(GpuDevice device) {
            ready = true;
            String dlss = DlssProvider.unavailableReason(device);
            String fsr = new FsrProvider().isAvailable(device) ? null : FsrProvider.unavailableReason();
            String rr = dlss != null ? dlss : DlssProvider.rayReconstructionUnavailableReason(device);
            dlssLine = "DLSS: " + brief(dlss, "dlss");
            fsrLine = "FSR: " + brief(fsr, "fsr");
            rrLine = "Ray Reconstruction: " + brief(rr, "dlss");
            gpuLine = device.supportsRayTracing() ? "Ray queries: yes (" + device.name() + ")" : "No ray queries on this GPU: drawn without rays";
            if (dlss != null) System.out.println("DLSS is not available: " + dlss);
            if (fsr != null) System.out.println("FSR is not available: " + fsr);
            if (rr != null) System.out.println("DLSS Ray Reconstruction is not available: " + rr);

            String first = switch (System.getProperty("rengl.demo.upscaler", "").toLowerCase(Locale.ROOT)) {
                case "smooth" -> UPSCALERS[0];
                case "builtin" -> UPSCALERS[1];
                case "fsr" -> UPSCALERS[2];
                case "dlss" -> UPSCALERS[3];
                default -> dlss == null ? "DLSS" : UPSCALERS[1];
            };
            if (!choose(first)) {
                first = UPSCALERS[1];
                choose(first);
            }
            reconstruct = !System.getProperty("rengl.demo.reconstruction", "true").equalsIgnoreCase("false");
            setting = true;
            upscalerBox.setSelected(first);
            qualityBox.setSelectedIndex(1);
            setting = false;
            int preset = Math.max(0, Math.min(PRESETS.length - 1, Integer.getInteger("rengl.demo.preset", PRESETS.length - 1)));
            presetBox.setSelectedIndex(preset);
            applyPreset(preset);
            setScale(QUALITY_SCALES[1]);
            setting = true;
            reconstructBox.setChecked(reconstruct);
            setting = false;
            reconstructBox.setEnabled(rr == null);
        }

        /** What the panel says of a library: ready, or the short of why not (the whole of it is printed). */
        private static String brief(String reason, String vendor) {
            if (reason == null) return "ready";
            if (reason.contains("not found")) return "libraries not found (-Drengl." + vendor + ".dir)";
            return shorten(reason);
        }

        private static String shorten(String text) {
            return text.length() <= 46 ? text : text.substring(0, 45) + "…";
        }

        boolean choose(String name) {
            GpuDevice device = view.getDevice();
            UpscalerProvider next;
            switch (name) {
                case "DLSS" -> next = new DlssProvider();
                case "FSR" -> next = new FsrProvider();
                case "Built-in temporal" -> next = Upscalers.builtIn();
                default -> next = null;
            }
            if (next != null && device != null && !next.isAvailable(device)) return false;
            provider = next;
            providerName = name;
            scene.setUpscaler(next);
            return true;
        }

        void setScale(float scale) {
            renderScale = Math.max(0.25f, Math.min(1f, scale));
            scene.setRenderScale(renderScale);
            scaleLabel.setText(String.format("Render scale: %d%%", Math.round(renderScale * 100)));
            setting = true;
            scaleSlider.setValue(Math.round(renderScale * 100));
            setting = false;
        }

        /** Hands the switches to the scene. Path tracing is global illumination with the rest of the way added, so it takes its place. */
        void applyEffects() {
            scene.setRayTracedShadows(shadows);
            scene.setRayTracedReflections(reflections);
            scene.setRayTracedAmbientOcclusion(occlusion);
            scene.setAmbientOcclusion(occlusion ? 1f : 0f);
            scene.setRayTracedGlobalIllumination(illumination);
            scene.setPathTracing(paths);
            scene.setPathTracingBounces(bounces);
            scene.setRayTracedGlobalIlluminationRays(rays);
            scene.setRayReconstruction(reconstruct);
            scene.resetTemporalHistory();
        }

        /** A preset sets all the switches, and the boxes follow. */
        void applyPreset(int preset) {
            shadows = preset >= 1;
            reflections = preset >= 2;
            occlusion = preset == 2;
            illumination = preset == 3;
            paths = preset == 4;
            setting = true;
            shadowsBox.setChecked(shadows);
            reflectionsBox.setChecked(reflections);
            occlusionBox.setChecked(occlusion);
            illuminationBox.setChecked(illumination);
            pathsBox.setChecked(paths);
            setting = false;
            applyEffects();
        }

        void setLamp(boolean on) {
            lampOn = on;
            panelMaterial.setEmissive(Color.WHITE, on ? 0.5f : 0f);
            lampLight.setIntensity(on ? 1.5f : 0f);
            scene.resetTemporalHistory();
        }

        // ---- the numbers -------------------------------------------------------------------------------------------

        private void count(float delta) {
            framesSince++;
            timeSince += delta;
            if (timeSince < 0.5f) return;
            double fps = framesSince / (double) timeSince;
            framesSince = 0;
            timeSince = 0;

            int outW = view.getPixelWidth(), outH = view.getPixelHeight();
            // A vendor's library may take only some sizes and moves the scale to one of them.
            var current = scene.getUpscaler();
            float drawn = current != null && renderScale < 1f ? current.supportedRenderScale(renderScale, outW, outH) : renderScale;
            sizeLine = String.format("Drawn at %d x %d, shown at %d x %d", Math.round(outW * drawn), Math.round(outH * drawn), outW, outH);
            rateLine = String.format("%.0f fps (%.1f ms)", fps, 1000 / Math.max(fps, 1));

            StringBuilder used = new StringBuilder("In use: ");
            int before = used.length();
            if (scene.isUsingRayTracedShadows()) used.append("shadows, ");
            if (scene.isUsingRayTracedReflections()) used.append("reflections, ");
            if (scene.isUsingRayTracedAmbientOcclusion()) used.append("occlusion, ");
            if (scene.isUsingPathTracing()) used.append("paths (").append(scene.getPathTracingBounces()).append(" bounces), ");
            else if (scene.isUsingRayTracedGlobalIllumination()) used.append("one bounce, ");
            inUseLine = used.length() == before ? "In use: no rays" : used.substring(0, used.length() - 2);

            if (scene.isUsingRayReconstruction()) denoiserLine = "Denoiser: DLSS Ray Reconstruction";
            else if (scene.isUsingPathTracing() || scene.isUsingRayTracedGlobalIllumination()) denoiserLine = "Denoiser: the engine's own filter";
            else denoiserLine = "Denoiser: not needed";
            show();
        }

        private void show() {
            String[] lines = {rateLine, sizeLine, inUseLine, denoiserLine, gpuLine, dlssLine, rrLine, fsrLine};
            if (numbers[0] == null) return;
            for (int i = 0; i < numbers.length; i++) numbers[i].setText(lines[i]);
        }

        // ---- the controls ------------------------------------------------------------------------------------------

        /** Fills the panel; returns how far down it goes. */
        float buildControls(Container panel) {
            float y = 16;
            TextOverlay title = new TextOverlay("Ray tracing and path tracing", 20f, Color.WHITE);
            title.setPosition(16, y);
            panel.addElement(title);
            y += 34;

            y = label(panel, "Preset", y);
            presetBox = new DropdownOverlay<>(PANEL_WIDTH - 32, 32);
            for (String name : PRESETS) presetBox.addItem(name);
            presetBox.setSelectedIndex(PRESETS.length - 1);
            presetBox.setPosition(16, y);
            presetBox.onSelect(name -> {
                if (!setting) applyPreset(presetBox.getSelectedIndex());
            });
            panel.addElement(presetBox);
            y += 44;

            shadowsBox = check(panel, "Ray traced shadows", y, false, on -> {
                shadows = on;
                applyEffects();
            });
            y += 26;
            reflectionsBox = check(panel, "Ray traced reflections", y, false, on -> {
                reflections = on;
                applyEffects();
            });
            y += 26;
            occlusionBox = check(panel, "Ray traced ambient occlusion", y, false, on -> {
                occlusion = on;
                applyEffects();
            });
            y += 26;
            illuminationBox = check(panel, "Global illumination (one bounce)", y, false, on -> {
                illumination = on;
                applyEffects();
            });
            y += 26;
            pathsBox = check(panel, "Path tracing", y, false, on -> {
                paths = on;
                applyEffects();
            });
            y += 34;

            TextOverlay bounceLabel = new TextOverlay("Path bounces: " + bounces, 14f, Color.WHITE);
            bounceLabel.setPosition(16, y);
            panel.addElement(bounceLabel);
            y += 22;
            SliderOverlay bounceSlider = new SliderOverlay(PANEL_WIDTH - 32, 1, 8, bounces);
            bounceSlider.setStep(1);
            bounceSlider.setPosition(16, y);
            bounceSlider.onValueChange(value -> {
                bounces = (int) Math.round(value);
                bounceLabel.setText("Path bounces: " + bounces);
                applyEffects();
            });
            panel.addElement(bounceSlider);
            y += 34;

            TextOverlay rayLabel = new TextOverlay("Rays per pixel: " + rays, 14f, Color.WHITE);
            rayLabel.setPosition(16, y);
            panel.addElement(rayLabel);
            y += 22;
            SliderOverlay raySlider = new SliderOverlay(PANEL_WIDTH - 32, 1, 8, rays);
            raySlider.setStep(1);
            raySlider.setPosition(16, y);
            raySlider.onValueChange(value -> {
                rays = (int) Math.round(value);
                rayLabel.setText("Rays per pixel: " + rays);
                applyEffects();
            });
            panel.addElement(raySlider);
            y += 38;

            y = label(panel, "Upscaler", y);
            upscalerBox = new DropdownOverlay<>(PANEL_WIDTH - 32, 32);
            for (String name : UPSCALERS) upscalerBox.addItem(name);
            upscalerBox.setSelectedIndex(0);
            upscalerBox.setPosition(16, y);
            upscalerBox.onSelect(name -> {
                if (setting) return;
                String before = providerName;
                if (!choose(name)) {
                    setting = true;
                    upscalerBox.setSelected(before);
                    setting = false;
                    return;
                }
                scene.resetTemporalHistory();
            });
            panel.addElement(upscalerBox);
            y += 44;

            y = label(panel, "Quality", y);
            qualityBox = new DropdownOverlay<>(PANEL_WIDTH - 32, 32);
            for (String name : QUALITY) qualityBox.addItem(name);
            qualityBox.setSelectedIndex(1);
            qualityBox.setPosition(16, y);
            qualityBox.onSelect(name -> {
                if (!setting) setScale(QUALITY_SCALES[qualityBox.getSelectedIndex()]);
            });
            panel.addElement(qualityBox);
            y += 44;

            scaleLabel = new TextOverlay("Render scale: 67%", 14f, Color.WHITE);
            scaleLabel.setPosition(16, y);
            panel.addElement(scaleLabel);
            y += 22;
            scaleSlider = new SliderOverlay(PANEL_WIDTH - 32, 25, 100, 67);
            scaleSlider.setStep(1);
            scaleSlider.setPosition(16, y);
            scaleSlider.onValueChange(value -> {
                if (!setting) setScale((float) (value / 100.0));
            });
            panel.addElement(scaleSlider);
            y += 36;

            reconstructBox = check(panel, "Ray Reconstruction (DLSS)", y, true, on -> {
                reconstruct = on;
                applyEffects();
            });
            y += 26;
            check(panel, "Ceiling lamp", y, true, this::setLamp);
            y += 26;
            check(panel, "Freeze the motion", y, false, on -> frozen = on);
            y += 34;

            ButtonOverlay camera = new ButtonOverlay("Camera: sweep", PANEL_WIDTH - 32, 30);
            camera.setPosition(16, y);
            camera.onAction(() -> {
                sweeping = !sweeping;
                camera.setText(sweeping ? "Camera: sweep" : "Camera: orbit (drag, scroll)");
                scene.resetTemporalHistory();
            });
            panel.addElement(camera);
            y += 40;

            for (int i = 0; i < numbers.length; i++) {
                numbers[i] = new TextOverlay("", i == 0 ? 15f : 13f, i == 0 ? Color.WHITE : new Color(1f, 1f, 1f, i < 5 ? 0.85f : 0.55f));
                numbers[i].setPosition(16, y);
                panel.addElement(numbers[i]);
                y += i == 0 ? 22 : 19;
            }

            return y;
        }

        /** A check box with its label; the change is handed on when the user makes it, not when a preset sets the box. */
        private CheckBoxOverlay check(Container panel, String text, float y, boolean on, Consumer<Boolean> change) {
            CheckBoxOverlay box = new CheckBoxOverlay(20, on);
            box.setPosition(16, y);
            box.onToggle(value -> {
                if (setting) return;
                change.accept(value);
            });
            panel.addElement(box);
            TextOverlay label = new TextOverlay(text, 14f, Color.WHITE);
            label.setPosition(46, y + 1);
            panel.addElement(label);
            return box;
        }

        private static float label(Container panel, String text, float y) {
            TextOverlay label = new TextOverlay(text, 14f, new Color(1f, 1f, 1f, 0.7f));
            label.setPosition(16, y);
            panel.addElement(label);
            return y + 22;
        }
    }
}
