package me.piitex.engine.audio;

import java.util.Arrays;

/**
 * A FLAC decoder. FLAC is a royalty-free lossless format, decoded here from the published stream specification
 * (constant, verbatim, fixed and LPC subframes with Rice-coded residuals). Bit depths 8 to 24 are converted to
 * 16-bit; mono and stereo only.
 * <p>
 * Frame positions are remembered as they are decoded, so a seek (used for loop points) only has to decode
 * forward from the nearest frame it has already passed.
 */
final class FlacStream implements PcmStream {
    private final String name;
    private final byte[] data;

    private int channels, rate, bitsPerSample;
    private long totalSamples;
    private int audioStart;

    private int pos;         // next byte to load into the bit cache
    private long cache;      // bit cache, the low cacheBits bits are unread
    private int cacheBits;

    private long[] indexSample = new long[256]; // start sample of each decoded frame
    private int[] indexOffset = new int[256];   // byte offset of each decoded frame
    private int indexCount;

    private long nextSample;   // start sample of the next frame to decode
    private long frameStart;   // start sample of the frame held in out
    private short[] out = new short[0];
    private int outLen, outPos;
    private int[][] channelData = new int[2][0];
    private int[] residual = new int[0];
    private boolean ended;

    FlacStream(String name, byte[] data) {
        this.name = name;
        this.data = data;
        readHeader();
    }

    // ---- bit reading ------------------------------------------------------------------------------------------

    private long readBits(int n) {
        if (n == 0) return 0;
        while (cacheBits < n) {
            cache = (cache << 8) | (pos < data.length ? data[pos] & 0xFF : 0);
            pos++;
            cacheBits += 8;
        }
        long v = (cache >>> (cacheBits - n)) & ((1L << n) - 1);
        cacheBits -= n;
        return v;
    }

    private int readInt(int n) {
        return (int) readBits(n);
    }

    private int readSigned(int n) {
        if (n == 0) return 0;
        return (int) (readBits(n) << (64 - n) >> (64 - n));
    }

    private void alignToByte() {
        cacheBits -= cacheBits & 7;
    }

    private int bytePosition() {
        return pos - cacheBits / 8;
    }

    private void setBytePosition(int offset) {
        pos = offset;
        cache = 0;
        cacheBits = 0;
    }

    private boolean atEnd() {
        return bytePosition() >= data.length;
    }

    // ---- stream header ----------------------------------------------------------------------------------------

