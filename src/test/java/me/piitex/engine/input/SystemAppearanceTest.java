package me.piitex.engine.input;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static me.piitex.engine.input.SystemAppearance.Mode.DARK;
import static me.piitex.engine.input.SystemAppearance.Mode.LIGHT;
import static org.junit.jupiter.api.Assertions.*;

class SystemAppearanceTest {
    @AfterEach
    void tearDown() {
        SystemAppearance.reset();
    }

    @Test
    void macOnlyPrintsDarkInDarkMode() {
        assertEquals(DARK, SystemAppearance.parseMac("Dark\n"));
        assertEquals(LIGHT, SystemAppearance.parseMac(""));
        assertEquals(LIGHT, SystemAppearance.parseMac(null));
    }

    @Test
    void windowsReadsTheRegistryValue() {
        String dark = "\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\n"
                + "    AppsUseLightTheme    REG_DWORD    0x0\n\n";
        assertEquals(DARK, SystemAppearance.parseWindows(dark));
        assertEquals(LIGHT, SystemAppearance.parseWindows(dark.replace("0x0", "0x1")));
        assertEquals(LIGHT, SystemAppearance.parseWindows("nothing useful"));
    }

    @Test
    void linuxLooksForDarkInTheSettingOrTheThemeName() {
        assertEquals(DARK, SystemAppearance.parseLinux("'prefer-dark'\n"));
        assertEquals(DARK, SystemAppearance.parseLinux("'Adwaita-dark'"));
        assertEquals(LIGHT, SystemAppearance.parseLinux("'default'"));
        assertEquals(LIGHT, SystemAppearance.parseLinux(null));
    }

    @Test
    void readingAlwaysGivesAnAnswer() {
        assertNotNull(SystemAppearance.read());
        assertNotNull(SystemAppearance.get());
    }

    @Test
    void removingTheListenerStopsTheWatcherAndDrainIsHarmless() {
        SystemAppearance.onChange(mode -> fail("no change happened"));
        SystemAppearance.drain();
        SystemAppearance.onChange(null);
        SystemAppearance.drain();
    }
}
