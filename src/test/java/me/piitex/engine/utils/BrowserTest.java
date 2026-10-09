package me.piitex.engine.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BrowserTest {
    @Test
    void acceptsWebAndMailAddresses() {
        assertEquals("https://example.com/a?b=1", Browser.parse("https://example.com/a?b=1").toString());
        assertEquals("http://localhost:8080", Browser.parse(" http://localhost:8080 ").toString());
        assertEquals("mailto:me@example.com", Browser.parse("mailto:me@example.com").toString());
    }

    @Test
    void bareAddressDefaultsToHttps() {
        assertEquals("https://example.com", Browser.parse("example.com").toString());
    }

    @Test
    void refusesEverythingElse() {
        assertNull(Browser.parse(null));
        assertNull(Browser.parse("  "));
        assertNull(Browser.parse("file:///etc/passwd"));
        assertNull(Browser.parse("javascript:alert(1)"));
        assertNull(Browser.parse("ms-msdt:/id PCWDiagnostic"));
        assertNull(Browser.parse("https://"));
        assertNull(Browser.parse("https://exa mple.com"));
    }

    @Test
    void refusesProgramsButNotDocuments(@TempDir Path dir) throws IOException {
        for (String name : new String[]{"setup.exe", "run.BAT", "go.sh", "tool.jar", "Thing.app", "x.command"}) {
            assertTrue(Browser.isProgram(dir.resolve(name)), name);
        }
        for (String name : new String[]{"photo.png", "archive.zip", "notes.txt", "noextension"}) {
            assertFalse(Browser.isProgram(dir.resolve(name)), name);
        }
        Path folder = Files.createDirectory(dir.resolve("scripts.sh"));
        assertFalse(Browser.isProgram(folder), "a folder is not run by opening it");
    }

    @Test
    void openAndRevealNeedAnExistingPath(@TempDir Path dir) throws IOException {
        assertFalse(Browser.open(dir.resolve("missing.txt")));
        assertFalse(Browser.reveal(dir.resolve("missing.txt")));
        assertFalse(Browser.open((Path) null));
        Path exe = Files.writeString(dir.resolve("a.exe"), "x");
        assertFalse(Browser.open(exe), "a program is never opened");
    }

    @Test
    void commandsPerPlatform(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("a b.txt"), "x");
        assertArrayEquals(new String[]{"open", file.toString()}, Browser.openCommand(Platform.MACOS, file));
        assertArrayEquals(new String[]{"open", "-R", file.toString()}, Browser.revealCommand(Platform.MACOS, file));
        assertArrayEquals(new String[]{"explorer.exe", "/select,", file.toString()}, Browser.revealCommand(Platform.WINDOWS, file));
        assertArrayEquals(new String[]{"explorer.exe", dir.toString()}, Browser.openCommand(Platform.WINDOWS, dir));
        assertArrayEquals(new String[]{"rundll32", "url.dll,FileProtocolHandler", file.toString()}, Browser.openCommand(Platform.WINDOWS, file));
        assertArrayEquals(new String[]{"xdg-open", dir.toString()}, Browser.revealCommand(Platform.LINUX, file));
        assertNull(Browser.openCommand(Platform.OTHER, file));
        assertNull(Browser.revealCommand(Platform.OTHER, file));
    }
}