    private void readHeader() {
        if (data.length > 10 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') { // an ID3v2 tag in front of the stream
            int size = ((data[6] & 0x7F) << 21) | ((data[7] & 0x7F) << 14) | ((data[8] & 0x7F) << 7) | (data[9] & 0x7F);
            setBytePosition(10 + size);
        }
        if (readInt(32) != 0x664C6143) throw new IllegalArgumentException("Not a FLAC file: " + name); // "fLaC"
        boolean last;
        boolean haveInfo = false;
        do {
            last = readInt(1) == 1;
            int type = readInt(7);
            int length = readInt(24);
            int blockStart = bytePosition();
            if (type == 0) {
                readInt(16);
                readInt(16);      // min and max block size
                readInt(24);
                readInt(24);      // min and max frame size
                rate = readInt(20);
                channels = readInt(3) + 1;
                bitsPerSample = readInt(5) + 1;
                totalSamples = readBits(36);
                haveInfo = true;
            }
            setBytePosition(blockStart + length);
        } while (!last && !atEnd());
        if (!haveInfo) throw new IllegalArgumentException("FLAC file has no stream info: " + name);
        if (channels > Downmix.MAX_CHANNELS) throw AudioDecoder.tooManyChannels(name, channels);
        if (bitsPerSample > 24)
            throw new IllegalArgumentException(name + " is " + bitsPerSample + "-bit FLAC; up to 24-bit is supported");
        audioStart = bytePosition();
        if (!decodeFrame()) throw new IllegalArgumentException("FLAC file has no audio frames: " + name);
        outPos = 0;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public int sampleRate() {
        return rate;
    }

    @Override
    public long totalFrames() {
        return totalSamples > 0 ? totalSamples : -1;
    }

    // ---- frames -----------------------------------------------------------------------------------------------

    /**
     * Decodes the next frame into {@code out}. Returns false at the end of the stream or at damaged data.
     */
    private boolean decodeFrame() {
        if (ended) return false;
        int start = bytePosition();
        if (start >= data.length - 4 || !decodeFrameAt(start)) {
            ended = true;
            outLen = outPos = 0;
            return false;
        }
        return true;
    }

    private boolean decodeFrameAt(int start) {
        if (readInt(14) != 0x3FFE) return false;
        readInt(1);
        readInt(1); // reserved, blocking strategy
        int blockSizeCode = readInt(4);
        int rateCode = readInt(4);
        int assignment = readInt(4);
        int sizeCode = readInt(3);
        readInt(1);
        readUtf8();
        int blockSize;
        if (blockSizeCode == 1) blockSize = 192;
        else if (blockSizeCode >= 2 && blockSizeCode <= 5) blockSize = 576 << (blockSizeCode - 2);
        else if (blockSizeCode == 6) blockSize = readInt(8) + 1;
        else if (blockSizeCode == 7) blockSize = readInt(16) + 1;
        else if (blockSizeCode >= 8) blockSize = 256 << (blockSizeCode - 8);
        else return false;
        if (rateCode == 12) readInt(8);
        else if (rateCode == 13 || rateCode == 14) readInt(16);
        readInt(8); // header CRC, not verified

        int bps = switch (sizeCode) {
            case 0 -> bitsPerSample;
            case 1 -> 8;
            case 2 -> 12;
            case 4 -> 16;
            case 5 -> 20;
            case 6 -> 24;
            default -> -1;
        };
        int frameChannels = assignment < 8 ? assignment + 1 : 2;
        if (bps < 0 || frameChannels != channels || blockSize > 65536) return false;

        for (int c = 0; c < frameChannels; c++) {
            // The side channel of a stereo pair carries one extra bit.
            boolean side = (assignment == 8 && c == 1) || (assignment == 9 && c == 0) || (assignment == 10 && c == 1);
            if (channelData[c].length < blockSize) channelData[c] = new int[blockSize];
            if (!decodeSubframe(channelData[c], blockSize, side ? bps + 1 : bps)) return false;
        }
        alignToByte();
        readInt(16); // frame CRC, not verified
        if (bytePosition() > data.length) return false;

        int[] a = channelData[0], b = channelData[1];
        if (assignment == 8) {
            for (int i = 0; i < blockSize; i++) b[i] = a[i] - b[i];
        } else if (assignment == 9) {
            for (int i = 0; i < blockSize; i++) a[i] = a[i] + b[i];
        } else if (assignment == 10) {
            for (int i = 0; i < blockSize; i++) {
                int side = b[i];
                int mid = (a[i] << 1) | (side & 1);
                a[i] = (mid + side) >> 1;
                b[i] = (mid - side) >> 1;
            }
        }

        int total = blockSize * channels;
        if (out.length < total) out = new short[total];
        int shift = bps - 16;
        for (int c = 0; c < channels; c++) {
            int[] src = channelData[c];
            for (int i = 0; i < blockSize; i++) {
                int v = shift > 0 ? src[i] >> shift : src[i] << -shift;
                out[i * channels + c] = (short) Math.max(-32768, Math.min(32767, v));
            }
        }
        outLen = total;
        outPos = 0;
        frameStart = nextSample;
        nextSample += blockSize;
        remember(frameStart, start);
        return true;
    }

    private void remember(long sample, int offset) {
        if (indexCount > 0 && indexSample[indexCount - 1] >= sample) return; // already known from an earlier pass
        if (indexCount == indexSample.length) {
            indexSample = Arrays.copyOf(indexSample, indexCount * 2);
            indexOffset = Arrays.copyOf(indexOffset, indexCount * 2);
        }
        indexSample[indexCount] = sample;
        indexOffset[indexCount] = offset;
        indexCount++;
    }

    /**
     * A frame or sample number in FLAC's UTF-8 style variable-length coding. Only skipped, never needed.
     */
    private void readUtf8() {
        int first = readInt(8);
        int extra = first < 0x80 ? 0 : first < 0xE0 ? 1 : first < 0xF0 ? 2 : first < 0xF8 ? 3 : first < 0xFC ? 4 : first < 0xFE ? 5 : 6;
        for (int i = 0; i < extra; i++) readInt(8);
    }

    private boolean decodeSubframe(int[] samples, int blockSize, int bps) {
        readInt(1);
        int type = readInt(6);
        int wasted = 0;
        if (readInt(1) == 1) {
            wasted = 1;
            while (readInt(1) == 0) wasted++;
        }
        int depth = bps - wasted;
        if (depth <= 0 || depth > 25) return false;

        if (type == 0) {
            Arrays.fill(samples, 0, blockSize, readSigned(depth));
        } else if (type == 1) {
            for (int i = 0; i < blockSize; i++) samples[i] = readSigned(depth);
        } else if (type >= 8 && type <= 12) {
            int order = type - 8;
            for (int i = 0; i < order; i++) samples[i] = readSigned(depth);
            if (!readResidual(samples, blockSize, order)) return false;
            restoreFixed(samples, blockSize, order);
        } else if (type >= 32) {
            int order = type - 31;
            for (int i = 0; i < order; i++) samples[i] = readSigned(depth);
            int precision = readInt(4) + 1;
            int shift = readSigned(5);
            int[] coefs = new int[order];
            for (int i = 0; i < order; i++) coefs[i] = readSigned(precision);
            if (!readResidual(samples, blockSize, order)) return false;
            restoreLpc(samples, blockSize, order, coefs, shift);
        } else {
            return false;
        }
        if (wasted > 0) {
            for (int i = 0; i < blockSize; i++) samples[i] <<= wasted;
        }
        return true;
    }

    /**
     * Reads the Rice-coded residual into {@code samples[order..]}; prediction is added afterwards.
     */
    private boolean readResidual(int[] samples, int blockSize, int order) {
        int method = readInt(2);
        if (method > 1) return false;
        int paramBits = method == 0 ? 4 : 5;
        int escape = method == 0 ? 15 : 31;
        int partitionOrder = readInt(4);
        int partitions = 1 << partitionOrder;
        int perPartition = blockSize >> partitionOrder;
        if (perPartition < order) return false;
        int i = order;
        for (int p = 0; p < partitions; p++) {
            int count = p == 0 ? perPartition - order : perPartition;
            int param = readInt(paramBits);
            if (param == escape) {
                int bits = readInt(5);
                for (int k = 0; k < count; k++) samples[i++] = readSigned(bits);
            } else {
                for (int k = 0; k < count; k++) {
                    int q = 0;
                    while (readInt(1) == 0) {
                        if (++q > 1 << 20) return false; // no terminating bit: damaged data
                    }
                    int v = (q << param) | readInt(param);
                    samples[i++] = (v >>> 1) ^ -(v & 1);
                }
            }
        }
        return true;
    }

    private static void restoreFixed(int[] s, int n, int order) {
        switch (order) {
            case 1 -> {
                for (int i = 1; i < n; i++) s[i] += s[i - 1];
            }
            case 2 -> {
                for (int i = 2; i < n; i++) s[i] += 2 * s[i - 1] - s[i - 2];
            }
            case 3 -> {
                for (int i = 3; i < n; i++) s[i] += 3 * s[i - 1] - 3 * s[i - 2] + s[i - 3];
            }
            case 4 -> {
                for (int i = 4; i < n; i++) s[i] += 4 * s[i - 1] - 6 * s[i - 2] + 4 * s[i - 3] - s[i - 4];
            }
            default -> {
            }
        }
    }

    private static void restoreLpc(int[] s, int n, int order, int[] coefs, int shift) {
        for (int i = order; i < n; i++) {
            long sum = 0;
            for (int j = 0; j < order; j++) sum += (long) coefs[j] * s[i - 1 - j];
            s[i] += (int) (sum >> shift);
        }
    }

    // ---- PcmStream --------------------------------------------------------------------------------------------

    @Override
    public int read(short[] dst, int off, int len) {
        int n = 0;
        while (n < len) {
            if (outPos >= outLen && !decodeFrame()) break;
            int take = Math.min(len - n, outLen - outPos);
            System.arraycopy(out, outPos, dst, off + n, take);
            outPos += take;
            n += take;
        }
        return n;
    }

    @Override
    public void seek(long frame) {
        long target = Math.max(0, frame);
        // Start from the last decoded frame at or before the target, or the beginning of the audio.
        int from = audioStart;
        long fromSample = 0;
        int lo = 0, hi = indexCount - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (indexSample[mid] <= target) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (found >= 0) {
            from = indexOffset[found];
            fromSample = indexSample[found];
        }
        setBytePosition(from);
        nextSample = fromSample;
        ended = false;
        outLen = outPos = 0;
        while (decodeFrame()) {
            if (target < nextSample) {
                outPos = (int) Math.max(0, target - frameStart) * channels;
                return;
            }
        }
    }

    @Override
    public void close() {
        ended = true;
    }
}
