package me.piitex.engine.packager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PlatformTest {
    private static Platform.JarTarget.Only only(String jar) {
        return assertInstanceOf(Platform.JarTarget.Only.class, Platform.classify(jar));
    }

    @Test
    void renglNativesArtifactsGoToTheirOwnPlatform() {
        assertEquals(Platform.WINDOWS_X64, only("rengl-natives-windows-0.1.0.jar").platform());
        assertEquals(Platform.LINUX_X64, only("rengl-natives-linux-0.1.0.jar").platform());
        assertEquals(Platform.LINUX_ARM64, only("rengl-natives-linux-arm64-0.1.0.jar").platform());
        assertEquals(Platform.MACOS_X64, only("rengl-natives-macos-0.1.0.jar").platform());
        assertEquals(Platform.MACOS_ARM64, only("rengl-natives-macos-arm64-0.1.0.jar").platform());
    }

    @Test
    void lwjglNativesStillGoToTheirOwnPlatform() {
        assertEquals(Platform.WINDOWS_X64, only("lwjgl-3.4.3-natives-windows.jar").platform());
        assertEquals(Platform.MACOS_ARM64, only("lwjgl-stb-3.4.3-natives-macos-arm64.jar").platform());
    }

    @Test
    void windowsArm64IsNotBuiltFor() {
        assertInstanceOf(Platform.JarTarget.Unsupported.class, Platform.classify("rengl-natives-windows-arm64-0.1.0.jar"));
    }

    @Test
    void codeJarsGoEverywhere() {
        assertInstanceOf(Platform.JarTarget.Any.class, Platform.classify("rengl-core-0.1.0.jar"));
        assertInstanceOf(Platform.JarTarget.Any.class, Platform.classify("rengl-natives-parent-0.1.0.jar"));
    }
}
