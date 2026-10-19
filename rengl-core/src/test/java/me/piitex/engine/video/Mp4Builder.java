package me.piitex.engine.video;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes small MP4 files for the tests: Motion JPEG video and 16-bit PCM audio ('jpeg' and 'sowt' sample
 * entries), either as one moov box (at the front or the back) or fragmented into moof/mdat pairs.
 */
final class Mp4Builder {
    enum Layout {MOOV_LAST, MOOV_FIRST, FRAGMENTED}

    private static final int VIDEO_SCALE = 1000, AUDIO_RATE = 8000;

    final int width, height, fps, frames;
    final AviBuilder frameSource;
    boolean audio = true;
    Layout layout = Layout.MOOV_LAST;
    /** Every n-th frame is a sync sample. */
    int keyEvery = 1;
    /** Frames of composition delay, like a file with B-frames: ctts holds it, an edit list hides it. */
    int delayFrames;
    String videoCodec = "jpeg";

    Mp4Builder(int width, int height, int fps, int frames) throws IOException {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.frames = frames;
        this.frameSource = new AviBuilder(width, height, fps, false).frames(frames);
    }

    private int ticks() {
        return VIDEO_SCALE / fps;
    }

    private int audioPerFrame() {
        return AUDIO_RATE / fps;
    }

    private short[] tone() {
        short[] pcm = new short[frames * audioPerFrame()];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (Math.sin(i * 0.2) * 8000);
        return pcm;
    }

    private byte[] audioBytes(int fromFrame, int toFrame) {
        short[] pcm = tone();
        ByteBuffer b = ByteBuffer.allocate((toFrame - fromFrame) * audioPerFrame() * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = fromFrame * audioPerFrame(); i < toFrame * audioPerFrame(); i++) b.putShort(pcm[i]);
        return b.array();
    }

    File write(File dir, String name) throws IOException {
        File f = new File(dir, name);
        Files.write(f.toPath(), build());
        return f;
    }

    byte[] build() throws IOException {
        return layout == Layout.FRAGMENTED ? buildFragmented() : buildPlain();
    }

    // ------------------------------------------------------------------ plain

