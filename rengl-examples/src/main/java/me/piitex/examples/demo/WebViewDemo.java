package me.piitex.examples.demo;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.overlays.WebViewOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.theme.DarkTheme;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.engine.webview.LoginFlow;
import me.piitex.engine.webview.WebViewOptions;
import me.piitex.engine.webview.WebViews;

import java.io.File;
import java.nio.file.Path;

/**
 * Web pages in a window, in the two ways the web view module offers: {@code WebViewDemo [address] [--pixels] [--chromium]}.
 * <p>
 * On the left, a <b>login portal</b> drawn as an ordinary element (pixel mode). It shows a made-up sign-in page, the
 * kind a game's account service would provide, and the result comes back through {@link LoginFlow} when the page
 * redirects to {@code mygame://signed-in}: the page is stopped there, never loaded, and the code and state are read from
 * the address. On the right, an <b>embedded browser</b> with an address bar, drawn by the browser itself over the window
 * (native mode, full speed). {@code --pixels} makes the browser pane a pixel-mode view too, to compare how they feel.
 * {@code --chromium} uses an installed Chrome, Edge or Chromium instead of the system's own engine, which is how the
 * module runs on macOS and Linux; it can only draw pictures, so the browser pane is pixel mode then. {@code --wkwebview}
 * (macOS) and {@code --webkitgtk} (Linux) use the system's own WebKit instead; they have not been run yet.
 */
public class WebViewDemo {
    private static final int W = 1280, H = 720;

    private static final String PORTAL = """
            <!doctype html><meta charset=utf-8><title>Example Portal</title>
            <style>
              body{margin:0;font:15px Segoe UI,sans-serif;background:#f4f6fb;color:#222}
              .head{background:#2a5298;color:#fff;padding:18px 24px;font-size:20px}
              form{padding:24px}
              label{display:block;margin-top:14px;font-weight:600}
              input[type=text],input[type=password]{width:100%;box-sizing:border-box;padding:10px;font-size:16px;border:1px solid #99a;border-radius:6px}
              input:focus{outline:2px solid #2a5298}
              button{margin-top:22px;padding:11px 22px;font-size:16px;background:#2a5298;color:#fff;border:0;border-radius:6px;cursor:pointer}
              button:hover{background:#1e3c72}
              .hint{margin-top:14px;color:#667;font-size:13px}
              #error{color:#b00;margin-top:12px;min-height:1.2em}
            </style>
            <div class=head>Example Portal &mdash; sign in</div>
            <form id=f>
              <label for=u>Account name</label><input id=u type=text autocomplete=off>
              <label for=p>Password</label><input id=p type=password>
              <div id=error></div>
              <button type=submit>Sign in</button>
              <div class=hint>Any name and the password <b>letmein</b> will do.</div>
            </form>
            <script>
              document.getElementById('f').addEventListener('submit', function (e) {
                e.preventDefault();
                var u = document.getElementById('u').value, p = document.getElementById('p').value;
                if (!u || p !== 'letmein') { document.getElementById('error').textContent = 'Wrong name or password.'; return; }
                location.href = 'mygame://signed-in?code=demo-' + encodeURIComponent(u) + '&state=@STATE@';
              });
            </script>
            """;

