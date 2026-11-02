package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.RenderAPI;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.render3d.GpuDevice;
import me.piitex.engine.render3d.Viewport3D;
import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Instances;
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
import me.piitex.engine.upscale.dlss.DlssFrameGeneration;
import me.piitex.engine.upscale.dlss.DlssProvider;
import me.piitex.engine.upscale.fsr.FsrFrameGeneration;
import me.piitex.engine.upscale.fsr.FsrProvider;
import me.piitex.engine.upscale.metalfx.MetalFxProvider;

import java.io.File;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Upscaling and frame generation, side by side: a yard of thin fences, fine checkered ground, spinning rotors and glowing orbs,
 * which a camera flies through. All of that is hard on a picture built from fewer pixels, and a lot of it moves, which
 * is where frame generation shows.
 * <ul>
 *   <li><b>Upscaler.</b> {@code Smooth} draws the scene smaller and stretches it, which is soft and shimmers; {@code Built-in} is the
 *       temporal upscaler of the 3D layer; {@code FSR} is AMD's FidelityFX Super Resolution (FSR 4 where the GPU has it, otherwise
 *       FSR 3); {@code DLSS} is NVIDIA's, on a GeForce RTX. The last two come from the {@code rengl-upscale} module, which carries none
 *       of the vendors' libraries: the ones that are not found are listed as unavailable, with the reason.</li>
 *   <li><b>Render scale.</b> The presets are the ones the vendors use (Quality 67%, Balanced 58%, Performance 50%, Ultra performance
 *       33%); the slider takes any size. Freeze the motion to see how the picture settles, and fly to see how it holds up.</li>
 *   <li><b>Frame generation.</b> The GPU makes a frame between each two the engine draws, so the display gets about twice as many
 *       as the scene does. It is started with the window, so it is chosen when the demo is launched:
 *       {@code -Drengl.demo.framegen=dlss} (NVIDIA, RTX 40 or newer, with the upscaler on DLSS) or {@code =fsr} (AMD's, on any
 *       Vulkan GPU, with the upscaler on FSR). The numbers show the frames drawn and the frames shown.</li>
 *   <li><b>Scene load.</b> Adds or removes small crates that all cast shadows, to make the GPU work as hard as you like.</li>
 * </ul>
 * Where the libraries are is told with {@code -Drengl.dlss.dir=<folder>} and {@code -Drengl.fsr.dir=<folder>}; see
 * {@code docs/3d/upscaling.md}. The demo draws with Vulkan, without vsync, and the 3D view fills the window (frame generation makes
 * its frames from the whole window). Drag to orbit and scroll to zoom when the camera is set to orbit.
 */
public class UpscalingDemo {
    private static final int WIDTH = 1600, HEIGHT = 900;
    private static final int PANEL_WIDTH = 360;
    private static final int MAX_CRATES = 60000;

    // The vendors' own names for the render scales.
    private static final String[] PRESETS = {"Native (100%)", "Quality (67%)", "Balanced (58%)", "Performance (50%)", "Ultra performance (33%)"};
    private static final float[] PRESET_SCALES = {1f, 0.67f, 0.58f, 0.5f, 0.33f};
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
    private static final String[] UPSCALERS = MAC ? new String[]{"Smooth enlargement", "Built-in temporal", "MetalFX"}
            : new String[]{"Smooth enlargement", "Built-in temporal", "FSR", "DLSS"};

