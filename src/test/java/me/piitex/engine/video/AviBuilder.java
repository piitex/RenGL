package me.piitex.engine.video;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes small AVI files for the tests, since no encoder is installed: Motion JPEG or uncompressed video, optional
 * 16-bit PCM audio, and three index layouts.
 */
final class AviBuilder {
    enum Index {IDX1, OPENDML, NONE}

    /** Solid frame colors, far enough apart to survive JPEG. */
    static final int[] COLORS = {0xE02020, 0x20E020, 0x2020E0, 0xE0E020, 0x20E0E0, 0xE020E0, 0xFFFFFF, 0x202020};

    final int width, height, fps;
    final List<byte[]> video = new ArrayList<>();
    final boolean raw;
    short[] audio; // mono
    int audioRate = 8000;
    Index index = Index.IDX1;
    /** Frames where chunk (frame % keyEvery != 0) is flagged as not a keyframe. 1 = all keyframes. */
    int keyEvery = 1;

    AviBuilder(int width, int height, int fps, boolean raw) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.raw = raw;
    }

    AviBuilder frames(int count) throws IOException {
        for (int i = 0; i < count; i++) video.add(raw ? rawFrame(COLORS[i % COLORS.length]) : jpeg(COLORS[i % COLORS.length]));
        return this;
    }

    AviBuilder tone(double seconds) {
        audio = new short[(int) (seconds * audioRate)];
        for (int i = 0; i < audio.length; i++) audio[i] = (short) (Math.sin(i * 0.2) * 8000);
        return this;
    }

    byte[] jpeg(int rgb) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(rgb));
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /** 24-bit bottom-up frame: top half the color, bottom half black, so orientation is checkable. */
    byte[] rawFrame(int rgb) {
        int stride = (width * 3 + 3) & ~3;
        byte[] out = new byte[stride * height];
        for (int row = 0; row < height; row++) {
            int y = height - 1 - row; // row 0 of the file is the bottom of the picture
            boolean top = y < height / 2;
            for (int x = 0; x < width; x++) {
                int p = row * stride + x * 3;
                out[p] = (byte) (top ? rgb & 0xFF : 0);
                out[p + 1] = (byte) (top ? (rgb >> 8) & 0xFF : 0);
                out[p + 2] = (byte) (top ? (rgb >> 16) & 0xFF : 0);
            }
        }
        return out;
    }

    File write(File dir, String name) throws IOException {
        File f = new File(dir, name);
        Files.write(f.toPath(), build());
        return f;
    }

    // ------------------------------------------------------------------ layout

    private ByteBuffer b;

    private void fourcc(String s) {
        for (int i = 0; i < 4; i++) b.put((byte) s.charAt(i));
    }

    private int begin(String id) {
        fourcc(id);
        int at = b.position();
        b.putInt(0);
        return at;
    }

    private int beginList(String id, String type) {
        int at = begin(id);
        fourcc(type);
        return at;
    }

    private void end(int sizePos) {
        int size = b.position() - sizePos - 4;
        b.putInt(sizePos, size);
        if ((size & 1) != 0) b.put((byte) 0);
    }

    byte[] build() {
        b = ByteBuffer.allocate(video.stream().mapToInt(a -> a.length + 16).sum() + (audio == null ? 0 : audio.length * 2) + (1 << 16))
                .order(ByteOrder.LITTLE_ENDIAN);
        int n = video.size();
        boolean dml = index == Index.OPENDML;
        int perFrame = audio == null ? 0 : audioRate / fps;

        int riff = beginList("RIFF", "AVI ");
        int hdrl = beginList("LIST", "hdrl");
        int avih = begin("avih");
        b.putInt(1_000_000 / fps).putInt(0).putInt(0).putInt(index == Index.IDX1 ? 0x10 : 0).putInt(n).putInt(0)
                .putInt(audio == null ? 1 : 2).putInt(1 << 20).putInt(width).putInt(height).putInt(0).putInt(0).putInt(0).putInt(0);
        end(avih);

        int[] indxPos = new int[2];
        int vstrl = beginList("LIST", "strl");
        int strh = begin("strh");
        fourcc("vids");
        fourcc(raw ? "\0\0\0\0" : "MJPG");
        b.putInt(0).putShort((short) 0).putShort((short) 0).putInt(0).putInt(1).putInt(fps).putInt(0).putInt(n)
                .putInt(1 << 20).putInt(-1).putInt(0).putShort((short) 0).putShort((short) 0).putShort((short) width).putShort((short) height);
        end(strh);
        int strf = begin("strf");
        b.putInt(40).putInt(width).putInt(height).putShort((short) 1).putShort((short) (raw ? 24 : 24));
        fourcc(raw ? "\0\0\0\0" : "MJPG");
        b.putInt(width * height * 3).putInt(0).putInt(0).putInt(0).putInt(0);
        end(strf);
        if (dml) indxPos[0] = placeholderIndx("00dc");
        end(vstrl);

        if (audio != null) {
            int astrl = beginList("LIST", "strl");
            int ah = begin("strh");
            fourcc("auds");
            b.putInt(0).putInt(0).putShort((short) 0).putShort((short) 0).putInt(0).putInt(1).putInt(audioRate).putInt(0)
                    .putInt(audio.length).putInt(4096).putInt(-1).putInt(2).putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0);
            end(ah);
            int af = begin("strf");
            b.putShort((short) 1).putShort((short) 1).putInt(audioRate).putInt(audioRate * 2).putShort((short) 2).putShort((short) 16);
            end(af);
            if (dml) indxPos[1] = placeholderIndx("01wb");
            end(astrl);
        }
        if (dml) {
            int odml = beginList("LIST", "odml");
            int dmlh = begin("dmlh");
            b.putInt(n);
            end(dmlh);
            end(odml);
        }
        end(hdrl);

        // movi segments
        int segments = dml ? 2 : 1;
        record Entry(String id, int flags, int offset, int size) {
        }
        List<Entry> idx1 = new ArrayList<>();
        int[][] ixAt = new int[segments][2];
        int[][] ixSize = new int[segments][2];
        int moviTag = 0;
        int audioDone = 0;
        int current = riff;
        for (int seg = 0; seg < segments; seg++) {
            if (seg == 1) {
                end(current);
                current = beginList("RIFF", "AVIX");
            }
            int movi = beginList("LIST", "movi");
            int tag = b.position() - 4;
            if (seg == 0) moviTag = tag;
            int from = seg == 0 ? 0 : n / 2, to = dml && seg == 0 ? n / 2 : n;
            List<int[]> vEntries = new ArrayList<>(), aEntries = new ArrayList<>();
            for (int i = from; i < to; i++) {
                boolean key = i % keyEvery == 0;
                int pos = b.position();
                int c = begin("00dc");
                b.put(video.get(i));
                end(c);
                idx1.add(new Entry("00dc", key ? 0x10 : 0, pos - moviTag, video.get(i).length));
                vEntries.add(new int[]{pos + 8, video.get(i).length | (key ? 0 : 0x80000000)});
                if (audio != null) {
                    int samples = i == n - 1 ? audio.length - audioDone : perFrame;
                    int apos = b.position();
                    int ac = begin("01wb");
                    for (int s = 0; s < samples; s++) b.putShort(audio[audioDone + s]);
                    audioDone += samples;
                    end(ac);
                    idx1.add(new Entry("01wb", 0x10, apos - moviTag, samples * 2));
                    aEntries.add(new int[]{apos + 8, samples * 2});
                }
            }
            if (dml) {
                int base = tag;
                for (int s = 0; s < (audio == null ? 1 : 2); s++) {
                    List<int[]> entries = s == 0 ? vEntries : aEntries;
                    ixAt[seg][s] = b.position();
                    int ix = begin(s == 0 ? "ix00" : "ix01");
                    b.putShort((short) 2).put((byte) 0).put((byte) 1).putInt(entries.size());
                    fourcc(s == 0 ? "00dc" : "01wb");
                    b.putLong(base).putInt(0);
                    for (int[] e : entries) b.putInt(e[0] - base).putInt(e[1]);
                    end(ix);
                    ixSize[seg][s] = b.position() - ixAt[seg][s] - 8;
                }
            }
            end(movi);
        }
        if (index == Index.IDX1) {
            int ix = begin("idx1");
            for (Entry e : idx1) {
                fourcc(e.id);
                b.putInt(e.flags).putInt(e.offset).putInt(e.size);
            }
            end(ix);
        }
        end(current);

        if (dml) {
            for (int s = 0; s < (audio == null ? 1 : 2); s++) {
                int p = indxPos[s];
                b.putInt(p + 4, 24 + 16 * 2); // chunk size of the placeholder is already right; keep explicit
                int q = p + 8 + 2 + 1 + 1;
                b.putInt(q, segments);
                int e = p + 8 + 24;
                for (int seg = 0; seg < segments; seg++) {
                    b.putLong(e, ixAt[seg][s]).putInt(e + 8, ixSize[seg][s]).putInt(e + 12, 0);
                    e += 16;
                }
            }
        }
        byte[] out = new byte[b.position()];
        b.flip();
        b.get(out);
        return out;
    }

    private int placeholderIndx(String chunkId) {
        int at = b.position();
        int ix = begin("indx");
        b.putShort((short) 4).put((byte) 0).put((byte) 0).putInt(2);
        fourcc(chunkId);
        b.putInt(0).putInt(0).putInt(0);
        for (int i = 0; i < 2; i++) b.putLong(0).putInt(0).putInt(0);
        end(ix);
        return at;
    }
}
