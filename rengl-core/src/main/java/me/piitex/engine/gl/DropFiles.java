package me.piitex.engine.gl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The bytes of the Windows {@code CF_HDROP} clipboard format, the one Explorer puts on a drag of files and takes from
 * one: a {@code DROPFILES} header followed by the paths, each ended by a NUL, with one more NUL after the last. Pure
 * byte shuffling, so it works (and is tested) on every platform.
 */
final class DropFiles {
    /** Size of the {@code DROPFILES} header: the offset of the names, a point, and two flags. */
    static final int HEADER = 20;

    private DropFiles() {
    }

    /**
     * The format's bytes for {@code files}, as UTF-16 names (the flag that says so is set), which is what every
     * current program writes and reads.
     */
    static byte[] encode(List<Path> files) {
        List<byte[]> names = new ArrayList<>(files.size());
        int size = HEADER + 2; // the NUL that ends the list
        for (Path file : files) {
            byte[] name = file.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_16LE);
            names.add(name);
            size += name.length + 2;
        }
        ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(HEADER);        // pFiles: where the names start
        out.putInt(0).putInt(0);   // pt: the drop point, unused by a source
        out.putInt(0);             // fNC: the point is not in the non-client area
        out.putInt(1);             // fWide: the names are UTF-16
        for (byte[] name : names) {
            out.put(name).putShort((short) 0);
        }
        out.putShort((short) 0);
        return out.array();
    }

    /**
     * The paths in {@code data}, in order. Names this file system cannot hold are skipped, and a block that is cut
     * short or points outside itself gives whatever names came before the damage.
     */
    static List<Path> decode(byte[] data) {
        List<Path> paths = new ArrayList<>();
        if (data == null || data.length < HEADER) return paths;
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int start = in.getInt(0);
        boolean wide = in.getInt(16) != 0;
        if (start < HEADER || start > data.length) return paths;

        int unit = wide ? 2 : 1;
        Charset charset = wide ? StandardCharsets.UTF_16LE : Charset.defaultCharset();
        int at = start;
        while (at + unit <= data.length) {
            int end = at;
            while (end + unit <= data.length && !(wide ? in.getShort(end) == 0 : data[end] == 0)) end += unit;
            if (end == at) break;                 // an empty name ends the list
            if (end + unit > data.length) break;  // no terminator: cut short
            try {
                paths.add(Path.of(new String(data, at, end - at, charset)));
            } catch (InvalidPathException ignored) {
                // a name this file system cannot hold
            }
            at = end + unit;
        }
        return paths;
    }
}