    static void main() {
        // The window starts frame generation, so it has to be asked for before the window's device is made.
        String generation = System.getProperty("rengl.demo.framegen", "none").toLowerCase(Locale.ROOT);
        if (generation.equals("dlss")) System.setProperty("rengl.dlss.framegen", "true");
        else if (generation.equals("fsr")) System.setProperty("rengl.fsr.framegen", "true");

        // Vsync is off because frame generation does not run with it; the cap is lifted so the numbers are the GPU's own.
        Window window = new Window(new WindowOptions("Upscaling and frame generation").setDimensions(WIDTH, HEIGHT)
                .setRenderer(RenderAPI.VULKAN).setVsync(false).setMaxFPS(0).setBackgroundFPS(0));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(WIDTH, HEIGHT);
        root.getStyling().setBackgroundColor(Color.BLACK);
        root.getStyling().setBorderThickness(0f);
        window.addContainer(root);

        Yard yard = new Yard(window, generation);

        Viewport3D view = new Viewport3D(WIDTH, HEIGHT);
        view.setContent(yard.scene);
        view.setInputListener(yard.orbit);
        root.addElement(view);
        yard.view = view;

        Container panel = new Container(PANEL_WIDTH, 684);
        panel.setPosition(16, 16);
        panel.getStyling().setBackgroundColor(new Color(0.04f, 0.05f, 0.08f, 0.82f));
        panel.getStyling().setHoverColor(new Color(0.04f, 0.05f, 0.08f, 0.82f));
        panel.getStyling().setBorderThickness(0f);
        panel.getStyling().setCornerRadius(12f);
        root.addElement(panel);
        yard.buildControls(panel);

        // The view fills the window as it is resized: frame generation works on the whole window.
        window.onResize((w, h) -> {
            root.setWidth(w);
            root.setHeight(h);
            view.setWidth(w);
            view.setHeight(h);
        });

        new Engine().start(window);
    }

    /** The scene and what the controls change in it. */
    private static final class Yard {
        final Window window;
        final String generation;
        final Scene3D scene = new Scene3D();
        final OrbitControls orbit = new OrbitControls(scene.camera());
        Viewport3D view;

        // The things that move.
        final Node3D[] blades = new Node3D[12];
        final Vec3[] hubs = new Vec3[4];
        final Node3D[] orbs = new Node3D[6];
        final Light[] orbLights = new Light[6];
        final Node3D[] spinners = new Node3D[16];
        final Node3D crates = new Node3D("crates", Mesh.cube(1f), new Material(new Color(0.7f, 0.55f, 0.35f, 1f)).setSpecular(0.1f));

        // What the controls say.
        UpscalerProvider provider;           // the upscaler in use, null for the smooth enlargement
        String providerName = UPSCALERS[0];
        float renderScale = 0.67f;
        int crateCount = 20000;
        boolean flying = true, frozen;
        float speed = 1f;
        double clock;                         // seconds of scene time
        boolean ready;                        // the device is known and the first upscaler has been chosen

        // The numbers.
        int framesSince;
        float timeSince;
        long lastPresentations;
        double drawnFps, shownFps;
        boolean generating;
        String generationLine = "", hintLine = "", dlssLine = "", fsrLine = "", sizeLine = "", rateLine = "", upscalerLine = "";
        Consumer<Yard> onNumbers = _ -> {
        };

        // The controls the scene changes.
        DropdownOverlay<String> upscalerBox, presetBox;
        SliderOverlay scaleSlider;
        CheckBoxOverlay generationBox;
        TextOverlay scaleLabel, crateLabel;
        TextOverlay[] numbers = new TextOverlay[7];
        boolean setting;                      // a control is being set by the code, not the user

        Yard(Window window, String generation) {
            this.window = window;
            this.generation = generation;
            buildWorld();
            scene.setUpdateHandler(this::update);
            orbit.setTarget(new Vec3(0, 2, 0)).setDistance(34f).setAngles(0.5f, 0.35f).setDistanceLimits(4f, 90f);
        }

        // ---- the scene ---------------------------------------------------------------------------------------------