    static void main(String[] args) {
        // --enable-native-access=ALL-UNNAMED
        String address = "https://example.com";
        boolean pixels = false;
        WebViewOptions.Engine engine = WebViewOptions.Engine.AUTO;
        for (String a : args) {
            if (a.equals("--pixels")) pixels = true;
            else if (a.equals("--chromium")) engine = WebViewOptions.Engine.CHROMIUM;
            else if (a.equals("--wkwebview")) engine = WebViewOptions.Engine.WKWEBVIEW;   // macOS; never run, see docs/webview
            else if (a.equals("--webkitgtk")) engine = WebViewOptions.Engine.WEBKITGTK;   // Linux; never run, see docs/webview
            else address = a;
        }

        Window window = new Window(new WindowOptions("Web view demo").setDimensions(W, H));
        Font.setDefaultFont(new File("rengl-examples/src/main/resources/Roboto-Regular.ttf"));
        ThemeManager.setCurrent(new DarkTheme());

        Container root = new Container(W, H);
        root.getStyling().setBackgroundColor(new Color(24 / 255f, 26 / 255f, 32 / 255f, 1f));
        root.getStyling().setBorderThickness(0f);

        TextOverlay left = new TextOverlay("Login portal (pixel mode, an ordinary element)", 14, Color.WHITE);
        left.setPosition(20, 14);
        root.addElement(left);
        TextOverlay status = new TextOverlay("Not signed in.", 14, new Color(1f, 0.84f, 0.4f, 1f));
        status.setPosition(20, 36);
        root.addElement(status);

        WebViewOptions probe = new WebViewOptions().setEngine(engine);
        if (!WebViews.isAvailable(probe)) {
            TextOverlay why = new TextOverlay(WebViews.describe(probe), 16, Color.WHITE);
            why.setPosition(20, 80);
            root.addElement(why);
            window.addContainer(root);
            new Engine().start(window);
            return;
        }

        String state = LoginFlow.randomState();
        WebViewOverlay portal = new WebViewOverlay(new WebViewOptions().setEngine(engine).allowHost("never-leaves.invalid"), 420, 590);
        portal.setPosition(20, 70);
        portal.getStyling().setCornerRadius(10f);
        portal.onRedirect("mygame://signed-in", url -> {
            LoginFlow.Result r = LoginFlow.parse(url);
            status.setText(r.stateMatches(state)
                    ? "Signed in. code=" + r.get("code") + "  (state matched, the redirect was never loaded)"
                    : "Rejected a sign-in with the wrong state.");
        });
        portal.loadHtml(PORTAL.replace("@STATE@", state));
        root.addElement(portal);

        WebViewOptions browserOptions = new WebViewOptions()
                .setEngine(engine)
                .setMode(pixels ? WebViewOptions.Mode.PIXELS : WebViewOptions.Mode.NATIVE)
                .setProfile(Path.of(System.getProperty("java.io.tmpdir"), "rengl-webview-demo-profile"))
                .setContextMenus(true);
        boolean nativePane = !pixels && WebViews.supportsNative(browserOptions);
        TextOverlay right = new TextOverlay(nativePane ? "Embedded browser (native mode, full speed)"
                : "Embedded browser (pixel mode, " + WebViews.resolve(browserOptions) + ")", 14, Color.WHITE);
        right.setPosition(470, 14);
        root.addElement(right);

        WebViewOverlay browser = new WebViewOverlay(browserOptions, 790, 580);
        browser.setPosition(470, 100);

        ButtonOverlay back = new ButtonOverlay("<", 40, 30);
        back.setPosition(470, 60);
        back.onMouseClick(_ -> browser.goBack());
        ButtonOverlay forward = new ButtonOverlay(">", 40, 30);
        forward.setPosition(516, 60);
        forward.onMouseClick(_ -> browser.goForward());
        ButtonOverlay reload = new ButtonOverlay("R", 40, 30);
        reload.setPosition(562, 60);
        reload.onMouseClick(_ -> browser.reload());
        TextFieldOverlay bar = new TextFieldOverlay(610, 60, 660, 30);
        bar.setText(address);
        bar.onSubmit(browser::load);
        browser.onUrlChanged(bar::setText);
        browser.onFailed(reason -> right.setText("Browser failed: " + reason));
        root.addElement(back);
        root.addElement(forward);
        root.addElement(reload);
        root.addElement(bar);
        root.addElement(browser);
        browser.load(address);

        window.addContainer(root);
        new Engine().start(window);
        portal.dispose();
        browser.dispose();
    }
}
