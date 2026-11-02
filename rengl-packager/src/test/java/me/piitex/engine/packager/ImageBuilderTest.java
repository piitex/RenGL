package me.piitex.engine.packager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JVM options an image starts with, per platform. */
class ImageBuilderTest {
    @Test
    void macOsRunsOnTheFirstThreadAndKeepsAwtHeadless() {
        List<String> options = options(Platform.MACOS_ARM64, List.of());
        assertTrue(options.contains("-XstartOnFirstThread"));
        assertTrue(options.contains("-Djava.awt.headless=true"));
        assertTrue(options.contains("--enable-native-access=ALL-UNNAMED"));
    }

    @Test
    void anAppsOwnHeadlessSettingIsKept() {
        List<String> options = options(Platform.MACOS_X64, List.of("-Djava.awt.headless=false"));
        assertTrue(options.contains("-Djava.awt.headless=false"));
        assertFalse(options.contains("-Djava.awt.headless=true"));
    }

    @Test
    void otherPlatformsAreLeftAlone() {
        for (Platform platform : List.of(Platform.WINDOWS_X64, Platform.LINUX_X64, Platform.LINUX_ARM64)) {
            List<String> options = options(platform, List.of());
            assertEquals(List.of("--enable-native-access=ALL-UNNAMED"), options, platform.id());
        }
    }

    @Test
    void theMacLauncherPassesHeadlessToJava(@TempDir Path dir) throws IOException {
        Path runtime = Files.createDirectories(dir.resolve("jlink"));
        Path root = new ImageBuilder(app(List.of()), Platform.MACOS_ARM64, List.of(), runtime).build(Files.createDirectories(dir.resolve("out")));
        String script = Files.readString(root.resolve("Contents").resolve("MacOS").resolve("Demo"));
        assertTrue(script.contains("\"-Djava.awt.headless=true\""), script);
        assertTrue(script.contains("\"-XstartOnFirstThread\""), script);
    }

    private static List<String> options(Platform platform, List<String> appOptions) {
        return new ImageBuilder(app(appOptions), platform, List.of(), Path.of(".")).javaOptions();
    }

    private static AppSpec app(List<String> javaOptions) {
        return new AppSpec("Demo", "1.0.0", "Acme", "Demo", "me.rengl.demo", Path.of("."), "app.jar", "Main", javaOptions, Map.of(), List.of(), List.of());
    }
}