        private void buildWorld() {
            scene.setBackground(new Color(0.05f, 0.07f, 0.12f, 1f));
            scene.setSkybox(Skybox.gradient(new Color(0.18f, 0.34f, 0.72f, 1f), new Color(0.85f, 0.8f, 0.72f, 1f), new Color(0.12f, 0.12f, 0.13f, 1f)));
            scene.setAmbient(new Color(0.7f, 0.8f, 1f, 1f), 0.35f);
            scene.setBloom(0.5f);
            scene.setBloomThreshold(1.1f);
            scene.camera().setFovYDegrees(65).setClip(0.1f, 400f);
            // The scene's sun, low enough to throw long shadows of the fences across the ground.
            scene.lights().getFirst().setDirection(new Vec3(-0.55f, -0.45f, -0.4f)).setColor(new Color(1f, 0.93f, 0.8f, 1f)).setIntensity(2.6f);
            scene.setShadowDistance(110f);

            // Ground: a checker of dark tiles on pale stone. The tiles are real geometry with hard edges, which alias and shimmer
            // when they are not drawn finely enough.
            Node3D ground = new Node3D("ground", Mesh.plane(300, 300), new Material(new Color(0.72f, 0.70f, 0.64f, 1f)).setSpecular(0.1f));
            scene.add(ground);
            Instances tiles = new Instances();
            for (int i = -124; i < 124; i++) {
                for (int j = -40; j < 40; j++) {
                    if (((i + j) & 1) == 0) continue;
                    tiles.add(new Vec3(i * 0.5f + 0.25f, 0.01f, j * 0.5f + 0.25f), Quat.IDENTITY, new Vec3(0.5f, 0.02f, 0.5f));
                }
            }
            Node3D tileNode = new Node3D("tiles", Mesh.cube(1f), new Material(new Color(0.13f, 0.14f, 0.17f, 1f)).setSpecular(0.1f));
            tileNode.setInstances(tiles);
            tileNode.setCastShadow(false);
            scene.add(tileNode);

            buildFences();
            buildRotors();
            buildOrbs();
            buildSpinners();

            crates.setInstances(new Instances());
            scene.add(crates);
            setCrateCount(crateCount);
        }

        /** Two rows of fence: thin pickets and rails, a few pixels wide from a distance, which is where upscalers differ. */
        private void buildFences() {
            Material wood = new Material(new Color(0.85f, 0.82f, 0.74f, 1f)).setSpecular(0.05f);
            Material iron = new Material(new Color(0.12f, 0.12f, 0.13f, 1f)).setSpecular(0.4f);
            Instances pickets = new Instances(), posts = new Instances();
            for (float side : new float[]{-1f, 1f}) {
                float z = side * 6f;
                for (float x = -60f; x <= 60f; x += 0.22f) {
                    pickets.add(new Vec3(x, 0.8f, z), Quat.IDENTITY, new Vec3(0.045f, 1.6f, 0.03f));
                }
                for (float x = -60f; x <= 60f; x += 6f) {
                    posts.add(new Vec3(x, 1.05f, z), Quat.IDENTITY, new Vec3(0.18f, 2.1f, 0.18f));
                }
            }
            Node3D picketNode = new Node3D("pickets", Mesh.cube(1f), wood);
            picketNode.setInstances(pickets);
            scene.add(picketNode);
            Node3D postNode = new Node3D("posts", Mesh.cube(1f), iron);
            postNode.setInstances(posts);
            scene.add(postNode);
            Mesh rail = Mesh.cube(1f);
            for (float side : new float[]{-1f, 1f}) {
                for (float y : new float[]{0.35f, 1.3f}) {
                    Node3D node = new Node3D("rail", rail, iron);
                    node.setScale(new Vec3(120f, 0.04f, 0.04f));
                    node.setPosition(0, y, side * 5.96f);
                    scene.add(node);
                }
            }
        }

        /** Four turbines beyond the fences: the blades are the fast, thin movers. */
        private void buildRotors() {
            Material steel = new Material(new Color(0.8f, 0.82f, 0.85f, 1f)).setSpecular(0.3f);
            Material blade = new Material(new Color(0.95f, 0.95f, 0.97f, 1f)).setSpecular(0.2f);
            float[] xs = {-30f, -10f, 10f, 30f};
            for (int i = 0; i < 4; i++) {
                float z = i % 2 == 0 ? -17f : 17f;
                Node3D tower = new Node3D("tower", Mesh.cylinder(0.3f, 13f, 12), steel);
                tower.setPosition(xs[i], 6.5f, z);
                scene.add(tower);
                hubs[i] = new Vec3(xs[i], 13.2f, z + (z < 0 ? 0.6f : -0.6f));
                Node3D hub = new Node3D("hub", Mesh.sphere(0.5f, 16, 8), steel);
                hub.setPosition(hubs[i]);
                scene.add(hub);
                for (int b = 0; b < 3; b++) {
                    Node3D node = new Node3D("blade", Mesh.cube(1f), blade);
                    node.setScale(new Vec3(0.28f, 5.5f, 0.08f));
                    node.setCastShadow(true);
                    scene.add(node);
                    blades[i * 3 + b] = node;
                }
            }
        }

