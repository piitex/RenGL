package me.piitex.engine.ui.theme;

import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.color.PaintCodec;
import me.piitex.engine.ui.color.ScrollingGradient;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class ThemeFileTest {
    @TempDir
    Path folder;

    private static void assertSame(Theme expected, Theme actual) {
        for (ThemeFile.Token token : ThemeFile.tokens()) {
            Object a = token.get().apply(expected), b = token.get().apply(actual);
            if (token.isColor() || token.isPaint()) {
                // Both sides are hex, which is all a file keeps, so a color that isn't a whole number of 255ths is rounded alike.
                assertEquals(PaintCodec.HEX.write((Paint) a), PaintCodec.HEX.write((Paint) b), token.name());
            } else {
                assertEquals(a, b, token.name());
            }
        }
    }

    @Test
    void everythingAThemeHasIsASettingInTheFile() {
        Set<String> getters = new TreeSet<>();
        for (Method method : Theme.class.getMethods()) {
            String name = method.getName();
            if (name.startsWith("get")) getters.add(Character.toLowerCase(name.charAt(3)) + name.substring(4));
        }
        Set<String> tokens = new TreeSet<>(ThemeFile.tokens().stream().map(ThemeFile.Token::name).toList());
        assertEquals(getters, tokens, "a new Theme getter needs a ThemeFile token, or a saved theme loses it");
    }

    @Test
    void aSavedThemeComesBackTheSame() {
        for (String id : ThemeFile.builtIns()) {
            BaseTheme theme = ThemeFile.builtIn(id);
            assertSame(theme, ThemeFile.parse(ThemeFile.toJson(theme)));
        }
    }

    @Test
    void aFileSaysOnlyWhatDiffersFromItsBase() {
        BaseTheme theme = ThemeFile.parse("{ \"base\": \"dark\", \"buttonBackground\": \"#FF0000\", \"cornerRadius\": 12 }");
        DarkTheme dark = new DarkTheme();
        assertEquals(1f, theme.getButtonBackground().getR(), 0.001f);
        assertEquals(0f, theme.getButtonBackground().getG(), 0.001f);
        assertEquals(12f, theme.getCornerRadius());
        assertEquals(dark.getText().getR(), theme.getText().getR());
        assertEquals(dark.getFieldBackground().getR(), theme.getFieldBackground().getR());
        assertEquals(new LightTheme().getCornerRadius(), ThemeFile.parse("{}").getCornerRadius(), "light when no base is given");
    }

    @Test
    void colorsAreHexOrChannelsAndTransparencyKeepsItsAlpha() {
        BaseTheme theme = ThemeFile.parse("{ \"caret\": [0, 0.5, 1], \"selection\": \"#11223380\", \"text\": \"#FFFFFF\" }");
        assertEquals(0.5f, theme.getCaret().getG(), 0.001f);
        assertEquals(0x80 / 255f, theme.getSelection().getA(), 0.001f);
        assertEquals("#11223380", ThemeFile.hex(theme.getSelection()));
        assertEquals("#FFFFFF", ThemeFile.hex(theme.getText()));
    }

    @Test
    void aWrongFileSaysWhatIsWrong() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> ThemeFile.parse("{ \"buttonBg\": \"#FFFFFF\" }"));
        assertTrue(unknown.getMessage().contains("\"buttonBg\" isn't a theme setting"), unknown.getMessage());
        assertTrue(unknown.getMessage().contains("buttonBackground"), "the right names are listed");

        IllegalArgumentException color = assertThrows(IllegalArgumentException.class, () -> ThemeFile.parse("{ \"text\": \"banana\" }"));
        assertTrue(color.getMessage().startsWith("\"text\":"), color.getMessage());
        IllegalArgumentException number = assertThrows(IllegalArgumentException.class, () -> ThemeFile.parse("{ \"cornerRadius\": \"big\" }"));
        assertTrue(number.getMessage().contains("cornerRadius"), number.getMessage());
        IllegalArgumentException base = assertThrows(IllegalArgumentException.class, () -> ThemeFile.parse("{ \"base\": \"neon\" }"));
        assertTrue(base.getMessage().contains("light, dark, crimson"), base.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ThemeFile.parse("[1, 2]"));
        assertThrows(IllegalArgumentException.class, () -> ThemeFile.parse("{ \"format\": 99 }"));
    }

    @Test
    void sizesAreKeptInRange() {
        BaseTheme theme = ThemeFile.parse("{ \"cornerRadius\": 500, \"borderThickness\": -3 }");
        assertEquals(64f, theme.getCornerRadius());
        assertEquals(0f, theme.getBorderThickness());
    }

    @Test
    void aCopyIsOnlyItsOwn() {
        BaseTheme dark = new DarkTheme();
        BaseTheme copy = ThemeFile.copy(dark);
        assertSame(dark, copy);
        copy.setText(Color.RED);
        copy.setCornerRadius(1f);
        assertNotEquals(Color.RED, dark.getText());
        assertNotEquals(1f, dark.getCornerRadius());
    }

    @Test
    void aGradientWindowBackgroundIsSavedAndReadBack() {
        BaseTheme theme = new DarkTheme();
        theme.setWindowBackground(new ScrollingGradient(List.of(Color.RED, Color.GREEN, Color.BLUE), 45f, 30f));
        theme.setContainerBackground(new LinearGradient(Color.BLACK, Color.WHITE, 90f));
        String json = ThemeFile.toJson(theme);
        assertTrue(json.contains("\"windowBackground\": {"), json);
        BaseTheme back = ThemeFile.parse(json);
        ScrollingGradient window = assertInstanceOf(ScrollingGradient.class, back.getWindowBackground());
        assertEquals(3, window.getColors().size());
        assertEquals(45f, window.getAngle());
        assertEquals(30f, window.getSpeed());
        LinearGradient panel = assertInstanceOf(LinearGradient.class, back.getContainerBackground());
        assertFalse(panel instanceof ScrollingGradient);
        assertEquals(90f, panel.getAngle());
        assertSame(theme, back);
        assertInstanceOf(LinearGradient.class, ThemeFile.copy(theme).getWindowBackground());
    }

    @Test
    void anEffectIsSavedAndReadBackAndNoneIsLeftOut() {
        BaseTheme theme = new DarkTheme();
        assertFalse(ThemeFile.toJson(theme).contains("Effect"), "no effect, nothing written");
        assertNull(ThemeFile.parse(ThemeFile.toJson(theme)).getWindowEffect());

        BaseTheme made = ThemeFile.parse("{ \"windowEffect\": { \"type\": \"constellation\", \"particleCount\": 120 }, "
                + "\"containerEffect\": { \"type\": \"dotgrid\", \"spacing\": 30 } }");
        assertEquals("constellation", made.getWindowEffect().get("type"));
        assertEquals(120, made.getWindowEffect().get("particleCount"));
        assertEquals("dotgrid", made.getContainerEffect().get("type"));
        assertTrue(made.getWindowEffect().containsKey("linkDistance"), "what the file leaves out is filled in as it starts");

        String json = ThemeFile.toJson(made);
        assertSame(made, ThemeFile.parse(json));
        assertEquals(made.getWindowEffect(), ThemeFile.parse(json).getWindowEffect());
        assertEquals(made.getWindowEffect(), ThemeFile.copy(made).getWindowEffect());

        assertNull(ThemeFile.parse("{ \"windowEffect\": null }").getWindowEffect(), "null is none");
        assertThrows(UnsupportedOperationException.class, () -> made.getWindowEffect().put("x", 1), "the settings can't be changed from outside");
        Map<String, Object> mine = new java.util.LinkedHashMap<>(made.getWindowEffect());
        made.setWindowEffect(mine);
        mine.put("particleCount", 1);
        assertEquals(120, made.getWindowEffect().get("particleCount"), "the theme keeps its own copy");
    }

    @Test
    void aBadPaintOrEffectSaysWhichSettingAndWhy() {
        IllegalArgumentException paint = assertThrows(IllegalArgumentException.class,
                () -> ThemeFile.parse("{ \"windowBackground\": { \"type\": \"gradient\", \"colors\": [\"#FFFFFF\"] } }"));
        assertTrue(paint.getMessage().startsWith("\"windowBackground\":"), paint.getMessage());
        assertTrue(paint.getMessage().contains("at least two"), paint.getMessage());

        IllegalArgumentException effect = assertThrows(IllegalArgumentException.class,
                () -> ThemeFile.parse("{ \"windowEffect\": { \"type\": \"snow\" } }"));
        assertTrue(effect.getMessage().startsWith("\"windowEffect\":"), effect.getMessage());
        assertTrue(effect.getMessage().contains("constellation"), "the kinds there are are listed: " + effect.getMessage());

        IllegalArgumentException setting = assertThrows(IllegalArgumentException.class,
                () -> ThemeFile.parse("{ \"containerEffect\": { \"type\": \"dotgrid\", \"spacing\": \"wide\" } }"));
        assertTrue(setting.getMessage().contains("spacing"), setting.getMessage());
    }

    @Test
    void aThemeLoadedFromAFileLooksForItsPicturesBesideIt() throws IOException {
        Path file = folder.resolve("project/theme.json");
        ThemeFile.save(new DarkTheme(), file);
        assertEquals(folder.resolve("project"), ThemeFile.load(file).assetBase());
        assertEquals(Path.of(""), ThemeFile.parse("{}").assetBase());
    }

    @Test
    void aFileIsWrittenWholeAndReadBack() throws IOException {
        Path file = folder.resolve("nested/theme.json");
        BaseTheme crimson = new CrimsonTheme();
        ThemeFile.save(crimson, file);
        assertEquals(List.of("theme.json"), Files.list(file.getParent()).map(p -> p.getFileName().toString()).toList(), "no temporary file is left");
        assertSame(crimson, ThemeFile.load(file));
        assertSame(crimson, ThemeFile.load(file.toString()));

        Files.writeString(file, "{ \"nope\": 1 }");
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> ThemeFile.load(file));
        assertTrue(bad.getMessage().startsWith(file.toString()), bad.getMessage());
        assertThrows(UncheckedIOException.class, () -> ThemeFile.load(folder.resolve("missing.json")));
    }

    @Test
    void builtInsAreFoundByNameInAnyCase() {
        assertInstanceOf(DarkTheme.class, ThemeFile.builtIn("Dark"));
        assertNull(ThemeFile.builtIn("neon"));
        assertNull(ThemeFile.builtIn(null));
        assertTrue(Arrays.asList("light", "dark", "crimson").containsAll(ThemeFile.builtIns()));
    }

    @Test
    void everySettingSaysWhatItChanges() {
        for (ThemeFile.Token token : ThemeFile.tokens()) {
            assertFalse(token.tooltip().isBlank(), token.name() + " has no tooltip");
        }
    }
}
