package me.piitex.engine.video;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

/**
 * MP4 and QuickTime files (ISO base media), both the plain kind with one {@code moov} box and the fragmented kind
 * ({@code moof} boxes, as downloaded from streaming sites). Only the box headers and sample tables are read when
 * the file opens, so {@code mdat} can be any size; samples are then read from disk one at a time.
 * <p>
 * This class only splits the file into packets. It says which codec each track uses and hands out a decoder when it
 * has one for it.
 */
final class Mp4Demuxer implements Demuxer {
    private static final Logger log = LogManager.getLogger("Video");
    private static final Set<String> CONTAINERS = Set.of("moov", "trak", "mdia", "minf", "stbl", "edts", "mvex", "moof", "traf");
    private static final Set<String> MJPEG = Set.of("jpeg", "mjpa", "mjpg");

    private static final class Track {
        int index, id;
        String handler = "", codec = "";
        long timescale = 1;
        int width, height, channels, rate, bits;
        byte[] config;

        /** Edit list: the media time that plays first, and the silence before it. */
        long mediaStart;
        double delay;
        /** How long the edit list says the media plays, or 0 when it does not say. */
        double editSeconds;

        // the sample table
        long[] off = new long[1024];
        int[] size = new int[1024];
        long[] dts = new long[1024];
        int[] cto = new int[1024];
        final BitSet sync = new BitSet();
        int count;
        long endDts;
        int maxCto;

        // fragment defaults from trex
        int defDuration, defSize, defFlags;

        // the pieces of a plain file's sample table, until they are combined
        int[] stts, ctts, stsc, stss;
        int[] sizes;
        int constSize, sampleCount;
        long[] chunkOffsets;

        void add(long offset, int bytes, long decode, int composition, boolean key) {
            if (count == off.length) {
                int n = count * 2;
                off = Arrays.copyOf(off, n);
                size = Arrays.copyOf(size, n);
                dts = Arrays.copyOf(dts, n);
                cto = Arrays.copyOf(cto, n);
            }
            off[count] = offset;
            size[count] = bytes;
            dts[count] = decode;
            cto[count] = composition;
            if (composition > maxCto) maxCto = composition;
            if (key) sync.set(count);
            count++;
        }

        double dtsSec(int i) {
            return (dts[i] - mediaStart) / (double) timescale + delay;
        }

        double ptsSec(int i) {
            return (dts[i] + cto[i] - mediaStart) / (double) timescale + delay;
        }
    }

    private final ByteSource src;
    private final String name;
    private final List<Track> tracks = new ArrayList<>();
    private Track video, audio;
    private MediaInfo info;
    private boolean audioDropped;
    private String droppedAudio = "";
    private long movieTimescale = 1;
    private long movieDuration;
    private final List<int[]> trexes = new ArrayList<>();

    // parse state
    private Track cur;
    private long moofStart;
    private Track fragTrack;
    private long fragBase, fragNext;
    private int fragDefDuration, fragDefSize, fragDefFlags;

    // read state
    private int vi, ai;

