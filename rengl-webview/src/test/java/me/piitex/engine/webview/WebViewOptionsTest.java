package me.piitex.engine.webview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebViewOptionsTest {
    @Test
    void withNoHostsAnythingOnTheWebIsAllowed() {
        WebViewOptions o = new WebViewOptions();
        assertTrue(o.allows("https://example.com/path"));
        assertTrue(o.allows("http://localhost:8080/"));
    }

    @Test
    void aHostAllowsItselfAndItsSubdomainsOnly() {
        WebViewOptions o = new WebViewOptions().allowHost("Example.com");
        assertTrue(o.allows("https://example.com/"));
        assertTrue(o.allows("https://login.example.com/a?b=c#d"));
        assertFalse(o.allows("https://evilexample.com/"));
        assertFalse(o.allows("https://example.com.evil.org/"));
    }

    @Test
    void userInfoAndPortsDoNotFoolTheHostCheck() {
        WebViewOptions o = new WebViewOptions().allowHost("example.com");
        assertFalse(o.allows("https://example.com@evil.org/"), "the host is what follows the @");
        assertFalse(o.allows("https://example.com:pw@evil.org:8443/"));
        assertTrue(o.allows("https://example.com:8443/"));
        assertTrue(o.allows("https://user@login.example.com/"));
    }

    @Test
    void localFilesAreNeverShown() {
        assertFalse(new WebViewOptions().allows("file:///C:/Users/x/secret.html"));
        assertFalse(new WebViewOptions().allowHost("example.com").allows("file://example.com/x"));
    }

    @Test
    void theViewsOwnPagesAreAlwaysAllowed() {
        WebViewOptions o = new WebViewOptions().allowHost("example.com");
        assertTrue(o.allows("about:blank"));
        assertTrue(o.allows("data:text/html;charset=utf-8,<p>hi"));
        assertFalse(o.allows("data:application/octet-stream;base64,AAAA"));
        assertFalse(o.allows(null));
    }

    @Test
    void anAddressWithNoHostIsRefusedWhenHostsAreListed() {
        assertFalse(new WebViewOptions().allowHost("example.com").allows("mailto:a@example.com"));
        assertFalse(new WebViewOptions().allowHost("example.com").allows("javascript:alert(1)"));
    }

    @Test
    void hostOfReadsIpv6AndMixedCase() {
        assertEquals("[::1]", WebViewOptions.hostOf("http://[::1]:8080/x"));
        assertEquals("example.com", WebViewOptions.hostOf("HTTPS://Example.COM/x"));
        assertNull(WebViewOptions.hostOf("no-scheme"));
    }

    @Test
    void nothingIsPermittedUnlessNamed() {
        assertTrue(new WebViewOptions().getPermissions().isEmpty());
        assertTrue(new WebViewOptions().allow(WebViewOptions.Permission.CAMERA).getPermissions().contains(WebViewOptions.Permission.CAMERA));
        assertEquals(WebViewOptions.Permission.GEOLOCATION, WebViewOptions.Permission.of(3));
        assertNull(WebViewOptions.Permission.of(99));
    }
}
