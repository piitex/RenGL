package me.piitex.examples.entities;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.entity.EntityScene;
import me.piitex.engine.utils.flags.GeneratedClass;

/**
 * A scene built entirely from a file, {@code demo/entities.json}: a sun with an orbiting planet and moon, a bobbing
 * image and a turning box with its collider outlined. Nothing in the code places anything. Change the file (or,
 * later, open it in the editor) and run again.
 * <p>
 * The components it uses are the engine's {@link me.piitex.engine.entity.SpriteRenderer} and
 * {@link me.piitex.engine.entity.BoxCollider}, and this package's {@link Spin}, {@link Bob} and {@link Quad}, found
 * by the class names in the file.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Opus 5.5", reviewed = false)
public class EntityDemo {

    static void main() {
        // -XstartOnFirstThread --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=deny
        Window window = new Window(new WindowOptions("Entity Demo").setDimensions(1000, 640));
        EntityScene scene = EntityScene.load("src/main/resources/demo/entities.json");
        scene.setShowColliders(true);
        window.getSceneManager().set(scene);
        new Engine().start(window);
    }
}
