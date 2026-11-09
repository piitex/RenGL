package me.piitex.engine.webview;

import me.piitex.engine.ui.overlays.WebViewOverlay;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The parts of signing a user in through a web page that a game or application has to get right, so a login portal
 * inside a window does not become the weak point.
 * <p>
 * Sign-in with OAuth 2.0 / OpenID Connect (and SAML) works the same way everywhere: send the user to the provider's page,
 * they sign in there, and the provider redirects to an address you chose with the result (a code or token) in it. A web
 * view has the advantage that it can stop at that address, never loading it, so no web server is needed.
 * <pre>{@code
 * String state = LoginFlow.randomState();
 * String verifier = LoginFlow.pkceVerifier();
 * WebViewOverlay web = new WebViewOverlay(new WebViewOptions().allowHost("login.example.com"), 420, 560);
 * LoginFlow.embedded(web, "mygame://signed-in", authorizeUrl(state, LoginFlow.pkceChallenge(verifier)), result -> {
 *     if (!result.stateMatches(state)) return;          // not the sign-in we started: ignore it
 *     exchangeForToken(result.get("code"), verifier);   // your request to the provider's token endpoint
 * });
 * }</pre>
 * Things this does not do for you, and that matter:
 * <ul>
 *   <li><b>Use the authorization-code flow with PKCE</b>, not the implicit flow. The code is worthless without the
 *       verifier that only your program has.</li>
 *   <li><b>Allow only the provider's hosts</b> ({@link WebViewOptions#allowHost}) and show the user the real host (see
 *       {@link WebViewOverlay#getUrl()}), so they can tell the portal is genuine.</li>
 *   <li><b>Some providers do not allow embedded browsers</b> for sign-in, Google among them, and block them. For those use
 *       {@link SystemBrowserLogin}, which opens the user's own browser and receives the result on a loopback address.</li>
 *   <li>The view keeps nothing after it closes unless you give it a profile folder, so sign-ins do not leak between
 *       users of one machine. The tokens you receive are yours to store safely.</li>
 * </ul>
 */
public final class LoginFlow {
    private LoginFlow() {
    }

    /**
     * The result a portal redirected to: the full address and the values in it.
     *
     * @param url    the address as the portal redirected to it
     * @param values the query values and then the fragment values (after {@code #}), decoded; a fragment value wins a clash
     */
    public record Result(String url, Map<String, String> values) {
        public String get(String name) {
            return values.get(name);
        }

        /** Whether the sign-in reported an error ({@code error=...}). */
        public boolean isError() {
            return values.containsKey("error");
        }

        /** Whether the {@code state} that came back is the one that was sent. Compares in constant time. */
        public boolean stateMatches(String expected) {
            return LoginFlow.sameState(expected, values.get("state"));
        }
    }

    /**
     * Sends the user to {@code startUrl} in {@code web} and calls {@code onResult} on the render thread when the portal
     * redirects to an address that begins with {@code redirectUri}. That address is not loaded.
     */
    public static void embedded(WebViewOverlay web, String redirectUri, String startUrl, Consumer<Result> onResult) {
        web.onRedirect(redirectUri, url -> onResult.accept(parse(url)));
        web.load(startUrl);
    }

    /**
     * Reads the values out of a redirect address: its query ({@code ?code=...&state=...}) and its fragment
     * ({@code #access_token=...}).
     */
    public static Result parse(String url) {
        Map<String, String> values = new LinkedHashMap<>();
        String rest = url;
        int hash = rest.indexOf('#');
        String fragment = hash >= 0 ? rest.substring(hash + 1) : "";
        if (hash >= 0) rest = rest.substring(0, hash);
        int question = rest.indexOf('?');
        if (question >= 0) read(rest.substring(question + 1), values);
        read(fragment, values);
        return new Result(url, values);
    }

    private static void read(String pairs, Map<String, String> into) {
        if (pairs.isEmpty()) return;
        for (String pair : pairs.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                into.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // a malformed escape: skip the pair rather than trust half of it
            }
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * A random value for the {@code state} parameter, which ties a redirect to the sign-in that started it.
     */
    public static String randomState() {
        return randomToken(24);
    }

    /**
     * A PKCE code verifier: 43 random characters. Keep it until the code is exchanged, and send it then.
     */
    public static String pkceVerifier() {
        return randomToken(32);
    }

    /**
     * The PKCE challenge for a verifier ({@code S256}): send it with the sign-in request, with
     * {@code code_challenge_method=S256}.
     */
    public static String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java has SHA-256
        }
    }

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * Whether two state values are equal, taking the same time however many characters match. Null never matches.
     */
    public static boolean sameState(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
