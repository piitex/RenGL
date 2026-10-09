package me.piitex.engine.audio;

/**
 * Reads the Xing / Info tag an MP3 encoder puts in its first frame, and the LAME extension after it, to learn how much
 * silence the encoder added at each end. A decoder that trims it plays the file back at its true length, so a looped
 * track joins without a gap. Package-private.
 *
 * @param delay           samples of encoder delay at the start (0 when the tag has none)
 * @param padding         samples of padding at the end
 * @param frames          audio frames the tag says follow it, or -1
 * @param samplesPerFrame 1152 for MPEG-1, 576 for MPEG-2 and 2.5
 * @param hasDelay        whether {@code delay} and {@code padding} were in the file
 */
record Mp3Gapless(int delay, int padding, long frames, int samplesPerFrame, boolean hasDelay) {
    /**
     * Samples the decoder itself adds on top of the encoder's delay (the Layer III synthesis filter).
     */
    static final int DECODER_DELAY = 529;

    /**
     * Sample frames to drop from the start of the decoded output.
     */
    int skip() {
        return hasDelay ? delay + DECODER_DELAY : 0;
    }

    /**
     * The playable length in sample frames, or -1 when the tag does not give enough to know it.
     */
    long length() {
        if (!hasDelay || frames < 0) return -1;
        return Math.max(0, frames * samplesPerFrame - delay - padding);
    }

    /**
     * The tag in these bytes, or null when the first audio frame is an ordinary one. The frame holding the tag has no
     * audio, so a non-null result also means "skip the first frame".
     */
    static Mp3Gapless parse(byte[] b) {
        int pos = 0;
        if (b.length > 10 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
            int size = (b[6] & 0x7F) << 21 | (b[7] & 0x7F) << 14 | (b[8] & 0x7F) << 7 | (b[9] & 0x7F);
            pos = 10 + size + ((b[5] & 0x10) != 0 ? 10 : 0);
        }
        while (pos + 4 <= b.length && !((b[pos] & 0xFF) == 0xFF && (b[pos + 1] & 0xE0) == 0xE0)) pos++;
        if (pos + 4 > b.length) return null;

        int version = (b[pos + 1] >> 3) & 3;      // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5
        int layer = (b[pos + 1] >> 1) & 3;        // 1 = Layer III
        if (version == 1 || layer != 1) return null;
        boolean crc = (b[pos + 1] & 1) == 0;
        boolean mono = ((b[pos + 3] >> 6) & 3) == 3;
        int sideInfo = version == 3 ? (mono ? 17 : 32) : (mono ? 9 : 17);
        int tag = pos + 4 + (crc ? 2 : 0) + sideInfo;
        if (tag + 8 > b.length) return null;
        boolean xing = b[tag] == 'X' && b[tag + 1] == 'i' && b[tag + 2] == 'n' && b[tag + 3] == 'g';
        boolean info = b[tag] == 'I' && b[tag + 1] == 'n' && b[tag + 2] == 'f' && b[tag + 3] == 'o';
        if (!xing && !info) return null;

        int spf = version == 3 ? 1152 : 576;
        int flags = readInt(b, tag + 4);
        int at = tag + 8;
        long frames = -1;
        if ((flags & 1) != 0) {
            frames = readInt(b, at) & 0xFFFFFFFFL;
            at += 4;
        }
        if ((flags & 2) != 0) at += 4;
        if ((flags & 4) != 0) at += 100;
        if ((flags & 8) != 0) at += 4;

        // The encoder's name is 9 bytes, then 12 bytes of other fields, then delay and padding as two 12-bit numbers.
        if (at + 24 <= b.length && (startsWith(b, at, "LAME") || startsWith(b, at, "Lavc") || startsWith(b, at, "Lavf"))) {
            int delay = (b[at + 21] & 0xFF) << 4 | (b[at + 22] & 0xFF) >> 4;
            int padding = (b[at + 22] & 0x0F) << 8 | (b[at + 23] & 0xFF);
            if (delay < 4096) return new Mp3Gapless(delay, padding, frames, spf, true);
        }
        return new Mp3Gapless(0, 0, frames, spf, false);
    }

    private static int readInt(byte[] b, int at) {
        if (at + 4 > b.length) return 0;
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
    }

    private static boolean startsWith(byte[] b, int at, String text) {
        for (int i = 0; i < text.length(); i++) if (b[at + i] != text.charAt(i)) return false;
        return true;
    }
}
