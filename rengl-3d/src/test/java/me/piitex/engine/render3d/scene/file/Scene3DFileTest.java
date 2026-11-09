package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.math.Quat;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class Scene3DFileTest {
    @TempDir
    Path temp;

    private static SceneProperty<Node3D> node(String name) {
        return SceneKinds.node().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    private static SceneProperty<Material> material(String name) {
        return SceneKinds.material().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    private static <T> SceneProperty<T> find(java.util.List<SceneProperty<T>> list, String name) {
        return list.stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    private static void png(Path file, int width, int height, int rgb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, rgb);
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    private static String roundTrip(Scene3D scene, Path base) {
        String first = Scene3DFile.toJson(scene);
        String second = Scene3DFile.toJson(Scene3DFile.parse(first, base, false));
        assertEquals(first, second);
        return first;
    }

    @Test
    void aNewSceneRoundTrips() {
        Scene3D scene = Scene3DFile.blank();
        String json = roundTrip(scene, temp);
        Scene3D again = Scene3DFile.parse(json, temp, false);
        assertEquals(1, again.lights().size());
        assertEquals(Light.Type.DIRECTIONAL, again.lights().getFirst().getType());
        assertTrue(again.root().getChildren().isEmpty());
    }

    @Test
    void everyShapeWithItsValuesRoundTrips() {
        Scene3D scene = new Scene3D();
        Node3D cube = SceneKinds.newNode(Shape.CUBE, "Crate");
        node("size").set(cube, 2.5f);
        node("position").set(cube, new Vec3(1, 2, 3));
        node("rotation").set(cube, new Vec3(10, 200, -30));
        node("scale").set(cube, new Vec3(1, 2, 1));
        node("castShadow").set(cube, false);
        Node3D ball = SceneKinds.newNode(Shape.SPHERE, "Ball");
        node("radius").set(ball, 0.75f);
        node("segments").set(ball, 12);
        node("rings").set(ball, 6);
        node("visible").set(ball, false);
        Node3D floor = SceneKinds.newNode(Shape.PLANE, "Floor");
        node("width").set(floor, 20f);
        node("depth").set(floor, 8f);
        Node3D group = new Node3D("Group");
        group.add(cube);
        group.add(ball);
        scene.add(group);
        scene.add(floor);

        String json = roundTrip(scene, temp);
        Scene3D again = Scene3DFile.parse(json, temp, false);
        Node3D c = Scene3DFile.find(again, "Crate");
        assertNotNull(c);
        assertEquals(Shape.CUBE, Scene3DFile.getShape(c));
        assertEquals(2.5f, (Float) node("size").get(c));
        assertEquals(new Vec3(1, 2, 3), c.getPosition());
        assertEquals(new Vec3(10, 200, -30).x(), ((Vec3) node("rotation").get(c)).x(), 0.01f);
        assertEquals(1.25f, c.getMesh().bounds().max().x(), 1e-4f);
        assertFalse(c.isCastShadow());
        Node3D b = Scene3DFile.find(again, "Ball");
        assertFalse(b.isVisible());
        assertEquals(12, (Integer) node("segments").get(b));
        assertEquals(0.75f, b.getMesh().bounds().max().x(), 0.01f);
        assertSame(Scene3DFile.find(again, "Group"), b.getParent());
        assertEquals(10f, Scene3DFile.find(again, "Floor").getMesh().bounds().max().x(), 1e-4f);
    }

    @Test
    void valuesThatAreTheDefaultAreLeftOut() {
        Scene3D scene = new Scene3D();
        scene.add(new Node3D("Empty"));
        String json = Scene3DFile.toJson(scene);
        assertTrue(json.contains("\"Empty\""));
        json = json.substring(json.indexOf("\"nodes\"")); // the camera and the lights say where they are
        for (String left : new String[]{"position", "scale", "visible", "pickable", "castShadow", "shape", "material"}) {
            assertFalse(json.contains("\"" + left + "\""), left + " should be left out of " + json);
        }
    }

    @Test
    void rotationsAsAnglesComeBackAsTheSameRotation() {
        Random random = new Random(7);
        for (int i = 0; i < 500; i++) {
            Quat q = new Quat(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f).normalized();
            Quat back = Euler.toQuat(Euler.of(q));
            float dot = Math.abs(q.x() * back.x() + q.y() * back.y() + q.z() * back.z() + q.w() * back.w());
            assertTrue(dot > 0.9999f, "quaternion " + q + " came back as " + back);
        }
        // Straight up is gimbal lock: the angles are different but the rotation is the same.
        Quat up = Euler.toQuat(new Vec3(90, 30, 40));
        Quat back = Euler.toQuat(Euler.of(up));
        assertTrue(Math.abs(up.x() * back.x() + up.y() * back.y() + up.z() * back.z() + up.w() * back.w()) > 0.9999f);
    }

    @Test
    void anglesTypedInAreGivenBackAsTyped() {
        Node3D node = new Node3D("n");
        node("rotation").set(node, new Vec3(0, 270, 0)); // an equivalent (0, -90, 0) would be a surprise
        assertEquals(new Vec3(0, 270, 0), node("rotation").get(node));
        node.rotate(Vec3.UP, 0.5f); // changed from outside: now it has to be worked out
        assertNotEquals(new Vec3(0, 270, 0), node("rotation").get(node));
    }

    @Test
    void materialsKeepWhatTheyAreGiven() throws IOException {
        png(temp.resolve("assets/tex.png"), 4, 4, 0xff8040);
        png(temp.resolve("assets/normal.png"), 4, 4, 0x8080ff);
        Scene3D scene = new Scene3D();
        Node3D cube = SceneKinds.newNode(Shape.CUBE, "Box");
        Material m = cube.getMaterial();
        material("color").set(m, new Color(0.2f, 0.4f, 0.6f, 0.5f));
        material("texture").set(m, "assets/tex.png", temp);
        material("blend").set(m, true);
        material("doubleSided").set(m, true);
        material("alphaCutoff").set(m, 0.3f);
        material("pbr").set(m, true);
        material("metallic").set(m, 0.8f);
        material("roughness").set(m, 0.2f);
        material("normalTexture").set(m, "assets/normal.png", temp);
        material("normalScale").set(m, 2f);
        material("emissiveColor").set(m, new Color(1f, 0.5f, 0.25f, 1f));
        material("emissiveStrength").set(m, 3f);
        scene.add(cube);

        String json = roundTrip(scene, temp);
        Material back = Scene3DFile.find(Scene3DFile.parse(json, temp, false), "Box").getMaterial();
        assertNotNull(back.getTexture());
        assertEquals(4, back.getTexture().width());
        assertNotNull(back.getNormalTexture());
        assertTrue(back.isBlend() && back.isDoubleSided() && back.isPbr());
        assertEquals(0.3f, back.getAlphaCutoff(), 1e-6f);
        assertEquals(0.8f, back.getMetallic(), 1e-6f);
        assertEquals(0.5f, back.getAlpha(), 1e-6f);
        assertEquals(3f, (Float) material("emissiveStrength").get(back), 1e-3f);
        assertEquals(0.5f, ((Color) material("emissiveColor").get(back)).getG(), 1e-3f);
        assertEquals("assets/tex.png", material("texture").get(back));
    }

    @Test
    void aHeightMapAndItsDepthAreKeptAndOnlyShownWhenThereIsOne() throws IOException {
        png(temp.resolve("assets/height.png"), 4, 4, 0x808080);
        Scene3D scene = new Scene3D();
        Node3D cube = SceneKinds.newNode(Shape.CUBE, "Box");
        Material m = cube.getMaterial();
        material("pbr").set(m, true);
        assertFalse(material("heightScale").appliesTo(m), "no depth to set until there is a height map");
        material("heightTexture").set(m, "assets/height.png", temp);
        material("heightScale").set(m, 0.12f);
        assertTrue(material("heightScale").appliesTo(m));
        assertNotNull(m.getHeightTexture());
        scene.add(cube);

        String json = roundTrip(scene, temp);
        assertTrue(json.contains("heightTexture") && json.contains("heightScale"), json);
        Material back = Scene3DFile.find(Scene3DFile.parse(json, temp, false), "Box").getMaterial();
        assertNotNull(back.getHeightTexture());
        assertEquals(0.12f, back.getHeightScale(), 1e-6f);
        assertEquals("assets/height.png", material("heightTexture").get(back));

        material("heightScale").set(back, 99f);
        assertEquals(Material.MAX_HEIGHT_SCALE, back.getHeightScale(), 1e-6f, "too deep is held at the most");

        Material plain = SceneKinds.newNode(Shape.CUBE, "Plain").getMaterial();
        assertFalse(roundTrip(sceneOf(plain), temp).contains("heightTexture"), "a material with none doesn't write one");
    }

    private static Scene3D sceneOf(Material material) {
        Scene3D scene = new Scene3D();
        Node3D cube = SceneKinds.newNode(Shape.CUBE, "Plain");
        cube.setMaterial(material);
        scene.add(cube);
        return scene;
    }

    @Test
    void aPlainMaterialStaysPlainAndSettingTexturesDoesntTurnOnPbr() throws IOException {
        png(temp.resolve("t.png"), 2, 2, 0xffffff);
        Material m = new Material();
        material("texture").set(m, "t.png", temp);
        assertFalse(m.isPbr());
        material("occlusionTexture").set(m, "t.png", temp);
        assertFalse(m.isPbr(), "the file says it is not physically based");
        material("metallic").set(m, 0.4f);
        assertFalse(m.isPbr());
        material("pbr").set(m, true);
        assertTrue(m.isPbr());
    }

    @Test
    void aPictureThatIsMissingIsKeptByName() {
        Scene3D scene = new Scene3D();
        Node3D cube = SceneKinds.newNode(Shape.CUBE, "Box");
        material("texture").set(cube.getMaterial(), "gone/missing.png", temp);
        scene.add(cube);
        assertNull(cube.getMaterial().getTexture());
        String json = roundTrip(scene, temp);
        assertTrue(json.contains("gone/missing.png"));
    }

    @Test
    void lightsOfEveryTypeRoundTrip() {
        Scene3D scene = new Scene3D();
        scene.lights().clear();
        Light sun = SceneKinds.newLight(Light.Type.DIRECTIONAL);
        Scene3DFile.setName(sun, "Sun");
        find(SceneKinds.light(Light.Type.DIRECTIONAL), "shadows").set(sun, LightShadow.HIGH);
        Light lamp = SceneKinds.newLight(Light.Type.POINT);
        find(SceneKinds.light(Light.Type.POINT), "range").set(lamp, 6.5f);
        find(SceneKinds.light(Light.Type.POINT), "falloff").set(lamp, Falloff.LINEAR);
        find(SceneKinds.light(Light.Type.POINT), "color").set(lamp, new Color(1f, 0.5f, 0.2f, 1f));
        Light torch = SceneKinds.newLight(Light.Type.SPOT);
        find(SceneKinds.light(Light.Type.SPOT), "innerAngle").set(torch, 10f);
        find(SceneKinds.light(Light.Type.SPOT), "outerAngle").set(torch, 40f);
        find(SceneKinds.light(Light.Type.SPOT), "shadows").set(torch, LightShadow.SCENE);
        scene.lights().add(sun);
        scene.lights().add(lamp);
        scene.lights().add(torch);

        String json = roundTrip(scene, temp);
        Scene3D again = Scene3DFile.parse(json, temp, false);
        assertEquals(3, again.lights().size());
        Light s = Scene3DFile.findLight(again, "Sun");
        assertNotNull(s);
        assertEquals(LightShadow.HIGH, find(SceneKinds.light(Light.Type.DIRECTIONAL), "shadows").get(s));
        Light l = again.lights().get(1);
        assertEquals(Light.Type.POINT, l.getType());
        assertEquals(6.5f, l.getRange(), 1e-6f);
        assertEquals(Falloff.LINEAR, find(SceneKinds.light(Light.Type.POINT), "falloff").get(l));
        assertEquals("Point Light", Scene3DFile.getName(l));
        Light t = again.lights().get(2);
        assertEquals(10f, t.getInnerAngle(), 1e-6f);
        assertEquals(40f, t.getOuterAngle(), 1e-6f);
        assertEquals(LightShadow.SCENE, find(SceneKinds.light(Light.Type.SPOT), "shadows").get(t));
        assertFalse(t.getShadows() == null);
    }

    @Test
    void theCameraAndTheWorldRoundTrip() throws IOException {
        png(temp.resolve("sky.png"), 8, 4, 0x3060c0);
        Scene3D scene = new Scene3D();
        find(SceneKinds.camera(), "position").set(scene.camera(), new Vec3(5, 6, 7));
        find(SceneKinds.camera(), "fov").set(scene.camera(), 45f);
        find(SceneKinds.camera(), "far").set(scene.camera(), 500f);
        find(SceneKinds.world(), "background").set(scene, new Color(0.9f, 0.1f, 0.1f, 1f));
        find(SceneKinds.world(), "fogDensity").set(scene, 0.02f);
        find(SceneKinds.world(), "skyKind").set(scene, SkyKind.PANORAMA);
        find(SceneKinds.world(), "skyPanorama").set(scene, "sky.png", temp);
        find(SceneKinds.world(), "skyBrightness").set(scene, 0.5f, temp);
        find(SceneKinds.world(), "environmentLighting").set(scene, 0.7f);
        find(SceneKinds.world(), "shadowDistance").set(scene, 30f);
        assertNotNull(scene.getSkybox());
        assertEquals(0.5f, scene.getSkybox().getBrightness(), 1e-6f);

        String json = roundTrip(scene, temp);
        Scene3D again = Scene3DFile.parse(json, temp, false);
        assertEquals(new Vec3(5, 6, 7), again.camera().getPosition());
        assertEquals(45f, again.camera().getFovYDegrees(), 1e-3f);
        assertEquals(500f, again.camera().getFar(), 1e-6f);
        assertEquals(0.02f, again.getFogDensity(), 1e-6f);
        assertEquals(30f, again.getShadowDistance(), 1e-6f);
        assertNotNull(again.getSkybox());
        assertNull(Scene3DFile.skyProblem(again));
    }

    @Test
    void aSkyWithoutItsPicturesSaysWhatIsWrong() {
        Scene3D scene = new Scene3D();
        find(SceneKinds.world(), "skyKind").set(scene, SkyKind.CUBE);
        assertNull(scene.getSkybox());
        assertNotNull(Scene3DFile.skyProblem(scene));
        find(SceneKinds.world(), "skyKind").set(scene, SkyKind.GRADIENT);
        assertNotNull(scene.getSkybox());
        assertNull(Scene3DFile.skyProblem(scene));
        assertTrue(Scene3DFile.toJson(scene).contains("GRADIENT"));
    }

    @Test
    void aModelIsLoadedAsItsOwnNodesAndNotSavedAsThem() throws IOException {
        Files.createDirectories(temp.resolve("models"));
        Files.writeString(temp.resolve("models/tri.obj"), """
                v 0 0 0
                v 1 0 0
                v 0 1 0
                f 1 2 3
                """);
        Scene3D scene = new Scene3D();
        Node3D holder = new Node3D("Statue");
        node("shape").set(holder, Shape.MODEL, temp);
        node("model").set(holder, "models/tri.obj", temp);
        scene.add(holder);
        assertNull(Scene3DFile.modelProblem(holder));
        assertEquals(1, holder.getChildren().size());
        Node3D inside = holder.getChildren().getFirst();
        assertTrue(Scene3DFile.isInternal(inside));
        assertSame(holder, Scene3DFile.owner(inside));
        assertFalse(Scene3DFile.isInternal(holder));
        assertNull(Scene3DFile.find(scene, inside.getName().equals("Statue") ? "no such" : inside.getName()));

        String json = roundTrip(scene, temp);
        assertFalse(json.contains("\"children\""), "a model's own nodes are not the scene's to save: " + json);
        assertTrue(json.contains("models/tri.obj"));
        Node3D back = Scene3DFile.find(Scene3DFile.parse(json, temp, false), "Statue");
        assertEquals(Shape.MODEL, Scene3DFile.getShape(back));
        assertEquals(1, back.getChildren().size());
    }

    @Test
    void aModelThatCantBeLoadedIsKeptByName() {
        Scene3D scene = new Scene3D();
        Node3D holder = new Node3D("Gone");
        node("shape").set(holder, Shape.MODEL, temp);
        node("model").set(holder, "models/missing.glb", temp);
        scene.add(holder);
        assertNotNull(Scene3DFile.modelProblem(holder));
        assertTrue(holder.getChildren().isEmpty());
        assertTrue(roundTrip(scene, temp).contains("models/missing.glb"));
        node("shape").set(holder, Shape.CUBE, temp);
        assertNull(Scene3DFile.modelProblem(holder));
        assertNotNull(holder.getMesh());
    }

    @Test
    void aCopyHasItsOwnMaterials() {
        Node3D cube = SceneKinds.newNode(Shape.CUBE, "Box");
        Node3D child = SceneKinds.newNode(Shape.SPHERE, "Ball");
        cube.add(child);
        material("color").set(child.getMaterial(), new Color(1f, 0f, 0f, 1f));
        Node3D copy = Scene3DFile.copy(cube, temp);
        assertNull(copy.getParent());
        assertEquals(1, copy.getChildren().size());
        Node3D childCopy = copy.getChildren().getFirst();
        assertNotSame(child.getMaterial(), childCopy.getMaterial());
        assertEquals(1f, childCopy.getMaterial().getRed(), 1e-6f);
        material("color").set(childCopy.getMaterial(), new Color(0f, 1f, 0f, 1f));
        assertEquals(1f, child.getMaterial().getRed(), 1e-6f);
    }

    @Test
    void saveAndLoadAFile() throws IOException {
        Scene3D scene = new Scene3D();
        scene.add(SceneKinds.newNode(Shape.SPHERE, "Ball"));
        Path file = temp.resolve("scenes/level.json");
        Scene3DFile.save(scene, file);
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(temp.resolve("scenes/level.json.tmp")));
        Scene3D back = Scene3DFile.load(file, temp);
        assertNotNull(Scene3DFile.find(back, "Ball"));
    }

    @Test
    void badFilesAreReportedByWhatIsWrong() {
        assertThrows(IllegalArgumentException.class, () -> Scene3DFile.parse("[]", temp, false));
        assertThrows(IllegalArgumentException.class, () -> Scene3DFile.parse("{\"format\":1}", temp, false));
        IllegalArgumentException newer = assertThrows(IllegalArgumentException.class, () -> Scene3DFile.parse("{\"format\":99,\"nodes\":[]}", temp, false));
        assertTrue(newer.getMessage().contains("newer"));
        IllegalArgumentException light = assertThrows(IllegalArgumentException.class,
                () -> Scene3DFile.parse("{\"lights\":[{\"type\":\"LASER\"}]}", temp, false));
        assertTrue(light.getMessage().contains("LASER") || light.getMessage().contains("type"), light.getMessage());
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> Scene3DFile.parse("{\"nodes\":[{\"name\":\"Bad\",\"position\":[1,2]}]}", temp, false));
        assertTrue(wrong.getMessage().contains("Bad") && wrong.getMessage().contains("position"), wrong.getMessage());
        assertThrows(java.io.UncheckedIOException.class, () -> Scene3DFile.load(temp.resolve("none.json"), temp));
    }

    @Test
    void aFileWithoutLightsKeepsTheDefaultOneAndAnEmptyListClearsThem() {
        assertEquals(1, Scene3DFile.parse("{\"nodes\":[]}", temp, false).lights().size());
        assertEquals(0, Scene3DFile.parse("{\"lights\":[],\"nodes\":[]}", temp, false).lights().size());
    }

    @Test
    void findLooksOnlyAtTheScenesOwnNodes() {
        Scene3D scene = new Scene3D();
        Node3D group = new Node3D("A");
        Node3D inner = new Node3D("B");
        group.add(inner);
        scene.add(group);
        assertSame(inner, Scene3DFile.find(scene, "B"));
        assertNull(Scene3DFile.find(scene, "root"));
        assertNull(Scene3DFile.find(scene, "C"));
    }

    @Test
    void propertiesOnlyAppearWhereTheyMeanSomething() {
        Node3D cube = SceneKinds.newNode(Shape.CUBE, "c");
        assertTrue(node("size").appliesTo(cube));
        assertFalse(node("radius").appliesTo(cube));
        assertFalse(node("model").appliesTo(cube));
        Material plain = new Material();
        assertTrue(material("specular").appliesTo(plain));
        assertFalse(material("metallic").appliesTo(plain));
        plain.setPbr(true);
        assertTrue(material("metallic").appliesTo(plain));
        assertFalse(material("specular").appliesTo(plain));
        assertFalse(find(SceneKinds.light(Light.Type.POINT), "range") == null);
        assertTrue(SceneKinds.light(Light.Type.DIRECTIONAL).stream().noneMatch(p -> p.getName().equals("range")));
    }

    @Test
    void numbersOfAnotherTypeAreConverted() {
        Node3D cube = SceneKinds.newNode(Shape.CUBE, "c");
        node("size").set(cube, 3); // an int for a float
        assertEquals(3f, (Float) node("size").get(cube));
        Node3D ball = SceneKinds.newNode(Shape.SPHERE, "b");
        node("segments").set(ball, 9.0); // a double for an int
        assertEquals(9, (Integer) node("segments").get(ball));
    }
}
