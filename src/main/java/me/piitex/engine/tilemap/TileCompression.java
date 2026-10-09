package me.piitex.engine.tilemap;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.zstd.Zstd;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * The ways Tiled compresses tile data and embedded images: zlib, gzip and zstd. Package-private.
 */
final class TileCompression {
    /** The most a zstd frame may say it holds: a map or image bigger than this is not one Tiled wrote. */
    private static final long MAX_ZSTD_SIZE = 1L << 30;

    private TileCompression() {
    }

    /**
     * {@code bytes} undone from {@code compression}: "" for none, "zlib", "gzip" or "zstd".
     *
     * @param where what the bytes are, for the message when they can't be read
     */
    static byte[] decompress(byte[] bytes, String compression, String where) {
        if (compression.isEmpty()) return bytes;
        if (compression.equals("zstd")) return zstd(bytes, where);
        try (InputStream in = switch (compression) {
            case "zlib" -> new InflaterInputStream(new ByteArrayInputStream(bytes));
            case "gzip" -> new GZIPInputStream(new ByteArrayInputStream(bytes));
            default -> throw new IllegalArgumentException(where + " is compressed in an unknown way: '" + compression + "'");
        }) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length * 4);
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException(where + ": data doesn't decompress as " + compression + ": " + e.getMessage(), e);
        }
    }

    /**
     * Compresses with zstd. For tests and tools that write maps; the engine itself only reads them.
     */
    static byte[] zstdCompress(byte[] bytes) {
        ByteBuffer src = MemoryUtil.memAlloc(bytes.length);
        ByteBuffer dst = MemoryUtil.memAlloc((int) Zstd.ZSTD_compressBound(bytes.length));
        try {
            src.put(bytes).flip();
            long size = Zstd.ZSTD_compress(dst, src, 3);
            if (Zstd.ZSTD_isError(size)) throw new IllegalStateException(Zstd.ZSTD_getErrorName(size));
            byte[] out = new byte[(int) size];
            dst.get(0, out);
            return out;
        } finally {
            MemoryUtil.memFree(src);
            MemoryUtil.memFree(dst);
        }
    }

    private static byte[] zstd(byte[] bytes, String where) {
        ByteBuffer src = null, dst = null;
        try {
            src = MemoryUtil.memAlloc(bytes.length);
            src.put(bytes).flip();
            long size = Zstd.ZSTD_getFrameContentSize(src);
            if (size < 0 || size > MAX_ZSTD_SIZE) {
                throw new IllegalArgumentException(where + ": zstd data that doesn't say how big it is, or is too big");
            }
            dst = MemoryUtil.memAlloc((int) size);
            long done = Zstd.ZSTD_decompress(dst, src);
            if (Zstd.ZSTD_isError(done)) {
                throw new IllegalArgumentException(where + ": data doesn't decompress as zstd: " + Zstd.ZSTD_getErrorName(done));
            }
            byte[] out = new byte[(int) done];
            dst.get(0, out);
            return out;
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            throw new IllegalArgumentException(where + " is compressed with zstd, and the zstd library isn't available here: in the map's"
                    + " properties in Tiled, set Tile Layer Format to CSV, or to Base64 uncompressed, zlib or gzip", e);
        } finally {
            if (src != null) MemoryUtil.memFree(src);
            if (dst != null) MemoryUtil.memFree(dst);
        }
    }
}
