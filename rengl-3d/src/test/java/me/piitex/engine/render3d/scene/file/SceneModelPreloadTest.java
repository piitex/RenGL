package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model read ahead of time, on another thread, is what the next node given its file shows: the node doesn't read it again.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class SceneModelPreloadTest {
    @TempDir
    Path temp;

    private static SceneProperty<Node3D> node(String name) {
        return SceneKinds.node().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    private Path triangle(float size) throws IOException {
        Path file = temp.resolve("models/tri.obj");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "v 0 0 0\nv " + size + " 0 0\nv 0 " + size + " 0\nf 1 2 3\n");
        return file;
    }

    private static Node3D holder(Path base, String model) {
        Node3D holder = SceneKinds.newNode(Shape.NONE, "Statue");
        node("model").set(holder, model, base);
        node("shape").set(holder, Shape.MODEL, base);
        return holder;
    }

    @Test
    void aModelReadOnAnotherThreadIsTheOneTheNodeShows() throws Exception {
        Path file = triangle(1f);
        FileTime stamp = Files.getLastModifiedTime(file);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                Scene3DFile.preloadModel("models/tri.obj", temp);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        worker.start();
        worker.join();
        assertNull(failure.get());

        // The file changes without its time changing, so only the model read ahead can be 1 across.
        triangle(5f);
        Files.setLastModifiedTime(file, stamp);

        Node3D node = holder(temp, "models/tri.obj");
        assertEquals(1f, node.bounds().max().x(), 1e-4f);
        assertNull(Scene3DFile.modelProblem(node));

        // It was taken, so the next node reads the file for itself.
        Node3D again = holder(temp, "models/tri.obj");
        assertEquals(5f, again.bounds().max().x(), 1e-4f);
    }

    @Test
    void aFileThatIsNotAModelIsSaidAndLeavesNothingBehind() throws IOException {
        Path file = temp.resolve("models/bad.obj");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not a model");
        assertThrows(IOException.class, () -> Scene3DFile.preloadModel("models/missing.obj", temp));

        Node3D node = holder(temp, "models/bad.obj");
        assertNotNull(Scene3DFile.modelProblem(node), "read for itself, and failed there");
    }

    @Test
    void aModelOfAnotherFileIsNotTakenByMistake() throws IOException {
        triangle(1f);
        Path other = temp.resolve("models/other.obj");
        Files.writeString(other, "v 0 0 0\nv 3 0 0\nv 0 3 0\nf 1 2 3\n");
        Scene3DFile.preloadModel("models/tri.obj", temp);

        Node3D node = holder(temp, "models/other.obj");
        assertEquals(3f, node.bounds().max().x(), 1e-4f);
    }
}