    private byte[] buildPlain() throws IOException {
        byte[] ftyp = box("ftyp", cat(ascii("isom"), u32(512), ascii("isom"), ascii("mp41")));
        // mdat payload: each frame followed by that frame's audio
        ByteArrayOutputStream mdat = new ByteArrayOutputStream();
        long[] videoAt = new long[frames], audioAt = new long[frames];
        int[] videoSize = new int[frames];
        for (int i = 0; i < frames; i++) {
            byte[] v = frameSource.video.get(i);
            videoAt[i] = mdat.size();
            videoSize[i] = v.length;
            mdat.write(v);
            if (audio) {
                audioAt[i] = mdat.size();
                mdat.write(audioBytes(i, i + 1));
            }
        }
        byte[] payload = mdat.toByteArray();
        long mdatHeader = 8;
        // moov size does not depend on the offsets in it, so build once to learn it, then for real
        int moovSize = plainMoov(videoAt, audioAt, videoSize, 0).length;
        long base = layout == Layout.MOOV_FIRST ? ftyp.length + moovSize + mdatHeader : ftyp.length + mdatHeader;
        byte[] moov = plainMoov(videoAt, audioAt, videoSize, base);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ftyp);
        if (layout == Layout.MOOV_FIRST) out.write(moov);
        out.write(box("mdat", payload));
        if (layout == Layout.MOOV_LAST) out.write(moov);
        return out.toByteArray();
    }

    private byte[] plainMoov(long[] videoAt, long[] audioAt, int[] videoSize, long base) {
        int duration = frames * ticks();
        List<byte[]> parts = new ArrayList<>();
        parts.add(mvhd(duration, audio ? 3 : 2));
        parts.add(videoTrak(duration, videoAt, videoSize, base));
        if (audio) parts.add(audioTrak(audioAt, base));
        return box("moov", parts.toArray(new byte[0][]));
    }

    private byte[] videoTrak(int duration, long[] at, int[] size, long base) {
        ByteArrayOutputStream stts = new ByteArrayOutputStream();
        stts.writeBytes(cat(u32(0), u32(1), u32(frames), u32(ticks())));
        List<byte[]> stbl = new ArrayList<>();
        stbl.add(videoStsd());
        stbl.add(box("stts", stts.toByteArray()));
        if (delayFrames > 0) stbl.add(box("ctts", cat(u32(0), u32(1), u32(frames), u32(delayFrames * ticks()))));
        if (keyEvery > 1) {
            List<byte[]> keys = new ArrayList<>();
            int n = 0;
            for (int i = 0; i < frames; i += keyEvery) {
                keys.add(u32(i + 1));
                n++;
            }
            stbl.add(box("stss", cat(u32(0), u32(n), cat(keys.toArray(new byte[0][])))));
        }
        ByteArrayOutputStream sizes = new ByteArrayOutputStream();
        sizes.writeBytes(cat(u32(0), u32(0), u32(frames)));
        for (int s : size) sizes.writeBytes(u32(s));
        stbl.add(box("stsz", sizes.toByteArray()));
        stbl.add(box("stsc", cat(u32(0), u32(1), u32(1), u32(1), u32(1))));
        ByteArrayOutputStream co = new ByteArrayOutputStream();
        co.writeBytes(cat(u32(0), u32(frames)));
        for (long a : at) co.writeBytes(u32(base + a));
        stbl.add(box("stco", co.toByteArray()));
        return trak(1, duration, VIDEO_SCALE, "vide", box("stbl", stbl.toArray(new byte[0][])), width, height, delayFrames);
    }

    private byte[] audioTrak(long[] at, long base) {
        int total = frames * audioPerFrame();
        List<byte[]> stbl = new ArrayList<>();
        stbl.add(audioStsd());
        stbl.add(box("stts", cat(u32(0), u32(1), u32(total), u32(1))));
        stbl.add(box("stsz", cat(u32(0), u32(2), u32(total))));
        stbl.add(box("stsc", cat(u32(0), u32(1), u32(1), u32(audioPerFrame()), u32(1))));
        ByteArrayOutputStream co = new ByteArrayOutputStream();
        co.writeBytes(cat(u32(0), u32(frames)));
        for (long a : at) co.writeBytes(u32(base + a));
        stbl.add(box("stco", co.toByteArray()));
        return trak(2, total, AUDIO_RATE, "soun", box("stbl", stbl.toArray(new byte[0][])), 0, 0, 0);
    }

    // ------------------------------------------------------------------ fragmented

    private byte[] buildFragmented() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(box("ftyp", cat(ascii("iso5"), u32(512), ascii("iso5"), ascii("dash"))));
        List<byte[]> parts = new ArrayList<>();
        parts.add(mvhd(0, audio ? 3 : 2));
        parts.add(trak(1, 0, VIDEO_SCALE, "vide", box("stbl", videoStsd(), box("stts", cat(u32(0), u32(0))),
                box("stsz", cat(u32(0), u32(0), u32(0))), box("stsc", cat(u32(0), u32(0))), box("stco", cat(u32(0), u32(0)))),
                width, height, delayFrames));
        if (audio) {
            parts.add(trak(2, 0, AUDIO_RATE, "soun", box("stbl", audioStsd(), box("stts", cat(u32(0), u32(0))),
                    box("stsz", cat(u32(0), u32(0), u32(0))), box("stsc", cat(u32(0), u32(0))), box("stco", cat(u32(0), u32(0)))),
                    0, 0, 0));
        }
        parts.add(box("mvex", box("trex", cat(u32(0), u32(1), u32(1), u32(ticks()), u32(0), u32(0))),
                box("trex", cat(u32(0), u32(2), u32(1), u32(0), u32(0), u32(0)))));
        out.write(box("moov", parts.toArray(new byte[0][])));

        int per = 7; // an odd fragment length, so fragments do not line up with sync samples
        for (int from = 0; from < frames; from += per) {
            int to = Math.min(frames, from + per);
            byte[] vdata = concat(frameSource.video.subList(from, to));
            byte[] adata = audio ? audioBytes(from, to) : new byte[0];
            byte[] moof = moof(from, to, vdata.length, adata.length, 0);
            moof = moof(from, to, vdata.length, adata.length, moof.length + 8); // data starts after the mdat header
            out.write(moof);
            out.write(box("mdat", vdata, adata));
        }
        return out.toByteArray();
    }

    private byte[] moof(int from, int to, int videoBytes, int audioBytes, int dataStart) {
        int n = to - from;
        // trun: data offset, per-sample duration, size and flags, plus the composition offset when delayed
        int vflags = 0x1 | 0x100 | 0x200 | 0x400 | (delayFrames > 0 ? 0x800 : 0);
        ByteArrayOutputStream trun = new ByteArrayOutputStream();
        trun.writeBytes(cat(u32(vflags), u32(n), u32(dataStart)));
        for (int i = from; i < to; i++) {
            trun.writeBytes(u32(ticks()));
            trun.writeBytes(u32(frameSource.video.get(i).length));
            trun.writeBytes(u32(i % keyEvery == 0 ? 0x02000000 : 0x01010000)); // sync / non-sync
            if (delayFrames > 0) trun.writeBytes(u32(delayFrames * ticks()));
        }
        byte[] vtraf = box("traf", box("tfhd", cat(u32(0x20000), u32(1))),
                box("tfdt", cat(u32(0), u32((long) from * ticks()))), box("trun", trun.toByteArray()));
        List<byte[]> parts = new ArrayList<>();
        parts.add(box("mfhd", cat(u32(0), u32(from / 7 + 1))));
        parts.add(vtraf);
        if (audio) {
            int frames = n * audioPerFrame();
            byte[] atrun = cat(u32(0x1 | 0x100 | 0x200), u32(1), u32(dataStart + videoBytes), u32(frames), u32(audioBytes));
            parts.add(box("traf", box("tfhd", cat(u32(0x20000), u32(2))),
                    box("tfdt", cat(u32(0), u32((long) from * audioPerFrame()))), box("trun", atrun)));
        }
        return box("moof", parts.toArray(new byte[0][]));
    }

    // ------------------------------------------------------------------ shared boxes

    private byte[] mvhd(int duration, int nextTrack) {
        return box("mvhd", cat(u32(0), u32(0), u32(0), u32(VIDEO_SCALE), u32(duration), u32(0x10000), new byte[2 + 10],
                matrix(), new byte[24], u32(nextTrack)));
    }

    private byte[] trak(int id, int duration, int scale, String handler, byte[] stbl, int w, int h, int delay) {
        byte[] tkhd = box("tkhd", cat(u32(0x000003), u32(0), u32(0), u32(id), u32(0), u32(duration), new byte[8],
                new byte[2], new byte[2], new byte[2], new byte[2], matrix(), u32((long) w << 16), u32((long) h << 16)));
        byte[] mdhd = box("mdhd", cat(u32(0), u32(0), u32(0), u32(scale), u32(duration), new byte[4]));
        byte[] hdlr = box("hdlr", cat(u32(0), u32(0), ascii(handler), new byte[12], new byte[]{'x', 0}));
        byte[] minf = box("minf", stbl);
        byte[] mdia = box("mdia", mdhd, hdlr, minf);
        if (delay > 0) {
            // an edit list that starts the media at the composition delay, so the first picture is at time 0
            byte[] elst = box("elst", cat(u32(0), u32(1), u32(duration), u32((long) delay * ticks()), u32(0x10000)));
            return box("trak", tkhd, box("edts", elst), mdia);
        }
        return box("trak", tkhd, mdia);
    }

    private byte[] videoStsd() {
        byte[] entry = box(videoCodec, cat(new byte[6], new byte[]{0, 1}, new byte[2], new byte[2], new byte[12],
                u16(width), u16(height), u32(0x480000), u32(0x480000), u32(0), u16(1), new byte[32], u16(24), u16(0xFFFF)));
        return box("stsd", cat(u32(0), u32(1), entry));
    }

    private byte[] audioStsd() {
        byte[] entry = box("sowt", cat(new byte[6], new byte[]{0, 1}, u16(0), u16(0), u32(0), u16(1), u16(16), u16(0),
                u16(0), u32((long) AUDIO_RATE << 16)));
        return box("stsd", cat(u32(0), u32(1), entry));
    }

    private static byte[] matrix() {
        return cat(u32(0x10000), u32(0), u32(0), u32(0), u32(0x10000), u32(0), u32(0), u32(0), u32(0x40000000));
    }

    private static byte[] box(String type, byte[]... parts) {
        int size = 8;
        for (byte[] p : parts) size += p.length;
        ByteBuffer b = ByteBuffer.allocate(size);
        b.putInt(size).put(ascii(type));
        for (byte[] p : parts) b.put(p);
        return b.array();
    }

    private static byte[] cat(byte[]... parts) {
        int size = 0;
        for (byte[] p : parts) size += p.length;
        ByteBuffer b = ByteBuffer.allocate(size);
        for (byte[] p : parts) b.put(p);
        return b.array();
    }

    private static byte[] concat(List<byte[]> parts) {
        return cat(parts.toArray(new byte[0][]));
    }

    private static byte[] u32(long v) {
        return ByteBuffer.allocate(4).putInt((int) v).array();
    }

    private static byte[] u16(int v) {
        return ByteBuffer.allocate(2).putShort((short) v).array();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
