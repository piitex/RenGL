package me.piitex.engine.ui.theme;

import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.TableView;
import me.piitex.engine.ui.containers.TreeView;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.RadioButtonOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.ToggleSwitchOverlay;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ThemeAccentTest {
    private static final Color MAGENTA = new Color(0.9f, 0.1f, 0.8f, 1f);
    private static final Color MAGENTA_WASH = new Color(0.9f, 0.1f, 0.8f, 0.2f);

    private static void assertColor(Color expected, Object actual) {
        assertEquals(ThemeFile.hex(expected), ThemeFile.hex((Color) actual));
    }

    private static BaseTheme themed() {
        BaseTheme theme = new BaseTheme();
        theme.setAccent(MAGENTA);
        theme.setAccentTint(MAGENTA_WASH);
        return theme;
    }

    @Test
    void oneAccentRestylesEveryControl() {
        BaseTheme theme = themed();

        SliderOverlay slider = new SliderOverlay(200);
        slider.applyTheme(theme);
        assertEquals(MAGENTA, slider.getFillColor());
        assertEquals(MAGENTA, slider.getThumbColor());

        ProgressBarOverlay bar = new ProgressBarOverlay(200, 8);
        bar.applyTheme(theme);
        assertEquals(MAGENTA, bar.getFillColor());

        CheckBoxOverlay box = new CheckBoxOverlay();
        box.applyTheme(theme);
        assertEquals(MAGENTA, box.getCheckColor());

        RadioButtonOverlay radio = new RadioButtonOverlay();
        radio.applyTheme(theme);
        assertEquals(MAGENTA, radio.getDotColor());

        ToggleSwitchOverlay toggle = new ToggleSwitchOverlay(40, 20, true);
        toggle.applyTheme(theme);
        assertEquals(MAGENTA, toggle.getOnColor());

        TreeView<String> tree = new TreeView<>(100, 100, s -> List.of());
        tree.applyTheme(theme);
        assertEquals(MAGENTA_WASH, tree.getSelectionColor());

        TableView<String> table = new TableView<>(100, 100);
        table.applyTheme(theme);
        assertEquals(MAGENTA_WASH, table.getSelectionColor());
    }

    @Test
    void aControlWithItsOwnColorKeepsIt() {
        SliderOverlay slider = new SliderOverlay(200);
        slider.setFillColor(Color.GREEN);
        slider.applyTheme(themed());
        assertEquals(Color.GREEN, slider.getFillColor());
        assertEquals(MAGENTA, slider.getThumbColor(), "only what was set is held back");
    }

    @Test
    void switchingThemesMovesTheAccent() {
        ProgressBarOverlay bar = new ProgressBarOverlay(200, 8);
        bar.applyTheme(new DarkTheme());
        assertColor(new DarkTheme().getAccent(), bar.getFillColor());
        bar.applyTheme(new CrimsonTheme());
        assertColor(new CrimsonTheme().getAccent(), bar.getFillColor());
    }

    @Test
    void theBuiltInThemesHaveTheirOwnAccent() {
        assertNotEquals(new LightTheme().getAccent(), new CrimsonTheme().getAccent());
        for (Theme theme : List.of(new LightTheme(), new DarkTheme(), new CrimsonTheme())) {
            assertEquals(1f, theme.getAccent().getA(), "the accent is opaque");
            assertTrue(theme.getAccentTint().getA() < 1f, "the tint is a wash");
        }
    }

    /** A theme written before the accent existed: every method but the two new ones comes from elsewhere. */
    private static Theme oldTheme(Theme base) {
        return (Theme) Proxy.newProxyInstance(Theme.class.getClassLoader(), new Class<?>[]{Theme.class}, (proxy, method, args) -> {
            if (method.isDefault() && (method.getName().equals("getAccent") || method.getName().equals("getAccentTint"))) {
                return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
            }
            return method.invoke(base, args);
        });
    }

    @Test
    void aThemeThatDoesNotSayGetsTheSelectionColor() {
        BaseTheme base = new BaseTheme();
        base.setSelection(new Color(0.2f, 0.4f, 0.6f, 0.5f));
        Theme old = oldTheme(base);
        assertColor(new Color(0.2f, 0.4f, 0.6f, 1f), old.getAccent());
        assertColor(base.getSelection(), old.getAccentTint());
    }

    @Test
    void theAccentIsInAThemeFile() {
        BaseTheme theme = themed();
        String json = ThemeFile.toJson(theme);
        assertTrue(json.contains("\"accent\""), json);
        assertTrue(json.contains("\"accentTint\""), json);
        BaseTheme back = ThemeFile.parse(json);
        assertEquals(ThemeFile.hex(MAGENTA), ThemeFile.hex(back.getAccent()));
        assertEquals(ThemeFile.hex(MAGENTA_WASH), ThemeFile.hex(back.getAccentTint()));

        BaseTheme fromBase = ThemeFile.parse("{\"base\": \"crimson\"}");
        assertEquals(ThemeFile.hex(new CrimsonTheme().getAccent()), ThemeFile.hex(fromBase.getAccent()));
        BaseTheme changed = ThemeFile.parse("{\"base\": \"dark\", \"accent\": \"#FF8800\"}");
        assertEquals("#FF8800", ThemeFile.hex(changed.getAccent()));
    }
}