        /** Glowing orbs that circle the middle of the yard with a light each: motion that is bright against a dark ground. */
        private void buildOrbs() {
            Color[] colors = {new Color(1f, 0.35f, 0.3f, 1f), new Color(0.3f, 0.7f, 1f, 1f), new Color(0.4f, 1f, 0.5f, 1f),
                    new Color(1f, 0.85f, 0.3f, 1f), new Color(0.85f, 0.4f, 1f, 1f), new Color(1f, 0.6f, 0.2f, 1f)};
            Mesh sphere = Mesh.sphere(0.4f, 24, 12);
            for (int i = 0; i < orbs.length; i++) {
                orbs[i] = new Node3D("orb", sphere, new Material(colors[i]).setEmissive(colors[i], 4f));
                orbs[i].setCastShadow(false);
                scene.add(orbs[i]);
                orbLights[i] = Light.point(new Vec3(0, 2, 0), colors[i], 5f, 9f);
                scene.lights().add(orbLights[i]);
            }
        }

        /** A ring of cubes that spin in place. */
        private void buildSpinners() {
            Material[] materials = {new Material(new Color(0.9f, 0.25f, 0.2f, 1f)).setSpecular(0.5f),
                    new Material(new Color(0.2f, 0.5f, 0.9f, 1f)).setSpecular(0.5f)};
            Mesh cube = Mesh.cube(1f);
            for (int i = 0; i < spinners.length; i++) {
                spinners[i] = new Node3D("spinner", cube, materials[i % 2]);
                scene.add(spinners[i]);
            }
        }

        /** Scatters the crates (the same ones each time, a prefix of the same random sequence), which is what loads the GPU. */
        void setCrateCount(int count) {
            crateCount = Math.max(0, Math.min(MAX_CRATES, count));
            Instances instances = new Instances();
            Random random = new Random(11);
            for (int i = 0; i < crateCount; i++) {
                float x = (random.nextFloat() * 2f - 1f) * 100f, z = (random.nextFloat() * 2f - 1f) * 100f;
                float size = 0.3f + random.nextFloat() * 0.9f;
                // Nothing in the corridor the camera flies down, or the fences it passes.
                if (Math.abs(z) < 8f && Math.abs(x) < 64f) z += z < 0 ? -9f : 9f;
                instances.add(new Vec3(x, size / 2f, z), Quat.fromAxisAngle(Vec3.UP, random.nextFloat() * 6.28f), new Vec3(size, size, size));
                float shade = 0.5f + random.nextFloat() * 0.5f;
                instances.setTint(i, shade, shade * (0.8f + random.nextFloat() * 0.2f), shade * 0.7f, 1f);
            }
            crates.setInstances(instances);
        }

        // ---- every frame -------------------------------------------------------------------------------------------

        private void update(Scene3D s, float delta) {
            GpuDevice device = view == null ? null : view.getDevice();
            if (device == null) return;
            if (!ready) start(device);

            if (!frozen) clock += delta * speed;
            double t = clock;
            moveThings(t);
            if (flying) fly(t);
            count(delta);
        }

        /** The camera circles the yard, low and then above the fences, always looking at its middle. */
        private void fly(double t) {
            Vec3 at = path(t);
            scene.camera().lookAt(at, new Vec3((float) (2 * Math.sin(t * 0.21)), 2.2f, 0));
        }

        private static Vec3 path(double t) {
            return new Vec3((float) (24 * Math.cos(t * 0.3)), (float) (3.4 + 1.5 * Math.sin(t * 0.5)), (float) (13 * Math.sin(t * 0.3)));
        }

