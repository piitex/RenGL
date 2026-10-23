package me.piitex.engine.video;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * AVI, including the OpenDML extension that lets a file grow past 4 GB (extra {@code RIFF AVIX} segments, indexed
 * by {@code indx} / {@code ix##} chunks). Packets are read one chunk at a time straight from disk. Seeking uses the
 * OpenDML index, or the old {@code idx1} index, or failing both a one-off scan of the chunk headers.
 */
final class AviDemuxer implements Demuxer {
    private static final Logger log = LogManager.getLogger("Video");
    private static final Set<String> MJPEG = Set.of("MJPG", "mjpg", "JPEG", "jpeg", "dmb1", "AVRn", "MJPA");

    private static final class Stream {
        int index;
        boolean video, audio;
        String type, handler;
        long scale = 1, rate = 1, length;
        // video format
        int width, height, bitCount;
        String compression = "\0\0\0\0";
        // audio format
        int tag, channels, sampleRate, blockAlign, bits;
        // OpenDML super index: payload position, size
        long indxPos = -1, indxSize;
        final Track track = new Track();
    }

    /** Where every chunk of one stream is, for seeking. */
    private static final class Track {
        long[] pos = new long[256]; // position of the chunk header
        boolean[] key = new boolean[256];
        long[] bytesBefore = new long[256];
        int count;
        long bytes;

        void add(long headerPos, int size, boolean isKey) {
            if (count == pos.length) {
                int n = count * 2;
                pos = Arrays.copyOf(pos, n);
                key = Arrays.copyOf(key, n);
                bytesBefore = Arrays.copyOf(bytesBefore, n);
            }
            pos[count] = headerPos;
            key[count] = isKey;
            bytesBefore[count] = bytes;
            bytes += size;
            count++;
        }
    }

    private final ByteSource src;
    private final List<Stream> streams = new ArrayList<>();
    private Stream video, audio;
    private long moviPos = -1, moviEnd = -1;
    private double dmlFrames = -1;
    private boolean indexed;
    private MediaInfo info;

    // read state
    private long videoFrames;  // video chunks passed so far
    private long audioBytes;   // audio bytes passed so far
    private int curStream;
    private int curSize;
    private long curPos;

    static boolean matches(byte[] head) {
        return head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'A' && head[9] == 'V' && head[10] == 'I' && head[11] == ' ';
    }

    AviDemuxer(File file) throws IOException {
        src = new ByteSource(file);
        try {
            parseHeaders();
            pickStreams();
            if (video == null) throw new IOException("No supported video track in " + file.getName());
            buildInfo();
            src.seek(moviPos + 4);
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    // ------------------------------------------------------------------ headers

    private void parseHeaders() throws IOException {
        src.seek(12);
        while (src.position() + 12 <= src.size()) {
            long at = src.position();
            String id = src.fourcc();
            long size = src.u32();
            if (id.equals("LIST")) {
                String type = src.fourcc();
                long end = at + 8 + size;
                if (type.equals("hdrl")) {
                    parseHdrl(end);
                    src.seek(end + (size & 1));
                } else if (type.equals("movi")) {
                    moviPos = at + 8;
                    moviEnd = end + (size & 1);
                    return;
                } else {
                    src.seek(end + (size & 1));
                }
            } else {
                src.seek(at + 8 + size + (size & 1));
            }
        }
        throw new IOException("Not a playable AVI: no 'movi' list");
    }

    private void parseHdrl(long end) throws IOException {
        while (src.position() + 8 <= end) {
            long at = src.position();
            String id = src.fourcc();
            long size = src.u32();
            long next = at + 8 + size + (size & 1);
            if (id.equals("LIST")) {
                String type = src.fourcc();
                if (type.equals("strl")) parseStrl(at + 8 + size);
                else if (type.equals("odml")) parseOdml(at + 8 + size);
            }
            src.seek(next);
        }
    }

    private void parseOdml(long end) throws IOException {
        while (src.position() + 8 <= end) {
            long at = src.position();
            String id = src.fourcc();
            long size = src.u32();
            if (id.equals("dmlh") && size >= 4) dmlFrames = src.u32();
            src.seek(at + 8 + size + (size & 1));
        }
    }

    private void parseStrl(long end) throws IOException {
        Stream s = new Stream();
        s.index = streams.size();
        while (src.position() + 8 <= end) {
            long at = src.position();
            String id = src.fourcc();
            long size = src.u32();
            long next = at + 8 + size + (size & 1);
            switch (id) {
                case "strh" -> {
                    s.type = src.fourcc();
                    s.handler = src.fourcc();
                    src.skip(4 + 2 + 2 + 4); // flags, priority, language, initial frames
                    s.scale = Math.max(1, src.u32());
                    s.rate = Math.max(1, src.u32());
                    src.skip(4); // start
                    s.length = src.u32();
                    s.video = s.type.equals("vids");
                    s.audio = s.type.equals("auds");
                }
                case "strf" -> {
                    if (s.video) {
                        src.skip(4); // biSize
                        s.width = src.s32();
                        s.height = src.s32();
                        src.skip(2);
                        s.bitCount = src.u16();
                        s.compression = src.fourcc();
                    } else if (s.audio) {
                        s.tag = src.u16();
                        s.channels = src.u16();
                        s.sampleRate = (int) src.u32();
                        src.skip(4);
                        s.blockAlign = src.u16();
                        s.bits = src.u16();
                        if (s.tag == 0xFFFE && size >= 40) { // extensible: the real format is in the sub-format GUID
                            src.skip(2 + 2 + 2 + 4);
                            s.tag = src.u16();
                        }
                    }
                }
                case "indx" -> {
                    s.indxPos = src.position();
                    s.indxSize = size;
                }
                default -> {
                }
            }
            src.seek(next);
        }
        streams.add(s);
    }

    private void pickStreams() {
        for (Stream s : streams) {
            if (video == null && s.video && supportsVideo(s)) video = s;
            if (audio == null && s.audio && supportsAudio(s)) audio = s;
        }
        if (video == null) {
            for (Stream s : streams) {
                if (s.video) throw new UnsupportedOperationException("AVI video codec '" + codecName(s)
                        + "' is not supported (supported: Motion JPEG and uncompressed RGB)");
            }
        }
        for (Stream s : streams) {
            if (s.audio && s != audio && audio == null) {
                log.warn("AVI audio format tag 0x{} is not supported (uncompressed PCM only); playing without sound.",
                        Integer.toHexString(s.tag));
                break;
            }
        }
    }

    private static String codecName(Stream s) {
        return isRaw(s) && s.handler != null ? s.handler : s.compression;
    }

    private static boolean supportsVideo(Stream s) {
        if (s.width <= 0 || s.height == 0) return false;
        boolean raw = isRaw(s);
        if (MJPEG.contains(s.compression) || MJPEG.contains(s.handler)) return true;
        return raw && RawVideoDecoder.supports(s.bitCount);
    }

    private static boolean isRaw(Stream s) {
        return s.compression.equals("\0\0\0\0") || s.compression.equals("DIB ") || s.compression.equals("RGB ");
    }

    private static boolean supportsAudio(Stream s) {
        boolean pcm = s.tag == 1, floating = s.tag == 3;
        return (pcm || floating) && s.channels > 0 && s.sampleRate > 0 && PcmAudioDecoder.supports(s.bits, floating);
    }

    private void buildInfo() {
        double fps = video.rate / (double) video.scale;
        double frames = dmlFrames > 0 ? dmlFrames : video.length;
        double duration = frames > 0 ? frames / fps : 0;
        boolean mjpeg = MJPEG.contains(video.compression) || MJPEG.contains(video.handler);
        info = new MediaInfo(video.width, Math.abs(video.height), fps, duration, mjpeg ? "MJPEG" : "Uncompressed",
                audio == null ? 0 : Math.min(audio.channels, 2), audio == null ? 0 : audio.sampleRate,
                audio == null ? null : "PCM", "AVI");
    }

    // ------------------------------------------------------------------ Demuxer

    @Override
    public MediaInfo info() {
        return info;
    }

    @Override
    public int videoStream() {
        return video.index;
    }

    @Override
    public int audioStream() {
        return audio == null ? -1 : audio.index;
    }

    @Override
    public FrameDecoder videoDecoder() {
        boolean mjpeg = MJPEG.contains(video.compression) || MJPEG.contains(video.handler);
        return mjpeg ? new MjpegDecoder() : new RawVideoDecoder(video.width, Math.abs(video.height), video.bitCount, video.height < 0);
    }

    @Override
    public AudioTrackDecoder audioDecoder() {
        return audio == null ? null : new PcmAudioDecoder(audio.channels, audio.sampleRate, audio.bits, audio.tag == 3);
    }

    @Override
    public Packet next() throws IOException {
        while (advance()) {
            int size = curSize;
            if (curStream == video.index) {
                long n = videoFrames++;
                if (size == 0) continue; // a dropped frame: the previous picture stays up
                return new Packet(curStream, n * video.scale / (double) video.rate, true, readPayload(size));
            }
            if (audio != null && curStream == audio.index) {
                double pts = audioBytes / (double) ((long) audio.sampleRate * audio.blockAlign);
                audioBytes += size;
                if (size == 0) continue;
                return new Packet(curStream, pts, true, readPayload(size));
            }
            src.seek(curPos + 8 + size + (size & 1));
        }
        return null;
    }

    private byte[] readPayload(int size) throws IOException {
        byte[] data = src.bytes(size);
        src.seek(curPos + 8 + size + (size & 1));
        return data;
    }

    /**
     * Moves to the next stream chunk, stepping into LIST and RIFF segments (their contents follow their header)
     * and skipping index and padding chunks. Leaves the source at the chunk's payload.
     */
    private boolean advance() throws IOException {
        while (true) {
            long p = src.position();
            if (p + 8 > src.size()) return false;
            String id = src.fourcc();
            long size = src.u32();
            if (id.equals("RIFF") || id.equals("LIST")) {
                src.skip(4);
                continue;
            }
            long end = p + 8 + size;
            int stream = streamOf(id);
            if (stream < 0) {
                if (end > src.size()) return false;
                src.seek(end + (size & 1));
                continue;
            }
            if (end > src.size() || size > Integer.MAX_VALUE) return false; // truncated file
            curStream = stream;
            curSize = (int) size;
            curPos = p;
            return true;
        }
    }

    /** The stream number of a data chunk id like "00dc", or -1 for anything else. */
    private static int streamOf(String id) {
        char a = id.charAt(0), b = id.charAt(1), c = id.charAt(2), d = id.charAt(3);
        if (a < '0' || a > '9' || b < '0' || b > '9') return -1;
        boolean data = c == 'd' && (d == 'c' || d == 'b') || c == 'w' && d == 'b';
        return data ? (a - '0') * 10 + (b - '0') : -1;
    }

    @Override
    public void seek(double seconds) throws IOException {
        if (!indexed) buildIndex();
        Track t = video.track;
        if (t.count == 0) {
            reposition(moviPos + 4, 0, 0);
            return;
        }
        double fps = video.rate / (double) video.scale;
        int frame = (int) Math.clamp((long) Math.floor(seconds * fps + 1e-6), 0, t.count - 1);
        while (frame > 0 && !t.key[frame]) frame--;
        long pos = t.pos[frame];
        long audioAt = 0;
        if (audio != null) {
            Track a = audio.track;
            int lo = 0, hi = a.count; // first audio chunk at or after pos
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (a.pos[mid] < pos) lo = mid + 1;
                else hi = mid;
            }
            audioAt = lo < a.count ? a.bytesBefore[lo] : a.bytes;
        }
        reposition(pos, frame, audioAt);
    }

    private void reposition(long pos, long frame, long audioByteCount) {
        src.seek(pos);
        videoFrames = frame;
        audioBytes = audioByteCount;
    }

    // ------------------------------------------------------------------ index

    private void buildIndex() throws IOException {
        indexed = true;
        boolean ok = false;
        try {
            ok = readOpenDmlIndex() || readIdx1();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the AVI index ({}); scanning the file instead.", e.toString());
        }
        if (!ok) scan();
        log.debug("AVI index: {} video chunks", video.track.count);
    }

    private boolean readOpenDmlIndex() throws IOException {
        boolean any = false;
        for (Stream s : new Stream[]{video, audio}) {
            if (s == null) continue;
            s.track.count = 0;
            s.track.bytes = 0;
            if (s.indxPos < 0) return false;
            src.seek(s.indxPos);
            src.skip(2);
            src.skip(1);
            int type = src.u8();
            long entries = src.u32();
            src.skip(4 + 12);
            if (type == 1) {
                readStandardIndex(s, s.indxPos);
                any = true;
            } else if (type == 0) {
                long[] offsets = new long[(int) entries];
                for (int i = 0; i < entries; i++) {
                    offsets[i] = src.s64();
                    src.skip(8); // size, duration
                }
                for (long offset : offsets) {
                    src.seek(offset + 8); // past the ix chunk header
                    readStandardIndex(s, offset + 8);
                }
                any = true;
            }
        }
        return any && video.track.count > 0;
    }

    private void readStandardIndex(Stream s, long payload) throws IOException {
        src.seek(payload);
        src.skip(2 + 1);
        int type = src.u8();
        if (type != 1) return;
        long n = src.u32();
        src.skip(4);
        long base = src.s64();
        src.skip(4);
        byte[] raw = new byte[(int) Math.min(n, 4096) * 8];
        long done = 0;
        while (done < n) {
            int batch = (int) Math.min(4096, n - done);
            src.readFully(raw, 0, batch * 8);
            var b = java.nio.ByteBuffer.wrap(raw, 0, batch * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < batch; i++) {
                long offset = b.getInt() & 0xFFFFFFFFL;
                int size = b.getInt();
                s.track.add(base + offset - 8, size & 0x7FFFFFFF, size >= 0);
            }
            done += batch;
        }
    }

    private boolean readIdx1() throws IOException {
        src.seek(moviEnd);
        while (src.position() + 8 <= src.size()) {
            long at = src.position();
            String id = src.fourcc();
            long size = src.u32();
            if (!id.equals("idx1")) {
                src.seek(at + 8 + size + (size & 1));
                continue;
            }
            for (Stream s : new Stream[]{video, audio}) {
                if (s != null) {
                    s.track.count = 0;
                    s.track.bytes = 0;
                }
            }
            long n = size / 16;
            boolean relative = true, checked = false;
            byte[] raw = new byte[4096 * 16];
            long done = 0;
            while (done < n) {
                int batch = (int) Math.min(4096, n - done);
                src.seek(at + 8 + done * 16);
                src.readFully(raw, 0, batch * 16);
                var b = java.nio.ByteBuffer.wrap(raw, 0, batch * 16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < batch; i++) {
                    byte c0 = b.get(i * 16), c1 = b.get(i * 16 + 1), c2 = b.get(i * 16 + 2), c3 = b.get(i * 16 + 3);
                    b.position(i * 16 + 4);
                    int flags = b.getInt();
                    long offset = b.getInt() & 0xFFFFFFFFL;
                    int chunkSize = b.getInt();
                    int stream = streamOf("" + (char) c0 + (char) c1 + (char) c2 + (char) c3);
                    if (stream < 0) continue;
                    if (!checked) { // offsets count from the 'movi' tag, but some writers use absolute file positions
                        checked = true;
                        relative = chunkIdAt(moviPos + offset) == stream;
                        if (!relative && chunkIdAt(offset) != stream) return false;
                    }
                    Stream s = stream == video.index ? video : audio != null && stream == audio.index ? audio : null;
                    if (s != null) s.track.add(relative ? moviPos + offset : offset, chunkSize, (flags & 0x10) != 0);
                }
                done += batch;
            }
            return video.track.count > 0;
        }
        return false;
    }

    private int chunkIdAt(long pos) throws IOException {
        if (pos < 0 || pos + 8 > src.size()) return -1;
        long keep = src.position();
        src.seek(pos);
        String id = src.fourcc();
        src.seek(keep);
        return streamOf(id);
    }

    /** Last resort: walks every chunk header in the file, skipping the data. */
    private void scan() throws IOException {
        for (Stream s : new Stream[]{video, audio}) {
            if (s != null) {
                s.track.count = 0;
                s.track.bytes = 0;
            }
        }
        src.seek(moviPos + 4);
        while (advance()) {
            Stream s = curStream == video.index ? video : audio != null && curStream == audio.index ? audio : null;
            if (s != null) s.track.add(curPos, curSize, true);
            src.seek(curPos + 8 + curSize + (curSize & 1));
        }
    }

    @Override
    public void close() {
        try {
            src.close();
        } catch (IOException ignored) {
        }
    }
}
