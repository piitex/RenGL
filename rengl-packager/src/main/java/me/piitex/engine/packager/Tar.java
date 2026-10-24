package me.piitex.engine.packager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Just enough of tar for the packager: write an image with Unix permissions (a Windows host cannot store them on disk),
 * and read the {@code jmods} out of a downloaded JDK.
 */
final class Tar {
    private static final int BLOCK = 512;

    private Tar() {
    }

    interface Visitor {
        void visit(String name, InputStream content) throws IOException;
    }

    /** Writes entries to a tar stream. Names longer than 100 bytes go in a pax header. */
    static final class Writer implements AutoCloseable {
        private final OutputStream out;

        Writer(OutputStream out) {
            this.out = out;
        }

        void directory(String name, int mode) throws IOException {
            header(name.endsWith("/") ? name : name + "/", 0, mode, '5');
        }

        void file(String name, Path source, int mode) throws IOException {
            long size = Files.size(source);
            header(name, size, mode, '0');
            try (InputStream in = Files.newInputStream(source)) {
                in.transferTo(out);
            }
            pad(size);
        }

        /** A symbolic link at {@code name} pointing to {@code target}. */
        void symlink(String name, String target, int mode) throws IOException {
            byte[] h = block(name, 0, mode, '2');
            byte[] targetBytes = target.getBytes(StandardCharsets.UTF_8);
            if (targetBytes.length > 100) throw new IOException("Link target too long: " + target);
            System.arraycopy(targetBytes, 0, h, 157, targetBytes.length);
            seal(h);
            out.write(h);
        }

        void file(String name, byte[] content, int mode) throws IOException {
            header(name, content.length, mode, '0');
            out.write(content);
            pad(content.length);
        }

        private void header(String name, long size, int mode, char type) throws IOException {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            if (nameBytes.length > 100) {
                byte[] record = paxRecord("path", name);
                writeHeader("PaxHeader", record.length, 0644, 'x');
                out.write(record);
                pad(record.length);
            }
            writeHeader(name, size, mode, type);
        }

        private void writeHeader(String name, long size, int mode, char type) throws IOException {
            byte[] h = block(name, size, mode, type);
            seal(h);
            out.write(h);
        }

        /** A header block, not yet checksummed. */
        private static byte[] block(String name, long size, int mode, char type) {
            byte[] h = new byte[BLOCK];
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(nameBytes, 0, h, 0, Math.min(100, nameBytes.length));
            octal(h, 100, 8, mode);
            octal(h, 108, 8, 0);
            octal(h, 116, 8, 0);
            octal(h, 124, 12, size);
            octal(h, 136, 12, System.currentTimeMillis() / 1000);
            java.util.Arrays.fill(h, 148, 156, (byte) ' ');
            h[156] = (byte) type;
            System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
            h[263] = '0';
            h[264] = '0';
            return h;
        }

        private static void seal(byte[] h) {
            java.util.Arrays.fill(h, 148, 156, (byte) ' ');
            long sum = 0;
            for (byte b : h) sum += b & 0xFF;
            String checksum = String.format("%06o\0 ", sum);
            System.arraycopy(checksum.getBytes(StandardCharsets.US_ASCII), 0, h, 148, 8);
        }

        private static byte[] paxRecord(String key, String value) {
            byte[] body = (" " + key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8);
            int length = body.length + 1;
            while (Integer.toString(length).length() + body.length != length) length = Integer.toString(length).length() + body.length;
            ByteArrayOutputStream record = new ByteArrayOutputStream();
            record.writeBytes(Integer.toString(length).getBytes(StandardCharsets.US_ASCII));
            record.writeBytes(body);
            return record.toByteArray();
        }

        private static void octal(byte[] h, int offset, int length, long value) {
            String s = String.format("%0" + (length - 1) + "o", value);
            System.arraycopy(s.getBytes(StandardCharsets.US_ASCII), 0, h, offset, length - 1);
        }

        private void pad(long size) throws IOException {
            int rest = (int) (size % BLOCK);
            if (rest != 0) out.write(new byte[BLOCK - rest]);
        }

        @Override
        public void close() throws IOException {
            out.write(new byte[BLOCK * 2]);
            out.close();
        }
    }

    /** Walks a tar stream, handing each regular file to {@code visitor} (name, content). The visitor must read or leave the stream alone. */
    static void read(InputStream in, Visitor visitor) throws IOException {
        String longName = null;
        byte[] h = new byte[BLOCK];
        while (in.readNBytes(h, 0, BLOCK) == BLOCK) {
            if (h[0] == 0) break;
            String name = string(h, 0, 100);
            long size = parseSize(h);
            char type = (char) h[156];
            String prefix = string(h, 345, 155);
            if (!prefix.isEmpty() && new String(h, 257, 5, StandardCharsets.US_ASCII).equals("ustar")) name = prefix + "/" + name;

            if (type == 'L' || type == 'x') {
                String text = new String(in.readNBytes((int) size), StandardCharsets.UTF_8);
                skip(in, padding(size));
                if (type == 'L') {
                    longName = text.strip().replace("\0", "");
                } else {
                    for (String line : text.split("\n")) {
                        int space = line.indexOf(' ');
                        if (space > 0 && line.startsWith("path=", space + 1)) longName = line.substring(space + 6);
                    }
                }
                continue;
            }
            if (longName != null) {
                name = longName;
                longName = null;
            }
            if (type == '0' || type == 0) {
                CountingStream content = new CountingStream(in, size);
                visitor.visit(name, content);
                content.drain();
            } else {
                skip(in, size);
            }
            skip(in, padding(size));
        }
    }

    private static long padding(long size) {
        return (BLOCK - size % BLOCK) % BLOCK;
    }

    private static String string(byte[] h, int offset, int max) {
        int end = offset;
        while (end < offset + max && h[end] != 0) end++;
        return new String(h, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long parseSize(byte[] h) {
        if ((h[124] & 0x80) != 0) { // base-256, for entries of 8 GB and up
            long v = h[124] & 0x7F;
            for (int i = 125; i < 136; i++) v = (v << 8) | (h[i] & 0xFF);
            return v;
        }
        String s = string(h, 124, 12).strip();
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }

    private static void skip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                if (in.read() < 0) throw new IOException("Unexpected end of archive");
                skipped = 1;
            }
            n -= skipped;
        }
    }

    /** One entry's bytes: reads stop at the entry's size. */
    private static final class CountingStream extends InputStream {
        private final InputStream in;
        private long remaining;

        CountingStream(InputStream in, long size) {
            this.in = in;
            this.remaining = size;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        void drain() throws IOException {
            Tar.skip(in, remaining);
            remaining = 0;
        }
    }
}
