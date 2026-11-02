package me.piitex.engine.gl;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The bytes Explorer reads a dragged list of files from, and the ones it writes. */
class DropFilesTest {
    private static Path path(String name) {
        return Path.of(System.getProperty("java.io.tmpdir"), name).toAbsolutePath();
    }

    @Test
    void theHeaderSaysWhereTheNamesStartAndThatTheyAreWide() {
        byte[] data = DropFiles.encode(List.of(path("a.txt")));
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(20, in.getInt(0), "pFiles");
        assertEquals(0, in.getInt(4) | in.getInt(8), "no drop point");
        assertEquals(0, in.getInt(12), "not in the non-client area");
        assertEquals(1, in.getInt(16), "wide characters");
    }

    @Test
    void everyNameIsEndedAndTheListHasOneMoreEnd() {
        Path a = path("a.txt"), b = path("b.txt");
        byte[] data = DropFiles.encode(List.of(a, b));
        int names = (a.toString().length() + 1 + b.toString().length() + 1) * 2;
        assertEquals(20 + names + 2, data.length);
        assertEquals(0, data[data.length - 1]);
        assertEquals(0, data[data.length - 2]);
        assertEquals(a.toString(), new String(data, 20, a.toString().length() * 2, StandardCharsets.UTF_16LE));
    }

    @Test
    void whatIsEncodedIsDecodedInOrder() {
        List<Path> files = List.of(path("zip contents/readme (1).txt"), path("folder"), path("naïve – 日本語.bin"));
        assertEquals(files, DropFiles.decode(DropFiles.encode(files)));
    }

    @Test
    void anEmptyListIsJustTheHeaderAndAnEnd() {
        byte[] data = DropFiles.encode(List.of());
        assertEquals(22, data.length);
        assertEquals(List.of(), DropFiles.decode(data));
    }

    @Test
    void relativePathsAreMadeAbsolute() {
        List<Path> back = DropFiles.decode(DropFiles.encode(List.of(Path.of("here.txt"))));
        assertEquals(Path.of("here.txt").toAbsolutePath(), back.getFirst());
    }

    @Test
    void narrowNamesAreReadToo() {
        byte[] names = "one\0two\0\0".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer block = ByteBuffer.allocate(20 + names.length).order(ByteOrder.LITTLE_ENDIAN);
        block.putInt(20).putInt(0).putInt(0).putInt(0).putInt(0).put(names);
        assertEquals(List.of(Path.of("one"), Path.of("two")), DropFiles.decode(block.array()));
    }

    @Test
    void aBlockCutShortGivesTheNamesBeforeTheDamage() {
        byte[] data = DropFiles.encode(List.of(path("first"), path("second")));
        byte[] cut = java.util.Arrays.copyOf(data, data.length - 6); // into the middle of the last name
        assertEquals(List.of(path("first")), DropFiles.decode(cut));
    }

    @Test
    void nonsenseGivesNothing() {
        assertEquals(List.of(), DropFiles.decode(null));
        assertEquals(List.of(), DropFiles.decode(new byte[5]));
        ByteBuffer wild = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        wild.putInt(9999).putInt(0).putInt(0).putInt(0).putInt(1); // names "start" past the end
        assertEquals(List.of(), DropFiles.decode(wild.array()));
        ByteBuffer inside = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        inside.putInt(4).putInt(0).putInt(0).putInt(0).putInt(1); // names "start" inside the header
        assertEquals(List.of(), DropFiles.decode(inside.array()));
    }
}