        private void moveThings(double t) {
            for (int i = 0; i < hubs.length; i++) {
                double spin = t * (i % 2 == 0 ? 1.6 : -1.2);
                for (int b = 0; b < 3; b++) {
                    double angle = spin + b * Math.PI * 2 / 3;
                    // A blade stands along the rotor's up, turned to the angle, with its middle half a blade out from the hub.
                    Vec3 offset = new Vec3((float) -Math.sin(angle) * 2.75f, (float) Math.cos(angle) * 2.75f, 0);
                    Node3D blade = blades[i * 3 + b];
                    blade.setPosition(hubs[i].add(offset));
                    blade.setRotation(Quat.fromAxisAngle(Vec3.Z, (float) angle));
                }
            }
            for (int i = 0; i < orbs.length; i++) {
                double angle = t * (0.5 + 0.08 * i) + i * Math.PI / 3;
                float radius = 5.5f + 1.6f * (i % 3);
                Vec3 at = new Vec3((float) Math.cos(angle) * radius * 1.6f, 1.2f + 1.1f * (i % 3) + (float) Math.sin(t * 1.3 + i) * 0.5f,
                        (float) Math.sin(angle) * radius * 0.5f);
                orbs[i].setPosition(at);
                orbLights[i].setVector(at);
            }
            for (int i = 0; i < spinners.length; i++) {
                double angle = i * Math.PI * 2 / spinners.length;
                float radius = 4.5f;
                spinners[i].setPosition((float) Math.cos(angle) * radius, 1.1f, (float) Math.sin(angle) * radius);
                spinners[i].setRotation(Quat.fromAxisAngle(new Vec3(0.3f, 1f, 0.2f).normalized(), (float) (t * (1.5 + i % 3 * 0.5))));
            }
        }

        // ---- the choices -------------------------------------------------------------------------------------------

        /** The device is there: see what can be used, and begin with the best of it. */
        private void start(GpuDevice device) {
            ready = true;
            String dlss = DlssProvider.unavailableReason(device);
            String fsr = fsrReason(device);
            String metal = MAC ? MetalFxProvider.unavailableReason(device) : "not a Mac";
            // The panel has two lines for vendors: DLSS and FSR, which are Windows libraries, and on a Mac MetalFX in their place.
            dlssLine = MAC ? "MetalFX: " + brief(metal, "metalfx") : "DLSS: " + brief(dlss, "dlss");
            fsrLine = MAC ? "" : "FSR: " + brief(fsr, "fsr");
            if (MAC && metal != null) System.out.println("MetalFX is not available: " + metal);
            if (!MAC && dlss != null) System.out.println("DLSS is not available: " + dlss);
            if (!MAC && fsr != null) System.out.println("FSR is not available: " + fsr);

            String first = UPSCALERS[1];
            if (metal == null) first = "MetalFX";
            else if (generation.equals("dlss") && dlss == null) first = "DLSS";
            else if (generation.equals("fsr") && fsr == null) first = "FSR";
            else if (dlss == null) first = "DLSS";
            else if (fsr == null) first = "FSR";
            choose(first);
            setting = true;
            upscalerBox.setSelected(first);
            presetBox.setSelectedIndex(1);
            setting = false;
            setScale(PRESET_SCALES[1]);
        }

        /** What the panel says of a vendor's upscaler: ready, or the short of why not (the whole of it is printed). */
        private static String brief(String reason, String vendor) {
            if (reason == null) return "ready";
            if (reason.contains("not found")) return "libraries not found (-Drengl." + vendor + ".dir)";
            return shorten(reason);
        }

        private static String fsrReason(GpuDevice device) {
            if (new FsrProvider().isAvailable(device)) return null;
            String reason = FsrProvider.unavailableReason();
            return reason != null ? reason : "the FidelityFX SDK would not start on this GPU";
        }

