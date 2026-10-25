package me.piitex.engine.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SetupInfoTest {
    private static final String INI = "name=My App\nversion=2.1.0\nvendor=Me\ndescription=Does things\nid=me.myapp\nexe=My App.exe\nui=installer.exe\n";

    @Test
    void parsesTheSettings() {
        assertEquals(new SetupInfo("My App", "2.1.0", "Me", "Does things", "My App.exe"), SetupInfo.parse(INI));
    }

    @Test
    void parsesWindowsLineEndingsAndMissingKeys() {
        assertEquals(new SetupInfo("Tool", "", "", "", "Tool.exe"), SetupInfo.parse("name=Tool\r\n"));
    }

    /** The setup program is an exe with the zip glued on; setup.ini has to be readable from it. */
    @Test
    void readsSetupIniFromAZipAppendedToAProgram(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new ZipEntry("setup.ini"));
            zip.write(INI.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("MyApp/app.jar"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        Path setup = dir.resolve("setup.exe");
        byte[] program = new byte[70_000];
        program[0] = 'M';
        program[1] = 'Z';
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(program);
        file.write(zipBytes.toByteArray());
        Files.write(setup, file.toByteArray());

        assertEquals("My App", SetupInfo.read(setup).name());
    }
}