    static boolean matches(byte[] head) {
        if (head.length < 8) return false;
        String type = new String(head, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        return Set.of("ftyp", "moov", "mdat", "wide", "free", "skip", "styp").contains(type);
    }

    Mp4Demuxer(File file) throws IOException {
        src = new ByteSource(file);
        name = file.getName();
        try {
            parseBoxes(0, src.size());
            pick();
            if (video == null) throw new IOException("No video track in " + file.getName());
            buildInfo();
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    // ------------------------------------------------------------------ boxes

    private void parseBoxes(long start, long end) throws IOException {
        src.seek(start);
        while (src.position() + 8 <= end) {
            long at = src.position();
            long size = src.be32();
            String type = src.fourcc();
            long header = 8;
            if (size == 1) {
                size = src.be64();
                header = 16;
            } else if (size == 0) {
                size = end - at;
            }
            if (size < header) throw new IOException("Corrupt MP4: box '" + type + "' has size " + size);
            long boxEnd = at + size;
            if (boxEnd > end) {
                if (!type.equals("mdat")) return; // a truncated header box: use what we have
                boxEnd = end;
            }
            long body = at + header;
            if (CONTAINERS.contains(type)) {
                enter(type, at, body, boxEnd);
            } else {
                leaf(type, body, boxEnd);
            }
            src.seek(boxEnd);
        }
    }

    private void enter(String type, long at, long body, long end) throws IOException {
        switch (type) {
            case "trak" -> {
                Track previous = cur;
                cur = new Track();
                cur.index = tracks.size();
                parseBoxes(body, end);
                if (!cur.handler.isEmpty()) tracks.add(cur);
                cur = previous;
            }
            case "stbl" -> {
                parseBoxes(body, end);
                if (cur != null) buildSamples(cur);
            }
            case "moov" -> {
                parseBoxes(body, end);
                for (int[] trex : trexes) {
                    for (Track t : tracks) {
                        if (t.id == trex[0]) {
                            t.defDuration = trex[1];
                            t.defSize = trex[2];
                            t.defFlags = trex[3];
                        }
                    }
                }
            }
            case "moof" -> {
                moofStart = at;
                fragTrack = null;
                parseBoxes(body, end);
            }
            case "traf" -> {
                fragTrack = null;
                parseBoxes(body, end);
            }
            default -> parseBoxes(body, end);
        }
    }

    private void leaf(String type, long body, long end) throws IOException {
        src.seek(body);
        switch (type) {
            case "mvhd" -> {
                int version = src.u8();
                src.skip(3 + (version == 1 ? 16 : 8));
                movieTimescale = Math.max(1, src.be32());
                movieDuration = version == 1 ? src.be64() : src.be32();
            }
            case "tkhd" -> {
                if (cur == null) return;
                int version = src.u8();
                src.skip(3 + (version == 1 ? 16 : 8));
                cur.id = (int) src.be32();
                src.skip(4 + (version == 1 ? 8 : 4) + 8 + 2 + 2 + 2 + 2 + 36);
                cur.width = (int) (src.be32() >> 16);
                cur.height = (int) (src.be32() >> 16);
            }
            case "mdhd" -> {
                if (cur == null) return;
                int version = src.u8();
                src.skip(3 + (version == 1 ? 16 : 8));
                cur.timescale = Math.max(1, src.be32());
            }
            case "hdlr" -> {
                if (cur == null) return;
                src.skip(8);
                cur.handler = src.fourcc();
            }
            case "stsd" -> {
                if (cur != null) sampleEntry(cur, end);
            }
            case "stts" -> {
                if (cur != null) cur.stts = pairs(end);
            }
            case "ctts" -> {
                if (cur != null) cur.ctts = pairs(end);
            }
            case "stsz" -> {
                if (cur == null) return;
                src.skip(4);
                cur.constSize = src.be32s();
                cur.sampleCount = src.be32s();
                if (cur.constSize == 0) {
                    cur.sizes = new int[cur.sampleCount];
                    for (int i = 0; i < cur.sampleCount; i++) cur.sizes[i] = src.be32s();
                }
            }
            case "stsc" -> {
                if (cur == null) return;
                src.skip(4);
                int n = src.be32s();
                cur.stsc = new int[n * 3];
                for (int i = 0; i < n * 3; i++) cur.stsc[i] = src.be32s();
            }
            case "stco" -> {
                if (cur == null) return;
                src.skip(4);
                int n = src.be32s();
                cur.chunkOffsets = new long[n];
                for (int i = 0; i < n; i++) cur.chunkOffsets[i] = src.be32();
            }
            case "co64" -> {
                if (cur == null) return;
                src.skip(4);
                int n = src.be32s();
                cur.chunkOffsets = new long[n];
                for (int i = 0; i < n; i++) cur.chunkOffsets[i] = src.be64();
            }
            case "stss" -> {
                if (cur == null) return;
                src.skip(4);
                int n = src.be32s();
                cur.stss = new int[n];
                for (int i = 0; i < n; i++) cur.stss[i] = src.be32s();
            }
            case "elst" -> {
                if (cur != null) editList(cur);
            }
            case "trex" -> {
                src.skip(4);
                int id = (int) src.be32();
                src.skip(4);
                int duration = src.be32s(), size = src.be32s(), flags = src.be32s();
                trexes.add(new int[]{id, duration, size, flags});
            }
            case "tfhd" -> fragmentHeader(end);
            case "tfdt" -> {
                if (fragTrack == null) return;
                int version = src.u8();
                src.skip(3);
                fragTrack.endDts = version == 1 ? src.be64() : src.be32();
            }
            case "trun" -> {
                if (fragTrack != null) run(fragTrack);
            }
            default -> {
            }
        }
    }

    /** A box of {@code (count, value)} pairs, as in stts and ctts. */
    private int[] pairs(long end) throws IOException {
        src.skip(4);
        int n = src.be32s();
        if (n < 0 || n * 8L > end - src.position()) throw new IOException("Corrupt MP4 table");
        int[] out = new int[n * 2];
        for (int i = 0; i < out.length; i++) out[i] = src.be32s();
        return out;
    }

    private void editList(Track t) throws IOException {
        int version = src.u8();
        src.skip(3);
        int n = src.be32s();
        double delay = 0;
        long start = 0;
        boolean started = false;
        for (int i = 0; i < n && !started; i++) {
            long duration = version == 1 ? src.be64() : src.be32();
            long mediaTime = version == 1 ? src.be64() : src.be32s();
            src.skip(4);
            if (mediaTime == -1 || mediaTime == 0xFFFFFFFFL && version == 0) {
                delay += duration / (double) movieTimescale;
            } else {
                start = mediaTime;
                t.editSeconds = duration / (double) movieTimescale;
                started = true;
            }
        }
        t.delay = delay;
        t.mediaStart = start;
    }

    // ------------------------------------------------------------------ sample descriptions

    private void sampleEntry(Track t, long end) throws IOException {
        src.skip(4);
        int entries = src.be32s();
        if (entries < 1) return;
        long at = src.position();
        long size = src.be32();
        t.codec = src.fourcc();
        long entryEnd = Math.min(at + size, end);
        src.skip(6 + 2);
        long children;
        if (t.handler.equals("vide")) {
            src.skip(16);
            t.width = src.be16();
            t.height = src.be16();
            src.skip(4 + 4 + 4 + 2 + 32 + 2 + 2);
            children = src.position();
        } else if (t.handler.equals("soun")) {
            int version = src.be16();
            src.skip(2 + 4);
            if (version == 2) {
                src.skip(2 + 2 + 2 + 2 + 4 + 4);
                t.rate = (int) Math.round(Double.longBitsToDouble(src.be64()));
                t.channels = src.be32s();
                src.skip(4);
                t.bits = src.be32s();
                src.skip(4 + 4 + 4);
            } else {
                t.channels = src.be16();
                t.bits = src.be16();
                src.skip(2 + 2);
                t.rate = src.be16();
                src.skip(2);
                if (version == 1) src.skip(16);
            }
            children = src.position();
        } else {
            return;
        }
        findConfig(t, children, entryEnd);
    }

    private void findConfig(Track t, long start, long end) throws IOException {
        src.seek(start);
        while (src.position() + 8 <= end) {
            long at = src.position();
            long size = src.be32();
            String type = src.fourcc();
            if (size < 8 || at + size > end) return;
            switch (type) {
                case "avcC", "hvcC", "vpcC", "av1C", "esds", "dOps", "pcmC", "dfLa" -> t.config = src.bytes((int) (size - 8));
                case "wave" -> findConfig(t, at + 8, at + size);
                default -> {
                }
            }
            src.seek(at + size);
        }
    }

    // ------------------------------------------------------------------ sample tables of a plain file

    /** Walks a (count, value) table one sample at a time. */
    private static final class Cursor {
        final int[] table;
        int run, left;
        long value;

        Cursor(int[] table) {
            this.table = table;
            left = table != null && table.length > 0 ? table[0] : 0;
        }

        int delta() {
            return table != null && run * 2 + 1 < table.length ? table[run * 2 + 1] : 0;
        }

        void skip(long samples) {
            while (samples > 0 && table != null && run * 2 < table.length) {
                int step = (int) Math.min(samples, left);
                value += (long) step * delta();
                left -= step;
                samples -= step;
                if (left == 0) {
                    run++;
                    left = run * 2 < table.length ? table[run * 2] : 0;
                }
            }
        }
    }

    private void buildSamples(Track t) {
        if (t.stsc == null || t.chunkOffsets == null || (t.sizes == null && t.constSize == 0)) return;
        PcmSpec pcm = pcm(t);
        boolean wholeChunks = pcm != null && t.constSize > 0;
        BitSet key = null;
        if (t.stss != null) {
            key = new BitSet();
            for (int s : t.stss) key.set(s - 1);
        }
        Cursor time = new Cursor(t.stts), comp = new Cursor(t.ctts);
        int runs = t.stsc.length / 3, run = 0, sample = 0;
        for (int c = 0; c < t.chunkOffsets.length; c++) {
            while (run + 1 < runs && t.stsc[(run + 1) * 3] <= c + 1) run++;
            int per = t.stsc[run * 3 + 1];
            long off = t.chunkOffsets[c];
            if (wholeChunks) {
                t.add(off, per * t.constSize, time.value, 0, true);
                time.skip(per);
                sample += per;
                continue;
            }
            for (int i = 0; i < per && sample < t.sampleCount; i++, sample++) {
                int bytes = t.constSize > 0 ? t.constSize : t.sizes[sample];
                long cto = comp.table == null ? 0 : comp.delta();
                t.add(off, bytes, time.value, (int) cto, key == null || key.get(sample));
                off += bytes;
                time.skip(1);
                comp.skip(1);
            }
        }
        t.endDts = time.value;
    }

    // ------------------------------------------------------------------ fragments

    private void fragmentHeader(long end) throws IOException {
        int flags = src.be32s() & 0xFFFFFF;
        int id = (int) src.be32();
        fragTrack = null;
        for (Track t : tracks) if (t.id == id) fragTrack = t;
        if (fragTrack == null) return;
        boolean explicit = (flags & 1) != 0;
        long base = explicit ? src.be64() : -1;
        if ((flags & 2) != 0) src.skip(4);
        fragDefDuration = (flags & 8) != 0 ? src.be32s() : fragTrack.defDuration;
        fragDefSize = (flags & 0x10) != 0 ? src.be32s() : fragTrack.defSize;
        fragDefFlags = (flags & 0x20) != 0 ? src.be32s() : fragTrack.defFlags;
        // Without an explicit base the data is relative to the moof (or, in old files, follows the previous traf).
        fragBase = explicit ? base : (fragNext > moofStart && (flags & 0x20000) == 0 ? fragNext : moofStart);
        fragNext = fragBase;
    }

    private void run(Track t) throws IOException {
        int flags = src.be32s() & 0xFFFFFF;
        int n = src.be32s();
        long data = fragNext;
        if ((flags & 1) != 0) data = fragBase + src.be32s();
        int firstFlags = (flags & 4) != 0 ? src.be32s() : -1;
        for (int i = 0; i < n; i++) {
            int duration = (flags & 0x100) != 0 ? src.be32s() : fragDefDuration;
            int bytes = (flags & 0x200) != 0 ? src.be32s() : fragDefSize;
            int sampleFlags = (flags & 0x400) != 0 ? src.be32s() : (i == 0 && firstFlags != -1 ? firstFlags : fragDefFlags);
            int cto = (flags & 0x800) != 0 ? src.be32s() : 0;
            t.add(data, bytes, t.endDts, cto, (sampleFlags & 0x10000) == 0);
            t.endDts += duration;
            data += bytes;
        }
        fragNext = data;
    }

    // ------------------------------------------------------------------ tracks

    private void pick() {
        for (Track t : tracks) {
            if (video == null && t.handler.equals("vide") && t.count > 0) video = t;
            if (audio == null && t.handler.equals("soun") && t.count > 0) audio = t;
        }
        if (audio != null && (audio.rate == 0 || audio.timescale > 65535)) audio.rate = (int) audio.timescale;
        if (audio != null && audioCodec(audio) == null) {
            audioDropped = true;
            droppedAudio = audio.codec;
            audio = null;
        }
    }

    private record PcmSpec(int bits, boolean bigEndian, boolean floating) {
    }

    private static PcmSpec pcm(Track t) {
        if (!t.handler.equals("soun")) return null;
        return switch (t.codec) {
            case "sowt" -> t.bits == 16 ? new PcmSpec(16, false, false) : null;
            case "twos" -> t.bits == 16 ? new PcmSpec(16, true, false) : null;
            case "in24" -> new PcmSpec(24, true, false);
            case "in32" -> new PcmSpec(32, true, false);
            case "fl32" -> new PcmSpec(32, true, true);
            case "ipcm" -> {
                if (t.config == null || t.config.length < 6) yield null;
                boolean little = (t.config[4] & 1) != 0;
                int bits = t.config[5] & 0xFF;
                yield PcmAudioDecoder.supports(bits, false) ? new PcmSpec(bits, !little, false) : null;
            }
            default -> null;
        };
    }

    private static String audioCodec(Track t) {
        if (t.channels <= 0 || t.rate <= 0) return null;
        return pcm(t) != null ? "PCM" : null;
    }

    private static String describeVideo(String codec) {
        return switch (codec) {
            case "avc1", "avc3" -> "H.264";
            case "hvc1", "hev1" -> "H.265";
            case "vp09" -> "VP9";
            case "vp08" -> "VP8";
            case "av01" -> "AV1";
            case "mp4v" -> "MPEG-4 Part 2";
            case "jpeg", "mjpa", "mjpg" -> "MJPEG";
            default -> codec;
        };
    }

    private void buildInfo() {
        // the last picture is shown after the last decode time by the composition delay
        double endSec = video.count == 0 ? 0 : (video.endDts + video.maxCto - video.mediaStart) / (double) video.timescale + video.delay;
        if (video.editSeconds > 0) endSec = video.delay + video.editSeconds; // the edit list is the authority
        double duration = endSec > 0 ? endSec : movieDuration / (double) movieTimescale;
        double fps = duration > 0 ? video.count / duration : 0;
        info = new MediaInfo(video.width, video.height, fps, duration, describeVideo(video.codec),
                audio == null ? 0 : Math.min(audio.channels, 2), audio == null ? 0 : audio.rate,
                audio == null ? null : audioCodec(audio), "MP4");
    }

    /**
     * Whether this class can play the whole file itself: its picture codec is one it decodes, and no soundtrack
     * had to be left out.
     */
    boolean fullySupported() {
        return MJPEG.contains(video.codec) && !audioDropped;
    }

    /** Logs that the soundtrack is being left out, when this is the reader that ends up used. */
    void warnIfDegraded() {
        if (audioDropped) log.warn("{}: the '{}' soundtrack is not supported; playing without sound.", name, droppedAudio);
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
        if (MJPEG.contains(video.codec)) return new MjpegDecoder();
        throw new UnsupportedOperationException("Unsupported video format: " + describeVideo(video.codec)
                + " video in an MP4 file is not supported. " + MediaFiles.convertHint(name));
    }

    @Override
    public AudioTrackDecoder audioDecoder() {
        if (audio == null) return null;
        PcmSpec spec = pcm(audio);
        return new PcmAudioDecoder(audio.channels, audio.rate, spec.bits, spec.floating, spec.bigEndian);
    }

    @Override
    public Packet next() throws IOException {
        while (true) {
            boolean takeVideo;
            if (vi < video.count && (audio == null || ai >= audio.count || video.dtsSec(vi) <= audio.dtsSec(ai))) {
                takeVideo = true;
            } else if (audio != null && ai < audio.count) {
                takeVideo = false;
            } else {
                return null;
            }
            Track t = takeVideo ? video : audio;
            int i = takeVideo ? vi++ : ai++;
            if (t.size[i] <= 0) continue;
            if (t.off[i] + t.size[i] > src.size()) return null; // truncated file
            src.seek(t.off[i]);
            double pts = takeVideo ? t.ptsSec(i) : t.dtsSec(i);
            return new Packet(t.index, pts, takeVideo ? t.sync.get(i) : true, src.bytes(t.size[i]));
        }
    }

    @Override
    public void seek(double seconds) throws IOException {
        int i = lastAtOrBefore(video, seconds);
        while (i > 0 && (!video.sync.get(i) || video.ptsSec(i) > seconds)) i--;
        vi = i;
        if (audio != null) ai = lastAtOrBefore(audio, video.dtsSec(i));
    }

    /** Index of the last sample whose decode time is at or before {@code seconds}, or 0. */
    private static int lastAtOrBefore(Track t, double seconds) {
        int lo = 0, hi = t.count - 1, found = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (t.dtsSec(mid) <= seconds + 1e-9) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    @Override
    public void close() {
        try {
            src.close();
        } catch (IOException ignored) {
        }
    }
}
