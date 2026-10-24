package me.piitex.engine.packager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Wraps one PNG in an {@code .icns} file, which macOS accepts with the PNG inside as it is. */
final class Icns {
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private Icns() {
    }

    static Path fromPng(Path png) throws IOException {
        byte[] data = Files.readAllBytes(png);
        if (data.length < 24 || !java.util.Arrays.equals(data, 0, 8, PNG_SIGNATURE, 0, 8)) {
            throw new IOException(png + " is not a PNG file");
        }
        ByteBuffer header = ByteBuffer.wrap(data, 16, 8); // the IHDR chunk: width, then height
        int width = header.getInt();
        int height = header.getInt();
        String type = switch (width) {
            case 1024 -> "ic10";
            case 512 -> "ic09";
            case 256 -> "ic08";
            case 128 -> "ic07";
            default -> throw new IOException(png + " is " + width + "x" + height + " px; a macOS icon needs a square 128, 256, 512 or 1024 px PNG");
        };
        if (width != height) throw new IOException(png + " is " + width + "x" + height + " px; a macOS icon has to be square");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("icns".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(ByteBuffer.allocate(4).putInt(8 + 8 + data.length).array());
        out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(ByteBuffer.allocate(4).putInt(8 + data.length).array());
        out.writeBytes(data);

        Path icns = Files.createTempFile("rengl-icon-", ".icns");
        icns.toFile().deleteOnExit();
        Files.write(icns, out.toByteArray());
        return icns;
    }
}