        /** Takes the upscaler of that name; returns false (and keeps the one in use) where it can not be used. */
        boolean choose(String name) {
            GpuDevice device = view.getDevice();
            UpscalerProvider next;
            switch (name) {
                case "DLSS" -> next = new DlssProvider();
                case "FSR" -> next = new FsrProvider();
                case "MetalFX" -> next = new MetalFxProvider();
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

        void setGeneration(boolean on) {
            if (generation.equals("dlss")) DlssFrameGeneration.setEnabled(on);
            else if (generation.equals("fsr")) FsrFrameGeneration.setEnabled(on);
        }

        // ---- the numbers -------------------------------------------------------------------------------------------

        /** Counts the frames drawn, and twice a second works out how many were drawn and how many were shown. */
        private void count(float delta) {
            framesSince++;
            timeSince += delta;
            if (timeSince < 0.5f) return;
            drawnFps = framesSince / (double) timeSince;
            shownFps = drawnFps;
            generating = false;
            switch (generation) {
                case "dlss" -> {
                    DlssFrameGeneration.State state = DlssFrameGeneration.state();
                    generating = state.active();
                    // Streamline says how many frames the last drawn one was shown as (2 with one generated).
                    if (generating) shownFps = drawnFps * Math.max(1, state.framesPresented());
                    generationLine = state.active() ? "DLSS frame generation: on"
                            : DlssFrameGeneration.isAvailable() ? "DLSS frame generation: off (" + state.statusText() + ")"
                            : "DLSS frame generation: " + shorten(DlssFrameGeneration.unavailableReason());
                    if (!generating && DlssFrameGeneration.isAvailable() && !DlssFrameGeneration.isEnabled()) generationLine = "DLSS frame generation: off";
                    if (generating && !"DLSS".equals(providerName)) generationLine = "Frame generation needs the DLSS upscaler";
                }
                case "fsr" -> {
                    FsrFrameGeneration.State state = FsrFrameGeneration.state();
                    generating = state.active() && FsrFrameGeneration.isEnabled();
                    if (generating) shownFps = (state.presentations() - lastPresentations) / (double) timeSince;
                    lastPresentations = state.presentations();
                    generationLine = generating ? "FSR frame generation: on"
                            : FsrFrameGeneration.isAvailable() ? "FSR frame generation: off"
                            : "FSR frame generation: " + shorten(FsrFrameGeneration.unavailableReason());
                    if (!"FSR".equals(providerName) && FsrFrameGeneration.isAvailable()) generationLine = "Frame generation needs the FSR upscaler";
                }
                default -> {
                    generationLine = "Frame generation: not started";
                    hintLine = "Launch with -Drengl.demo.framegen=dlss or fsr";
                }
            }
            if (generationBox != null) generationBox.setEnabled(generation.equals("dlss") || generation.equals("fsr"));

            int outW = view.getPixelWidth(), outH = view.getPixelHeight();
            // A vendor's library may take only some sizes (DLSS has none between a third and a half), and moves the scale to one.
            var provider = scene.getUpscaler();
            float drawnScale = provider != null && renderScale < 1f ? provider.supportedRenderScale(renderScale, outW, outH) : renderScale;
            sizeLine = String.format("Drawn at %d x %d, shown at %d x %d", Math.round(outW * drawnScale), Math.round(outH * drawnScale), outW, outH);
            upscalerLine = "Upscaler: " + providerName + (providerName.equals(UPSCALERS[0]) && renderScale >= 1f ? " (native)" : "");
            rateLine = String.format("Drawn %.0f fps (%.1f ms)  |  Shown %.0f fps", drawnFps, 1000 / Math.max(drawnFps, 1), shownFps);
            framesSince = 0;
            timeSince = 0;
            show();
        }

        private static String shorten(String text) {
            if (text == null) return "starting";
            return text.length() <= 46 ? text : text.substring(0, 45) + "…";
        }

        private void show() {
            String[] lines = {upscalerLine, sizeLine, rateLine, shorten(generationLine), hintLine, dlssLine, fsrLine};
            if (numbers[0] == null) return;
            for (int i = 0; i < numbers.length; i++) numbers[i].setText(lines[i]);
        }

        // ---- the controls ------------------------------------------------------------------------------------------

        void buildControls(Container panel) {
            float y = 16;
            TextOverlay title = new TextOverlay("Upscaling and frame generation", 20f, Color.WHITE);
            title.setPosition(16, y);
            panel.addElement(title);
            y += 34;

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
            presetBox = new DropdownOverlay<>(PANEL_WIDTH - 32, 32);
            for (String preset : PRESETS) presetBox.addItem(preset);
            presetBox.setSelectedIndex(1);
            presetBox.setPosition(16, y);
            presetBox.onSelect(name -> {
                if (setting) return;
                setScale(PRESET_SCALES[presetBox.getSelectedIndex()]);
            });
            panel.addElement(presetBox);
            y += 44;

            scaleLabel = new TextOverlay("Render scale: 67%", 14f, Color.WHITE);
            scaleLabel.setPosition(16, y);
            panel.addElement(scaleLabel);
            y += 24;
            scaleSlider = new SliderOverlay(PANEL_WIDTH - 32, 25, 100, 67);
            scaleSlider.setStep(1);
            scaleSlider.setPosition(16, y);
            scaleSlider.onValueChange(value -> {
                if (setting) return;
                setScale((float) (value / 100.0));
            });
            panel.addElement(scaleSlider);
            y += 40;

            generationBox = new CheckBoxOverlay(20, true);
            generationBox.setPosition(16, y);
            generationBox.onToggle(this::setGeneration);
            panel.addElement(generationBox);
            TextOverlay generationText = new TextOverlay("Frame generation", 14f, Color.WHITE);
            generationText.setPosition(46, y + 1);
            panel.addElement(generationText);
            y += 38;

            crateLabel = new TextOverlay("", 14f, Color.WHITE);
            crateLabel.setPosition(16, y);
            panel.addElement(crateLabel);
            crateLabel.setText("Scene load: " + crateCount + " crates");
            y += 24;
            SliderOverlay load = new SliderOverlay(PANEL_WIDTH - 32, 0, MAX_CRATES, crateCount);
            load.setStep(2000);
            load.setPosition(16, y);
            load.onValueChange(value -> {
                int count = (int) Math.round(value);
                if (count == crateCount) return;
                crateLabel.setText("Scene load: " + count + " crates");
                setCrateCount(count);
            });
            panel.addElement(load);
            y += 42;

            ButtonOverlay camera = new ButtonOverlay("Camera: fly-through", PANEL_WIDTH - 32, 32);
            camera.setPosition(16, y);
            camera.onAction(() -> {
                flying = !flying;
                camera.setText(flying ? "Camera: fly-through" : "Camera: orbit (drag, scroll)");
                scene.resetTemporalHistory();
            });
            panel.addElement(camera);
            y += 44;

            TextOverlay speedLabel = new TextOverlay("Speed: 1.0x", 14f, Color.WHITE);
            speedLabel.setPosition(16, y);
            panel.addElement(speedLabel);
            y += 24;
            SliderOverlay speedSlider = new SliderOverlay(PANEL_WIDTH - 32, 0.2, 4, 1);
            speedSlider.setPosition(16, y);
            speedSlider.onValueChange(value -> {
                speed = value.floatValue();
                speedLabel.setText(String.format("Speed: %.1fx", value));
            });
            panel.addElement(speedSlider);
            y += 40;

            CheckBoxOverlay freeze = new CheckBoxOverlay(20, false);
            freeze.setPosition(16, y);
            freeze.onToggle(on -> frozen = on);
            panel.addElement(freeze);
            TextOverlay freezeText = new TextOverlay("Freeze the motion", 14f, Color.WHITE);
            freezeText.setPosition(46, y + 1);
            panel.addElement(freezeText);
            y += 50;

            for (int i = 0; i < numbers.length; i++) {
                numbers[i] = new TextOverlay("", i == 2 ? 15f : 13f, i == 2 ? Color.WHITE : new Color(1f, 1f, 1f, i < 5 ? 0.85f : 0.55f));
                numbers[i].setPosition(16, y);
                panel.addElement(numbers[i]);
                y += i == 4 ? 28 : 20;
            }
        }

        private static float label(Container panel, String text, float y) {
            TextOverlay label = new TextOverlay(text, 14f, new Color(1f, 1f, 1f, 0.7f));
            label.setPosition(16, y);
            panel.addElement(label);
            return y + 22;
        }
    }
}
