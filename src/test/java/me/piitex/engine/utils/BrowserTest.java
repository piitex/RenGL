package me.piitex.engine.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserTest {
    @Test
    void acceptsWebAndMailAddresses() {
        assertEquals("https://example.com/a?b=1", Browser.parse("https://example.com/a?b=1").toString());
        assertEquals("http://localhost:8080", Browser.parse(" http://localhost:8080 ").toString());
        assertEquals("mailto:me@example.com", Browser.parse("mailto:me@example.com").toString());
    }

    @Test
    void bareAddressDefaultsToHttps() {
        assertEquals("https://example.com", Browser.parse("example.com").toString());
    }

    @Test
    void refusesEverythingElse() {
        assertNull(Browser.parse(null));
        assertNull(Browser.parse("  "));
        assertNull(Browser.parse("file:///etc/passwd"));
        assertNull(Browser.parse("javascript:alert(1)"));
        assertNull(Browser.parse("ms-msdt:/id PCWDiagnostic"));
        assertNull(Browser.parse("https://"));
        assertNull(Browser.parse("https://exa mple.com"));
    }
}
