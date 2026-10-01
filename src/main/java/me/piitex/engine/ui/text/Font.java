package me.piitex.engine.ui.text;

import org.jetbrains.skia.FontMgr;
import org.jetbrains.skia.FontStyle;
import org.jetbrains.skia.Typeface;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches {@link Typeface} instances by file path.
 * <p>
 * Loading goes through {@link FontMgr} rather than calling a static method on
 * {@link Typeface} directly. Skia deprecated and removed Typeface::makeFromFile and
 * Typeface::makeDefault upstream some time ago, making file loading and font matching
 * the FontMgr's job. Depending on the exact Skiko version, the old Typeface.Companion
 * convenience methods may be absent rather than merely deprecated. Going through
 * FontMgr is stable across both old and current Skiko builds.
 * <p>
 * Parsing the whole font file (name tables, glyph outlines, etc.) up front is
 * expensive. Constructing a {@link org.jetbrains.skia.Font} from an already-loaded
 * Typeface is cheap, while reloading the file itself is not. Caching it once lets every
 * TextOverlay using the same font file share one Typeface.
 * <p>
 * This needs no GL or Metal context. Font parsing is pure CPU work, backed by FreeType,
 * so it is safe to call from any thread, including the main thread before the render
 * thread exists.
 * <p>
 * Cached Typefaces are never closed. They are native-backed, through Skia's Managed,
 * but bounded by the number of distinct font files an application uses and typically
 * live for the whole process, so reference-counting them against every TextOverlay that
 * might still be drawing with one is not worth the complexity for a UI toolkit. An
 * application loading many one-off fonts at runtime, such as user-uploaded fonts,
 * rather than a fixed set shipped with it, would need to revisit this.
 */
public final class Font {
    private static final Map<String, Typeface> cache = new ConcurrentHashMap<>();
    private static volatile Typeface defaultTypeface;
    private static File defaultFont;

    private Font() {
    }

    /**
     * Loads (or returns the cached) Typeface for the given font file.
     */
    public static Typeface load(File fontFile) {
        String key = fontFile.getAbsolutePath();
        return cache.computeIfAbsent(key, path -> {
            // FontMgr.Companion.getDefault() is the underlying OS font manager
            // (CoreText, DirectWrite, or Fontconfig depending on the platform).
            // It is what knows how to parse a font file on disk.
            Typeface t = FontMgr.Companion.getDefault().makeFromFile(path, 0);
            if (t == null) {
                throw new IllegalArgumentException("Not a valid font file: " + path);
            }
            return t;
        });
    }

    /**
     * Loads (or returns the cached) Typeface for the given path string.
     */
    public static Typeface load(String fontFilePath) {
        return load(new File(fontFilePath));
    }

    /**
     * The platform's default typeface, loaded once and reused.
     */
    // In FontCache.java
    public static Typeface systemDefault() {
        Typeface t = defaultTypeface;
        if (t == null) {
            synchronized (Font.class) {
                t = defaultTypeface;
                if (t == null) {
                    FontMgr fonts = FontMgr.Companion.getDefault();
                    t = normalTypeface(fonts, "matchFamilyStyle", null);
                    // CoreText matches no family for a null name; the legacy lookup gives the system's default one.
                    if (t == null) t = normalTypeface(fonts, "legacyMakeTypeface", "");
                    if (t == null) {
                        // Log a warning instead of crashing
                        System.err.println("Warning: FontMgr could not resolve a default typeface. Please provide a custom font file.");
                        return null;
                    }
                    defaultTypeface = t;
                }
            }
        }
        return t;
    }

    /**
     * The font every text overlay's default, no-file constructor falls back to. This is
     * whatever was last passed to {@link #setDefaultFont(File)}, loaded and cached on
     * first use the same way {@link #load(File)} does, or {@link #systemDefault()} if it
     * was never set. Setting it once avoids passing the same font File to every
     * {@code TextOverlay} and {@code TextFieldOverlay} constructed.
     */
    public static Typeface getDefault() {
        File font = defaultFont;
        return font != null ? load(font) : systemDefault();
    }

    /**
     * A Skia font of the {@link #getDefault() default typeface} at {@code size} points, for drawing text with
     * {@link me.piitex.engine.gl.renderer.Renderer#drawText Renderer.drawText} and measuring it. Cheap: the typeface is
     * loaded once and shared. Make the fonts a game draws with once, after {@link #setDefaultFont} is called, rather
     * than every frame.
     */
    public static org.jetbrains.skia.Font sized(float size) {
        return new org.jetbrains.skia.Font(getDefault(), size);
    }

    /**
     * Sets the font file {@link #getDefault()} resolves to from now on. Call it once,
     * early, such as right after creating the Window, instead of passing the same File
     * to every text overlay individually. Pass null to go back to
     * {@link #systemDefault()}. This does not affect any overlay given an explicit font
     * file of its own, only those using the no-file default.
     */
    public static void setDefaultFont(File defaultFont) {
        Font.defaultFont = defaultFont;
    }
    /**
     * Calls one of FontMgr's family lookups with the normal style. Since Skiko 0.152 {@code FontStyle} is a Kotlin value
     * class, so these methods take a bare int and carry a mangled name ({@code matchFamilyStyle-ooATI1c}) that Java
     * source can't call; they are found by their prefix instead.
     */
    private static Typeface normalTypeface(FontMgr fonts, String method, String family) {
        try {
            int normal = 0;
            boolean found = false;
            for (java.lang.reflect.Method m : FontStyle.Companion.getClass().getMethods()) {
                if (m.getName().startsWith("getNORMAL") && m.getParameterCount() == 0 && m.getReturnType() == int.class) {
                    normal = (int) m.invoke(FontStyle.Companion);
                    found = true;
                    break;
                }
            }
            if (!found) return null;
            for (java.lang.reflect.Method m : FontMgr.class.getMethods()) {
                if (m.getName().startsWith(method) && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == String.class && m.getParameterTypes()[1] == int.class) {
                    return (Typeface) m.invoke(fonts, family, normal);
                }
            }
        } catch (ReflectiveOperationException e) {
            System.err.println("Warning: could not look up the default typeface: " + e);
        }
        return null;
    }
}