package me.piitex.engine.video;

import java.util.ArrayDeque;

/**
 * Hands out pixel arrays and takes them back once a picture has been uploaded, so a 4K video does not allocate
 * 33 MB for every frame it decodes.
 */
final class Buffers {
    private static final int KEEP = 8;
    private final ArrayDeque<byte[]> free = new ArrayDeque<>();

    synchronized byte[] take(int size) {
        byte[] b;
        while ((b = free.poll()) != null) {
            if (b.length == size) return b;
        }
        return new byte[size];
    }

    synchronized void give(byte[] b) {
        if (free.size() < KEEP) free.add(b);
    }
}
