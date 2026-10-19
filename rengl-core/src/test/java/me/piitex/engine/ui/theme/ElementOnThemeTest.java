package me.piitex.engine.ui.theme;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ElementOnThemeTest {
    private final Theme before = ThemeManager.getCurrent();

    @AfterEach
    void restore() {
        ThemeManager.setCurrent(before);
    }

    @Test
    void runsNowAndOnEveryThemeSwitch() {
        ThemeManager.setCurrent(new LightTheme());
        Element element = new Element();
        List<Theme> seen = new ArrayList<>();
        element.onTheme(seen::add);
        assertEquals(1, seen.size());
        assertSame(ThemeManager.getCurrent(), seen.getFirst());

        DarkTheme dark = new DarkTheme();
        ThemeManager.setCurrent(dark);
        assertEquals(2, seen.size());
        assertSame(dark, seen.getLast());
    }

    @Test
    void aHandStyledColorFollowsTheTheme() {
        ThemeManager.setCurrent(new LightTheme());
        Element element = new Element();
        element.onTheme(theme -> element.getStyling().setBackgroundColor(theme.getAccent()));
        assertEquals(ThemeFile.hex((Color) new LightTheme().getAccent()), ThemeFile.hex((Color) element.getStyling().getBackgroundColor()));

        ThemeManager.setCurrent(new CrimsonTheme());
        assertEquals(ThemeFile.hex(new CrimsonTheme().getAccent()), ThemeFile.hex((Color) element.getStyling().getBackgroundColor()));
    }

    @Test
    void severalCallbacksRunInOrderAndNullIsIgnored() {
        Container container = new Container(10, 10);
        List<String> order = new ArrayList<>();
        container.onTheme(t -> order.add("a"));
        container.onTheme(t -> order.add("b"));
        container.onTheme(null);
        order.clear();
        ThemeManager.setCurrent(new DarkTheme());
        assertEquals(List.of("a", "b"), order);
    }
}
