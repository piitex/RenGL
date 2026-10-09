package me.piitex.engine.ui.color;

import me.piitex.engine.io.Json;
import me.piitex.engine.io.JsonWriter;
import me.piitex.engine.property.PropertyTypes;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class PaintCodecTest {
    private static Object roundTrip(Object json) {
        return Json.parse(JsonWriter.write(json));
    }

    @Test
    void aSolidColorIsWrittenAsAColorAlwaysWas() {
        Object json = PaintCodec.JSON.write(new Color(1f, 0.5f, 0f, 0.25f));
        assertEquals(List.of(1f, 0.5f, 0f, 0.25f), json);
        assertEquals("#FF8000", PaintCodec.HEX.write(new Color(1f, 0.5f, 0f, 1f)));
        assertEquals("#FF800040", PaintCodec.HEX.write(new Color(1f, 0.5f, 0f, 0.25f)));

        Color read = assertInstanceOf(Color.class, PaintCodec.JSON.read(List.of(0.2, 0.4, 0.6)));
        assertEquals(0.4f, read.getG(), 1e-6f);
        assertEquals(1f, read.getA());
        assertEquals(1f, ((Color) PaintCodec.JSON.read("#FF0000")).getR());
    }

    @Test
    void aGradientKeepsItsColorsAngleAndSpeed() {
        LinearGradient gradient = new LinearGradient(List.of(Color.RED, Color.GREEN, Color.BLUE), 90f);
        Map<?, ?> json = (Map<?, ?>) roundTrip(PaintCodec.HEX.write(gradient));
        assertEquals("gradient", json.get("type"));
        assertEquals(List.of("#FF0000", "#00FF00", "#0000FF"), json.get("colors"));
        assertFalse(json.containsKey("scroll"), "a still gradient doesn't scroll");

        LinearGradient back = assertInstanceOf(LinearGradient.class, PaintCodec.HEX.read(json));
        assertFalse(back instanceof ScrollingGradient);
        assertEquals(3, back.getColors().size());
        assertEquals(90f, back.getAngle());
        assertEquals(1f, back.getStartColor().getR());
        assertEquals(1f, back.getEndColor().getB());

        ScrollingGradient scrolling = new ScrollingGradient(Color.BLACK, Color.WHITE, 0f, -40f);
        ScrollingGradient scrolled = assertInstanceOf(ScrollingGradient.class, PaintCodec.JSON.read(roundTrip(PaintCodec.JSON.write(scrolling))));
        assertEquals(-40f, scrolled.getSpeed());
        assertEquals(0f, scrolled.getAngle());
        assertEquals(0f, scrolled.getOffset(), "a new one starts where it starts");
    }

    @Test
    void aGradientWithNoAngleStaysCornerToCorner() {
        LinearGradient corner = new LinearGradient(Color.RED, Color.BLUE);
        assertFalse(corner.hasAngle());
        Map<?, ?> json = (Map<?, ?>) PaintCodec.JSON.write(corner);
        assertFalse(json.containsKey("angle"));
        assertFalse(((LinearGradient) PaintCodec.JSON.read(json)).hasAngle());
    }

    @Test
    void aRainbowKeepsItsSettings() {
        RainbowColor rainbow = new RainbowColor(10f, 0.5f, 0.75f, 0.9f);
        rainbow.setHueOffset(0.25f);
        RainbowColor back = assertInstanceOf(RainbowColor.class, PaintCodec.JSON.read(roundTrip(PaintCodec.JSON.write(rainbow))));
        assertEquals(10f, back.getCycleDuration());
        assertEquals(0.5f, back.getSaturation());
        assertEquals(0.75f, back.getBrightness());
        assertEquals(0.9f, back.getAlpha());
        assertEquals(0.25f, back.getHueOffset());
        Map<?, ?> plain = (Map<?, ?>) PaintCodec.JSON.write(new RainbowColor());
        assertFalse(plain.containsKey("hueOffset"));
    }

    @Test
    void whatItCantWriteIsLeftOutAndWhatItCantReadSaysWhy() {
        assertNull(PaintCodec.JSON.write(new AnimatedPaint() {
            public void advance(float deltaSeconds) {
            }
        }));
        assertThrows(IllegalArgumentException.class, () -> PaintCodec.JSON.read(5.0));
        IllegalArgumentException noType = assertThrows(IllegalArgumentException.class, () -> PaintCodec.JSON.read(Map.of("colors", List.of("#000000"))));
        assertTrue(noType.getMessage().contains("no \"type\""), noType.getMessage());
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> PaintCodec.JSON.read(Map.of("type", "radial")));
        assertTrue(unknown.getMessage().contains("gradient or rainbow"), unknown.getMessage());
        IllegalArgumentException few = assertThrows(IllegalArgumentException.class, () -> PaintCodec.JSON.read(Map.of("type", "gradient", "colors", List.of("#000000"))));
        assertTrue(few.getMessage().contains("at least two"), few.getMessage());
        assertThrows(IllegalArgumentException.class, () -> PaintCodec.JSON.read(Map.of("type", "gradient", "colors", List.of("#000000", "#FFFFFF"), "angle", "up")));
    }

    @Test
    void aPaintIsAPropertyTypeThatTakesAnyOfThem() {
        assertTrue(PropertyTypes.isSupported(Paint.class));
        assertInstanceOf(LinearGradient.class, PropertyTypes.codecFor(Paint.class).read(PropertyTypes.codecFor(Paint.class).write(new LinearGradient(Color.RED, Color.BLUE, 10f))));
    }

    @Test
    void aGradientNeedsTwoColors() {
        assertThrows(IllegalArgumentException.class, () -> new LinearGradient(List.of(Color.RED), 0f));
        assertThrows(NullPointerException.class, () -> new LinearGradient(Color.RED, null));
    }

    @Test
    void anAngleRunsTheLineAcrossTheBoxFromEdgeToEdge() {
        // 0 degrees: left to right through the middle of a 200 by 100 box at (10, 20).
        float[] across = GradientUtil.axis(new LinearGradient(Color.RED, Color.BLUE, 0f), 10, 20, 210, 120);
        assertArrayEquals(new float[]{10, 70, 210, 70}, across, 1e-3f);
        // 90 degrees: top to bottom.
        float[] down = GradientUtil.axis(new LinearGradient(Color.RED, Color.BLUE, 90f), 10, 20, 210, 120);
        assertArrayEquals(new float[]{110, 20, 110, 120}, down, 1e-3f);
        // 180: right to left.
        float[] back = GradientUtil.axis(new LinearGradient(Color.RED, Color.BLUE, 180f), 10, 20, 210, 120);
        assertArrayEquals(new float[]{210, 70, 10, 70}, back, 1e-3f);
        // 45 degrees on a square runs corner to corner; on a wide box it reaches the two corners' own projection.
        float[] diagonal = GradientUtil.axis(new LinearGradient(Color.RED, Color.BLUE, 45f), 0, 0, 100, 100);
        assertArrayEquals(new float[]{0, 0, 100, 100}, diagonal, 1e-3f);
        float[] wide = GradientUtil.axis(new LinearGradient(Color.RED, Color.BLUE, 45f), 0, 0, 200, 100);
        float length = (float) Math.hypot(wide[2] - wide[0], wide[3] - wide[1]);
        assertEquals((200 + 100) * (float) Math.cos(Math.PI / 4), length, 1e-2f);
        assertEquals(100f, (wide[0] + wide[2]) / 2, 1e-3f, "through the middle");
    }

    @Test
    void noAngleIsTheBoxsOwnCorners() {
        assertArrayEquals(new float[]{1, 2, 31, 42}, GradientUtil.axis(new LinearGradient(Color.RED, Color.BLUE), 1, 2, 31, 42));
    }
}
