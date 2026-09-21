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

    private Font() {}

    /** Loads (or returns the cached) Typeface for the given font file. */
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

    /** Loads (or returns the cached) Typeface for the given path string. */
    public static Typeface load(String fontFilePath) {
        return load(new File(fontFilePath));
    }

    /** The platform's default typeface, loaded once and reused. */
    // In FontCache.java

    public static Typeface systemDefault() {
        Typeface t = defaultTypeface;
        if (t == null) {
            synchronized (Font.class) {
                t = defaultTypeface;
                if (t == null) {
                    t = FontMgr.Companion.getDefault().matchFamilyStyle(null, FontStyle.Companion.getNORMAL());
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
     * Sets the font file {@link #getDefault()} resolves to from now on. Call it once,
     * early, such as right after creating the Window, instead of passing the same File
     * to every text overlay individually. Pass null to go back to
     * {@link #systemDefault()}. This does not affect any overlay given an explicit font
     * file of its own, only those using the no-file default.
     */
    public static void setDefaultFont(File defaultFont) {
        Font.defaultFont = defaultFont;
    }
}