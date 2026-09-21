package me.piitex.engine.ui.theme;

/**
 * The engine's built-in light theme, identical to a fresh {@link BaseTheme}, since that
 * class's field defaults are already the light theme's values. It exists as its own
 * named class, rather than callers writing {@code new BaseTheme()}, so a light and dark
 * toggle reads the same way in both directions. See {@link DarkTheme}.
 */
public class LightTheme extends BaseTheme {
}
