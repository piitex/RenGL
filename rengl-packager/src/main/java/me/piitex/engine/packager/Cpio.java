package me.piitex.engine.packager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The "newc" cpio format that an {@code .rpm} keeps its files in: a header, the name and the content for each, all on 4-byte boundaries. */
final class Cpio {
    static final int FILE = 0100000, DIRECTORY = 0040000, SYMLINK = 0120000;

    private Cpio() {
    }

    static final class Writer {
        private final OutputStream out;
        private long written;

        Writer(OutputStream out) {
            this.out = out;
        }

        /** Bytes written so far, before any compression the stream does. */
        long size() {
            return written;
        }

        /** A directory, or a symbolic link when {@code content} is its target. {@code number} identifies the entry (rpm uses its place in the file list, from 1). */
        void entry(String name, int number, int mode, long mtime, byte[] content) throws IOException {
            header(name, number, mode, mtime, content.length);
            write(content);
            pad(content.length);
        }

        void file(String name, int number, int mode, long mtime, Path source) throws IOException {
            long size = Files.size(source);
            header(name, number, mode, mtime, size);
            try (InputStream in = Files.newInputStream(source)) {
                written += in.transferTo(out);
            }
            pad(size);
        }

        void finish() throws IOException {
            header("TRAILER!!!", 0, 0, 0, 0);
            out.flush();
        }

        private void header(String name, int number, int mode, long mtime, long size) throws IOException {
            if (size > 0xFFFFFFFFL) throw new IOException(name + " is too big for a cpio archive (4 GB)");
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            StringBuilder h = new StringBuilder("070701");
            field(h, number);
            field(h, mode);
            field(h, 0);                         // uid
            field(h, 0);                         // gid
            field(h, (mode & DIRECTORY) != 0 && (mode & FILE) == 0 ? 2 : 1);   // links
            field(h, mtime);
            field(h, size);
            field(h, 0);                         // device
            field(h, 0);
            field(h, 0);                         // rdev
            field(h, 0);
            field(h, nameBytes.length + 1);
            field(h, 0);                         // checksum, unused in this format
            write(h.toString().getBytes(StandardCharsets.US_ASCII));
            write(nameBytes);
            write(new byte[] {0});
            pad(110 + nameBytes.length + 1);
        }

        private static void field(StringBuilder h, long value) {
            h.append(String.format("%08x", value));
        }

        private void write(byte[] bytes) throws IOException {
            out.write(bytes);
            written += bytes.length;
        }

        private void pad(long length) throws IOException {
            int extra = (int) ((4 - length % 4) % 4);
            if (extra > 0) write(new byte[extra]);
        }
    }
}
