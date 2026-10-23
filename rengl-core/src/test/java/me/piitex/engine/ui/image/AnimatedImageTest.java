package me.piitex.engine.ui.image;

import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AnimatedImageTest {
    private static final int[] COLORS = {0xFF0000, 0x00FF00, 0x0000FF};

    /** A 4x4 GIF of solid red, green and blue frames, 100 ms, 200 ms and 0 ms (which players show as 100 ms). */
    private static byte[] gif(int loops) throws Exception {
        int[] delays = {10, 20, 0}; // centiseconds
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < COLORS.length; i++) {
                BufferedImage frame = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = frame.createGraphics();
                g.setColor(new Color(COLORS[i]));
                g.fillRect(0, 0, 4, 4);
                g.dispose();

                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), param);
                String format = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
                IIOMetadataNode gce = child(root, "GraphicControlExtension");
                gce.setAttribute("delayTime", String.valueOf(delays[i]));
                gce.setAttribute("disposalMethod", "none");
                if (i == 0) {
                    IIOMetadataNode app = child(child(root, "ApplicationExtensions"), "ApplicationExtension");
                    app.setAttribute("applicationID", "NETSCAPE");
                    app.setAttribute("authenticationCode", "2.0");
                    // loop count 0 = forever; otherwise the number of repeats after the first play
                    app.setUserObject(new byte[]{1, (byte) (loops & 0xFF), (byte) (loops >> 8)});
                }
                meta.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(frame, null, meta), param);
            }
            writer.endWriteSequence();
        }
        return bytes.toByteArray();
    }

    private static IIOMetadataNode child(IIOMetadataNode parent, String name) {
        for (var n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) n;
        }
        IIOMetadataNode created = new IIOMetadataNode(name);
        parent.appendChild(created);
        return created;
    }

    private static int rgb(Image image) {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocPixels(image.getImageInfo());
            image.readPixels(bitmap);
            return bitmap.getColor(1, 1) & 0xFFFFFF;
        }
    }

    @Test
    void readsFramesAndDurations() throws Exception {
        try (AnimatedImage gif = AnimatedImage.decode(gif(0))) {
            assertTrue(gif.isAnimated());
            assertEquals(3, gif.getFrameCount());
            assertEquals(4, gif.getWidth());
            assertEquals(0.1f, gif.getFrameDuration(0), 1e-4f);
            assertEquals(0.2f, gif.getFrameDuration(1), 1e-4f);
            assertEquals(0.1f, gif.getFrameDuration(2), 1e-4f, "a zero delay plays at 100 ms, as in a browser");
            assertEquals(0.4f, gif.getDuration(), 1e-4f);
            assertEquals(0, gif.frameAt(0.05));
            assertEquals(1, gif.frameAt(0.1));
            assertEquals(1, gif.frameAt(0.29));
            assertEquals(2, gif.frameAt(0.3));
            assertEquals(2, gif.frameAt(99));
        }
    }

    @Test
    void framesAreComposedInAnyOrder() throws Exception {
        try (AnimatedImage gif = AnimatedImage.decode(gif(0))) {
            for (int i : new int[]{0, 1, 2, 1, 0, 2, 2, 0}) {
                assertEquals(COLORS[i], rgb(gif.getFrame(i)), "frame " + i);
            }
        }
    }

    @Test
    void stillImagesAreNotAnimated() throws Exception {
        BufferedImage png = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(png, "png", out);
        assertFalse(AnimatedImage.mayBeAnimated(out.toByteArray()));
        assertTrue(AnimatedImage.mayBeAnimated(gif(0)));
    }

    @Test
    void playerLoopsForeverAndSeeks() throws Exception {
        try (AnimatedImage gif = AnimatedImage.decode(gif(0))) {
            ImagePlayer player = new ImagePlayer(gif);
            int[] loops = {0};
            player.onLoop(() -> loops[0]++);
            assertTrue(player.isLooping());
            assertTrue(player.isPlaying());

            player.update(0.15f);
            assertEquals(1, player.getFrameIndex());
            player.update(0.3f); // 0.45 -> wraps to 0.05
            assertEquals(0, player.getFrameIndex());
            assertEquals(1, loops[0]);
            assertFalse(player.isFinished());

            player.pause();
            player.update(10f);
            assertEquals(0.05, player.getPosition(), 1e-6);

            player.seekFrame(2);
            assertEquals(2, player.getFrameIndex());
            player.seek(-5);
            assertEquals(0, player.getPosition());
            player.setProgress(0.5f);
            assertEquals(1, player.getFrameIndex());
        }
    }

    @Test
    void playerPlaysALimitedNumberOfTimesThenFinishes() throws Exception {
        try (AnimatedImage gif = AnimatedImage.decode(gif(0))) {
            ImagePlayer player = new ImagePlayer(gif);
            player.setLoopCount(2);
            int[] finished = {0}, looped = {0};
            player.onFinish(() -> finished[0]++).onLoop(() -> looped[0]++);

            player.update(0.5f);
            assertEquals(1, looped[0]);
            assertEquals(0, finished[0]);
            player.update(0.5f); // 0.1 into the second pass + 0.5 -> past the end
            assertEquals(1, finished[0]);
            assertTrue(player.isFinished());
            assertFalse(player.isPlaying());
            assertEquals(2, player.getFrameIndex(), "holds on the last frame");

            player.update(1f);
            assertEquals(1, finished[0], "finish fires once");

            player.play(); // play after the end starts over
            assertFalse(player.isFinished());
            assertEquals(0, player.getFrameIndex());
        }
    }

    @Test
    void fileLoopCountIsRead() throws Exception {
        try (AnimatedImage once = AnimatedImage.decode(gif(0))) {
            assertEquals(0, once.getLoopCount(), "a loop block of 0 repeats forever");
        }
        try (AnimatedImage twice = AnimatedImage.decode(gif(2))) {
            assertEquals(3, twice.getLoopCount(), "the first play plus two repeats");
        }
    }

    @Test
    void speedScalesTime() throws Exception {
        try (AnimatedImage gif = AnimatedImage.decode(gif(0))) {
            ImagePlayer player = new ImagePlayer(gif);
            player.setSpeed(2f);
            player.update(0.1f);
            assertEquals(0.2, player.getPosition(), 1e-6);
            assertThrows(IllegalArgumentException.class, () -> player.setSpeed(-1f));
        }
    }

    @Test
    void toAnimationKeepsEveryFrame() throws Exception {
        try (AnimatedImage gif = AnimatedImage.decode(gif(0))) {
            var animation = gif.toAnimation(me.piitex.engine.graphics.Animation.PlayMode.LOOP);
            assertEquals(3, animation.getFrameCount());
            assertEquals(0.4f, animation.getDuration(), 1e-4f);
            for (int i = 0; i < 3; i++) {
                assertEquals(COLORS[i], rgb(animation.getFrame(i).region().getTexture().getImage()));
            }
        }
    }

    @Test
    void imageOverlayPlaysAGifAndLeavesAPngStill(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path gifFile = dir.resolve("a.gif");
        java.nio.file.Files.write(gifFile, gif(0));
        var overlay = new me.piitex.engine.ui.overlays.ImageOverlay(gifFile.toString());
        assertTrue(overlay.isAnimated());
        assertEquals(4, overlay.getWidth());
        assertEquals(COLORS[0], rgb(overlay.getImage()));
        overlay.update(0.15f);
        assertEquals(COLORS[1], rgb(overlay.getImage()));
        overlay.pause();
        overlay.update(0.15f);
        assertEquals(COLORS[1], rgb(overlay.getImage()));
        overlay.stop();
        assertEquals(COLORS[0], rgb(overlay.getImage()));
        overlay.play();
        assertTrue(overlay.isPlaying());
        overlay.dispose();

        java.nio.file.Path pngFile = dir.resolve("a.png");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", pngFile.toFile());
        var still = new me.piitex.engine.ui.overlays.ImageOverlay(pngFile.toString());
        assertFalse(still.isAnimated());
        assertNull(still.getPlayer());
        still.update(1f);
        still.play(); // a no-op, not an error
        assertEquals(2, still.getNaturalWidth());
    }
}
