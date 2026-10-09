package me.piitex.engine.input;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenFilesTest {
    private final List<List<Path>> received = new ArrayList<>();

    @AfterEach
    void clear() {
        OpenFiles.reset();
    }

    @Test
    void requestsBeforeAListenerAreKeptUntilOneIsSet(@TempDir Path dir) throws IOException {
        Path zip = Files.writeString(dir.resolve("a.zip"), "x");
        OpenFiles.fromArguments(new String[]{zip.toString()});
        OpenFiles.drain();
        assertTrue(received.isEmpty());

        OpenFiles.onOpenFiles(received::add);
        OpenFiles.drain();
        assertEquals(List.of(List.of(zip)), received);

        OpenFiles.drain();
        assertEquals(1, received.size(), "delivered once");
    }

    @Test
    void argumentsKeepOnlyExistingFilesAndIgnoreOptions(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("a.zip"), "x");
        Path folder = Files.createDirectory(dir.resolve("folder"));
        OpenFiles.onOpenFiles(received::add);
        OpenFiles.fromArguments(new String[]{"--verbose", file.toString(), dir.resolve("missing.zip").toString(), "", folder.toString()});
        OpenFiles.drain();
        assertEquals(List.of(List.of(file, folder)), received);
    }

    @Test
    void noQualifyingArgumentsDeliverNothing() {
        OpenFiles.onOpenFiles(received::add);
        OpenFiles.fromArguments(new String[]{"--help"});
        OpenFiles.fromArguments(new String[0]);
        OpenFiles.fromArguments(null);
        OpenFiles.drain();
        assertTrue(received.isEmpty());
    }

    @Test
    void theLaunchCommandsOwnWordsAreNotFilesToOpen(@TempDir Path dir) throws IOException {
        Path real = Files.writeString(dir.resolve("a.zip"), "x");
        Path onClassPath = Path.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator)[0]).toAbsolutePath().normalize();
        List<Path> kept = OpenFiles.onlyRealFiles(List.of(real, dir.resolve("missing"), onClassPath));
        assertEquals(List.of(real), kept);
    }

    @Test
    void aListenerThatThrowsDoesNotLoseTheRest(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a"), "x"), b = Files.writeString(dir.resolve("b"), "x");
        OpenFiles.fromArguments(new String[]{a.toString()});
        OpenFiles.fromArguments(new String[]{b.toString()});
        OpenFiles.onOpenFiles(paths -> {
            received.add(paths);
            if (received.size() == 1) throw new IllegalStateException("boom");
        });
        OpenFiles.drain();
        assertEquals(2, received.size());
    }
}
