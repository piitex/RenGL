package me.piitex.engine.webview.webkit;

/**
 * What a typed character is on a US keyboard: which key produces it and whether Shift is held. Used to put a plausible
 * physical key and modifier on the key press made for a typed character, so a page's {@code shiftKey} and {@code code}
 * are right for ASCII. Other characters (an accented letter, Chinese) have no key here.
 */
public final class UsLayout {
    private UsLayout() {
    }

    /** The shifted symbols, and (at the same index in {@link #UNSHIFTED}) the characters the same keys type without Shift. */
    private static final String SHIFTED = "~!@#$%^&*()_+{}|:\"<>?";
    private static final String UNSHIFTED = "`1234567890-=[]\\;',./";

    /** Whether typing the character on a US keyboard takes Shift. */
    public static boolean needsShift(int codePoint) {
        if (codePoint >= 'A' && codePoint <= 'Z') return true;
        return codePoint < 128 && SHIFTED.indexOf(codePoint) >= 0;
    }

    /** The character the same key types without Shift, or the character itself when Shift is not involved. */
    public static int unshifted(int codePoint) {
        if (codePoint >= 'A' && codePoint <= 'Z') return codePoint + 32;
        if (codePoint < 128) {
            int i = SHIFTED.indexOf(codePoint);
            if (i >= 0) return UNSHIFTED.charAt(i);
        }
        return codePoint;
    }

    /** Whether a US keyboard has a key for the character (with or without Shift). */
    public static boolean hasKey(int codePoint) {
        int base = unshifted(codePoint);
        return base == ' ' || (base >= 'a' && base <= 'z') || (base >= '0' && base <= '9') || (base < 128 && UNSHIFTED.indexOf(base) >= 0);
    }
}
