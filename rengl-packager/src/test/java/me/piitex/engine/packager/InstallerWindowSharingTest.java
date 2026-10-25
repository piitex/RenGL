package me.piitex.engine.packager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When the installer window may run on the app's own runtime and jars instead of carrying a runtime of its own. */
class InstallerWindowSharingTest {
    private static final String WINDOW_JAR = "rengl-packager-1.0-ui.jar";

    private static Path jar(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve(name), content);
    }

    @Test
    void anAppBuiltFromTheSameJarsSharesItsRuntime(@TempDir Path root) throws IOException {
        List<Path> window = List.of(jar(root.resolve("ui"), WINDOW_JAR, "window"), jar(root.resolve("ui"), "rengl-core.jar", "engine"));
        List<Path> app = List.of(jar(root.resolve("app"), "game.jar", "game"), jar(root.resolve("app"), "rengl-core.jar", "engine"));

        assertNull(Packager.sharingProblem(window, WINDOW_JAR, app, List.of("java.base", "java.desktop"), List.of("java.base", "java.desktop", "jdk.unsupported")));
    }

    @Test
    void anAppWithoutTheEngineGetsARuntimeForTheWindow(@TempDir Path root) throws IOException {
        List<Path> window = List.of(jar(root.resolve("ui"), WINDOW_JAR, "window"), jar(root.resolve("ui"), "rengl-core.jar", "engine"));
        List<Path> app = List.of(jar(root.resolve("app"), "game.jar", "game"));

        String problem = Packager.sharingProblem(window, WINDOW_JAR, app, List.of("java.base"), List.of("java.base"));
        assertNotNull(problem);
        assertTrue(problem.contains("rengl-core.jar"), problem);
    }

    @Test
    void aJarOfTheSameNameButAnotherBuildIsNotShared(@TempDir Path root) throws IOException {
        // The same length, so only the content tells them apart.
        List<Path> window = List.of(jar(root.resolve("ui"), WINDOW_JAR, "window"), jar(root.resolve("ui"), "rengl-core.jar", "engine-a"));
        List<Path> app = List.of(jar(root.resolve("app"), "rengl-core.jar", "engine-b"));

        String problem = Packager.sharingProblem(window, WINDOW_JAR, app, List.of("java.base"), List.of("java.base"));
        assertNotNull(problem);
        assertTrue(problem.contains("different build"), problem);
    }

    @Test
    void aRuntimeWithoutAModuleTheWindowNeedsIsNotShared(@TempDir Path root) throws IOException {
        List<Path> window = List.of(jar(root.resolve("ui"), WINDOW_JAR, "window"));

        String problem = Packager.sharingProblem(window, WINDOW_JAR, List.of(), List.of("java.base", "java.desktop"), List.of("java.base"));
        assertEquals("the app's runtime has no java.desktop module", problem);
    }
}
