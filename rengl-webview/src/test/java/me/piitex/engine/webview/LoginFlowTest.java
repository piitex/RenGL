package me.piitex.engine.webview;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LoginFlowTest {
    @Test
    void readsTheQueryAndTheFragmentOfARedirect() {
        LoginFlow.Result r = LoginFlow.parse("mygame://signed-in?code=a%2Bb&state=xyz#access_token=tok&state=frag");
        assertEquals("a+b", r.get("code"));
        assertEquals("tok", r.get("access_token"));
        assertEquals("frag", r.get("state"), "the fragment wins a clash");
        assertFalse(r.isError());
    }

    @Test
    void reportsASignInThatFailed() {
        LoginFlow.Result r = LoginFlow.parse("https://app.example/cb?error=access_denied&error_description=No+thanks");
        assertTrue(r.isError());
        assertEquals("No thanks", r.get("error_description"));
    }

    @Test
    void anAddressWithNothingToReadHasNoValues() {
        assertTrue(LoginFlow.parse("mygame://signed-in").values().isEmpty());
        assertTrue(LoginFlow.parse("mygame://signed-in?").values().isEmpty());
    }

    @Test
    void aBrokenEscapeIsSkippedNotHalfTrusted() {
        LoginFlow.Result r = LoginFlow.parse("x://y?bad=%zz&good=1");
        assertNull(r.get("bad"));
        assertEquals("1", r.get("good"));
    }

    @Test
    void stateMustMatchExactlyAndNullNeverMatches() {
        assertTrue(LoginFlow.sameState("abc", "abc"));
        assertFalse(LoginFlow.sameState("abc", "abd"));
        assertFalse(LoginFlow.sameState("abc", "abcd"));
        assertFalse(LoginFlow.sameState(null, null));
        assertFalse(LoginFlow.sameState("abc", null));
    }

    @Test
    void randomValuesAreLongAndDifferentEachTime() {
        assertNotEquals(LoginFlow.randomState(), LoginFlow.randomState());
        assertEquals(43, LoginFlow.pkceVerifier().length());
        assertTrue(LoginFlow.randomState().matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void theChallengeIsTheSpecifiedSha256OfTheVerifier() {
        // the example in RFC 7636, appendix B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                LoginFlow.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void theLoopbackReceiverCompletesOnceWithWhatTheBrowserSent() throws Exception {
        try (SystemBrowserLogin login = SystemBrowserLogin.start()) {
            assertTrue(login.redirectUri().startsWith("http://127.0.0.1:"));
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> stray = client.send(HttpRequest.newBuilder(URI.create(login.redirectUri().replace("/callback", "/other"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, stray.statusCode());
            assertFalse(login.result().isDone(), "an unrelated request is not a sign-in");

            HttpResponse<String> ok = client.send(HttpRequest.newBuilder(URI.create(login.redirectUri() + "?code=c1&state=s1")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            assertTrue(ok.body().contains("close this window"));
            LoginFlow.Result r = login.await(5, TimeUnit.SECONDS);
            assertNotNull(r);
            assertEquals("c1", r.get("code"));
            assertTrue(r.stateMatches("s1"));
        }
    }

    @Test
    void theReceiverOnlyListensOnTheLoopback() throws Exception {
        try (SystemBrowserLogin login = SystemBrowserLogin.start()) {
            assertTrue(login.redirectUri().contains("127.0.0.1"));
        }
    }

    @Test
    void onlyWebAddressesOpenInTheBrowser() throws Exception {
        try (SystemBrowserLogin login = SystemBrowserLogin.start()) {
            assertThrows(java.io.IOException.class, () -> login.open("file:///C:/Windows/System32/calc.exe"));
            assertThrows(java.io.IOException.class, () -> login.open("javascript:alert(1)"));
        }
    }
}
