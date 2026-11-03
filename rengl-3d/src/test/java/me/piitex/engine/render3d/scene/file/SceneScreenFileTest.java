package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.render3d.scene.UiScreen;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.file.UiFile;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A material that names a layout shows it as a screen: the file draws it, saves what it was told, and keeps a name it
 * can't read.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class SceneScreenFileTest {
    @TempDir
    Path temp;

    private void layout(String name, Color color) throws IOException {
        Container root = new Container(80, 40);
        root.getStyling().setBackgroundColor(color);
        UiFile.save(root, temp.resolve(name));
    }

    private static String scene(String material) {
        return "{\"format\":1,\"nodes\":[{\"name\":\"Monitor\",\"shape\":\"PLANE\",\"material\":" + material + "}]}";
    }

    @Test
    void aLayoutIsDrawnOntoTheGlowOfTheMaterial() throws IOException {
        layout("screens/panel.json", new Color(1f, 0f, 0f, 1f));
        Node3D monitor = Scene3DFile.find(Scene3DFile.parse(scene("{\"screen\":\"screens/panel.json\"}"), temp, false), "Monitor");

        UiScreen screen = Scene3DFile.screen(monitor);
        assertNotNull(screen, "the layout became a screen");
        assertNull(Scene3DFile.screenProblem(monitor));
        assertSame(screen.texture(), monitor.getMaterial().getEmissiveTexture());
        assertEquals(80, screen.texture().width());
        assertEquals(40, screen.texture().height());
        assertTrue(monitor.getMaterial().getEmissiveRed() > 0f, "a screen glows without the file saying how much");
    }

    @Test
    void sharpnessMakesTheTextureBigger() throws IOException {
        layout("panel.json", new Color(0f, 1f, 0f, 1f));
        Node3D monitor = Scene3DFile.find(Scene3DFile.parse(scene("{\"screen\":\"panel.json\",\"screenScale\":2}"), temp, false), "Monitor");

        assertEquals(160, Scene3DFile.screen(monitor).texture().width());
        assertEquals(80, Scene3DFile.screen(monitor).texture().height());
    }

    @Test
    void theScreenIsSavedAndNothingElseIsAdded() throws IOException {
        layout("panel.json", Color.WHITE);
        String json = Scene3DFile.toJson(Scene3DFile.parse(scene("{\"screen\":\"panel.json\",\"screenScale\":2}"), temp, false));

        assertTrue(json.contains("\"screen\": \"panel.json\"") || json.contains("\"screen\":\"panel.json\""), json);
        assertTrue(json.contains("screenScale"), json);
        assertFalse(json.contains("emissiveTexture"), "the layout is not saved as a glow map: " + json);

        String plain = Scene3DFile.toJson(Scene3DFile.parse(scene("{}"), temp, false));
        assertFalse(plain.contains("screen"), plain);
    }

    @Test
    void aLayoutThatCantBeReadIsKeptAndSaid() {
        Node3D monitor = Scene3DFile.find(Scene3DFile.parse(scene("{\"screen\":\"missing.json\"}"), temp, false), "Monitor");

        assertNull(Scene3DFile.screen(monitor));
        assertNotNull(Scene3DFile.screenProblem(monitor));
        assertNull(monitor.getMaterial().getEmissiveTexture());
        assertTrue(Scene3DFile.toJson(Scene3DFile.parse(scene("{\"screen\":\"missing.json\"}"), temp, false)).contains("missing.json"),
                "saving doesn't lose the name");
    }

    @Test
    void clearingTheScreenGivesTheGlowMapBack() throws IOException {
        layout("panel.json", Color.WHITE);
        Scene3D loaded = Scene3DFile.parse(scene("{\"screen\":\"panel.json\"}"), temp, false);
        Node3D monitor = Scene3DFile.find(loaded, "Monitor");
        UiScreen screen = Scene3DFile.screen(monitor);
        Material material = monitor.getMaterial();

        SceneKinds.material().stream().filter(p -> p.getName().equals("screen")).findFirst().orElseThrow().set(material, "", temp);

        assertNull(Scene3DFile.screen(monitor));
        assertNotSame(screen.texture(), material.getEmissiveTexture());
    }

    @Test
    void aSceneLoadedToRunDrawsItsScreensAgainByItselfAndAnEditorsDoesNot() throws IOException {
        layout("panel.json", new Color(0f, 0f, 1f, 1f));
        String json = scene("{\"screen\":\"panel.json\"}");

        Scene3D running = Scene3DFile.parse(json, temp, true);
        assertEquals(java.util.List.of(Scene3DFile.screen(Scene3DFile.find(running, "Monitor"))), running.screens());
        running.addScreen(running.screens().getFirst());
        assertEquals(1, running.screens().size(), "adding it twice changes nothing");

        assertTrue(Scene3DFile.parse(json, temp, false).screens().isEmpty());
    }
}
