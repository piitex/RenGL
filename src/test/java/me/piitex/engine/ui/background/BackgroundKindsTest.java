package me.piitex.engine.ui.background;

import me.piitex.engine.io.Json;
import me.piitex.engine.io.JsonWriter;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.RainbowColor;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class BackgroundKindsTest {
    @TempDir
    Path folder;

    private static Map<String, Object> through(Map<String, Object> settings) {
        @SuppressWarnings("unchecked") Map<String, Object> read = (Map<String, Object>) Json.parse(JsonWriter.write(settings));
        return read;
    }

    private void picture(String name) throws IOException {
        Files.createDirectories(folder.resolve("assets"));
        ImageIO.write(new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB), "png", folder.resolve(name).toFile());
    }

    @Test
    void everyKindMakesWritesAndReadsBackTheSame() {
        for (BackgroundKinds.Kind kind : BackgroundKinds.all()) {
            Background made = kind.create();
            assertSame(kind, BackgroundKinds.of(made), kind.getId());
            Map<String, Object> written = BackgroundKinds.write(made);
            assertEquals(kind.getId(), written.get("type"));
            Background back = BackgroundKinds.read(through(written), Path.of(""));
            assertSame(made.getClass(), back.getClass());
            assertEquals(written, BackgroundKinds.write(back), kind.getId() + " comes back as it went");
        }
    }

    @Test
    void everySettingCanBeShownAndChanged() {
        for (BackgroundKinds.Kind kind : BackgroundKinds.all()) {
            Background background = kind.create();
            assertFalse(kind.getLabel().isBlank(), kind.getId());
            assertFalse(kind.getSettings().isEmpty(), kind.getId());
            for (BackgroundKinds.Setting setting : kind.getSettings()) {
                String where = kind.getId() + "." + setting.getName();
                assertFalse(setting.getLabel().isBlank(), where);
                Object value = setting.get(background);
                assertNotNull(value, where);
                assertTrue(setting.getType().isInstance(value) || setting.getType() == Color.class && value instanceof Color, where);
                if (setting.isNumber()) assertTrue(setting.getMin() < setting.getMax(), where + " has a range");
                setting.set(background, value);
                assertEquals(BackgroundKinds.write(background).get(setting.getName()), BackgroundKinds.write(kind.create()).get(setting.getName()), where + " set to what it was changes nothing");
            }
        }
    }

    @Test
    void aSettingIsKeptInItsRange() {
        BackgroundKinds.Kind grid = BackgroundKinds.get("dotgrid");
        DotGridBackground dots = (DotGridBackground) grid.create();
        grid.getSetting("spacing").set(dots, 5000);
        assertEquals(1000f, dots.getSpacing(), "the setting's own top, which the background doesn't enforce");
        grid.getSetting("spacing").set(dots, 1);
        assertEquals(4f, dots.getSpacing(), "and its own bottom");
        BackgroundKinds.Kind constellation = BackgroundKinds.get("constellation");
        ConstellationBackground field = (ConstellationBackground) constellation.create();
        constellation.getSetting("particleCount").set(field, 12.6);
        assertEquals(13, field.getParticleCount(), "a whole number setting takes a whole number");
        constellation.getSetting("particleCount").set(field, 100000);
        assertEquals(1000, field.getParticleCount());
    }

    @Test
    void whatTheFileLeavesOutStartsAsItAlwaysDid() {
        Background read = BackgroundKinds.read(Map.of("type", "constellation", "particleCount", 5.0, "lineColor", "#FF0000"), Path.of(""));
        ConstellationBackground field = assertInstanceOf(ConstellationBackground.class, read);
        assertEquals(5, field.getParticleCount());
        assertEquals(1f, ((me.piitex.engine.ui.color.Color) field.getLineColor()).getR());
        assertEquals(new ConstellationBackground().getLinkDistance(), field.getLinkDistance());
        assertEquals(new ConstellationBackground().getMinSpeed(), field.getMinSpeed());
    }

    @Test
    void aSpeedRangeKeepsBothEnds() {
        ConstellationBackground field = (ConstellationBackground) BackgroundKinds.read(Map.of("type", "constellation", "minSpeed", 5.0, "maxSpeed", 90.0), Path.of(""));
        assertEquals(5f, field.getMinSpeed());
        assertEquals(90f, field.getMaxSpeed());
        ElectricNodeBackground electric = (ElectricNodeBackground) BackgroundKinds.read(Map.of("type", "electric", "minArcLength", 20.0, "maxArcLength", 300.0), Path.of(""));
        assertEquals(20f, electric.getMinArcLength());
        assertEquals(300f, electric.getMaxArcLength());
    }

    @Test
    void aWrongSettingsObjectSaysWhatIsWrong() {
        IllegalArgumentException type = assertThrows(IllegalArgumentException.class, () -> BackgroundKinds.read(Map.of("type", "snow"), Path.of("")));
        assertTrue(type.getMessage().contains("dotgrid") && type.getMessage().contains("image"), type.getMessage());
        assertThrows(IllegalArgumentException.class, () -> BackgroundKinds.read(Map.of("spacing", 3), Path.of("")));
        assertThrows(IllegalArgumentException.class, () -> BackgroundKinds.read("dotgrid", Path.of("")));
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class, () -> BackgroundKinds.read(Map.of("type", "dotgrid", "spacing", "wide"), Path.of("")));
        assertTrue(wrong.getMessage().startsWith("\"spacing\":"), wrong.getMessage());
        IllegalArgumentException color = assertThrows(IllegalArgumentException.class, () -> BackgroundKinds.read(
                Map.of("type", "dotgrid", "dotColor", "not a color"), Path.of("")));
        assertTrue(color.getMessage().startsWith("\"dotColor\":"), color.getMessage());
    }

    @Test
    void aColorSettingCanBeAGradient() {
        Map<String, Object> file = Map.of("type", "dotgrid", "dotColor",
                Map.of("type", "gradient", "colors", List.of("#000000", "#FFFFFF"), "angle", 90));
        DotGridBackground grid = (DotGridBackground) BackgroundKinds.read(file, Path.of(""));
        assertInstanceOf(me.piitex.engine.ui.color.LinearGradient.class, grid.getDotColor());
        DotGridBackground back = (DotGridBackground) BackgroundKinds.read(through(BackgroundKinds.write(grid)), Path.of(""));
        assertInstanceOf(me.piitex.engine.ui.color.LinearGradient.class, back.getDotColor());
    }

    @Test
    void aColorSettingCanBeARainbow() {
        ElectricNodeBackground electric = new ElectricNodeBackground();
        electric.setGlowColor(new RainbowColor(4f));
        Map<String, Object> written = through(BackgroundKinds.write(electric));
        assertInstanceOf(Map.class, written.get("glowColor"));
        ElectricNodeBackground back = (ElectricNodeBackground) BackgroundKinds.read(written, Path.of(""));
        assertInstanceOf(RainbowColor.class, back.getGlowColor());
        assertEquals(4f, ((RainbowColor) back.getGlowColor()).getCycleDuration());
    }

    @Test
    void aBackgroundOfAnotherClassIsNotWritten() {
        Background mine = (renderer, x, y, width, height) -> {
        };
        assertNull(BackgroundKinds.of(mine));
        assertNull(BackgroundKinds.write(mine));
        assertNull(BackgroundKinds.of(null));
    }

    @Test
    void applyingChangesWhatDiffersAndKeepsTheOneThatIsShowing() {
        ConstellationBackground showing = new ConstellationBackground();
        Map<String, Object> settings = new java.util.LinkedHashMap<>(BackgroundKinds.write(showing));
        settings.put("linkDistance", 33f);
        settings.put("particleCount", showing.getParticleCount());

        assertSame(showing, BackgroundKinds.apply(showing, settings, Path.of("")));
        assertEquals(33f, showing.getLinkDistance());

        Background other = BackgroundKinds.apply(showing, Map.of("type", "dotgrid"), Path.of(""));
        assertNotSame(showing, other);
        assertInstanceOf(DotGridBackground.class, other);
        assertInstanceOf(DotGridBackground.class, BackgroundKinds.apply(null, Map.of("type", "dotgrid"), Path.of("")));
    }

    @Test
    void applyingDoesNotReseedWhatItDidNotChange() throws ReflectiveOperationException {
        ConstellationBackground showing = new ConstellationBackground();
        showing.update(0.016f, 0, 0, 200, 100); // seeds the field
        java.lang.reflect.Field particles = ConstellationBackground.class.getDeclaredField("particles");
        particles.setAccessible(true);
        Object seeded = particles.get(showing);
        assertNotNull(seeded);

        Map<String, Object> settings = new java.util.LinkedHashMap<>(BackgroundKinds.write(showing));
        settings.put("particleColor", List.of(1f, 0f, 0f, 1f));
        BackgroundKinds.apply(showing, settings, Path.of(""));
        assertEquals(1f, ((me.piitex.engine.ui.color.Color) showing.getParticleColor()).getR());
        assertSame(seeded, particles.get(showing), "a color changed, so the dots stay where they are");

        settings.put("particleCount", showing.getParticleCount() + 1);
        BackgroundKinds.apply(showing, settings, Path.of(""));
        assertNull(particles.get(showing), "a count changed, so the field starts over");
    }

    @Test
    void aPictureIsReadFromTheFolderItsPathIsRelativeTo() throws IOException {
        picture("assets/sky.png");
        Background read = BackgroundKinds.read(Map.of("type", "image", "image", "assets/sky.png", "fit", "TILE"), folder);
        ImageBackground image = assertInstanceOf(ImageBackground.class, read);
        assertNotNull(image.getImage());
        assertFalse(image.isMissing());
        assertEquals(ImageBackground.Fit.TILE, image.getFit());
        assertEquals("assets/sky.png", image.getPath());
        assertEquals(8, image.getImage().getWidth());
    }

    @Test
    void aPictureThatIsntThereIsKeptByItsPathAndSaysSo() {
        ImageBackground image = (ImageBackground) BackgroundKinds.read(Map.of("type", "image", "image", "assets/gone.png"), folder);
        assertNull(image.getImage());
        assertTrue(image.isMissing());
        assertEquals("assets/gone.png", BackgroundKinds.write(image).get("image"), "the path isn't lost");
        image.setPath("");
        assertFalse(image.isMissing());
    }

    @Test
    void applyingANewPathReadsItsPicture() throws IOException {
        picture("a.png");
        picture("b.png");
        ImageBackground image = (ImageBackground) BackgroundKinds.read(Map.of("type", "image", "image", "a.png"), folder);
        assertNotNull(image.getImage());
        Map<String, Object> settings = new java.util.LinkedHashMap<>(BackgroundKinds.write(image));
        settings.put("image", "b.png");
        assertSame(image, BackgroundKinds.apply(image, settings, folder));
        assertEquals("b.png", image.getPath());
        assertNotNull(image.getImage());
        settings.put("image", "none.png");
        BackgroundKinds.apply(image, settings, folder);
        assertTrue(image.isMissing());
        assertNull(image.getImage());
    }

    @Test
    void aPictureFillsItsSpaceAsItsFitSays() throws IOException {
        picture("p.png"); // 8 by 4
        ImageBackground image = (ImageBackground) BackgroundKinds.read(Map.of("type", "image", "image", "p.png"), folder);
        RecordingRenderer calls = new RecordingRenderer();

        image.setFit(ImageBackground.Fit.STRETCH);
        image.render(calls.proxy(), 10, 20, 100, 100);
        assertEquals(List.of("clip 10.0 20.0 100.0 100.0", "image 10.0 20.0 100.0 100.0", "popClip"), calls.log);

        calls.log.clear();
        image.setFit(ImageBackground.Fit.CONTAIN); // 8x4 into 100x100 is 100 wide, 50 high, in the middle
        image.render(calls.proxy(), 10, 20, 100, 100);
        assertEquals("image 10.0 45.0 100.0 50.0", calls.log.get(1));

        calls.log.clear();
        image.setFit(ImageBackground.Fit.COVER); // fills 100x100 from the middle 4x4 of the picture
        image.render(calls.proxy(), 10, 20, 100, 100);
        assertEquals("region 2.0 0.0 4.0 4.0 to 10.0 20.0 100.0 100.0", calls.log.get(1));

        calls.log.clear();
        image.setFit(ImageBackground.Fit.TILE); // 8x4 tiles over 20x8: 3 across, 2 down
        image.render(calls.proxy(), 0, 0, 20, 8);
        assertEquals(6, calls.log.stream().filter(line -> line.startsWith("image")).count());
    }

    @Test
    void aPictureThatIsntThereDrawsNothing() {
        RecordingRenderer calls = new RecordingRenderer();
        new ImageBackground().render(calls.proxy(), 0, 0, 50, 50);
        assertTrue(calls.log.isEmpty());
    }

    /** A renderer that only writes down the clip and image calls the background makes. */
    private static final class RecordingRenderer {
        final List<String> log = new java.util.ArrayList<>();

        me.piitex.engine.gl.renderer.Renderer proxy() {
            return (me.piitex.engine.gl.renderer.Renderer) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{me.piitex.engine.gl.renderer.Renderer.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "pushClip" -> log.add("clip " + args[0] + " " + args[1] + " " + args[2] + " " + args[3]);
                            case "popClip" -> log.add("popClip");
                            case "drawImage" -> log.add("image " + args[1] + " " + args[2] + " " + args[3] + " " + args[4]);
                            case "drawImageRegion" -> log.add("region " + args[1] + " " + args[2] + " " + args[3] + " " + args[4]
                                    + " to " + args[5] + " " + args[6] + " " + args[7] + " " + args[8]);
                            default -> {
                            }
                        }
                        return null;
                    });
        }
    }

    @Test
    void everyKindAndSettingExplainsItself() {
        for (BackgroundKinds.Kind kind : BackgroundKinds.all()) {
            assertFalse(kind.getDescription().isBlank(), kind.getId() + " has no description");
            for (BackgroundKinds.Setting setting : kind.getSettings()) {
                assertFalse(setting.getTooltip().isBlank(), kind.getId() + "." + setting.getName() + " has no tooltip");
            }
        }
    }
}
