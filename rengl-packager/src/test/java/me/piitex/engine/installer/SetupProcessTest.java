package me.piitex.engine.installer;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SetupProcessTest {
    @Test
    void readsProgressLines() {
        assertEquals(new SetupProcess.Progress(42), SetupProcess.parse("progress=42"));
        assertEquals(new SetupProcess.Progress(100), SetupProcess.parse("progress=100 "));
    }

    @Test
    void keepsProgressInRange() {
        assertEquals(new SetupProcess.Progress(100), SetupProcess.parse("progress=250"));
        assertEquals(new SetupProcess.Progress(0), SetupProcess.parse("progress=-3"));
    }

    @Test
    void readsFailures() {
        assertEquals(new SetupProcess.Failure("Could not create the folder: C:\\x"), SetupProcess.parse("error=Could not create the folder: C:\\x"));
    }

    @Test
    void ignoresOtherLines() {
        assertNull(SetupProcess.parse(""));
        assertNull(SetupProcess.parse("progress=abc"));
        assertNull(SetupProcess.parse("something else"));
    }

    @Test
    void commandCarriesTheChoices() {
        var command = SetupProcess.command(Path.of("C:\\Temp\\setup.exe"), new SetupProcess.Choices("C:\\Apps\\My App", true, false));
        assertEquals(java.util.List.of("C:\\Temp\\setup.exe", "/S", "/DIR=C:\\Apps\\My App", "/DESKTOP=1", "/STARTMENU=0", "/PROGRESS"), command);
    }
}
